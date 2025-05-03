package com.example.payshield.producer;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

import java.io.File;
import java.util.Properties;

public class PaymentProducer {
    public static void main(String[] args) throws Exception {
        // Load Avro schema
        Schema schema = new Schema.Parser()
                .parse(new File("../../schemas/payment-event.avsc"));

        // Configure producer
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                KafkaAvroSerializer.class.getName());
        props.put("schema.registry.url", "http://localhost:8081");

        Producer<String, GenericRecord> producer =
                new KafkaProducer<>(props);

        // Send a quick test record
        GenericRecord evt = new GenericData.Record(schema);
        evt.put("msgId", "test-1");
        evt.put("debitAcct", "a");
        evt.put("creditAcct", "b");
        evt.put("amount", 123.45);
        evt.put("transactionTs", System.currentTimeMillis());

        producer.send(new ProducerRecord<>("payments", "test-1", evt),
                (md, ex) -> {
                    if (ex != null) ex.printStackTrace();
                    else System.out.printf("OK @%d%n", md.offset());
                });

        producer.flush();
        producer.close();
    }
}
