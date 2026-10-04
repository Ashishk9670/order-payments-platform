package com.example.shop.order.api;

import com.example.shop.order.redis.IdempotencyService;
import com.example.shop.order.security.CurrentUser;
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

    private final OrderPlacementService placement;
    private final OrderService orders;
    private final IdempotencyService idempotency;
    private final CurrentUser access;

    public OrderController(OrderPlacementService placement, OrderService orders, IdempotencyService idempotency,
                           CurrentUser access) {
        this.placement = placement;
        this.orders = orders;
        this.idempotency = idempotency;
        this.access = access;
    }

    /** 202: the order is accepted; confirmation happens asynchronously via the saga. Poll GET or use GraphQL. */
    @PostMapping
    public ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        access.requireAccess(request.customerId());
        if (key == null) {
            return accepted(placement.place(request));
        }
        // keys are scoped per caller so one customer can never replay (or probe) another's key
        String scopedKey = access.customerId() + ":" + key;
        return switch (idempotency.claim(scopedKey)) {
            case Claim.Completed c -> ResponseEntity.ok(visible(orders.get(c.orderId())));
            case Claim.InFlight f -> throw new IdempotencyConflictException(
                    "A request with this Idempotency-Key is still being processed");
            case Claim.Acquired a -> {
                try {
                    OrderView view = placement.place(request);
                    idempotency.complete(scopedKey, view.id());
                    yield accepted(view);
                } catch (RuntimeException e) {
                    idempotency.release(scopedKey);
                    throw e;
                }
            }
        };
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable UUID id) {
        return visible(orders.get(id));
    }

    /** Someone else's order is reported as not found, so ids cannot be probed. */
    private OrderView visible(OrderView view) {
        if (!access.canAccess(view.customerId())) {
            throw new OrderNotFoundException(view.id());
        }
        return view;
    }

    /** Starts a refund; the order moves to REFUND_PENDING and then CANCELLED asynchronously. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderView> cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelOrderRequest body) {
        visible(orders.get(id));
        OrderView view = orders.requestCancellation(id, body == null ? null : body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping
    public List<OrderView> list(@RequestParam String customerId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        access.requireAccess(customerId);
        return orders.listByCustomer(customerId, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    private static ResponseEntity<OrderView> accepted(OrderView view) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/orders/" + view.id())).body(view);
    }
}
