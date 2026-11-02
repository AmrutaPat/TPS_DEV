package com.example.transactions.service;

import com.example.transactions.domain.TransactionInbox;
import com.example.transactions.dto.*;
import com.example.transactions.repository.TransactionInboxRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class TransactionIngestionService {
    private final TransactionInboxRepository inboxRepository;

    public TransactionIngestionService(TransactionInboxRepository inboxRepository) {
        this.inboxRepository = inboxRepository;
    }

    @Transactional
    public TransactionResponse accept(TransactionRequest request) {
        var existing = inboxRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            TransactionInbox item = existing.get();
            if (!samePayload(item, request)) {
                throw new IdempotencyConflictException(
                        "Idempotency key was already used with a different request payload");
            }
            return TransactionResponse.from(item);
        }

        var transactionIdMatch = inboxRepository.findByTransactionId(request.transactionId());
        if (transactionIdMatch.isPresent()) {
            throw new IdempotencyConflictException("Transaction ID already exists");
        }

        TransactionInbox item = new TransactionInbox(request.idempotencyKey(),
                request.transactionId(), request.accountId(), request.sequenceNumber(),
                request.amount(), request.type());
        try {
            return TransactionResponse.from(inboxRepository.saveAndFlush(item));
        } catch (DataIntegrityViolationException ex) {
            throw new IdempotencyConflictException(
                    "Duplicate idempotency key, transaction ID, or account sequence");
        }
    }

    private boolean samePayload(TransactionInbox existing, TransactionRequest request) {
        return existing.getTransactionId().equals(request.transactionId())
                && existing.getAccountId().equals(request.accountId())
                && existing.getSequenceNumber() == request.sequenceNumber()
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getType() == request.type();
    }

    // Stable hash helper for future payload auditing/signature use.
    @SuppressWarnings("unused")
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
