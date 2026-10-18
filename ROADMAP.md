# Tunnel Server: Backend Feature Roadmap

An open-source, self-hostable tunneling system (ngrok-style) built in Java, deployed on a Contabo VPS. This document covers **backend features only**: the tunnel engine, client agent (CLI), APIs, security, infrastructure and operations. No frontend or UI work is included.

**Legend**
- **[MVP]** needed for a first working release
- **[v1]** needed for a solid public release
- **[Later]** future or advanced work

---

## 1. Core Tunneling Engine

| Feature | Stage |
|---|---|
| HTTP/HTTPS tunnels with subdomain routing (`myapp.yourdomain.com`) | MVP |
| TCP tunnels on allocated public ports (SSH, databases, game servers) | MVP |
| Stream multiplexing: many public connections over one client control connection | MVP |
| Custom (reserved) subdomains and random subdomains | MVP |
| Automatic client reconnect with exponential backoff and session resume | MVP |
| Heartbeats (PING/PONG) and dead-connection detection | MVP |
| Backpressure and flow control so slow clients cannot exhaust server memory | MVP |
| Subdomain-to-session registry (in-memory first, shared store later) | MVP |
| TLS tunnels with SNI-based routing and passthrough | v1 |
| WebSocket and SSE passthrough | v1 |
| HTTP/2 and gRPC support | v1 |
| Custom domains (CNAME verification, automatic certificates) | v1 |
| Multiple tunnels per client from one config | v1 |
| Host header rewriting and custom header injection | v1 |
| UDP tunnels (game servers, DNS, WireGuard) | Later |
| Path-based routing (`/api` to one port, `/` to another) | Later |
| Compression (gzip/zstd) on the control channel | Later |
| Load balancing across multiple clients sharing one tunnel name | Later |
| Static file and directory serving mode | Later |
| Webhook relay: buffer and replay webhooks while the client is offline | Later |

### Protocol design (multiplexing)
- Frame format: `[streamId][type][length][payload]`
- Frame types: `OPEN`, `DATA`, `CLOSE`, `PING`, `PONG`, `REGISTER`, `ERROR`
- Alternative transports: WebSocket or HTTP/2 for built-in framing
- Control messages encoded with Jackson (JSON) or Protobuf
- Protocol versioning and capability negotiation between client and server **[v1]**
- Public protocol specification so third parties can write clients **[Later]**

---

## 2. CLI Client (Agent)

| Feature | Stage |
|---|---|
| Commands: `login`, `logout`, `http <port>`, `tcp <port>`, `start`, `list`, `stop`, `config`, `version` | MVP |
| Flags: `--subdomain`, `--domain`, `--auth`, `--region`, `--inspect` | MVP |
| Authtoken storage in a secure local config file | MVP |
| Clear error messages and meaningful exit codes | MVP |
| YAML config file with multiple named tunnels | v1 |
| Device login flow (`login` obtains a token through a verification code) | v1 |
| Native-image build checked on every pull request from the first client networking code ([ADR-0006](docs/adr/0006-cli-distribution-native-binaries.md)) | MVP |
| Cross-platform binaries: Windows, macOS, Linux (x64 and ARM) via GraalVM native-image, released through JReleaser | v1 |
| Install methods: `install.sh` (curl), Homebrew tap, Scoop bucket | v1 |
| Self-update and version check (`update`) | Later |
| Install methods: winget, apt/deb and rpm, Docker image | Later |
| Signed binaries: macOS notarization, Windows code signing | Later |
| Run as a background service (systemd, Windows service) | Later |
| Shell autocompletion | Later |

---

## 3. Local Request Inspection (Client-side API)

Capture and replay traffic through a local HTTP API on the client, with no UI work required.

- Capture request and response metadata, headers, bodies and timing **[v1]**
- Replay a captured request, with optional modification **[v1]**
- Query and filter captured requests by path, method and status **[v1]**
- Export as HAR or cURL **[Later]**
- Webhook signature debugging helpers (Stripe, GitHub, etc.) **[Later]**

---

## 4. Authentication and Accounts

| Feature | Stage |
|---|---|
| Email/password registration with email verification | MVP |
| Login, logout, password reset | MVP |
| API tokens (authtokens): create, name, revoke, scope | MVP |
| Secrets hashing: tokens and passwords stored hashed, never in plaintext | MVP |
| OAuth / social login (GitHub, Google) | v1 |
| Two-factor authentication (TOTP, recovery codes) | v1 |
| Session management: list and revoke active sessions | v1 |
| Device authorization flow for CLI login | v1 |
| Account deletion and data export (GDPR) | v1 |
| Passkeys / WebAuthn | Later |
| SSO via SAML / OIDC for organizations | Later |

---

## 5. Teams and Organizations

- Organizations with members and roles (owner, admin, member, viewer) **[Later]**
- Email invitations **[Later]**
- Shared reserved domains and tokens **[Later]**
- Per-team quotas and billing **[Later]**
- Audit log: who created or stopped which tunnel, and when **[Later]**

---

## 6. Access Control and Security

| Feature | Stage |
|---|---|
| Rate limiting per tunnel, per IP and per user | MVP |
| Request size limits and timeouts | MVP |
| Blocklist of forbidden subdomains (`login`, `paypal`, `admin`, etc.) | MVP |
| Token and password hashing (Argon2 or bcrypt) | MVP |
| Basic auth on a tunnel | v1 |
| IP allow/deny lists (CIDR) | v1 |
| Interstitial warning response for free-tier traffic (anti-phishing) | v1 |
| Abuse reporting endpoint and admin takedown | v1 |
| OAuth-protected tunnels (visitor must authenticate) | Later |
| Phishing and malware detection hooks (e.g. Google Safe Browsing API) | Later |
| Mutual TLS for client-to-server authentication | Later |
| Signed webhook verification | Later |

---

## 7. TLS and Certificates

- Automatic wildcard certificate through Let's Encrypt DNS-01 (acme4j) **[MVP]**
- Auto-renewal with hot reload and no downtime **[MVP]**
- HTTP to HTTPS redirect **[MVP]**
- Modern TLS configuration (TLS 1.2+/1.3, HSTS) **[MVP]**
- Automatic per-domain certificates for custom domains (HTTP-01 or TLS-ALPN-01) **[v1]**
- Certificate storage and rotation handling **[v1]**

---

## 8. Plans, Quotas and Billing

| Feature | Stage |
|---|---|
| Plan tiers (Free, Pro, Team) with feature gating | v1 |
| Quotas: concurrent tunnels, bandwidth, requests per minute, reserved domains | v1 |
| Usage metering and enforcement | v1 |
| Self-hosted mode with billing module fully disabled | v1 |
| Payment integrations: Stripe, plus M-Pesa / Flutterwave / Paystack | Later |
| Invoices and receipts | Later |

---

## 9. Observability and Operations

| Feature | Stage |
|---|---|
| Structured JSON logging | MVP |
| Health endpoints (`/health`, `/ready`) | MVP |
| Metrics via Micrometer and Prometheus | v1 |
| Admin API: manage users, tunnels, bans, plans, system stats | v1 |
| Live session listing and forced session termination | v1 |
| Alerting (email, Slack, Telegram) | Later |
| Distributed tracing (OpenTelemetry) | Later |
| Uptime monitoring endpoints for a public status service | Later |

---

## 10. Backend Platform Components

| Component | Notes | Stage |
|---|---|---|
| PostgreSQL | Users, tokens, domains, plans, audit data | MVP |
| Flyway or Liquibase | Schema migrations | MVP |
| Background jobs | Certificate renewal, usage rollups, stale-session cleanup | MVP |
| Email service | SMTP, Resend or SES for verification, resets and alerts | MVP |
| Redis | Rate limits, sessions, pub/sub for multi-node routing | v1 |
| REST API | OpenAPI/Swagger documented, covering all account and tunnel operations | v1 |
| Public API and API keys | Lets users automate tunnel creation | Later |
| Outgoing webhooks | Events such as tunnel started/stopped | Later |
| Java SDK (plus other languages) | Embed tunnels in apps and tests | Later |

---

## 11. Scaling and Infrastructure

| Feature | Stage |
|---|---|
| Single-node deployment on Contabo | MVP |
| Docker and docker-compose (server, Postgres, Redis, reverse proxy) | MVP |
| Server hardening: UFW, fail2ban, SSH keys only | MVP |
| Zero-downtime deploys and graceful shutdown that drains tunnels | v1 |
| Automated backups (Postgres dumps to object storage) | v1 |
| DDoS mitigation (Cloudflare in front of the API; tunnel ports are harder to proxy) | v1 |
| CI/CD with GitHub Actions: build, test, release binaries, deploy | v1 |
| Multi-region / edge nodes (`--region eu\|us\|asia\|africa`) | Later |
| Multi-node routing with a shared session registry | Later |

### Contabo-specific notes
- **Ports:** inbound is open by default, so manage access with UFW. Reserve a range for TCP tunnels (e.g. 20000-30000).
- **Bandwidth:** check the port speed and fair-use terms if you expect heavy traffic.
- **IPv6:** included, so IPv6 tunnels are cheap to offer.
- **No managed services:** you run Postgres, Redis and backups yourself, so automate backups early.
- **Single point of failure:** one VPS means downtime during reboots, so reconnect logic in the client matters.
- **IP reputation:** tunnel abuse can get your IP flagged, so the abuse controls in section 6 protect the server.

---

## 12. Open-Source Project Essentials

- License choice: Apache-2.0, or AGPL-3.0 to discourage closed SaaS forks **[MVP]**
- README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY.md **[MVP]**
- Issue and PR templates, changelog, semantic versioning **[MVP]**
- Automated tests: unit, integration (client and server in-process), load tests **[MVP]**
- Self-hosting guide with one-command install **[v1]**
- Protocol spec and API reference documentation **[v1]**
- Plugin / extension points (custom auth providers, custom storage) **[Later]**

---

## 13. Suggested Module Layout (Maven / Gradle multi-module)

```
common        shared utilities and models
protocol      frame definitions and message codecs
server-core   tunnel engine, routing, session registry
server-api    REST API, auth, quotas, billing, admin
client-core   tunnel client logic, reconnect, inspection API
client-cli    command-line interface and packaging
```

Keeping `protocol` separate lets contributors work on one piece at a time and makes third-party clients possible.

---

## 14. Suggested Build Order

1. TCP-only tunnel: client registers, server opens a public port, bytes are forwarded
2. Add multiplexing so many connections share one control channel
3. Add HTTP host-based routing (subdomain to client)
4. Add TLS, auth tokens and reconnect logic
5. Add accounts, API tokens and Postgres persistence
6. Add rate limiting, quotas, abuse controls and observability
7. Add custom domains, inspection API, teams and billing
8. Add extras: WebSocket passthrough, UDP, multi-region, SDKs