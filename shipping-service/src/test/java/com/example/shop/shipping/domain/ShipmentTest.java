package com.example.shop.shipping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private Shipment pending() {
        return Shipment.pending(UUID.randomUUID(), T);
    }

    @Test
    void walksPendingShippedDelivered() {
        var s = pending();
        assertThat(s.getStatus()).isEqualTo(Shipment.Status.PENDING);

        assertThat(s.ship("DHL", "TRACK-1", T.plusSeconds(60))).isTrue();
        assertThat(s.getStatus()).isEqualTo(Shipment.Status.SHIPPED);
        assertThat(s.getCarrier()).isEqualTo("DHL");
        assertThat(s.getTrackingNumber()).isEqualTo("TRACK-1");
        assertThat(s.getShippedAt()).isEqualTo(T.plusSeconds(60));

        assertThat(s.deliver(T.plusSeconds(120))).isTrue();
        assertThat(s.getStatus()).isEqualTo(Shipment.Status.DELIVERED);
        assertThat(s.getDeliveredAt()).isEqualTo(T.plusSeconds(120));
    }

    @Test
    void cannotSkipOrRepeatSteps() {
        var s = pending();
        assertThat(s.deliver(T)).as("not shipped yet").isFalse();

        s.ship("DHL", "1", T);
        assertThat(s.ship("UPS", "2", T)).as("already shipped").isFalse();
        assertThat(s.getCarrier()).isEqualTo("DHL");   // the first answer stands
        assertThat(s.cancel()).as("cannot recall a parcel on its way").isFalse();

        s.deliver(T);
        assertThat(s.deliver(T)).isFalse();
    }

    @Test
    void aPendingShipmentCanBeCancelledAndThenNeverShips() {
        var s = pending();
        assertThat(s.cancel()).isTrue();
        assertThat(s.cancel()).isFalse();
        assertThat(s.ship("DHL", "1", T)).isFalse();
        assertThat(s.getStatus()).isEqualTo(Shipment.Status.CANCELLED);
    }

    @Test
    void aCancelledRecordMadeBeforeConfirmationCannotShip() {
        var s = Shipment.cancelled(UUID.randomUUID(), T);
        assertThat(s.getStatus()).isEqualTo(Shipment.Status.CANCELLED);
        assertThat(s.ship("DHL", "1", T)).isFalse();
    }
}
