package com.example.transactions.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {
    @Id
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private long lastProcessedSequence;

    @Version
    private Long version;

    protected Account() {}

    public Account(Long accountId, BigDecimal balance, long lastProcessedSequence) {
        this.accountId = accountId;
        this.balance = balance;
        this.lastProcessedSequence = lastProcessedSequence;
    }

    public Long getAccountId() { return accountId; }
    public BigDecimal getBalance() { return balance; }
    public long getLastProcessedSequence() { return lastProcessedSequence; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setLastProcessedSequence(long value) { this.lastProcessedSequence = value; }
}
