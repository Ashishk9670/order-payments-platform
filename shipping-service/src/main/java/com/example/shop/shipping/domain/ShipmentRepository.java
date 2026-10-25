package com.example.shop.shipping.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByOrderId(UUID orderId);

    /** The warehouse queue: oldest first. */
    List<Shipment> findByStatusOrderByCreatedAtAsc(Shipment.Status status, Pageable page);
}
