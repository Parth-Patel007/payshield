package com.example.payshield.producer;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.File;
import java.util.Properties;

public class TestProducer {
    public static void main(String[] args) throws Exception {
        Schema schema = new Schema.Parser()
                .parse(new File("schemas/payment-event.avsc"));

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                KafkaAvroSerializer.class.getName());
        props.put("schema.registry.url", "http://localhost:8081");

        Producer<String, GenericRecord> producer = new KafkaProducer<>(props);

        double[] testAmounts = {3000.0, 8000.0, 10000.0, 12000.0};
        for (double amt : testAmounts) {
            GenericRecord event = new GenericData.Record(schema);
            String id = "test-" + ((int) amt);
            event.put("msgId", id);
            event.put("debitAcct", "acct-debit-" + id);
            event.put("creditAcct", "acct-credit-" + id);
            event.put("amount", amt);
            event.put("transactionTs", System.currentTimeMillis());

            producer.send(new ProducerRecord<>("payments", id, event),
                    (md, ex) -> {
                        if (ex != null) System.err.println("Send failed: " + ex);
                        else System.out.printf("→ Sent %s amt=%.0f%n", id, amt);
                    });
        }

        producer.flush();
        producer.close();
        System.out.println("✅  Test events sent.");
    }
}
