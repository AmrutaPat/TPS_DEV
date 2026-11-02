package com.example.transactions.controller;

import com.example.transactions.dto.*;
import com.example.transactions.domain.TransactionInbox;
import com.example.transactions.repository.TransactionInboxRepository;
import com.example.transactions.service.TransactionIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionIngestionService ingestionService;
    private final TransactionInboxRepository inboxRepository;

    public TransactionController(TransactionIngestionService ingestionService,
                                 TransactionInboxRepository inboxRepository) {
        this.ingestionService = ingestionService;
        this.inboxRepository = inboxRepository;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> submit(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.accepted().body(ingestionService.accept(request));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> get(@PathVariable String transactionId) {
        return inboxRepository.findByTransactionId(transactionId)
                .map(TransactionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<TransactionResponse> list() {
        return inboxRepository.findAll().stream().map(TransactionResponse::from).toList();
    }
}
