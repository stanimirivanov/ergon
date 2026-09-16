# QA service

## Status

Legacy predecessor module retained while Ergon replaces chat-centric Q&A with
the case and resolution model. Do not add new Ergon capabilities here.

## Purpose

The Spring Boot service coordinates the predecessor ask-question flow using the
retrieval service, MongoDB, Redis, and an Ollama-compatible model. It listens on
port `8084`.

```powershell
docker compose --profile ai up -d mongodb redis ollama
.\mvnw.cmd -pl qa-service -am spring-boot:run -Dspring-boot.run.profiles=local
.\mvnw.cmd -B -ntp -pl qa-service -am verify
```

The question API and conversation model are not Ergon compatibility boundaries.
