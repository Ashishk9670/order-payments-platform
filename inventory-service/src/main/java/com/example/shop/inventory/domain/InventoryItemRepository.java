package com.example.shop.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    /** Atomic check-and-decrement in one statement; correct even without the Redis lock (which only cuts contention). */
    @Modifying(clearAutomatically = true)
    @Query("update InventoryItem i set i.available = i.available - :qty, i.reserved = i.reserved + :qty "
            + "where i.sku = :sku and i.available >= :qty")
    int tryReserve(@Param("sku") String sku, @Param("qty") int qty);

    // No clearAutomatically: callers release several lines in one transaction and then update the Reservation entities, which a
    // cleared persistence context would silently detach (the stock came back but the reservation status was never saved).
    @Modifying(flushAutomatically = true)
    @Query("update InventoryItem i set i.available = i.available + :qty, i.reserved = i.reserved - :qty where i.sku = :sku")
    int release(@Param("sku") String sku, @Param("qty") int qty);

    @Modifying(clearAutomatically = true)
    @Query("update InventoryItem i set i.available = i.available + :qty where i.sku = :sku")
    int addStock(@Param("sku") String sku, @Param("qty") int qty);
}
