package com.example.transactions.worker;

import com.example.transactions.domain.TransactionStatus;
import com.example.transactions.repository.TransactionInboxRepository;
import com.example.transactions.service.TransactionProcessingService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransactionWorker {
    private static final Logger log = LoggerFactory.getLogger(TransactionWorker.class);

    private final TransactionInboxRepository inboxRepository;
    private final TransactionProcessingService processingService;

    @Value("${app.worker.batch-size:20}")
    private int batchSize;
    @Value("${app.worker.initial-backoff-ms:2000}")
    private long initialBackoffMs;
    @Value("${app.worker.max-backoff-ms:60000}")
    private long maxBackoffMs;
    @Value("${app.worker.enabled:true}")
    private boolean enabled;

    public TransactionWorker(TransactionInboxRepository inboxRepository,
                             TransactionProcessingService processingService) {
        this.inboxRepository = inboxRepository;
        this.processingService = processingService;
    }

    @Scheduled(fixedDelayString = "${app.worker.fixed-delay-ms:1500}")
    public void poll() {
        if (!enabled) return;
        var ids = inboxRepository.findEligibleIds(TransactionStatus.PENDING,
                Instant.now(), PageRequest.of(0, batchSize));
        for (Long id : ids) {
            try {
                processingService.processOne(id);
            } catch (TransactionProcessingService.PermanentTransactionException ex) {
                processingService.markPermanentFailure(id, ex.getMessage());
            } catch (Exception ex) {
                long delay = Math.min(initialBackoffMs * (1L << Math.min(10, retryCount(id))), maxBackoffMs);
                log.warn("Transaction inbox item {} failed; retry scheduled: {}", id, ex.toString());
                processingService.recordFailure(id, ex.getMessage(), delay);
            }
        }
    }

    private int retryCount(Long id) {
        return inboxRepository.findById(id).map(item -> item.getAttempts()).orElse(0);
    }
}
