############################################
# Create payments & fraud-alerts topics
############################################
resource "null_resource" "create_topics" {
  depends_on = [docker_container.kafka]

  provisioner "local-exec" {
    command = <<-EOT
      # Payments topic: 6 partitions for parallelism, RF=1 for single-node dev
      docker exec payshield_kafka kafka-topics --bootstrap-server payshield_kafka:9092 \
        --create --if-not-exists --topic payments \
        --partitions 6 --replication-factor 1

      # Alerts topic: 3 partitions, RF=1
      docker exec payshield_kafka kafka-topics --bootstrap-server payshield_kafka:9092 \
        --create --if-not-exists --topic fraud-alerts \
        --partitions 3 --replication-factor 1
    EOT
  }
}
