package com.example.payshield.flink;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroDeserializationSchema;
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroSerializationSchema;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

public class ThresholdJob {

    public static void main(String[] args) throws Exception {
        // 1. Load config from application.properties
        ParameterTool p = ParameterTool.fromPropertiesFile(
                ThresholdJob.class.getClassLoader().getResourceAsStream("application.properties")
        );

        String brokers   = p.getRequired("bootstrap.servers");
        String registry  = p.getRequired("schema.registry.url");
        String inTopic   = p.getRequired("input.topic");
        String outTopic  = p.getRequired("output.topic");
        double threshold = p.getDouble("threshold.amount");
        long   ckptMs    = p.getLong("execution.checkpointing.interval", 30_000);

        // 2. Load Avro schema from Payment.avsc
        InputStream schemaStream = ThresholdJob.class.getClassLoader().getResourceAsStream("Payment.avsc");
        Schema schema = new Schema.Parser().parse(schemaStream);

        // 3. Flink environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(ckptMs, CheckpointingMode.EXACTLY_ONCE);

        // 4. Kafka source config
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", brokers);
        kafkaProps.setProperty("group.id", "flink-threshold-group");
        kafkaProps.setProperty("schema.registry.url", registry);
        kafkaProps.setProperty("auto.offset.reset", "earliest");

        KafkaSource<GenericRecord> source = KafkaSource
                .<GenericRecord>builder()
                .setBootstrapServers(brokers)
                .setGroupId("flink-threshold-group")
                .setTopics(inTopic)
                .setValueOnlyDeserializer(
                        ConfluentRegistryAvroDeserializationSchema.forGeneric(schema, registry)
                )
                .setProperties(kafkaProps)
                .build();

        DataStream<GenericRecord> stream = env.fromSource(
                source,
                WatermarkStrategy
                        .<GenericRecord>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((rec, ts) -> (long) rec.get("transactionTs")),
                "payments-source"
        );

        // 5. Split into main + fraud side-output
        OutputTag<GenericRecord> fraudTag = new OutputTag<>("fraud") {};

        SingleOutputStreamOperator<GenericRecord> main = stream.process(
                new ProcessFunction<GenericRecord, GenericRecord>() {
                    @Override
                    public void processElement(GenericRecord rec, Context ctx, Collector<GenericRecord> out) {
                        double amt = (double) rec.get("amount");
                        if (amt >= threshold) {
                            ctx.output(fraudTag, rec);
                        } else {
                            out.collect(rec);
                        }
                    }
                });

        DataStream<GenericRecord> frauds = main.getSideOutput(fraudTag);

        // 6. Kafka sink for frauds
        KafkaSink<GenericRecord> sink = KafkaSink
                .<GenericRecord>builder()
                .setBootstrapServers(brokers)
                .setKafkaProducerConfig(new Properties() {{
                    put("schema.registry.url", registry);
                    put("enable.idempotence", "true");
                }})
                .setRecordSerializer(
                        KafkaRecordSerializationSchema.builder()
                                .setTopic(outTopic)
                                .setValueSerializationSchema(
                                        ConfluentRegistryAvroSerializationSchema.forGeneric(
                                                outTopic + "-value", schema, registry
                                        )
                                )
                                .build()
                )
                .setTransactionalIdPrefix("flink-threshold-tx")
                .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                .build();

        frauds.sinkTo(sink).name("fraud-alerts-sink");

        // 7. Execute
        env.execute("Threshold Fraud Detection Job");
    }
}
