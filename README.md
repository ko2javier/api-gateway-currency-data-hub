# API Gateway — KO2 Platform

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)
![Spring Cloud Gateway](https://img.shields.io/badge/Spring_Cloud-Gateway-6DB33F?logo=spring)
![WebFlux](https://img.shields.io/badge/Reactive-WebFlux-6DB33F?logo=spring)
![Docker](https://img.shields.io/badge/Docker-deployed-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue)

Reactive API Gateway — the single entry point for the KO2 Platform. Validates JWT tokens, checks the Redis token blacklist, and routes requests to downstream services. Part of the [KO2 Platform](https://github.com/ko2javier/server-infrastructure) microservices ecosystem.

**Live demo:** [hub.ko2-oreilly.com](https://hub.ko2-oreilly.com) · **Swagger:** [api.ko2-oreilly.com/swagger-ui](https://api.ko2-oreilly.com/webjars/swagger-ui/index.html)

---

## What it does

- Single entry point for all client requests (port 7000)
- Validates JWT signature and checks Redis token blacklist on every authenticated request
- Injects `X-User-Name` and `X-User-Roles` headers so downstream services need no JWT dependency
- Routes `/auth/**` to Auth Service and `/weather/**` `/currency/**` to API Service
- Aggregates Swagger UI from all services into a single docs endpoint

## Request flow

```
Client request (Authorization: Bearer <token>)
  → JwtAuthFilter
      → validate JWT signature
      → check Redis blacklist (token invalidated on logout?)
      → inject X-User-Name + X-User-Roles headers
  → Route to downstream service
      /auth/**      → Auth Service :4000
      /weather/**   → API Service  :5000
      /currency/**  → API Service  :5000
```

Public routes (`/auth/login`) bypass the filter.

## Tech stack

| | |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 3.3.5 |
| Gateway | Spring Cloud Gateway (reactive, non-blocking) |
| Reactive | Project Reactor / WebFlux |
| Token validation | JJWT + Redis blacklist check |
| Cache | Redis (Railway) |
| Docs | SpringDoc OpenAPI 3 — aggregated Swagger UI |
| Build | Gradle |
| Deploy | Docker · Hetzner VPS · GitHub Actions CI/CD |

## Design decisions

**Gateway owns authentication — services are JWT-unaware:** Downstream services use a `HeaderAuthFilter` that reads plain HTTP headers injected here. No JWT library, no shared secret. Changing the auth mechanism only touches this service.

**Reactive stack (WebFlux):** Non-blocking I/O for routing and Redis lookups. Consistent with Spring Cloud Gateway's reactive model — avoids blocking threads on I/O-bound operations.

**CORS restricted to explicit origins:** Only `hub.ko2-oreilly.com` (production) and `localhost:4200` (dev) are allowed. No wildcard.

## Part of the KO2 Platform

```
Frontend (Angular 19 · Vercel)
    └── API Gateway :7000  ← this repo
            ├── Auth Service :4000  ← login / logout / token blacklist
            └── API Service :5000  ← weather + currency + multilevel cache
```

→ [server-infrastructure](https://github.com/ko2javier/server-infrastructure) — full architecture, Docker Compose, live demo credentials

## License

MIT
