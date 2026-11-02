package com.example.transactions.repository;

import com.example.transactions.domain.AccountLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountLedgerRepository extends JpaRepository<AccountLedger, Long> {
    Optional<AccountLedger> findByTransactionId(String transactionId);
    List<AccountLedger> findByAccountIdOrderBySequenceNumber(Long accountId);
}
