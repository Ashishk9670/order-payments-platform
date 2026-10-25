package com.example.shop.payment.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "psp_webhook_event")
public class PspWebhookEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String payload;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PspWebhookEvent() {
    }

    public PspWebhookEvent(String eventId, String type, String payload, Instant receivedAt) {
        this.eventId = eventId;
        this.type = type;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
