package com.example.shop.common.outbox;

import com.example.shop.common.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final TraceContextCodec traces;
    private final boolean cdc;

    /**
     * @param cdc true when Debezium streams the outbox table from the database log (outbox.cdc.enabled): the row only
     *            has to exist briefly in the WAL, so it is deleted right after the insert instead of piling up.
     */
    @Autowired
    public OutboxService(OutboxRepository repository, ObjectMapper mapper, TraceContextCodec traces,
                         @Value("${outbox.cdc.enabled:false}") boolean cdc) {
        this.repository = repository;
        this.mapper = mapper;
        this.traces = traces;
        this.cdc = cdc;
    }

    public OutboxService(OutboxRepository repository, ObjectMapper mapper, TraceContextCodec traces) {
        this(repository, mapper, traces, false);
    }

    /** Without tracing (unit tests, simple uses). */
    public OutboxService(OutboxRepository repository, ObjectMapper mapper) {
        this(repository, mapper, null, false);
    }

    /** MANDATORY: an event must be saved atomically with the state change that caused it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, DomainEvent event) {
        try {
            OutboxEvent row = new OutboxEvent(event.eventId(), event.orderId().toString(), topic,
                    event.eventType(), mapper.writeValueAsString(event))
                    .withTraceContext(traces == null ? null : traces.capture());
            if (cdc) {
                // flush so the INSERT really reaches the WAL (Debezium reads it there), then delete in the same transaction
                repository.saveAndFlush(row);
                repository.delete(row);
            } else {
                repository.save(row);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.eventType(), e);
        }
    }
}
