package com.example.transactions.service;

import com.example.transactions.domain.*;
import com.example.transactions.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class TransactionProcessingService {
    private final TransactionInboxRepository inboxRepository;
    private final AccountRepository accountRepository;
    private final AccountLedgerRepository ledgerRepository;

    @Value("${app.worker.max-attempts:5}")
    private int maxAttempts;

    public TransactionProcessingService(TransactionInboxRepository inboxRepository,
                                        AccountRepository accountRepository,
                                        AccountLedgerRepository ledgerRepository) {
        this.inboxRepository = inboxRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public void processOne(Long inboxId) {
        TransactionInbox inbox = inboxRepository.findByIdForUpdate(inboxId).orElse(null);
        if (inbox == null || inbox.getStatus() != TransactionStatus.PENDING
                || inbox.getNextRetryAt().isAfter(Instant.now())) {
            return;
        }

        // The ledger unique constraint is the final guard against duplicate business effects.
        if (ledgerRepository.findByTransactionId(inbox.getTransactionId()).isPresent()) {
            inbox.markCompleted();
            return;
        }

        Account account = accountRepository.findByIdForUpdate(inbox.getAccountId())
                .orElseThrow(() -> new PermanentTransactionException("Account not found: " + inbox.getAccountId()));

        long expected = account.getLastProcessedSequence() + 1;
        if (inbox.getSequenceNumber() > expected) {
            inbox.defer("Waiting for sequence " + expected);
            return;
        }
        if (inbox.getSequenceNumber() < expected) {
            inbox.markFailed("Stale sequence. Expected " + expected
                    + " but received " + inbox.getSequenceNumber());
            return;
        }

        BigDecimal updatedBalance = inbox.getType() == TransactionType.CREDIT
                ? account.getBalance().add(inbox.getAmount())
                : account.getBalance().subtract(inbox.getAmount());

        if (inbox.getType() == TransactionType.DEBIT && updatedBalance.signum() < 0) {
            throw new PermanentTransactionException("Insufficient funds");
        }

        account.setBalance(updatedBalance);
        account.setLastProcessedSequence(inbox.getSequenceNumber());
        ledgerRepository.save(new AccountLedger(inbox.getTransactionId(),
                inbox.getAccountId(), inbox.getSequenceNumber(),
                inbox.getAmount(), inbox.getType()));
        inbox.markCompleted();
    }

    @Transactional
    public void recordFailure(Long inboxId, String message, long delayMillis) {
        inboxRepository.findByIdForUpdate(inboxId).ifPresent(inbox -> {
            if (inbox.getStatus() == TransactionStatus.PENDING) {
                inbox.recordFailure(message, maxAttempts, delayMillis);
            }
        });
    }

    @Transactional
    public void markPermanentFailure(Long inboxId, String message) {
        inboxRepository.findByIdForUpdate(inboxId).ifPresent(inbox -> {
            if (inbox.getStatus() == TransactionStatus.PENDING) {
                inbox.markFailed(message);
            }
        });
    }

    public static class PermanentTransactionException extends RuntimeException {
        public PermanentTransactionException(String message) { super(message); }
    }
}
