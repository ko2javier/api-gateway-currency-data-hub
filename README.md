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
| Cache | Redis 7.2 (self-hosted, same Docker network) |
| Docs | SpringDoc OpenAPI 3 — aggregated Swagger UI |
| Build | Gradle |
| Deploy | GitHub Actions → ghcr.io → Hetzner VPS, pinned by commit SHA |

## Design decisions

**Gateway owns authentication — services are JWT-unaware:** Downstream services use a `HeaderAuthFilter` that reads plain HTTP headers injected here. No JWT library, no shared secret. Changing the auth mechanism only touches this service.

**Reactive stack (WebFlux):** Non-blocking I/O for routing and Redis lookups. Consistent with Spring Cloud Gateway's reactive model — avoids blocking threads on I/O-bound operations.

**CORS restricted to explicit origins:** Only `hub.ko2-oreilly.com` (production) and `localhost:4200` (dev) are allowed. No wildcard.

**A JWT blacklist that fails open:** if Redis is unreachable, the blacklist check is skipped and the request continues on signature alone — so a Redis outage silently re-validates every logged-out token until it expires. The alternative, rejecting while Redis is down, trades a security hole for a full platform outage. The behaviour is deliberate and pinned by a test that proves it (`RedisCaidoIT`), so it cannot change unnoticed.

## Tests

```bash
./gradlew test            # 15 tests, ~35 s
```

They need Docker: a **real Redis** runs in a container (Testcontainers), because the token blacklist is the one thing a mock cannot prove. The suite starts the gateway on a real port and sends real HTTP through it — a Spring Cloud Gateway `GlobalFilter` only runs when routing actually happens, so a mocked environment would skip the filter under test entirely.

What they pin: the 401 contract and its JSON body, a forged signature, an expired token, the two identity headers (`X-User-Name`, `X-User-Roles`) that every downstream service trusts, a blacklisted token, the public routes, and the fail-open behaviour above — asserted by stopping the Redis container mid-test.

**The suite was tested against itself.** Four faults were injected one at a time — skipping the blacklist lookup, dropping the identity header, adding `/weather` to the public paths, and deleting a route from the YAML — and each one turns the suite red.

CI runs them on every push to `master` **before** the server is touched: if they fail, nothing is built and nothing is deployed.

## Deployment

```
push to master ──▶ tests          15 tests, real Redis via Testcontainers
                     └──▶ build   image to ghcr.io, tagged `latest` and the commit SHA
                            └──▶ deploy   SSH: pull that SHA, restart, wait for 200
```

Each stage gates the next, so a red test never reaches the registry and a failed build never reaches the server.

**The image is built here, not on the VPS.** Until 18 Oct 2026 the deploy step SSH'd in and ran `gradle bootJar` on the server itself: three and a half minutes of compilation, and RAM spent on a box where RAM is the binding constraint. Worse, the `git pull` happened *before* the build, so a compilation failure left the server's checkout on the new commit with the old container still running.

**Deploys pin the commit SHA, not `latest`.** The running container states which code it serves, and a rollback is the same command with the previous SHA. The deploy waits for a 200 from `/actuator/health` and fails loudly, with container logs attached, if it never arrives.

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
