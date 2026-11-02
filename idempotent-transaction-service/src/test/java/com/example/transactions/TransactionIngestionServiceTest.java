package com.example.transactions;

import com.example.transactions.domain.TransactionType;
import com.example.transactions.dto.TransactionRequest;
import com.example.transactions.repository.TransactionInboxRepository;
import com.example.transactions.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class TransactionIngestionServiceTest {
    @Autowired TransactionIngestionService service;
    @Autowired TransactionInboxRepository repository;

    @Test
    void sameIdempotencyKeyAndSamePayloadReturnsExistingRequest() {
        var request = new TransactionRequest("KEY-1", "TX-1", 1001L, 1,
                new BigDecimal("100.00"), TransactionType.CREDIT);

        var first = service.accept(request);
        var second = service.accept(request);

        assertEquals(first.transactionId(), second.transactionId());
        assertEquals(1, repository.count());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        var first = new TransactionRequest("KEY-2", "TX-2", 1001L, 1,
                new BigDecimal("100.00"), TransactionType.CREDIT);
        var changed = new TransactionRequest("KEY-2", "TX-2", 1001L, 1,
                new BigDecimal("200.00"), TransactionType.CREDIT);

        service.accept(first);
        assertThrows(IdempotencyConflictException.class, () -> service.accept(changed));
    }
}
