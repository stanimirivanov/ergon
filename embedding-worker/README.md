# Embedding worker

## Status

Legacy predecessor module. It remains buildable while the Ergon evidence
compiler is developed and receives no unrelated new product behavior.

## Purpose

The Spring Boot worker consumes predecessor article events from Kafka, creates
deterministic or Ollama embeddings, and projects chunks into PostgreSQL/pgvector.
It uses port `8082` for management/runtime endpoints.

Start PostgreSQL and Kafka with the root Compose file. Start the optional
`ollama` profile only for explicit local AI testing; default CI must remain
deterministic and independent of paid or mutable model services.

```powershell
.\mvnw.cmd -pl embedding-worker -am spring-boot:run -Dspring-boot.run.profiles=local
.\mvnw.cmd -B -ntp -pl embedding-worker -am verify
```
