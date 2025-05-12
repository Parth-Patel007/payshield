output "kafka_bootstrap_servers" {
  description = "Bootstrap address for Kafka (host‐accessible)"
  value       = "localhost:9092"
}

output "schema_registry_url" {
  description = "URL for Schema Registry"
  value       = "http://localhost:8081"
}

output "redis_url" {
  description = "URL for Redis"
  value       = "localhost:6379"
}

output "postgres_url" {
  description = "JDBC URL for Postgres"
  value       = "jdbc:postgresql://localhost:5432/payshield_audit"
}

output "prometheus_url" {
  description = "Prometheus UI URL"
  value       = "http://localhost:9090"
}

output "grafana_url" {
  description = "Grafana UI URL"
  value       = "http://localhost:3000"
}
