package com.example.shop.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.order.domain.OrderStatus;
import com.example.shop.order.redis.IdempotencyService;
import com.example.shop.order.redis.RateLimitInterceptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID = """
            {"customerId":"cust-1","items":[{"sku":"SKU-1001","quantity":2,"unitPrice":10.00}]}""";

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean IdempotencyService idempotency;
    @MockitoBean RateLimitInterceptor rateLimit;

    private final UUID id = UUID.randomUUID();
    private final OrderView view = new OrderView(id, "cust-1", OrderStatus.PENDING, new BigDecimal("20.00"), null,
            Instant.now(), List.of(new OrderView.ItemView("SKU-1001", 2, new BigDecimal("10.00"))));

    @BeforeEach
    void setUp() throws Exception {
        when(rateLimit.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void placeReturns202WithLocation() throws Exception {
        when(orders.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/orders/" + id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void invalidBodyReturns400ProblemJson() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"","items":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(orders, never()).place(any());
    }

    @Test
    void zeroQuantityIsRejected() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"c","items":[{"sku":"S","quantity":0,"unitPrice":1.00}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        when(orders.get(id)).thenThrow(new OrderNotFoundException(id));

        mvc.perform(get("/api/v1/orders/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order " + id + " not found"));
    }

    @Test
    void idempotencyKeyAcquiredCompletesTheClaim() throws Exception {
        when(idempotency.claim("k1")).thenReturn(new IdempotencyService.Claim.Acquired());
        when(orders.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());
        verify(idempotency).complete("k1", id);
    }

    @Test
    void idempotentReplayReturnsOriginalOrderWithoutCreatingAnother() throws Exception {
        when(idempotency.claim("k1")).thenReturn(new IdempotencyService.Claim.Completed(id));
        when(orders.get(id)).thenReturn(view);

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        verify(orders, never()).place(any());
    }

    @Test
    void concurrentRequestWithSameKeyGets409() throws Exception {
        when(idempotency.claim("k1")).thenReturn(new IdempotencyService.Claim.InFlight());

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPlacementReleasesTheIdempotencyKeySoClientCanRetry() throws Exception {
        when(idempotency.claim("k1")).thenReturn(new IdempotencyService.Claim.Acquired());
        when(orders.place(any())).thenThrow(new IllegalStateException("db down"));

        try {
            mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k1")
                    .contentType(MediaType.APPLICATION_JSON).content(VALID));
        } catch (Exception expected) {
            // unhandled exception propagates through MockMvc; what matters is the key was released
        }
        verify(idempotency).release("k1");
    }
}
