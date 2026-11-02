package com.example.transactions.dto;

import com.example.transactions.domain.TransactionInbox;
import com.example.transactions.domain.TransactionStatus;
import java.time.Instant;

public record TransactionResponse(String transactionId, String idempotencyKey,
                                  TransactionStatus status, int attempts,
                                  String lastError, Instant createdAt) {
    public static TransactionResponse from(TransactionInbox item) {
        return new TransactionResponse(item.getTransactionId(), item.getIdempotencyKey(),
                item.getStatus(), item.getAttempts(), item.getLastError(), item.getCreatedAt());
    }
}
