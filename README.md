# Idempotent Background Transaction Processing Service

## 1. Overview

This project implements a reliable background transaction processing service using Java 21 and Spring Boot.

The service accepts asynchronous financial transaction requests and processes them safely despite:

- Duplicate requests from upstream systems.
- Out-of-order transaction delivery.
- Client retries after network timeouts.
- Temporary database or processing failures.
- Concurrent requests targeting the same account.
- Application restarts and worker retries.

The primary objective is to ensure that a transaction produces **at most one business effect**, even when the request is delivered multiple times.

### Core design principle

The system uses at-least-once processing with database-enforced idempotency and atomic transactions to achieve effectively-once business processing.

It does not claim literal exactly-once message delivery.

---

## 2. Technology Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| REST APIs | Spring Web |
| Persistence | Spring Data JPA |
| Database | H2 (development/demo) |
| Validation | Jakarta Bean Validation |
| Background processing | Spring Scheduler |
| Testing | JUnit 5, Mockito |
| Monitoring | Spring Boot Actuator |
| Build | Maven |

**Production database recommendation:** PostgreSQL, with database migrations and appropriate locking and indexing strategies.

---

## 3. Architecture

The application follows a simple layered architecture.

```text
             Upstream Systems
                    |
                    v
             REST Controller
                    |
                    v
          Transaction Ingestion
                    |
                    v
             Inbox Repository
                    |
                    v
             Durable Inbox
                    |
                    v
          Background Worker
                    |
                    v
         Transaction Processor
                    |
             +------+------+
             |             |
             v             v
        Account Table   Ledger Table
             |             |
             +------+------+
                    |
                    v
             Database Commit
```

### Component responsibilities

**1. Transaction Controller**
- Exposes the transaction ingestion API.
- Validates incoming requests.
- Returns transaction identifiers and processing status.

**2. Transaction Ingestion Service**
- Validates idempotency keys.
- Detects duplicate transaction submissions.
- Rejects conflicting requests.
- Persists accepted requests in the inbox.

**3. Transaction Inbox**
- Stores transaction requests before processing.
- Tracks processing status, attempts, and retry eligibility.
- Provides a durable record of accepted work.

**4. Background Worker**
- Polls eligible pending transactions.
- Delegates transaction execution to the processing service.
- Handles retryable and permanent failures.

**5. Transaction Processing Service**
- Locks the relevant transaction and account.
- Checks transaction ordering.
- Validates account balance.
- Updates account balance.
- Creates a ledger entry.
- Marks the inbox record as completed.

These database changes are committed within a single transaction.

---

## 4. Important Architecture Decisions

### 4.1 Durable inbox instead of processing inside the API request

The API does not perform the complete financial transaction synchronously.

Instead, it persists the request and allows a background worker to process it.

**Why?**

- Reduces API response time.
- Separates ingestion from execution.
- Allows recovery after application restarts.
- Supports retries without requiring upstream resubmission.
- Provides visibility into pending and failed work.

The inbox acts as a durable work queue.

### 4.2 Idempotency key

Every request must contain an idempotency key.

Example:

```http
POST /api/transactions
Idempotency-Key: payment-10001
Content-Type: application/json
```

The idempotency key uniquely identifies a logical client operation.

Expected behavior:

| Scenario | Expected result |
|---|---|
| Same key, same payload | Return existing transaction |
| Same key, different payload | HTTP 409 Conflict |
| New key, new transaction | Accept and enqueue |
| Previously completed request retried | Do not execute again |

The database enforces uniqueness of the idempotency key.

The payload should also be compared so that a client cannot accidentally reuse an idempotency key for a different operation.

### 4.3 Database-level consistency

Application-level checks alone are insufficient for concurrent transaction processing.

The database provides the final consistency boundary through:

- Unique constraints.
- Transactional commits and rollbacks.
- Account row locking.
- Ledger uniqueness constraints.
- Optimistic versioning where appropriate.

For a debit operation, the account balance must never become negative.

### 4.4 Atomic transaction processing

The following operations must succeed or fail together:

1. Validate transaction eligibility.
2. Update account balance.
3. Insert ledger entry.
4. Mark inbox record as completed.

If any operation fails, the database transaction rolls back.

This prevents a situation where the account balance changes but the corresponding ledger entry is missing.

### 4.5 Transaction ordering

Each account has a sequence number for ordering its transactions.

For example:

| Account | Sequence | Transaction |
|---|---:|---|
| 1001 | 1 | Debit ₹500 |
| 1001 | 2 | Credit ₹1,000 |
| 1001 | 3 | Debit ₹200 |

If sequence 3 arrives before sequence 2, sequence 3 is deferred.

It is not processed until the missing sequence is completed.

This protects account state from out-of-order delivery.

---

## 5. Idempotency Guarantees

The implementation aims to guarantee:

1. A logical transaction is accepted only once for a given idempotency key.
2. A repeated request with the same key and payload does not create another business transaction.
3. A repeated request with a different payload is rejected.
4. A completed transaction is not applied again.
5. A ledger uniqueness constraint protects against duplicate ledger entries.
6. Account updates and ledger creation are committed atomically.

### Important distinction

The system does not guarantee that a worker executes a transaction only once.

A worker may attempt the same transaction more than once because of retries, process crashes, or overlapping polling.

Instead, it guarantees that repeated processing attempts cannot create duplicate committed business effects, provided all business changes remain within the protected database transaction and uniqueness constraints.

For external services, additional idempotency mechanisms are required.

---

## 6. Failure Scenarios and Recovery

| Failure scenario | Handling strategy |
|---|---|
| Duplicate API request | Return existing transaction status |
| Same key with different payload | Reject with HTTP 409 |
| Worker crashes before processing | Pending inbox record remains available |
| Database failure during processing | Roll back the transaction |
| Temporary processing failure | Retry with exponential backoff |
| Retry limit exceeded | Mark transaction DEAD |
| Future sequence arrives early | Defer until missing sequence is processed |
| Insufficient account balance | Mark as permanent business failure |
| Duplicate ledger insertion | Database uniqueness constraint prevents duplicate effect |

### Example: Worker crash during processing

Consider this sequence:

1. Worker reads a pending transaction.
2. Worker locks the account.
3. Worker updates the balance.
4. Application crashes before database commit.

Because the database transaction was not committed, the balance update and ledger insertion are rolled back.

The inbox transaction remains eligible for retry.

On the next attempt, the worker processes the transaction again.

This is why atomic database transactions are essential.

### Example: API timeout after successful ingestion

1. Client submits a transaction.
2. Server persists the request.
3. Server commits successfully.
4. Network connection fails before the response reaches the client.
5. Client retries using the same idempotency key.

The service identifies the existing request and returns its transaction status instead of creating another transaction.

---

## 7. Retry and Dead-Letter Strategy

Transient failures should not immediately result in permanent transaction failure.

The service uses retry attempts with increasing delays.

Example backoff:

```text
Attempt 1: 1 second
Attempt 2: 2 seconds
Attempt 3: 4 seconds
Attempt 4: 8 seconds
```

After the configured retry limit, the transaction is marked as `DEAD`.

In this implementation, `DEAD` is a terminal database status rather than a separate message-broker dead-letter queue.

In production, I would add:

- Retry limits configurable by transaction type.
- Exponential backoff with jitter.
- Operational alerts for dead transactions.
- Manual replay with audit tracking.
- A dedicated dead-letter workflow if a message broker is introduced.

Permanent business failures, such as insufficient balance, should not be retried as transient technical failures.

---

## 8. API Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/transactions` | Submit a transaction |
| GET | `/api/transactions/{transactionId}` | Retrieve transaction status |
| GET | `/api/transactions` | List transactions |
| GET | `/api/accounts/{id}` | Retrieve account details |
| GET | `/actuator/health` | Application health |

### Example request

```json
{
  "transactionId": "TXN-10001",
  "idempotencyKey": "PAYMENT-10001",
  "accountId": 1001,
  "sequenceNumber": 1,
  "type": "DEBIT",
  "amount": 500.00,
  "description": "Utility payment"
}
```

The exact field names and validation rules are defined by the DTO implementation.

---

## 9. Running the Application

### Prerequisites

- Java 21
- Maven 3.9+

### Build and test

```bash
mvn clean test
```

### Start the application

```bash
mvn spring-boot:run
```

Application URL:

```text
http://localhost:8080
```

H2 console:

```text
http://localhost:8080/h2-console
```

JDBC URL:

```text
jdbc:h2:mem:transactionsdb
```

Username: `sa`

Password: blank

Note: H2 is configured for demonstration. Its in-memory data is lost when the application stops.

---

## 10. Testing Strategy

The most important tests for this application are not limited to successful API requests.

I would validate:

- Same idempotency key and same payload.
- Same idempotency key and different payload.
- Concurrent duplicate submissions.
- Duplicate transaction IDs with different idempotency keys.
- Out-of-order transaction delivery.
- Insufficient balance.
- Database rollback during processing.
- Worker retry after temporary failures.
- Retry exhaustion and DEAD status.
- Concurrent debits against the same account.
- Duplicate ledger prevention.

For a production-ready release, these should include integration tests against PostgreSQL and multi-worker concurrency tests.

---

## 11. Production Readiness and Future Enhancements

This project is a focused assessment implementation, not a claim of production readiness.

For production deployment, I would extend it with:

| Area | Enhancement |
|---|---|
| Database | PostgreSQL with Flyway migrations |
| Worker coordination | Atomic claims using `SELECT ... FOR UPDATE SKIP LOCKED` or lease-based claiming |
| Scalability | Multiple worker instances |
| Messaging | Kafka or SQS where asynchronous broker integration is required |
| Observability | Structured logs, correlation IDs, metrics and distributed tracing |
| Security | OAuth2/JWT, service authorization and audit controls |
| Reliability | Backoff with jitter, configurable retries and operational replay |
| External integrations | Transactional outbox and downstream idempotency |
| Testing | Testcontainers and concurrency tests |

### Multi-instance worker coordination

The current demo worker polls the inbox and locks individual records during processing.

For multiple production instances, I would introduce an atomic claim mechanism with lease expiration or PostgreSQL `SKIP LOCKED`.

This prevents several workers from repeatedly selecting the same pending work and provides better recovery when a worker dies after claiming a transaction.

### External side effects

Database transactions cannot atomically commit changes in an external payment gateway, notification service, or another microservice.

I would use the transactional outbox pattern:

1. Commit the account update, ledger entry and outbox event together.
2. Publish the outbox event asynchronously.
3. Track publication status.
4. Require downstream idempotency to handle repeated delivery.

---

## 12. Engineering Decisions and Trade-offs

The key trade-off is simplicity versus production scalability.

For a two-hour assessment, a database-backed inbox and scheduled worker provide a clear, testable implementation without introducing unnecessary infrastructure.

For a high-throughput enterprise banking platform, I would evaluate:

- Broker-based ingestion using Kafka or SQS.
- Partitioning by account identifier to preserve ordering.
- Atomic database claiming and worker leases.
- Database connection pool sizing.
- Backpressure and rate limiting.
- Partitioning or archiving large inbox and ledger tables.
- Reconciliation jobs and operational replay.

The architecture deliberately separates request acceptance from transaction execution so that the system can evolve without changing its core business consistency rules.

---

## 13. Conclusion

The central objective of this implementation is to make transaction processing reliable in the presence of duplicate delivery, out-of-order requests, concurrent execution and partial failures.

The solution combines:

- Durable request persistence.
- Idempotency keys.
- Database-enforced uniqueness.
- Atomic account and ledger updates.
- Transaction ordering.
- Retry and terminal failure handling.
