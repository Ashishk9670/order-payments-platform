package com.example.shop.shipping.api;

import com.example.shop.shipping.domain.Shipment;
import com.example.shop.shipping.domain.ShipmentRepository;
import com.example.shop.shipping.domain.ShippingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The warehouse's view: what is waiting to be packed, and the two things an operator does with a parcel. */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {

    public record ShipmentView(UUID id, UUID orderId, Shipment.Status status, String carrier, String trackingNumber,
                               Instant createdAt, Instant shippedAt, Instant deliveredAt) {
        static ShipmentView from(Shipment s) {
            return new ShipmentView(s.getId(), s.getOrderId(), s.getStatus(), s.getCarrier(), s.getTrackingNumber(),
                    s.getCreatedAt(), s.getShippedAt(), s.getDeliveredAt());
        }
    }

    public record ShipRequest(@NotBlank @Size(max = 100) String carrier, @NotBlank @Size(max = 100) String trackingNumber) {
    }

    private final ShipmentRepository shipments;
    private final ShippingService shipping;

    public ShipmentController(ShipmentRepository shipments, ShippingService shipping) {
        this.shipments = shipments;
        this.shipping = shipping;
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> byOrder(@PathVariable UUID orderId) {
        return shipments.findByOrderId(orderId).<ResponseEntity<?>>map(s -> ResponseEntity.ok(ShipmentView.from(s)))
                .orElseGet(() -> notFound(orderId));
    }

    /** The queue for one status (default PENDING = waiting to be packed), oldest first. */
    @GetMapping
    public List<ShipmentView> queue(@RequestParam(defaultValue = "PENDING") Shipment.Status status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return shipments.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream().map(ShipmentView::from).toList();
    }

    @PostMapping("/order/{orderId}/ship")
    public ResponseEntity<?> ship(@PathVariable UUID orderId, @Valid @RequestBody ShipRequest body) {
        return respond(orderId, shipping.ship(orderId, body.carrier().trim(), body.trackingNumber().trim()),
                "Only a PENDING shipment can be shipped");
    }

    @PostMapping("/order/{orderId}/deliver")
    public ResponseEntity<?> deliver(@PathVariable UUID orderId) {
        return respond(orderId, shipping.deliver(orderId), "Only a SHIPPED shipment can be delivered");
    }

    private ResponseEntity<?> respond(UUID orderId, ShippingService.Outcome outcome, String conflict) {
        return switch (outcome) {
            case NOT_FOUND -> notFound(orderId);
            case NOT_ALLOWED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, conflict));
            case DONE -> shipments.findByOrderId(orderId).<ResponseEntity<?>>map(s -> ResponseEntity.ok(ShipmentView.from(s)))
                    .orElseGet(() -> notFound(orderId));
        };
    }

    private static ResponseEntity<?> notFound(UUID orderId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No shipment for order " + orderId));
    }
}
