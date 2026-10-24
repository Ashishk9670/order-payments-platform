package com.example.shop.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.payment.config.SecurityConfig;
import com.example.shop.payment.domain.Payment;
import com.example.shop.payment.domain.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PaymentRepository payments;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("caller")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private final UUID orderId = UUID.randomUUID();

    @Test
    void anAdminOrAServiceReadsThePaymentOfAnOrder() throws Exception {
        var payment = Payment.authorized(orderId, new BigDecimal("42.00"));
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        for (String role : new String[] {"ADMIN", "SERVICE"}) {
            mvc.perform(get("/api/v1/payments/order/" + orderId).with(role(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                    .andExpect(jsonPath("$.amount").value(42.0));
        }
    }

    @Test
    void anOrderWithoutAPaymentIs404ProblemJson() throws Exception {
        when(payments.findByOrderId(orderId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/payments/order/" + orderId).with(role("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No payment for order " + orderId));
    }

    @Test
    void noTokenIs401AndACustomerIs403() throws Exception {
        mvc.perform(get("/api/v1/payments/order/" + orderId)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/payments/order/" + orderId).with(role("CUSTOMER"))).andExpect(status().isForbidden());
        verify(payments, never()).findByOrderId(any());
    }

    @Test
    void thereIsNoWayToWriteThroughThisApi() throws Exception {
        mvc.perform(post("/api/v1/payments/order/" + orderId).with(role("ADMIN"))).andExpect(status().is4xxClientError());
        verify(payments, never()).save(any());
    }
}
