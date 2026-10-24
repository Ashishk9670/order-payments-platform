package com.example.shop.inventory.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderIdAndStatus(UUID orderId, Reservation.Status status);

    /** Stock that is still held for an order, whether or not it was paid: what a failed payment or a refund gives back. */
    List<Reservation> findByOrderIdAndStatusIn(UUID orderId, Collection<Reservation.Status> statuses);

    /** Orders that have held stock, unpaid, since before the cutoff (oldest first). */
    @Query(value = "select distinct order_id from reservation where status = 'RESERVED' and created_at < :cutoff limit :max",
            nativeQuery = true)
    List<UUID> findExpiredOrderIds(@Param("cutoff") Instant cutoff, @Param("max") int max);

    /**
     * Locks all still-unpaid lines of these orders. SKIP LOCKED (-2) so several instances can expire different orders without
     * waiting for each other, and an order whose payment is being committed right now is left alone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from Reservation r where r.orderId in :orderIds and r.status = com.example.shop.inventory.domain.Reservation.Status.RESERVED")
    List<Reservation> lockUnpaidLines(@Param("orderIds") Collection<UUID> orderIds);
}
