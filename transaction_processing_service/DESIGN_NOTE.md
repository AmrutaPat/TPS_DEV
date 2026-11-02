# Design Note — Background Transaction Processing Service

## Objective
Demonstrate safe handling of duplicate, out-of-order, invalid, and retry-prone transaction requests
while keeping input ingestion separate from processing logic.

## Architecture
JSONL input adapter → validation and deduplication → deterministic batch ordering → retry-aware processor
→ in-memory ledger effect → logs and summary. The input adapter and effect sink are replaceable seams
for a queue/API and a database-backed ledger.

## Idempotency
The business key is `transactionId`. The processor records a unique transaction in state before
processing; subsequent occurrences return `DUPLICATE`. The ledger append is guarded by an
`effect_applied` flag. A reused `requestId` for a different transaction is rejected. This protects
the demo process only; production needs durable unique constraints and atomic effect/state updates.

## Out-of-order handling
Accepted records are sorted by sequence number within the batch, with stable tie-breakers. Gaps are
logged and do not block the batch. This trade-off suits a bounded demo batch. A strict ordered workflow
would persist a per-account sequence cursor, park later records in `PENDING`, and release them when gaps
are filled or explicitly resolved. The provided sample has sequence numbers across different accounts,
so a per-account contiguous sequence rule would be inappropriate without a domain-specific sequence contract.

## Retry and failure
Transient errors are simulated by transaction ID. Retries are immediate and bounded by `max_retries`.
A record that succeeds after a retry produces one ledger effect; exhaustion produces `FAILED` with no
effect. Production should add backoff/jitter, durable retry metadata, error classification, and dead-letter
handling.

## Lifecycle
`RECEIVED` → `PROCESSING` → `PROCESSED`; transient error → `RETRY_PENDING` → retry; exhausted retry or
validation failure → `FAILED`; repeated business transaction → `DUPLICATE`. `PENDING` is reserved for a
future strict sequence-gating implementation.

## Observability
Logs cover receipt, validation failures, duplicates, sequence gaps, transient errors, and successful
processing. `summary()` reports request outcome counts and current transaction state counts, unique
transactions, and ledger effect count. Production metrics should include throughput, processing latency,
retry rate, failure rate, queue lag, and age of oldest pending item.

## Production-readiness gaps
- In-memory state is lost on restart and cannot coordinate multiple workers.
- The side effect and state transition are not atomic across a crash.
- No concurrency control, durable queue, backoff scheduler, DLQ, auth, tracing, or alerting.
- Strict sequence enforcement and gap recovery are not implemented.

Recommended next steps: persistent store with unique indexes, transactional inbox/outbox, queue consumer
with safe acknowledgement, concurrency tests, retry scheduling, DLQ/replay tooling, and metrics/alerts.

## AI-assisted development
AI can help scaffold implementation, suggest edge cases, and draft documentation. The candidate should
state the actual AI usage transparently, run the test suite, review the implementation, and validate the
sample output. Do not represent generated code as tested until tests have actually been run.
