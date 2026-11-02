# Idempotent Background Transaction Processing Service

A runnable assessment starter implementation using Java 21, Spring Boot 3.5.6, Spring Data JPA, H2 and a scheduled background worker.

## What it demonstrates

- REST transaction ingestion returning HTTP 202.
- Idempotency key checks; same key + same payload returns the existing request.
- Same key with a changed payload returns HTTP 409.
- Database uniqueness constraints on idempotency key, transaction ID, and account sequence.
- Durable inbox table and scheduled worker.
- Per-inbox and per-account pessimistic locks.
- Atomic account balance update + ledger insert + inbox completion.
- Sequence ordering: later sequence numbers are deferred until gaps are filled.
- Retry with exponential backoff and terminal DEAD state after configured attempts.
- Permanent business failures such as missing account and insufficient funds.
- Actuator health endpoint and H2 console.

## Prerequisites

- JDK 21
- Maven 3.9+
- IntelliJ IDEA

## Run

```bash
mvn clean test
mvn spring-boot:run
```

Application: http://localhost:8080

H2 console: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:transactionsdb`
- User: `sa`
- Password: (blank)

Health: http://localhost:8080/actuator/health

## Demo accounts

| Account ID | Opening balance | Last sequence |
|---|---:|---:|
| 1001 | 10000.00 | 0 |
| 1002 | 5000.00 | 0 |

## Submit a transaction

```bash
curl -i -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "REQ-10001",
    "transactionId": "TX-10001",
    "accountId": 1001,
    "sequenceNumber": 1,
    "amount": 125.00,
    "type": "DEBIT"
  }'
```

Expected response: HTTP 202 Accepted. The scheduled worker processes the request asynchronously.

## Verify status and balance

```bash
curl http://localhost:8080/api/transactions/TX-10001
curl http://localhost:8080/api/accounts/1001
```

## Test idempotency

Submit the exact same JSON again. It returns the existing transaction rather than creating another inbox item.

Change the amount but keep `idempotencyKey` the same. The API returns HTTP 409.

## Test out-of-order delivery

Submit sequence 2 before sequence 1 for the same account. Sequence 2 stays PENDING with a sequence-gap reason. Submit sequence 1; the worker can then process sequence 1 followed by sequence 2.

## Design notes and assessment limitations

This is a focused assessment implementation, not a fully hardened payment platform.

- H2 is in-memory; data is lost on restart. Use PostgreSQL for durable production deployment.
- The worker fetches candidate IDs and then locks each inbox row. This is safe for the demonstrated database transaction boundaries, but a production multi-node worker should use a database-specific atomic claim strategy (for example PostgreSQL `FOR UPDATE SKIP LOCKED`) and lease/claim metadata.
- `@Version` is included as an additional optimistic concurrency guard; pessimistic locks serialize account processing.
- The ledger unique constraints are a final duplicate-effect guard.
- The sample supports one ordered sequence per account. If the source system has independent streams, use a stream/partition key instead of account ID.
- A sequence gap is deferred rather than failed. A stale sequence not already represented in the ledger is marked FAILED for investigation.
- External side effects must not be called inside this database transaction and assumed to be atomic. Use an outbox and downstream idempotency for external systems.
- Add authentication/authorization, rate limiting, retention, alerting, structured correlation IDs, database migrations (Flyway), and operational dashboards before production.

## Suggested assessment walkthrough

1. Explain the inbox pattern and the unique idempotency constraint.
2. Demonstrate duplicate submission.
3. Demonstrate payload mismatch (409).
4. Demonstrate sequence 2 arriving before sequence 1.
5. Explain the atomic transaction boundary for account + ledger + inbox.
6. Explain retry versus permanent failure.
7. Run tests and show the health endpoint.

## Suggested GitHub Copilot workflow

Use Copilot Plan mode to review the design, then ask Agent mode to:
1. Review the project for correctness and concurrency hazards.
2. Add PostgreSQL profile and Flyway migrations.
3. Add integration tests for worker crash/rollback and concurrent duplicate submissions.
4. Add Testcontainers-based PostgreSQL tests.
5. Add structured logging and metrics.
Always inspect the generated diff and run the tests.
