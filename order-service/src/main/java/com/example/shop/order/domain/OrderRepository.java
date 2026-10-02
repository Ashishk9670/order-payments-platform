package com.example.shop.order.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    record ItemRow(UUID orderId, String sku, int quantity, java.math.BigDecimal unitPrice) {
    }

    /** One query for many orders: backs the GraphQL batch loader (no N+1). */
    @Query("select new com.example.shop.order.domain.OrderRepository$ItemRow(i.order.id, i.sku, i.quantity, i.unitPrice) "
            + "from OrderItem i where i.order.id in :ids")
    List<ItemRow> findItemRows(@Param("ids") List<UUID> ids);
}
