package com.example.shop.order.api;

import com.example.shop.order.catalog.CatalogClient;
import com.example.shop.order.domain.Order;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Prices a request from the catalog, then persists it. Deliberately NOT transactional: no DB connection is held while
 * waiting on the HTTP call to the catalog.
 */
@Service
public class OrderPlacementService {

    private final CatalogClient catalog;
    private final OrderService orders;

    public OrderPlacementService(CatalogClient catalog, OrderService orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    public OrderView place(PlaceOrderRequest request) {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (var item : request.items()) {
            prices.computeIfAbsent(item.sku(), sku -> {
                BigDecimal price = catalog.priceOf(sku);
                if (price == null) {
                    throw new UnknownProductException(sku);
                }
                return price;
            });
        }
        List<Order.ItemSpec> lines = request.items().stream()
                .map(i -> new Order.ItemSpec(i.sku(), i.quantity(), prices.get(i.sku()))).toList();
        return orders.create(request.customerId(), lines);
    }
}
