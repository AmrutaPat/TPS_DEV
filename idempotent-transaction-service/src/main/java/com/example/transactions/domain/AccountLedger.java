package com.example.transactions.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_ledger",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_ledger_transaction_id", columnNames = "transaction_id"),
           @UniqueConstraint(name = "uk_ledger_account_sequence", columnNames = {"account_id", "sequence_number"})
       })
public class AccountLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 100)
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

    @Column(nullable = false)
    private Instant processedAt;

    protected AccountLedger() {}

    public AccountLedger(String transactionId, Long accountId, long sequenceNumber,
                         BigDecimal amount, TransactionType type) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.sequenceNumber = sequenceNumber;
        this.amount = amount;
        this.type = type;
        this.processedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public Instant getProcessedAt() { return processedAt; }
}
