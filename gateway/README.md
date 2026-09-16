# Gateway

## Status

Legacy predecessor module. It remains buildable during the rewrite and must not
be presented as Ergon's production edge or authorization layer.

## Purpose

The Spring Cloud Gateway application routes the predecessor article and
question APIs to locally running services. It listens on port `8080` and has no
Ergon control-plane route.

```powershell
.\mvnw.cmd -pl gateway -am spring-boot:run -Dspring-boot.run.profiles=local
.\mvnw.cmd -B -ntp -pl gateway -am verify
```

Future Ergon ingress belongs to a separately designed security and deployment
slice, not an implicit extension of these routes.
