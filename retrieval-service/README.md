# Retrieval service

## Status

Legacy predecessor module retained during the Ergon rewrite. Its public search
contract is not a compatibility target for new Ergon work.

## Purpose

The Spring Boot service performs tenant-scoped predecessor hybrid retrieval over
PostgreSQL full-text and pgvector indexes. It uses Ollama-compatible embedding
configuration and listens on port `8083`.

```powershell
docker compose --profile ai up -d postgres ollama
.\mvnw.cmd -pl retrieval-service -am spring-boot:run -Dspring-boot.run.profiles=local
.\mvnw.cmd -B -ntp -pl retrieval-service -am verify
```

Default verification uses deterministic/test adapters and must not depend on a
live model provider.
