package com.example.shop.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and publishes to Kafka (at-least-once). The order id is the record key, so all events of one
 * order land on one partition and keep their order. Duplicates are possible after a crash between send and commit;
 * consumers are idempotent (see IdempotencyGuard). Each send runs inside a span restored from the trace context that
 * was stored with the event, so distributed traces survive the asynchronous outbox hop.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)   // false when Debezium CDC does the publishing
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TraceContextCodec traces;
    private final Counter published;
    private final Counter failures;

    @Autowired
    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, TraceContextCodec traces,
                       ObjectProvider<MeterRegistry> meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.traces = traces;
        MeterRegistry registry = meters.getIfAvailable();
        this.published = registry == null ? null : Counter.builder("outbox.published")
                .description("Outbox events delivered to Kafka").register(registry);
        this.failures = registry == null ? null : Counter.builder("outbox.publish.failures")
                .description("Outbox publish attempts that failed and will be retried").register(registry);
    }

    /** Without tracing or metrics (unit tests). */
    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka) {
        this.repository = repository;
        this.kafka = kafka;
        this.traces = null;
        this.published = null;
        this.failures = null;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockNextBatch(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            Span span = traces == null ? null : traces.startRelaySpan(event.getTraceContext(), "outbox relay " + event.getTopic());
            try (var ignored = traces == null ? null : traces.activate(span)) {
                kafka.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
                event.markPublished();
                if (published != null) {
                    published.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // stop here to preserve ordering; already-sent events in this batch are still committed
                log.warn("Outbox publish failed for {} ({}), will retry: {}", event.getId(), event.getTopic(), e.toString());
                if (failures != null) {
                    failures.increment();
                }
                if (span != null) {
                    span.error(e);
                }
                return;
            } finally {
                if (span != null) {
                    span.end();
                }
            }
        }
    }

    @Scheduled(cron = "${outbox.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void cleanup() {
        int removed = repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)));
        if (removed > 0) {
            log.info("Removed {} published outbox rows", removed);
        }
    }
}
