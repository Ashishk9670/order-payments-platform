package com.example.shop.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReservationExpiryJobTest {

    private final ReservationService service = mock(ReservationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void expiresReservationsOlderThanTheTtl() {
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        when(service.expireStale(cutoff.capture(), eq(ReservationExpiryJob.BATCH), anyString())).thenReturn(0);

        new ReservationExpiryJob(service, Duration.ofMinutes(30), clock).run();

        assertThat(cutoff.getValue()).isEqualTo(Instant.parse("2026-01-01T11:30:00Z"));
    }

    @Test
    void aFullBatchTriggersAnotherPassUntilNothingIsLeft() {
        when(service.expireStale(any(), anyInt(), anyString())).thenReturn(ReservationExpiryJob.BATCH, ReservationExpiryJob.BATCH, 7);

        new ReservationExpiryJob(service, Duration.ofMinutes(30), clock).run();

        verify(service, times(3)).expireStale(any(), anyInt(), anyString());
    }

    @Test
    void theReasonNamesTheConfiguredTtl() {
        var reason = ArgumentCaptor.forClass(String.class);
        when(service.expireStale(any(), anyInt(), reason.capture())).thenReturn(0);

        new ReservationExpiryJob(service, Duration.ofMinutes(15), clock).run();

        assertThat(reason.getValue()).isEqualTo("Payment not received within PT15M");
    }
}
