package com.example.shop.common.inbox;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Call at the start of a consumer's transaction. The insert is part of that transaction, so "marked processed"
 * and "business effect applied" commit or roll back together.
 */
@Component
public class IdempotencyGuard {

    private final ProcessedEventRepository repository;

    public IdempotencyGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /** @return true if this is the first time the event is seen; false for a duplicate that must be skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(UUID eventId) {
        return repository.insertIfAbsent(eventId) == 1;
    }
}
