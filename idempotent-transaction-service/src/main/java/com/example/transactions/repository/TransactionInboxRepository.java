package com.example.transactions.repository;

import com.example.transactions.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface TransactionInboxRepository extends JpaRepository<TransactionInbox, Long> {
    Optional<TransactionInbox> findByIdempotencyKey(String key);
    Optional<TransactionInbox> findByTransactionId(String transactionId);

    @Query("select t.id from TransactionInbox t where t.status = :status and t.nextRetryAt <= :now order by t.createdAt")
    List<Long> findEligibleIds(@Param("status") TransactionStatus status,
                               @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionInbox t where t.id = :id")
    Optional<TransactionInbox> findByIdForUpdate(@Param("id") Long id);
}
