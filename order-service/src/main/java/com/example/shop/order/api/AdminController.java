package com.example.shop.order.api;

import com.example.shop.order.reconcile.StuckOrdersMonitor;
import com.example.shop.order.reconcile.StuckOrdersMonitor.StuckOrder;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator view, ADMIN only (see SecurityConfig). Runbook: docs/runbooks/stuck-orders.md. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final StuckOrdersMonitor stuck;

    public AdminController(StuckOrdersMonitor stuck) {
        this.stuck = stuck;
    }

    /** Orders stuck in PENDING / INVENTORY_RESERVED / REFUND_PENDING beyond their thresholds, oldest first. */
    @GetMapping("/stuck-orders")
    public List<StuckOrder> stuckOrders(@RequestParam(defaultValue = "100") int limit) {
        return stuck.list(Math.max(1, Math.min(limit, 500)));
    }
}
