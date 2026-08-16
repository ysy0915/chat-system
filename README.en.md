# BoshiAI Agent · Multi-Model Collaboration & Intelligent Debate Platform

> **Let multiple AIs debate, reason, and co-create for you — like a team of experts.**
>
> Author: Yang Siyi · BoshiAI Team · August 2026

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF.svg)](.github/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.1-brightgreen.svg)](pom.xml)
[![Tests](https://img.shields.io/badge/Tests-892%20passed-success.svg)](#running-tests)
[![Node](https://img.shields.io/badge/Node-18%2B-339933.svg)](frontend/package.json)

**License**: This project is licensed under the [Apache License 2.0](LICENSE) — free to use, modify, and distribute.

> ⚠️ The project documentation is primarily in Chinese. This README provides an English overview. For full details, refer to the Chinese documents under [`docs/`](docs/).

---

## Overview

BoshiAI Agent is a **multi-model intelligent collaboration platform**. Unlike traditional AI products where "one model answers one question", BoshiAI brings **Doubao, DeepSeek, and Qwen** (multiple LLMs) into the same conversation to debate, reason, and collaborate on complex tasks — integrated with RAG knowledge retrieval, multimodal generation, and AI games in a single platform.

- **Live Demo**: http://112.124.106.108/chat/home
- **Source Code**: https://github.com/ysy0915/chat-system

> The live environment enforces security interception (User-Agent validation). Script/API calls must include a browser `User-Agent` header; normal browser access is unaffected.

### How It Differs from Traditional AI Products

| Traditional AI | BoshiAI Agent |
|---|---|
| Single-model answers | Multi-model group chat + three-way debate |
| Black-box reasoning | Real-time transparent chain-of-thought |
| One parameter set for all questions | Three-tier intent funnel (graded processing) |
| Chat / drawing / video in separate apps | Unified entry for chat, debate, creation, and games |
| Stuck when a model errors | Automatic model failover / retry / degradation |

---

## Feature Overview

### AI Group Chat
Multiple AI models participate in a public chat room simultaneously, with streaming output, real-time online user count, and cross-user Q&A visibility.

### Private Conversation Space
JWT-authenticated private AI conversations with persistent history, file upload, voice input, voice read-back, history search, model switching, and regeneration. Knowledge/fact questions automatically trigger RAG retrieval augmentation.

### Debate Arena
- **Standard Debate**: randomly picks **3–6 models** from configured chat models to form teams (model count selectable, Chinese display names), 1–10 rounds per session, with reflection + verdict-style summarization each round; supports Redis-backed **cross-session memory** (re-debating the same topic starts with prior stances) and **reflection visualization** (real-time frontend hints during the Reflection phase).
- **Tree Debate**: LLM auto-decomposes the question into 2–3 analytical perspectives → each perspective debated in parallel by 3 fast models (auto-excludes local slow models) → aggregated summary, with a draggable DAG tree visualization on the frontend.

### Emotion Tree Hole
Anonymous emotional outlet with empathetic AI responses. **Memory augmentation**: LLM extracts user profiles (scenario/emotion/preference), making responses progressively personalized. Three-layer memory: Redis short-term → Milvus long-term vectors → user profile.

### Multimodal Generation
Text-to-image / text-to-video / image-to-3D (GLB/OBJ/STL).

### AI Multiplayer Games
Castle Siege (real-time AI lord battles), Snake King (multiplayer snake), AI Pong.

### Knowledge Base RAG
Upload PDF/Word/TXT → auto-parse & chunk → Milvus vector storage → intent-driven retrieval-augmented answers during conversation.

### Knowledge Graph
Neo4j graph database stores entities and relations; LLM auto-extracts triples; frontend Canvas visualizes the knowledge network.

### Multi-Agent Parallel Workflow
Ultra-long / cross-domain requests are auto-decomposed into ≤9 subtasks → distributed via RabbitMQ to dual-instance 10-concurrency Workers for parallel execution → converged and compressed to ≤1000 characters. Global rate limiting + fair distribution + dead-letter retry + reconciliation fallback.

---

## Core Innovations

### 1. Three-Tier Intent Funnel
Computational resources allocated by hit probability: L1 rules (0-1ms) → L2 semantics (30-80ms) → L3 LLM (200-1000ms). L1+L2 hit rate target >95%. Intent drives automatic temperature tuning (code 0.2 / creative 0.95 / translation 0.1). High-confidence L3 results are auto-fed back into L1/L2 (self-reinforcing loop).

### 2. Tree Debate — Plan-and-Execute + LangGraph Hybrid Orchestration
LLM decomposes perspectives (Plan) → Java `CompletableFuture` for inter-perspective parallelism + LangGraph4j `StateGraph` for intra-perspective iterative debate (Execute) → LLM aggregated summary. A single perspective failure doesn't affect others; aggregation failure auto-falls-back to local concatenation.

### 3. Real-Time Chain-of-Thought
Three-state state machine separates reasoning token-by-token, 11-character buffer zero-latency push, 300-character safe degradation, real-time display only (never persisted).

### 4. AI Error Self-Healing
Frequency/auth/network/parse error classification & recovery (model switch / retry / degradation), layered with Resilience4j circuit breaker (50% failure rate → 30s open).

### 5. Multi-Agent Reliability Closed Loop
Redis Lua atomic rate limiting (8 parallel / overload degradation) + manual ack zero-loss + DLX dead-letter exponential backoff retry + Reconciler 30s reconciliation fallback (auto-recovery after server restart).

### 6. Conversational Auto-RAG
Knowledge/fact questions automatically retrieve from the knowledge base; three-tier retrievability check (switch → intent → exclude real-time/personal data); graceful fallback to normal answers on miss.

---

## Technical Architecture

```
Presentation  React SPA (KeepAlive persistent pages · SockJS/STOMP streaming)
              · chain-of-thought rendering · tree-debate DAG canvas · knowledge graph canvas · mobile-friendly

Gateway       chat-web (port 8081)
              JWT auth · three-tier rate limiting · content safety · WebSocket · CoreClient load balancing

Business      chat-core (port 9090 primary / 9092 replica, dual-instance HA)
              chat/debate/tree-hole orchestration · three-tier intent funnel · agent tools · CoT · multi-agent workflow

AI Layer      chat-llm (port 9095)   multi-provider strategy + graph execution engine + RAG + knowledge graph + gRPC
              chat-games (port 8083) · chat-media (port 8084)

Infra         MySQL (RDS) · Redis · RabbitMQ · Nacos · Neo4j · Milvus
              Prometheus stack (12 alert rules → DingTalk push)
```

| Layer | Technology |
|------|------|
| **Backend** | Spring Boot 3.1, Spring Cloud (Nacos), MyBatis, gRPC |
| **AI Engine** | chat-llm standalone LLM service (multi-provider: OpenAI-compatible / DeepSeek / Doubao) + self-built LangGraph-style graph execution engine |
| **Knowledge Base** | Milvus vector DB + Embedding + RAG |
| **Messaging** | RabbitMQ (cross-node broadcast · multi-agent subtask distribution · DLX dead-letter retry) |
| **Databases** | MySQL + Redis + Neo4j |
| **Observability** | Prometheus + Alertmanager + Micrometer Tracing + AOP business metrics |
| **Frontend** | React 18 + Vite + Router v6 + WebSocket streaming |
| **Deployment** | Docker + Docker Compose + Nginx + dual-server architecture |

---

## Project Structure

```
chat-system-project/
├── chat-common/       # Shared library (entities, DTOs, security, utils, interceptors)
├── chat-core/         # Core AI service (orchestration, agent tools, intent recognition)  port 9090/9092
├── chat-web/          # Web gateway (controllers, WebSocket)                               port 8081
├── chat-llm/          # Standalone LLM service (multi-provider, graph engine, RAG, KG, gRPC) port 9095
├── chat-games/        # Game service (castle siege, pong, snake)                           port 8083
├── chat-media/        # Multimodal service (text-to-image/video, image-to-3D)              port 8084
├── frontend/          # Frontend SPA (React + Vite)
├── scripts/           # Ops scripts (deploy, restart, monitor, migrate)
└── docs/              # Full documentation (7-category doc center + ADR + deployment assets)
```

---

## Preview

![Homepage Preview](docs/screenshots/homepage.png)

> Multi-model collaboration & intelligent debate platform homepage — integrating debate arena, knowledge graph, personal chat, emotion tree hole, AI group chat, and multimodal generation.

## Quick Start

### Option 1: Docker One-Click Start (Recommended)

```bash
# Start all middleware + build + backend + frontend
bash scripts/quickstart.sh

# Or only middleware (MySQL/Redis/RabbitMQ/Nacos/Milvus/Neo4j)
bash scripts/quickstart.sh infra

# Stop
bash scripts/quickstart.sh stop
```

> Prerequisites: Docker + JDK 17 + Maven 3.8+ + Node 18+
>
> Or use Docker Compose directly:
> ```bash
> docker compose --profile all up -d    # full deployment (backend + frontend + middleware)
> docker compose --profile dev up -d    # middleware only (local development)
> ```

### Option 2: Local Development (Manual)

#### Requirements
- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0, Redis 6+, RabbitMQ 3.9+
- Milvus 2.3+ (optional, required for knowledge base)

```bash
# 1. Start infrastructure
brew install redis rabbitmq
brew services start redis
brew services start rabbitmq

# 2. Build
mvn clean install -DskipTests

# 3. Start chat-llm (must start before core)
java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=9095

# 4. Start chat-core
java -jar chat-core/target/chat-core-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=9090

# 5. Start chat-web (new terminal)
java -jar chat-web/target/chat-web-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=8080 \
  --app.core.base-url=http://127.0.0.1:9090

# 6. Start frontend
cd frontend && npm install && npm run dev
```

Access:
- Frontend: http://localhost:5173
- API: http://localhost:8080/api/v1/*
- Swagger UI: http://localhost:8080/swagger-ui.html

> **API note**: Production enforces security interception (UA validation + sensitive-endpoint rate limiting). Script/tool API calls must include a browser `User-Agent` header, otherwise `403` is returned. Example:
> ```bash
> curl -H "User-Agent: Mozilla/5.0" http://localhost:8080/api/v1/messages/online-count
> ```

### chat-llm Standalone Mode (Zero-Dependency)

chat-llm supports **standalone deployment with zero external dependencies** — no MySQL/Redis/Neo4j/Milvus required. Model management, RAG retrieval, conversation memory, and knowledge graph all use in-memory implementations in a single process. Ideal for local demos or as a generic LLM gateway.

```bash
# 1. Build (only chat-llm and its chat-common dependency)
mvn clean install -DskipTests -pl chat-llm -am

# 2. Configure API keys (only what you need; production uses DB `llm_provider_props` as the single source of truth, refreshed every 60s — no restart needed)
export DEEPSEEK_API_KEY=sk-xxx
export QWEN_API_KEY=sk-xxx
export DOUBAO_API_KEY=sk-xxx

# 3. Start standalone (HTTP 9095 / gRPC 9195)
java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=standalone --server.port=9095
```

> See `chat-llm/STANDALONE.md` for full details and curl examples.

---

## Running Tests

```bash
# Full test suite (892 cases, all green)
mvn clean test

# Per module
mvn test -pl chat-common  # 277 tests
mvn test -pl chat-core    # 257 tests
mvn test -pl chat-web     # 90 tests
mvn test -pl chat-llm     # 198 tests
mvn test -pl chat-games   # 44 tests
mvn test -pl chat-media   # 26 tests
```

---

## Security & Compliance

| Policy | Details |
|------|------|
| IP rate limiting | 600 req/min |
| User rate limiting | 20 req/min, 200 req/hour |
| Sensitive endpoint limiting | Login/register 10 req/min |
| Login anti-brute-force | 5 consecutive failures → 15 min lockout |
| Registration captcha | Arithmetic captcha, 5-min validity, single-use |
| Auto-ban | >1000 req/60s → 10 min ban |
| Content safety | Alibaba Cloud content-safety API (porn/violence/sensitive content blocked) |
| Data isolation | JWT auth + user-level session isolation, chain-of-thought never persisted |

---

## Engineering Metrics

| Dimension | Metric |
|------|------|
| Testing | **892 test cases all green**, incl. @SpringBootTest integration & Mapper contract tests |
| Code quality | Checkstyle **0 violations** · PMD 2000+→92 · SpotBugs 0 blockers |
| Architecture | Dual core/web HA + stop broadcast + nodeId anti-backlog + LangGraph hybrid + multi-agent workflow |
| Model abstraction | Provider strategy + SPI factory + registry + dynamic routing + self-service model management + tool platformization + storage SPI hot-swap |
| Observability | Prometheus stack (8 system + 4 business alert rules) + Micrometer Tracing |
| Documentation | 7-category doc center + 25 ADRs + Swagger |
| CI/CD | GitHub Actions CI + Deploy + Security + OWASP dependency scanning |
| Load test | 500 concurrent, P50 154ms, zero failures |

---

## Documentation

> Full categorized index: [docs/README.md](docs/README.md) (Chinese). Key English-relevant docs:

| Document | Content |
|------|------|
| [Architecture Overview](docs/01-架构设计/架构全盘说明.md) | Master architecture → module details → core flows → data flow → deployment |
| [Architecture Decision Records](docs/01-架构设计/ADR-架构决策记录.md) | 25 key architecture decisions (context/decision/consequence) |
| [Deployment & Ops Guide](docs/03-运维部署/部署运维手册.md) | Local/server/Docker deployment, monitoring & alerting |
| [Troubleshooting Guide](docs/03-运维部署/故障排查指南.md) | Symptom → root cause → fix |

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow and coding standards.

- Report bugs / request features: use [Issue templates](.github/ISSUE_TEMPLATE/)
- Submit code: Fork → feature branch → PR (must pass CI)
- Code of conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

## License

Licensed under the [Apache License 2.0](LICENSE).

---

## About

- **Author**: Yang Siyi · BoshiAI Team
- **GitHub**: https://github.com/ysy0915/chat-system
- **Live Demo**: http://112.124.106.108/chat/home

### Project Timeline

The project was initiated in **late July 2026** and completed a full design-to-production iteration in about two weeks:

| Date | Milestone |
|------|--------|
| 07-30 | Project kickoff, frontend scaffolding, first backend startup |
| 08-03~09 | Feature development: UI/logo, multi-model sessions, architecture design |
| 08-11 | Multi-model collaboration architecture finalized, baseline load testing |
| 08-12 | Model abstraction SPI factory + self-service model management |
| 08-13 | Multi-Agent parallel workflow + tool platformization + storage SPI + 892 tests green |
| 08-14 | chat-llm standalone mode + tree debate multi-model |
| 08-15 | Performance & stability hardening + V1.2.0 DDL + open-source standardization |
| 08-16 | Security hardening (method-level auth / WS auth / log masking / upload SSRF) + zero dependency vulnerabilities + web auto-scaling (Nacos upstream) + real online-count stats (895 tests green) |
| 08-17 | Personal chat perf fix (11s→1-3s) + configurable deep thinking (frontend toggle) + doubao mini swap + tree debate true parallelism + package refactor + controller layering + memory governance + games leaderboard fix + feature trim |

> **About commit history**: During early development, model API keys were accidentally committed to the repository. To thoroughly purge sensitive information, the repository was archived and rebuilt, so git commit timestamps are concentrated after 08-14. Full iteration details: [Architecture Assessment Report](docs/01-架构设计/架构评估报告.md) & [CHANGELOG-3.0.md](docs/07-变更与经验/CHANGELOG-3.0.md).

> BoshiAI Agent — AI that doesn't just answer, but debates, reasons, and empathizes.
