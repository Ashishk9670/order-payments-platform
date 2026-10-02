package com.example.shop.order.api;

import com.example.shop.order.redis.IdempotencyService;
import com.example.shop.order.redis.IdempotencyService.Claim;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orders, IdempotencyService idempotency) {
        this.orders = orders;
        this.idempotency = idempotency;
    }

    /** 202: the order is accepted; confirmation happens asynchronously via the saga. Poll GET or use GraphQL. */
    @PostMapping
    public ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null) {
            return accepted(orders.place(request));
        }
        return switch (idempotency.claim(key)) {
            case Claim.Completed c -> ResponseEntity.ok(orders.get(c.orderId()));
            case Claim.InFlight f -> throw new IdempotencyConflictException(
                    "A request with this Idempotency-Key is still being processed");
            case Claim.Acquired a -> {
                try {
                    OrderView view = orders.place(request);
                    idempotency.complete(key, view.id());
                    yield accepted(view);
                } catch (RuntimeException e) {
                    idempotency.release(key);
                    throw e;
                }
            }
        };
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @GetMapping
    public List<OrderView> list(@RequestParam String customerId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return orders.listByCustomer(customerId, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    private static ResponseEntity<OrderView> accepted(OrderView view) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/orders/" + view.id())).body(view);
    }
}
