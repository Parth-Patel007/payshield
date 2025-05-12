package com.example.payshield.consumer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

import org.apache.avro.generic.GenericRecord;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class PaymentConsumer {

    public static void main(String[] args) throws Exception {
        // 1) Load application.properties
        Properties appProps = new Properties();
        try (InputStream in = PaymentConsumer.class
                .getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) throw new IllegalStateException("application.properties not found");
            appProps.load(in);
        }

        // 2) Read config values
        String brokers      = appProps.getProperty("bootstrap.servers");
        String registryUrl  = appProps.getProperty("schema.registry.url");
        String groupId      = appProps.getProperty("group.id");
        String consumeTopic = appProps.getProperty("consumer.topic");
        String alertTopic   = appProps.getProperty("producer.topic");
        String jdbcUrl      = appProps.getProperty("db.url");
        String jdbcUser     = appProps.getProperty("db.user");
        String jdbcPass     = appProps.getProperty("db.password");
        double threshold    = Double.parseDouble(appProps.getProperty("fraud.threshold"));

        // → Redis settings
        String redisHost    = appProps.getProperty("redis.host");
        int    redisPort    = Integer.parseInt(appProps.getProperty("redis.port"));
        int    redisTtl     = Integer.parseInt(appProps.getProperty("redis.ttl.seconds"));

        // 3) Setup HikariCP
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(jdbcUser);
        hc.setPassword(jdbcPass);
        HikariDataSource ds = new HikariDataSource(hc);

        // 4) JedisPool for Redis
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        JedisPool jedisPool = new JedisPool(poolConfig, redisHost, redisPort);

        // 5) Consumer props
        Properties cProps = new Properties();
        cProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        cProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        cProps.put("schema.registry.url", registryUrl);
        cProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        cProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps.put("specific.avro.reader", "false");

        // 6) Producer props for fraud‐alerts
        Properties pProps = new Properties();
        pProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        pProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        pProps.put("schema.registry.url", registryUrl);
        pProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // 7) Main try‐with‐resources
        try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(cProps);
             KafkaProducer<String, GenericRecord> producer = new KafkaProducer<>(pProps)) {

            // ensure tables exist
            try (Connection conn = ds.getConnection()) {
                createTables(conn);
            }

            consumer.subscribe(Collections.singletonList(consumeTopic));
            System.out.println("🔄  Listening on " + consumeTopic);

            // 8) Poll loop
            while (true) {
                ConsumerRecords<String, GenericRecord> recs =
                        consumer.poll(Duration.ofSeconds(1));
                if (recs.isEmpty()) continue;

                // one DB tx per batch
                try (Connection conn = ds.getConnection()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement payStmt = conn.prepareStatement(
                            "INSERT INTO payments VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING");
                         PreparedStatement fraudDbStmt = conn.prepareStatement(
                                 "INSERT INTO fraud_alerts VALUES (?,?,?,?) ON CONFLICT DO NOTHING")) {

                        for (ConsumerRecord<String, GenericRecord> rec : recs) {
                            GenericRecord evt = rec.value();
                            String msgId = evt.get("msgId").toString();

                            // → Idempotency check: skip duplicates
                            try (Jedis jedis = jedisPool.getResource()) {
                                if (jedis.exists(msgId)) {
                                    System.out.printf("↩️  Skipping duplicate %s%n", msgId);
                                    continue;
                                }
                            }

                            double amt = (double) evt.get("amount");
                            Timestamp ts = new Timestamp((long) evt.get("transactionTs"));

                            // payments table
                            payStmt.setString(1, msgId);
                            payStmt.setString(2, evt.get("debitAcct").toString());
                            payStmt.setString(3, evt.get("creditAcct").toString());
                            payStmt.setDouble(4, amt);
                            payStmt.setTimestamp(5, ts);
                            payStmt.addBatch();

                            // fraud → DB + Kafka fan-out
                            if (amt >= threshold) {
                                fraudDbStmt.setString(1, msgId);
                                fraudDbStmt.setDouble(2, amt);
                                fraudDbStmt.setTimestamp(3, ts);
                                fraudDbStmt.setString(4, "THRESHOLD_EXCEEDED");
                                fraudDbStmt.addBatch();

                                producer.send(new ProducerRecord<>(alertTopic, msgId, evt));
                            }
                        }

                        // commit DB + Kafka in lock‐step
                        payStmt.executeBatch();
                        fraudDbStmt.executeBatch();
                        conn.commit();

                        producer.flush();
                        consumer.commitSync();

                        // → record IDs in Redis for TTL seconds
                        try (Jedis jedis = jedisPool.getResource()) {
                            for (ConsumerRecord<String, GenericRecord> rec : recs) {
                                String id = rec.value().get("msgId").toString();
                                jedis.setex(id, redisTtl, "");
                            }
                        }

                        System.out.printf("✔︎  Committed %d messages%n", recs.count());
                    } catch (Exception e) {
                        conn.rollback();
                        System.err.println("❌ Tx failed – rolled back: " + e.getMessage());
                    }
                }
            }
        } finally {
            ds.close();
            jedisPool.close();
        }
    }

    private static void createTables(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                  msg_id TEXT PRIMARY KEY,
                  debit_acct TEXT, credit_acct TEXT,
                  amount DOUBLE PRECISION, tx_time TIMESTAMP
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS fraud_alerts (
                  msg_id TEXT PRIMARY KEY,
                  amount DOUBLE PRECISION, tx_time TIMESTAMP,
                  reason TEXT
                )""");
        }
    }
}
