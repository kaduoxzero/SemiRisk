# SemiRisk Go + LangGraph Rewrite Plan

> Canonical language: English. Chinese counterpart: `go-langgraph-rewrite-plan.zh-CN.md`.

## 1. Product boundary

SemiRisk remains a semiconductor supply-chain risk intelligence platform. The Agent is one subsystem, not the product center.

The rewritten system must continue to deliver:

- public-source ingestion and normalization
- semiconductor supply-chain signal storage
- deterministic risk scoring and snapshots
- alert lifecycle and analyst workflow
- enterprise profiles
- GIS-oriented risk views
- knowledge ingestion and retrieval
- risk reports
- system administration and RBAC
- AI-assisted investigation and knowledge Q&A

The existing Java/Spring deployment is a six-service backend. The rewrite collapses this into a Go modular monolith while preserving public API behavior during migration.

## 2. Target architecture

```text
Vue 3 UI
   |
   v
Go HTTP API / Modular Monolith
   |
   +-- Auth / RBAC
   +-- Ingestion / Crawler
   +-- Risk Engine
   +-- Alerts
   +-- Enterprise
   +-- GIS
   +-- Knowledge
   +-- Reports
   +-- System
   +-- Job Runtime
   +-- Agent Gateway
   |
   +-----------------------+
   |                       |
   v                       v
PostgreSQL + pgvector   Python Agent Runtime
Business data           LangGraph orchestration
FTS / vector index      Reasoning / planning
Jobs / audit            Evidence synthesis
   ^                       |
   |                       |
   +------ typed tool RPC--+
```

## 3. Ownership rules

### Go owns

- external HTTP API
- authentication, sessions/tokens, RBAC, CSRF and rate limiting
- PostgreSQL business schema and migrations
- ingestion, parsing, normalization and deduplication
- deterministic risk scoring
- alert state transitions
- report-job lifecycle
- enterprise and GIS data aggregation
- object-store access
- scheduling and durable jobs
- audit logs
- Agent tool execution and authorization

### Python/LangGraph owns

- Agent graph orchestration
- question decomposition
- investigation planning
- retrieval planning
- evidence synthesis
- hypothesis generation
- citation-aware answer generation
- report narrative drafting
- model routing and prompt policy
- Agent checkpoint state

### Hard rule

Python Agent code must not directly mutate SemiRisk business tables. Business reads/writes are exposed as typed tools from Go.

LangGraph may use a dedicated PostgreSQL schema for checkpoints through a restricted database role. The Agent role must not have write permission on the business schema.

## 4. Data platform

### Replace MySQL and Elasticsearch with PostgreSQL + pgvector

PostgreSQL becomes the durable system of record.

Required extensions:

- `vector`
- `pg_trgm`
- `citext`

Search model:

```text
Hybrid Search
 = PostgreSQL full-text search
 + trigram similarity
 + pgvector semantic search
 + recency / risk weighting
```

Primary knowledge tables:

- `knowledge_documents`
- `knowledge_chunks`
- `crawler_signals`
- `enterprise_records`
- `risk_snapshots`
- `risk_alerts`

Agent observability tables:

- `agent_runs`
- `agent_steps`
- `agent_citations`

LangGraph checkpointer tables live in a separate `langgraph` schema.

### Remove mandatory infrastructure

The rewrite removes these as hard dependencies:

- Redis
- RabbitMQ
- Nacos
- Zipkin
- Elasticsearch

Replacement strategy:

- cache: in-process cache first; PostgreSQL where durability is required
- queue: PostgreSQL job table + `FOR UPDATE SKIP LOCKED`
- locking: PostgreSQL advisory locks
- scheduling: Go scheduler + durable job rows
- tracing: structured logs and OpenTelemetry-compatible hooks
- search: PostgreSQL FTS + pgvector

MinIO/S3-compatible object storage remains an optional adapter because uploads and generated reports are binary artifacts.

## 5. Go repository layout

```text
cmd/semirisk/
    main.go

internal/
    app/
    config/
    httpapi/
    middleware/
    storage/postgres/
    jobs/
    audit/
    objectstore/
    agentbridge/

    modules/
        auth/
        ingest/
        risk/
        alert/
        enterprise/
        gis/
        knowledge/
        report/
        system/
        agent/

agent/
    pyproject.toml
    src/semirisk_agent/
        worker.py
        protocol.py
        contracts/
        graphs/
        nodes/
        tools/
        models/
        prompts/
        checkpoints/
        evaluation/
```

No package may directly query another module's tables. Cross-module access goes through application services or repository interfaces.

## 6. Agent runtime model

The Go process manages one or more persistent Python workers.

Initial transport:

- persistent child process
- JSONL typed envelopes over stdin/stdout
- stderr reserved for logs
- configurable worker pool

The transport is deliberately replaceable. A future remote Agent deployment may use gRPC without changing domain APIs.

Each Agent run has:

- `run_id`
- `user_id`
- `kind`
- `trace_id`
- budget
- model profile
- graph version
- checkpoint namespace

## 7. LangGraph graph catalog

### 7.1 Knowledge QA Graph

Used by `/api/knowledge/ask`.

```text
Intake
 -> ClassifyQuestion
 -> BuildRetrievalPlan
 -> HybridRetrieve
 -> RerankEvidence
 -> AssessEvidence
 -> SynthesizeAnswer
 -> VerifyCitations
 -> Finalize
```

State includes:

- question
- normalized query
- intent
- search queries
- retrieved chunks
- evidence set
- citations
- answer draft
- verification findings
- model usage

The graph must never invent a citation. Every citation must resolve to a stored document/chunk or an explicitly fetched public source.

### 7.2 Risk Investigation Graph

Used when an analyst asks for a deeper investigation of an event, company, region or material.

```text
Intake
 -> ScopeInvestigation
 -> GatherSignals
 -> ExpandEntities
 -> AnalyzeTimeline
 -> AnalyzeSupplyChainImpact
 -> GenerateHypotheses
 -> EvidenceCheck
 -> RiskConclusion
 -> RecommendActions
 -> Finalize
```

Output is an investigation artifact with claims, evidence, uncertainty and recommended analyst actions.

### 7.3 Daily Risk Report Graph

```text
LoadSnapshot
 -> SelectMaterialEvents
 -> ClusterSignals
 -> BuildRiskNarrative
 -> DraftSections
 -> VerifyFacts
 -> FinalizeReport
```

Go owns report-job state and file generation. LangGraph generates structured report content, not PDF/DOCX/PPTX bytes.

### 7.4 Alert Triage Graph

```text
LoadAlert
 -> CorrelateSignals
 -> AssessSeverity
 -> ExplainReasoning
 -> RecommendDisposition
```

The graph may recommend `PROCESSING`, `IGNORED` or escalation, but Go performs the actual state transition after policy/analyst approval.

### 7.5 Enterprise Intelligence Graph

```text
LoadEnterprise
 -> RetrieveRelatedSignals
 -> BuildTimeline
 -> IdentifyDependencies
 -> AssessRiskDrivers
 -> ProduceProfileInsight
```

## 8. Agent tool surface

Phase 1 tools are read-only:

- `knowledge.search`
- `knowledge.get_document`
- `signal.search`
- `signal.get`
- `risk.snapshot.latest`
- `risk.timeline`
- `alert.get`
- `enterprise.search`
- `enterprise.get`
- `gis.context`
- `source.fetch`

Later controlled tools:

- `report.save_draft`
- `alert.propose_disposition`
- `investigation.save`

Direct SQL, filesystem and shell access are not Agent tools.

## 9. Model layer

Chat and embedding providers are separate abstractions.

```text
ChatProvider
EmbeddingProvider
Reranker (optional)
```

This avoids coupling DeepSeek chat models to vector generation.

If embeddings are unavailable, the product must continue operating with PostgreSQL FTS + trigram retrieval. Agent execution must degrade gracefully rather than fail the whole platform.

## 10. API compatibility and new Agent API

Existing `/api/...` routes remain compatible while the Vue UI is migrated.

Compatibility route:

- `POST /api/knowledge/ask` -> starts `knowledge_qa` graph

New canonical Agent API:

- `POST /api/agent/runs`
- `GET /api/agent/runs/{id}`
- `GET /api/agent/runs/{id}/events` (SSE)
- `POST /api/agent/runs/{id}/cancel`
- `GET /api/agent/runs/{id}/citations`

Agent runs are first-class durable records and can be resumed after worker restart through LangGraph checkpoints.

## 11. PostgreSQL job runtime

Async work must not require RabbitMQ in v1.

`jobs` table fields:

- id
- kind
- payload
- status
- priority
- attempts
- max_attempts
- run_after
- locked_by
- locked_at
- last_error
- created_at
- updated_at

Workers claim jobs with `FOR UPDATE SKIP LOCKED`.

Jobs include:

- crawler refresh
- document chunking
- embedding generation
- risk recalculation
- daily report generation
- report rendering
- Agent execution

## 12. Migration phases

### R0 - Architecture and parity inventory

- freeze current `/api` contract
- inventory current MySQL data
- define PostgreSQL schema
- define Go module boundaries
- define Agent protocol and LangGraph state schemas

Exit: architecture tests and API inventory complete.

### R1 - Go foundation + PostgreSQL

- Go HTTP server
- configuration
- PostgreSQL pool
- migrations
- health/readiness
- structured logging
- common response/error model
- pgvector/FTS schema

Exit: service starts against PostgreSQL and migrations pass from empty DB.

### R2 - Auth + System

Migrate:

- login/logout/me
- RBAC
- CSRF
- password hashing/reset
- user administration
- audit logs
- model configuration metadata

Exit: existing Vue authentication flow works unchanged against Go.

### R3 - Ingestion + Risk + Alerts

Migrate:

- public-source crawler
- normalization and dedup
- risk snapshots
- deterministic scoring
- alert derivation
- alert lifecycle
- dashboard/risk APIs

Exit: Go output is parity-tested against representative Java fixtures.

### R4 - Knowledge platform

- document model
- chunking pipeline
- PostgreSQL FTS
- pgvector embeddings
- hybrid retrieval
- citations
- knowledge search APIs

Exit: Elasticsearch is no longer required.

### R5 - LangGraph Agent Runtime

- persistent Python worker
- typed Go/Python protocol
- LangGraph checkpointer
- model providers
- read-only Go tools
- Knowledge QA Graph
- streaming run events

Exit: `/api/knowledge/ask` runs through LangGraph and produces verifiable citations.

### R6 - Investigation + Report Agents

- Risk Investigation Graph
- Daily Risk Report Graph
- Alert Triage Graph
- Enterprise Intelligence Graph
- structured artifacts
- model/evidence evaluation

Exit: AI report generation no longer depends on single-shot chat calls.

### R7 - Reports + Uploads + Object Store

- upload parsing
- artifact storage adapter
- report jobs
- PDF/DOCX/PPTX rendering
- SSE progress

Exit: current upload/report UI behavior is preserved.

### R8 - Frontend cleanup

- remove legacy assumptions about six backend ports
- add Agent run streaming UX
- expose citations and investigation traces
- update system-management health view for PostgreSQL/pgvector and Agent worker

Exit: all production UI paths use Go API only.

### R9 - Data migration and cutover

- MySQL -> PostgreSQL migration tool
- Elasticsearch -> knowledge_documents/chunks import
- ID mapping and verification
- dual-run parity window
- freeze Java writes
- final delta migration
- switch production traffic

Exit: PostgreSQL is authoritative and Java services are read-only/offline.

### R10 - Java removal and release hardening

- delete Spring/Maven backend
- delete obsolete Redis/RabbitMQ/Nacos/Zipkin/Elasticsearch deployment files
- simplify Docker Compose
- add Go + Python release CI
- backup/restore tests
- load tests
- security tests
- migration rollback procedure

Exit: a clean environment runs SemiRisk with PostgreSQL + Go + Agent runtime + optional object storage only.

## 13. Testing strategy

### Go

- unit tests per module
- repository integration tests against PostgreSQL + pgvector
- API contract tests
- migration tests
- auth/security tests
- job locking/retry tests

### Agent

- LangGraph node tests
- graph routing tests
- checkpoint/resume tests
- tool contract tests
- citation correctness tests
- hallucination regression set
- semiconductor-domain golden questions

### End to end

- ingest source -> signal -> risk snapshot -> alert
- upload -> parse -> knowledge chunk -> retrieval
- question -> LangGraph -> hybrid retrieval -> cited answer
- signal -> investigation graph -> evidence-backed conclusion
- daily snapshot -> report graph -> rendered report
- crash Agent worker -> resume run

## 14. Release gates

A rewritten feature is complete only when:

1. Go implementation exists.
2. PostgreSQL persistence is real.
3. API contract is tested.
4. Existing UI path works or is intentionally migrated.
5. No Java service is required for that domain.
6. If AI is involved, the path runs through LangGraph.
7. Agent output has evidence/citation verification where applicable.
8. Restart/recovery behavior is tested.

## 15. Final production topology

Minimum production deployment:

```text
semirisk-web      Vue static assets
semirisk          Go binary
semirisk-agent    Python LangGraph worker (managed by Go or sidecar)
postgres          PostgreSQL + pgvector
object-store      optional S3/MinIO for binary artifacts
```

The target is deliberately not a microservice platform. Scale-out boundaries may be introduced later only when measured load requires them.
