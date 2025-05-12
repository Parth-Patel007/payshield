package com.example.payshield.producer;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.File;
import java.util.Properties;

public class PaymentProducer {
    public static void main(String[] args) throws Exception {
        // 1) Load your Avro schema
        Schema schema = new Schema.Parser()
                .parse(new File("schemas/payment-event.avsc"));  // relative to project root

        // 2) Producer configuration
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                KafkaAvroSerializer.class.getName());
        props.put("schema.registry.url", "http://localhost:8081");

        // 3) Create & send 5 sample events
        try (Producer<String, GenericRecord> producer = new KafkaProducer<>(props)) {
            for (int i = 1; i <= 5; i++) {
                GenericRecord event = new GenericData.Record(schema);
                String msgId = "msg-" + i;
                event.put("msgId",         msgId);
                event.put("debitAcct",     "acct-debit-" + i);
                event.put("creditAcct",    "acct-credit-" + i);
                event.put("amount",        10000.0 + i);
                event.put("transactionTs", System.currentTimeMillis());

                ProducerRecord<String, GenericRecord> record =
                        new ProducerRecord<>("payments", msgId, event);
                producer.send(record);
            }
            producer.flush();
        }

        System.out.println("✅  Sent 5 test events to payments topic.");
    }
}
