package com.example.shop.payment.domain;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Demo adapter: approves everything up to a limit, so you can trigger the "payment failed" saga path on demand. */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private final BigDecimal limit;

    public SimulatedPaymentGateway(@Value("${payment.simulated-limit:10000}") BigDecimal limit) {
        this.limit = limit;
    }

    @Override
    public Result authorize(UUID orderId, BigDecimal amount) {
        return amount.compareTo(limit) > 0 ? Result.decline("Amount exceeds card limit") : Result.approve();
    }
}
