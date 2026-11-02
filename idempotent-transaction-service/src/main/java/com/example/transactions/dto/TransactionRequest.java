package com.example.transactions.dto;

import com.example.transactions.domain.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record TransactionRequest(
    @NotBlank @Size(max = 100) String idempotencyKey,
    @NotBlank @Size(max = 100) String transactionId,
    @NotNull @Positive Long accountId,
    @Positive long sequenceNumber,
    @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotNull TransactionType type
) {}
