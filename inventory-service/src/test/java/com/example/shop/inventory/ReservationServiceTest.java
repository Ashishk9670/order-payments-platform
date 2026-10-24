package com.example.shop.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.InventoryExpired;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.common.inbox.IdempotencyGuard;
import com.example.shop.common.outbox.OutboxService;
import com.example.shop.inventory.domain.InventoryItemRepository;
import com.example.shop.inventory.domain.Reservation;
import com.example.shop.inventory.domain.ReservationRepository;
import com.example.shop.inventory.domain.ReservationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock InventoryItemRepository items;
    @Mock ReservationRepository reservations;
    @Mock IdempotencyGuard guard;
    @Mock OutboxService outbox;
    ReservationService service;
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new ReservationService(items, reservations, guard, outbox, meters);
    }

    private OrderCreated order(OrderCreated.Line... lines) {
        return OrderCreated.of(UUID.randomUUID(), "cust-1", List.of(lines), new BigDecimal("30.00"));
    }

    @Test
    void reservesAllLinesAndEmitsInventoryReserved() {
        when(guard.firstTime(any())).thenReturn(true);
        when(items.tryReserve(anyString(), anyInt())).thenReturn(1);
        var order = order(new OrderCreated.Line("B", 1), new OrderCreated.Line("A", 2));

        service.reserve(order);

        verify(items).tryReserve("A", 2);
        verify(items).tryReserve("B", 1);
        verify(reservations, times(2)).save(any(Reservation.class));
        verify(outbox).publish(eq(Topics.INVENTORY_RESERVED), any(InventoryReserved.class));
    }

    @Test
    void shortageGivesBackLinesAlreadyTakenAndEmitsInventoryFailed() {
        when(guard.firstTime(any())).thenReturn(true);
        when(items.tryReserve("A", 2)).thenReturn(1);
        when(items.tryReserve("B", 1)).thenReturn(0);   // B is short; lines are processed in sorted order: A then B
        var order = order(new OrderCreated.Line("B", 1), new OrderCreated.Line("A", 2));

        service.reserve(order);

        verify(items).release("A", 2);
        verify(items, never()).release(eq("B"), anyInt());
        verify(reservations, never()).save(any());
        var captor = ArgumentCaptor.forClass(InventoryFailed.class);
        verify(outbox).publish(eq(Topics.INVENTORY_FAILED), captor.capture());
        assertThat(captor.getValue().reason()).contains("B");
        verify(outbox, never()).publish(eq(Topics.INVENTORY_RESERVED), any());
    }

    @Test
    void duplicateOrderCreatedIsIgnored() {
        when(guard.firstTime(any())).thenReturn(false);

        service.reserve(order(new OrderCreated.Line("A", 1)));

        verify(items, never()).tryReserve(anyString(), anyInt());
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void paymentFailureReleasesReservedStockOnce() {
        var orderId = UUID.randomUUID();
        var r1 = new Reservation(orderId, "A", 2);
        var r2 = new Reservation(orderId, "B", 1);
        when(guard.firstTime(any())).thenReturn(true);
        when(reservations.findByOrderIdAndStatusIn(eq(orderId), any())).thenReturn(List.of(r1, r2));

        service.releaseFor(PaymentFailed.of(orderId, "declined"));

        verify(items).release("A", 2);
        verify(items).release("B", 1);
        assertThat(r1.getStatus()).isEqualTo(Reservation.Status.RELEASED);
        assertThat(r2.getStatus()).isEqualTo(Reservation.Status.RELEASED);
    }

    @Test
    void duplicatePaymentFailedDoesNotReleaseTwice() {
        when(guard.firstTime(any())).thenReturn(false);

        service.releaseFor(PaymentFailed.of(UUID.randomUUID(), "declined"));

        verify(items, never()).release(anyString(), anyInt());
    }

    @Test
    void refundReleasesReservedStockToo() {
        var orderId = UUID.randomUUID();
        var r = new Reservation(orderId, "A", 3);
        when(guard.firstTime(any())).thenReturn(true);
        when(reservations.findByOrderIdAndStatusIn(eq(orderId), any())).thenReturn(List.of(r));

        service.releaseFor(PaymentRefunded.of(orderId, UUID.randomUUID()));

        verify(items).release("A", 3);
        assertThat(r.getStatus()).isEqualTo(Reservation.Status.RELEASED);
    }

    @Test
    void duplicateRefundEventDoesNotReleaseTwice() {
        when(guard.firstTime(any())).thenReturn(false);

        service.releaseFor(PaymentRefunded.of(UUID.randomUUID(), UUID.randomUUID()));

        verify(items, never()).release(anyString(), anyInt());
    }

    @Test
    void releaseAlsoGivesBackStockThatWasAlreadyPaidForSoRefundsRestock() {
        var orderId = UUID.randomUUID();
        var paid = new Reservation(orderId, "A", 2);
        paid.commit();
        when(guard.firstTime(any())).thenReturn(true);
        when(reservations.findByOrderIdAndStatusIn(eq(orderId), any())).thenReturn(List.of(paid));

        service.releaseFor(PaymentRefunded.of(orderId, UUID.randomUUID()));

        verify(items).release("A", 2);
        assertThat(paid.getStatus()).isEqualTo(Reservation.Status.RELEASED);
        var statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(reservations).findByOrderIdAndStatusIn(eq(orderId), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(Reservation.Status.RESERVED, Reservation.Status.COMMITTED);
    }

    // ---- payment commits a reservation ----------------------------------------------------------------------------

    @Test
    void aPaymentTurnsTheReservationIntoASaleSoItCanNeverExpire() {
        var orderId = UUID.randomUUID();
        var line1 = new Reservation(orderId, "A", 1);
        var line2 = new Reservation(orderId, "B", 2);
        when(guard.firstTime(any())).thenReturn(true);
        when(reservations.findByOrderIdAndStatus(orderId, Reservation.Status.RESERVED)).thenReturn(List.of(line1, line2));

        service.commit(PaymentAuthorized.of(orderId, UUID.randomUUID()));

        assertThat(line1.getStatus()).isEqualTo(Reservation.Status.COMMITTED);
        assertThat(line2.getStatus()).isEqualTo(Reservation.Status.COMMITTED);
        verify(items, never()).release(anyString(), anyInt());
    }

    @Test
    void aRedeliveredPaymentDoesNothing() {
        when(guard.firstTime(any())).thenReturn(false);

        service.commit(PaymentAuthorized.of(UUID.randomUUID(), UUID.randomUUID()));

        verify(reservations, never()).findByOrderIdAndStatus(any(), any());
    }

    // ---- expiry ---------------------------------------------------------------------------------------------------

    @Test
    void staleUnpaidOrdersAreReleasedAsAWholeAndTheOrderServiceIsTold() {
        var orderId = UUID.randomUUID();
        var cutoff = Instant.parse("2026-01-01T00:00:00Z");
        var a = new Reservation(orderId, "A", 2);
        var b = new Reservation(orderId, "B", 1);
        when(reservations.findExpiredOrderIds(cutoff, 50)).thenReturn(List.of(orderId));
        when(reservations.lockUnpaidLines(List.of(orderId))).thenReturn(List.of(a, b));

        int expired = service.expireStale(cutoff, 50, "Payment not received within PT30M");

        assertThat(expired).isEqualTo(1);
        verify(items).release("A", 2);
        verify(items).release("B", 1);
        assertThat(a.getStatus()).isEqualTo(Reservation.Status.EXPIRED);
        assertThat(b.getStatus()).isEqualTo(Reservation.Status.EXPIRED);
        var event = ArgumentCaptor.forClass(InventoryExpired.class);
        verify(outbox).publish(eq(Topics.INVENTORY_EXPIRED), event.capture());
        assertThat(event.getValue().orderId()).isEqualTo(orderId);
        assertThat(event.getValue().reason()).contains("PT30M");
        assertThat(meters.counter("reservations.expired").count()).isEqualTo(1.0);
    }

    @Test
    void nothingStaleMeansNothingHappens() {
        when(reservations.findExpiredOrderIds(any(), anyInt())).thenReturn(List.of());

        assertThat(service.expireStale(Instant.now(), 50, "x")).isZero();

        verify(reservations, never()).lockUnpaidLines(any());
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void anOrderThatAnotherInstanceIsAlreadyExpiringIsSkippedNotDoubleReleased() {
        var orderId = UUID.randomUUID();
        when(reservations.findExpiredOrderIds(any(), anyInt())).thenReturn(List.of(orderId));
        when(reservations.lockUnpaidLines(any())).thenReturn(List.of());   // SKIP LOCKED returned nothing: someone else holds the rows

        assertThat(service.expireStale(Instant.now(), 50, "x")).isZero();

        verify(items, never()).release(anyString(), anyInt());
        verify(outbox, never()).publish(any(), any());
    }
}
