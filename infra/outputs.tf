output "kafka_bootstrap_server" {
  value = "localhost:9092"
}

output "schema_registry_url" {
  value = "http://localhost:8081"
}

output "redis_address" {
  value = "localhost:6379"
}

output "postgres_dsn" {
  value = "postgres://admin:admin@localhost:5432/payshield_audit?sslmode=disable"
}

output "prometheus_url" {
  value = "http://localhost:9090"
}

output "grafana_url" {
  value = "http://localhost:3000"
}
