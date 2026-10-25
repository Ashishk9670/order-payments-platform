package com.example.shop.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the card processor's asynchronous notifications (disputes, delayed settlements, refunds made in its dashboard).
 * The endpoint is public by necessity, so the signature IS the authentication: unsigned, forged or stale requests get 401 and
 * leave no trace. Verified events are stored once per processor event id (redelivery answers "duplicate"), counted, and
 * available to operators for reconciliation. They deliberately do not change orders or payments by themselves: an automatic
 * saga effect needs a business decision (what does a dispute do to an order?) that belongs in its own change.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    static final String SIGNATURE_HEADER = "PSP-Signature";

    private final WebhookSignature signature;
    private final PspWebhookEventRepository events;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Clock clock;

    public WebhookController(@Value("${psp.webhook-secret:}") String secret,
                             @Value("${psp.webhook-tolerance:PT5M}") Duration tolerance,
                             PspWebhookEventRepository events, ObjectMapper mapper, MeterRegistry meters, Clock clock) {
        // an empty secret means "not configured": the endpoint then does not exist, rather than accepting anything
        this.signature = secret.isBlank() ? null : new WebhookSignature(secret, tolerance, clock);
        this.events = events;
        this.mapper = mapper;
        this.meters = meters;
        this.clock = clock;
    }

    @PostMapping("/webhooks/psp")
    public ResponseEntity<?> receive(@RequestHeader(value = SIGNATURE_HEADER, required = false) String header,
                                     @RequestBody String body) {
        if (signature == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!signature.isValid(header, body)) {
            meters.counter("psp.webhooks.rejected", "reason", "signature").increment();
            log.warn("Rejected a PSP webhook with a missing, invalid or stale signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String id;
        String type;
        try {
            JsonNode json = mapper.readTree(body);
            id = json.path("id").asText("");
            type = json.path("type").asText("");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is not JSON"));
        }
        if (id.isBlank() || id.length() > 100 || type.isBlank() || type.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and type are required"));
        }
        if (events.insertIfAbsent(id, type, body, clock.instant()) == 0) {
            return duplicate(type);   // already stored: redelivery, or the same event racing on another thread
        }
        meters.counter("psp.webhooks.received", "type", type).increment();
        log.info("PSP webhook {} ({}) stored", id, type);
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    private ResponseEntity<?> duplicate(String type) {
        meters.counter("psp.webhooks.duplicates", "type", type).increment();
        return ResponseEntity.ok(Map.of("status", "duplicate"));   // 2xx so the processor stops retrying
    }

    /** For operators: the most recent verified events, newest first. */
    @GetMapping("/api/v1/payments/webhook-events")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return events.findAllByOrderByReceivedAtDesc(PageRequest.of(0, size)).stream()
                .map(e -> Map.<String, Object>of("id", e.getEventId(), "type", e.getType(),
                        "receivedAt", e.getReceivedAt().toString(), "payload", e.getPayload()))
                .toList();
    }
}
