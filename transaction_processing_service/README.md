# Background Transaction Processing Service

A small Python 3.10+ demonstration of idempotent transaction processing, duplicate detection,
batch ordering, retry handling, validation, logging, and status summaries. It uses only the Python
standard library.

## Run

```bash
python transaction_processor.py
```

Or specify another JSON Lines input file:

```bash
python transaction_processor.py --input sample_input.jsonl --max-retries 2
```

Each non-empty line must contain one JSON object. The program prints JSON with per-request outcomes,
current transaction states, ledger effects, and summary counts. Logs are written to stderr.

## Tests

From this directory:

```bash
python -m unittest discover -s tests -v
```

## Architecture

- **Input adapter:** `load_jsonl()` reads JSON Lines. Replace this adapter with an HTTP endpoint,
  queue consumer, or database poller without changing business processing.
- **Processor:** validates records, applies duplicate policy, orders accepted records, runs retries,
  and writes the demo ledger effect.
- **Idempotency/state store:** `state` is keyed by `transactionId`; `request_ids` prevents a request
  ID from being reused for a different transaction.
- **Effect sink:** `ledger` is an in-memory stand-in for the actual business side effect.
- **Operations:** structured key/value log messages and `summary()` provide basic visibility.

## Design decisions

### Idempotency and duplicates

`transactionId` is the business idempotency key. A repeated transaction ID is returned as `DUPLICATE`
and does not append a second ledger effect, even when the later request has a different request ID.
A reused `requestId` for a different transaction is rejected as `FAILED` because it may indicate
an upstream correlation bug. In production, enforce these constraints with unique database indexes
and durable transaction state.

### Out-of-order strategy

This implementation uses **batch sequence ordering**: accepted records are sorted by sequence number,
then account ID and transaction ID for deterministic tie-breaking. Sequence gaps within the same
account are logged as warnings, but do not block processing. This is appropriate for a compact demo
where the batch is the available processing window and sequence is an ordering hint. It is not a strict
contiguous-sequence gate. If business correctness requires every earlier event, use a durable per-account
sequence cursor, persist gaps as `PENDING`, and release later records only when missing sequence numbers
arrive or an operator resolves the gap. The supplied sample's sequence values span multiple accounts,
so enforcing per-account sequence 1..N would incorrectly block ACC-202 and ACC-203.

### Retry and failure handling

The demo can simulate a configured number of transient failures per transaction. It retries immediately
up to `max_retries`; after exhaustion the state is `FAILED`. A successful transaction appends its ledger
effect only once. Production retry should use exponential backoff with jitter, a maximum elapsed time,
error classification, and a dead-letter/manual-replay path. Persist attempt count and next-attempt time.

### Status lifecycle

Requests pass through `RECEIVED` → `PROCESSING` → `PROCESSED`, with transient failures briefly marked
`RETRY_PENDING`. Validation failures and exhausted retries end as `FAILED`. Replays return `DUPLICATE`.
`PENDING` is reserved for a future implementation with a strict sequence gate.

### Observability

The demo emits logs for receipt, validation failure, duplicate detection, sequence gaps, transient
failures, and success. The summary reports request outcome counts, current transaction-state counts,
unique transactions, and ledger effects. A production version should add correlation IDs, structured
JSON logs, metrics (throughput, retry rate, oldest pending age, failure rate, processing latency),
tracing, dashboards, and alerts.

## Production hardening recommendations

1. Replace in-memory dictionaries and ledger with durable storage; state must survive process restart.
2. Atomically claim a transaction and apply the business effect in one database transaction, or use a
   transactional outbox/inbox pattern. An in-memory guard does not protect across workers.
3. Enforce unique constraints on `transactionId` and request ID, and use compare-and-set/row locks for
   concurrent consumers.
4. Define queue semantics explicitly: at-least-once delivery is common; consumers must remain idempotent.
   Acknowledge only after durable commit; configure visibility timeout and dead-letter queue.
5. Add retry backoff, jitter, error taxonomy, retry budgets, dead-letter handling, and safe replay tooling.
6. For strict ordering, partition by account and maintain a durable sequence cursor plus gap timeout and
   recovery procedure.
7. Add schema versioning, input size limits, authentication/authorization, secrets management, and audit
   retention before exposing an API.
8. Test concurrent duplicate submissions, process crashes between effect and status update, persistence
   restart, malformed JSON, and integration behavior with the real queue/database.

## AI-assisted development and validation

AI assistance can accelerate scaffolding, test-case generation, and documentation. The candidate should
truthfully describe which suggestions were used, review every generated change, run the unit tests, inspect
the sample output, and manually verify idempotency, ordering, and retry exhaustion. Do not claim validation
steps that were not actually performed. This repository includes unit tests for duplicate, out-of-order,
invalid, retry-success, retry-exhaustion, and request-ID conflict cases.

## Known assessment-scope limitations

This is a single-process in-memory demo, not a durable distributed processor. `PROCESSING` and
`RETRY_PENDING` are represented in state during execution but the CLI prints final request outcomes.
Malformed JSON lines are converted into a parse-error record and then rejected by normal validation.
The demo's simulated failure counter is process-local. These are deliberate two-hour-assessment trade-offs.
