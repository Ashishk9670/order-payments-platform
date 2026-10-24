package com.example.shop.inventory.domain;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases stock that was reserved but never paid for. Without it an abandoned or stuck order would hold inventory forever.
 * A payment that arrives AFTER its reservation expired is refunded by the order service (see OrderSagaListener).
 */
@Component
@ConditionalOnProperty(name = "inventory.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    static final int BATCH = 50;

    private final ReservationService service;
    private final Duration ttl;
    private final Clock clock;

    @Autowired   // two constructors: tell Spring which one (the other is for tests)
    public ReservationExpiryJob(ReservationService service, @Value("${inventory.reservation-ttl:PT30M}") Duration ttl) {
        this(service, ttl, Clock.systemUTC());
    }

    ReservationExpiryJob(ReservationService service, Duration ttl, Clock clock) {
        this.service = service;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${inventory.expiry.poll-interval-ms:30000}")
    public void run() {
        int expired;
        do {
            expired = service.expireStale(clock.instant().minus(ttl), BATCH, "Payment not received within " + ttl);
        } while (expired == BATCH);   // a full batch means there may be more
        log.debug("Reservation expiry pass finished (ttl {})", ttl);
    }
}
