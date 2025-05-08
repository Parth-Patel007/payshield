package com.example.payshield.consumer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.io.InputStream;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public class PaymentConsumer {

    public static void main(String[] args) throws Exception {
        // 1) Load application.properties
        Properties appProps = new Properties();
        try (InputStream in =
                     PaymentConsumer.class.getClassLoader()
                             .getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            appProps.load(in);
        }

        // 2) Read config values
        String bootstrapServers   = appProps.getProperty("bootstrap.servers");
        String schemaRegistryUrl  = appProps.getProperty("schema.registry.url");
        String groupId            = appProps.getProperty("group.id");
        String topic              = appProps.getProperty("consumer.topic");
        String jdbcUrl            = appProps.getProperty("db.url");
        String jdbcUser           = appProps.getProperty("db.user");
        String jdbcPass           = appProps.getProperty("db.password");
        int    maxPoolSize        = Integer.parseInt(appProps.getProperty("db.maxPoolSize", "10"));
        double fraudThreshold     = Double.parseDouble(appProps.getProperty("fraud.threshold"));

        // 3) Set up HikariCP DataSource
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(jdbcUser);
        hikariConfig.setPassword(jdbcPass);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        HikariDataSource ds = new HikariDataSource(hikariConfig);

        // 4) Configure Kafka consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                KafkaAvroDeserializer.class.getName());
        consumerProps.put("schema.registry.url", schemaRegistryUrl);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                appProps.getProperty("enable.auto.commit"));
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                appProps.getProperty("auto.offset.reset"));
        consumerProps.put("specific.avro.reader", "false");

        try (KafkaConsumer<String, GenericRecord> consumer =
                     new KafkaConsumer<>(consumerProps)) {

            // 5) Ensure tables exist
            try (Connection db = ds.getConnection()) {
                createTables(db);
            }

            consumer.subscribe(Collections.singletonList(topic));
            System.out.printf("🔄  Listening on topic %s …%n", topic);

            // 6) Main poll‐loop
            while (true) {
                ConsumerRecords<String, GenericRecord> records =
                        consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) continue;

                // one DB transaction per batch
                try (Connection db = ds.getConnection()) {
                    db.setAutoCommit(false);
                    try (PreparedStatement payStmt = db.prepareStatement(
                            "INSERT INTO payments VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING");
                         PreparedStatement fraudStmt = db.prepareStatement(
                                 "INSERT INTO fraud_alerts VALUES (?,?,?,?) ON CONFLICT DO NOTHING")) {

                        for (ConsumerRecord<String, GenericRecord> rec : records) {
                            GenericRecord evt = rec.value();
                            String msgId      = evt.get("msgId").toString();
                            String debitAcct  = evt.get("debitAcct").toString();
                            String creditAcct = evt.get("creditAcct").toString();
                            double amount     = (double) evt.get("amount");
                            Timestamp ts      = new Timestamp((long) evt.get("transactionTs"));

                            // payments table
                            payStmt.setString(1, msgId);
                            payStmt.setString(2, debitAcct);
                            payStmt.setString(3, creditAcct);
                            payStmt.setDouble(4, amount);
                            payStmt.setTimestamp(5, ts);
                            payStmt.addBatch();

                            // fraud_alerts if over threshold
                            if (amount >= fraudThreshold) {
                                fraudStmt.setString(1, msgId);
                                fraudStmt.setDouble(2, amount);
                                fraudStmt.setTimestamp(3, ts);
                                fraudStmt.setString(4, "THRESHOLD_EXCEEDED");
                                fraudStmt.addBatch();
                            }
                        }

                        payStmt.executeBatch();
                        fraudStmt.executeBatch();
                        db.commit();            // durable in Postgres
                        consumer.commitSync();  // durable in Kafka
                        System.out.printf("✔︎  Committed %d messages%n", records.count());
                    } catch (Exception e) {
                        db.rollback();
                        System.err.println("❌ Tx failed, rolled back: " + e.getMessage());
                    } finally {
                        db.setAutoCommit(true);
                    }
                }
            }
        } finally {
            ds.close();
        }
    }

    private static void createTables(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                  msg_id TEXT PRIMARY KEY,
                  debit_acct TEXT,
                  credit_acct TEXT,
                  amount DOUBLE PRECISION,
                  tx_time TIMESTAMP
                )""");

            s.execute("""
                CREATE TABLE IF NOT EXISTS fraud_alerts (
                  msg_id TEXT PRIMARY KEY,
                  amount DOUBLE PRECISION,
                  tx_time TIMESTAMP,
                  reason TEXT
                )""");
        }
    }
}
