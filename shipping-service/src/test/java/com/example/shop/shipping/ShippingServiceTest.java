package com.example.shop.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.OrderDelivered;
import com.example.shop.common.events.OrderShipped;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.shipping.domain.Shipment;
import com.example.shop.shipping.domain.ShipmentRepository;
import com.example.shop.shipping.domain.ShippingService;
import com.example.shop.shipping.domain.ShippingService.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShippingServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock ShipmentRepository shipments;
    @Mock IdempotencyGuard guard;
    @Mock OutboxService outbox;
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ShippingService service;
    final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ShippingService(shipments, guard, outbox, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double count(String name) {
        return meters.counter(name).count();
    }

    @Test
    void aConfirmedOrderEntersTheQueueOnce() {
        var event = OrderConfirmed.of(orderId, "cust");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.empty());

        service.onOrderConfirmed(event);

        var saved = ArgumentCaptor.forClass(Shipment.class);
        verify(shipments).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Shipment.Status.PENDING);
        assertThat(saved.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(count("shipments.created")).isEqualTo(1.0);
    }

    @Test
    void aRedeliveredConfirmationDoesNothing() {
        var event = OrderConfirmed.of(orderId, "cust");
        when(guard.firstTime(event.eventId())).thenReturn(false);

        service.onOrderConfirmed(event);

        verify(shipments, never()).save(any());
    }

    @Test
    void aConfirmationAfterACancellationDoesNotStartAShipment() {
        var event = OrderConfirmed.of(orderId, "cust");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(Shipment.cancelled(orderId, NOW)));

        service.onOrderConfirmed(event);

        verify(shipments, never()).save(any());
    }

    @Test
    void cancellingBeforeDispatchStopsTheShipment() {
        var shipment = Shipment.pending(orderId, NOW);
        var event = OrderCancellationRequested.of(orderId, "changed my mind");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(shipment));

        service.onCancellationRequested(event);

        assertThat(shipment.getStatus()).isEqualTo(Shipment.Status.CANCELLED);
        assertThat(count("shipments.cancelled")).isEqualTo(1.0);
    }

    @Test
    void cancellingAfterDispatchIsFlaggedForAnOperatorNotSilentlyIgnored() {
        var shipment = Shipment.pending(orderId, NOW);
        shipment.ship("DHL", "1", NOW);
        var event = OrderCancellationRequested.of(orderId, "too late");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(shipment));

        service.onCancellationRequested(event);

        assertThat(shipment.getStatus()).isEqualTo(Shipment.Status.SHIPPED);   // cannot be recalled
        assertThat(count("shipments.cancel_too_late")).isEqualTo(1.0);
    }

    @Test
    void aCancellationThatOvertakesTheConfirmationLeavesATombstone() {
        var event = OrderCancellationRequested.of(orderId, "early");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.empty());

        service.onCancellationRequested(event);

        var saved = ArgumentCaptor.forClass(Shipment.class);
        verify(shipments).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Shipment.Status.CANCELLED);
    }

    @Test
    void aRedeliveredCancellationOfACancelledShipmentIsNotCountedAsTooLate() {
        var event = OrderCancellationRequested.of(orderId, "again");
        when(guard.firstTime(event.eventId())).thenReturn(true);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(Shipment.cancelled(orderId, NOW)));

        service.onCancellationRequested(event);

        assertThat(count("shipments.cancel_too_late")).isZero();
    }

    @Test
    void shippingPublishesOneEventAndRepeatsAreRefused() {
        var shipment = Shipment.pending(orderId, NOW);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(shipment));

        assertThat(service.ship(orderId, "DHL", "TRACK-9")).isEqualTo(Outcome.DONE);
        assertThat(service.ship(orderId, "UPS", "OTHER")).isEqualTo(Outcome.NOT_ALLOWED);

        var published = ArgumentCaptor.forClass(com.example.shop.common.events.DomainEvent.class);
        verify(outbox).publish(eq(Topics.SHIPMENT_SHIPPED), published.capture());   // exactly once
        var event = (OrderShipped) published.getValue();
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.carrier()).isEqualTo("DHL");
        assertThat(event.trackingNumber()).isEqualTo("TRACK-9");
    }

    @Test
    void deliveringNeedsAShippedParcel() {
        var shipment = Shipment.pending(orderId, NOW);
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(shipment));

        assertThat(service.deliver(orderId)).isEqualTo(Outcome.NOT_ALLOWED);
        verify(outbox, never()).publish(any(), any());

        service.ship(orderId, "DHL", "1");
        assertThat(service.deliver(orderId)).isEqualTo(Outcome.DONE);
        verify(outbox).publish(eq(Topics.SHIPMENT_DELIVERED), any(OrderDelivered.class));
    }

    @Test
    void unknownOrdersAreNotFound() {
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThat(service.ship(orderId, "DHL", "1")).isEqualTo(Outcome.NOT_FOUND);
        assertThat(service.deliver(orderId)).isEqualTo(Outcome.NOT_FOUND);
    }
}
