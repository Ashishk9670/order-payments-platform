package com.example.shop.payment.api;

import com.example.shop.payment.domain.Payment;
import com.example.shop.payment.domain.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    public record PaymentView(UUID id, UUID orderId, BigDecimal amount, Payment.Status status, String failureReason) {
    }

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> byOrder(@PathVariable UUID orderId) {
        return payments.findByOrderId(orderId)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(new PaymentView(p.getId(), p.getOrderId(), p.getAmount(),
                        p.getStatus(), p.getFailureReason())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No payment for order " + orderId)));
    }
}
