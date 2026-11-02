package com.example.transactions.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transaction_inbox",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_inbox_idempotency_key", columnNames = "idempotency_key"),
           @UniqueConstraint(name = "uk_inbox_account_sequence", columnNames = {"account_id", "sequence_number"})
       },
       indexes = {
           @Index(name = "idx_inbox_status_retry", columnList = "status,next_retry_at"),
           @Index(name = "idx_inbox_transaction_id", columnList = "transaction_id")
       })
public class TransactionInbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 100)
    private String transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextRetryAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 1000)
    private String lastError;

    @Version
    private Long version;

    protected TransactionInbox() {}

    public TransactionInbox(String key, String transactionId, Long accountId,
                            long sequenceNumber, BigDecimal amount, TransactionType type) {
        this.idempotencyKey = key;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.sequenceNumber = sequenceNumber;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.attempts = 0;
        this.nextRetryAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = TransactionStatus.COMPLETED;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void defer(String reason) {
        this.status = TransactionStatus.PENDING;
        this.nextRetryAt = Instant.now().plusMillis(1500);
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    public void recordFailure(String reason, int maxAttempts, long delayMillis) {
        this.attempts++;
        this.lastError = reason;
        this.updatedAt = Instant.now();
        this.status = this.attempts >= maxAttempts ? TransactionStatus.DEAD : TransactionStatus.PENDING;
        this.nextRetryAt = Instant.now().plusMillis(delayMillis);
    }

    public void markFailed(String reason) {
        this.status = TransactionStatus.FAILED;
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastError() { return lastError; }
}
