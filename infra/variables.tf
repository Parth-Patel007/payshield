variable "docker_network_name" {
  default = "payshield"
}


variable "kafka_image" {
  type    = string
  default = "confluentinc/cp-kafka:7.6.0"
}

variable "schema_registry_image" {
  type    = string
  default = "confluentinc/cp-schema-registry:7.6.0"
}

variable "redis_image" {
  type    = string
  default = "redis:7.0-alpine"
}

variable "postgres_image" {
  type    = string
  default = "postgres:15"
}

variable "prometheus_image" {
  type    = string
  default = "prom/prometheus:latest"
}

variable "grafana_image" {
  type    = string
  default = "grafana/grafana:latest"
}
