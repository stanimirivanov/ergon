# Ingestion service

## Status

Legacy predecessor module. It remains buildable while Ergon replaces the old
article/RAG model and is not a target for new product capabilities.

## Purpose

The Spring Boot service owns the predecessor knowledge-article event stream,
projections, idempotent commands, and transactional outbox. It uses PostgreSQL,
Kafka, Flyway, and port `8081`.

Start local infrastructure with `docker compose up -d postgres kafka`, then:

```powershell
.\mvnw.cmd -pl ingestion-service -am spring-boot:run -Dspring-boot.run.profiles=local
```

Reuse behavior only through an explicit Ergon migration slice. The article API
is not an Ergon compatibility requirement.

```powershell
.\mvnw.cmd -B -ntp -pl ingestion-service -am verify
```
