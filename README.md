# Payshield Real-Time Fraud-Detection Pipeline

## Overview
A solo project (Jan – Apr 2025) that builds a real-time streaming platform for fraud and AML detection using:
- Java 11, Apache Kafka (Avro + Schema Registry), Apache Flink CEP  
- PostgreSQL, Redis  
- Docker, Terraform  
- Prometheus & Grafana observability

## Diagram
<img width="1250" alt="Screenshot 2025-05-12 at 3 27 45 PM" src="https://github.com/user-attachments/assets/284cddcb-c575-4152-b983-5c99cd22f1d8" />



## Quick Start

1. Bring up the stack  
   $ terraform init
   $ terraform apply -auto-approve
   

3. Verify that each service is running:

   Kafka broker  
   $ docker exec payshield_kafka kafka-topics --bootstrap-server localhost:9092 --list

   Flink JMX exporter (port 9404)  
   $ curl -s http://localhost:9404/metrics | head -n5

   Kafka JMX exporter (port 9101)  
   $ curl -s http://localhost:9101/metrics | grep kafka_server_BrokerTopicMetrics

   Redis exporter (port 9121)  
   $ curl -s http://localhost:9121/metrics | grep redis_up

   Postgres exporter (port 9187)  
   $ curl -s http://localhost:9187/metrics | grep pg_up

   Schema Registry  
   $ curl http://localhost:8081/subjects

4. Teardown everything  
   $ terraform destroy -auto-approve

## URLs

- Grafana:   http://localhost:3000  
- Prometheus: http://localhost:9090  
- Schema Registry: http://localhost:8081  
