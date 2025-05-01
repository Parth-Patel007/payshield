terraform {
  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 2.15"
    }
  }
}

provider "docker" {}

############################################
# 1. Network & Kafka metadata volume
############################################
resource "docker_network" "payshield" {
  name = var.docker_network_name
}

resource "docker_volume" "kafka_data" {
  name = "payshield_kafka_data"
}

############################################
# 2. Generate a KRaft Cluster ID
############################################
data "external" "cluster_id" {
  program = [
    "bash", "-c",
    <<-SCRIPT
      id=$(docker run --rm ${var.kafka_image} \
            kafka-storage random-uuid)
      echo "{\"cluster_id\":\"$id\"}"
    SCRIPT
  ]
}

locals {
  cluster_id = data.external.cluster_id.result.cluster_id
}

############################################
# 3. Format the metadata store (idempotent)
############################################
resource "null_resource" "bootstrap_kraft" {
  triggers = {
    cid = local.cluster_id
  }

  provisioner "local-exec" {
    command = <<-SCRIPT
      set -e
      # Ensure ownership for cp-kafka default user
      docker run --rm \
        -v ${docker_volume.kafka_data.name}:/var/lib/kraft-combined-logs \
        busybox \
        chown -R 1000:1000 /var/lib/kraft-combined-logs

      # Format the Raft metadata
      docker run --rm \
        -v ${docker_volume.kafka_data.name}:/var/lib/kraft-combined-logs \
        ${var.kafka_image} \
        kafka-storage format \
          --cluster-id "${local.cluster_id}" \
          --config /etc/kafka/kraft/server.properties \
          --ignore-formatted
    SCRIPT
  }
}

############################################
# 4. Kafka Container (single-node KRaft)
############################################
resource "docker_image" "kafka" {
  name = var.kafka_image
}

resource "docker_container" "kafka" {
  name  = "payshield_kafka"
  image = var.kafka_image

  # wait until metadata is formatted
  depends_on = [
    docker_network.payshield,
    docker_volume.kafka_data,
    null_resource.bootstrap_kraft
  ]

  networks_advanced {
    name = docker_network.payshield.name
  }

  volumes {
    volume_name    = docker_volume.kafka_data.name
    container_path = "/var/lib/kraft-combined-logs"
  }

  env = [
    "CLUSTER_ID=${local.cluster_id}",
    "KAFKA_PROCESS_ROLES=broker,controller",
    "KAFKA_NODE_ID=1",
    "KAFKA_CONTROLLER_QUORUM_VOTERS=1@payshield_kafka:9093",
    "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
    "KAFKA_LOG_DIRS=/var/lib/kraft-combined-logs",
    "KAFKA_AUTO_CREATE_TOPICS_ENABLE=true",
    "KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",

    "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://payshield_kafka:9092",

    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
  ]

  ports {
    internal = 9092
    external = 9092
  }
}

############################################
# 5. Schema Registry
############################################
resource "docker_image" "schema_registry" {
  name = var.schema_registry_image
}

resource "docker_container" "schema_registry" {
  name  = "payshield_schema_registry"
  image = var.schema_registry_image

  depends_on = [docker_container.kafka]

  networks_advanced {
    name = docker_network.payshield.name
  }

  env = [
    "SCHEMA_REGISTRY_HOST_NAME=schema-registry",
    "SCHEMA_REGISTRY_LISTENERS=http://0.0.0.0:8081",
    "SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=PLAINTEXT://payshield_kafka:9092"
  ]

  ports {
    internal = 8081
    external = 8081
  }
}

############################################
# 6. Redis
############################################
resource "docker_image" "redis" {
  name = var.redis_image
}

resource "docker_container" "redis" {
  name  = "payshield_redis"
  image = var.redis_image

  networks_advanced {
    name = docker_network.payshield.name
  }

  ports {
    internal = 6379
    external = 6379
  }
}

############################################
# 7. PostgreSQL
############################################
resource "docker_image" "postgres" {
  name = var.postgres_image
}

resource "docker_container" "postgres" {
  name  = "payshield_postgres"
  image = var.postgres_image

  env = [
    "POSTGRES_USER=admin",
    "POSTGRES_PASSWORD=admin",
    "POSTGRES_DB=payshield_audit"
  ]

  networks_advanced {
    name = docker_network.payshield.name
  }

  ports {
    internal = 5432
    external = 5432
  }
}

############################################
# 8. Prometheus
############################################
resource "docker_image" "prometheus" {
  name = var.prometheus_image
}

resource "docker_container" "prometheus" {
  name  = "payshield_prometheus"
  image = var.prometheus_image

  volumes {
    host_path      = abspath("${path.module}/prometheus.yml")
    container_path = "/etc/prometheus/prometheus.yml"
    read_only      = true
  }

  networks_advanced {
    name = docker_network.payshield.name
  }

  ports {
    internal = 9090
    external = 9090
  }
}

############################################
# 9. Grafana
############################################
resource "docker_image" "grafana" {
  name = var.grafana_image
}

resource "docker_container" "grafana" {
  name  = "payshield_grafana"
  image = var.grafana_image

  networks_advanced {
    name = docker_network.payshield.name
  }

  ports {
    internal = 3000
    external = 3000
  }
}
