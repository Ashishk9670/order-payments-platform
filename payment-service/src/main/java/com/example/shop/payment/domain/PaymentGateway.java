package com.example.shop.payment.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Port to the card processor. Swap the simulated adapter for a real PSP client without touching the saga code. */
public interface PaymentGateway {

    record Result(boolean approved, String declineReason) {
        public static Result approve() {
            return new Result(true, null);
        }

        public static Result decline(String reason) {
            return new Result(false, reason);
        }
    }

    Result authorize(UUID orderId, BigDecimal amount);

    Result refund(UUID orderId, BigDecimal amount);
}
