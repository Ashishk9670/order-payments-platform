package com.example.shop.order.api;

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

import com.example.shop.order.catalog.CatalogUnavailableException;
import com.example.shop.order.domain.OrderStatus;
import com.example.shop.order.redis.IdempotencyService;
import com.example.shop.order.redis.RateLimitInterceptor;
import com.example.shop.order.security.CurrentUser;
import com.example.shop.order.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, CurrentUser.class})
class OrderControllerTest {

    private static final String VALID = """
            {"customerId":"cust-1","items":[{"sku":"SKU-1001","quantity":2}]}""";

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean OrderPlacementService placement;
    @MockitoBean IdempotencyService idempotency;
    @MockitoBean RateLimitInterceptor rateLimit;
    @MockitoBean JwtDecoder jwtDecoder;   // the real decoder validates signatures; tests inject an already-authenticated JWT

    private static RequestPostProcessor as(String customer) {
        return jwt().jwt(j -> j.subject(customer)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ops")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private final UUID id = UUID.randomUUID();
    private final OrderView view = new OrderView(id, "cust-1", OrderStatus.PENDING, new BigDecimal("20.00"), null,
            Instant.now(), List.of(new OrderView.ItemView("SKU-1001", 2, new BigDecimal("10.00"))));

    @BeforeEach
    void setUp() throws Exception {
        when(rateLimit.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void placeReturns202WithLocation() throws Exception {
        when(placement.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/orders/" + id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void invalidBodyReturns400ProblemJson() throws Exception {
        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"","items":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(placement, never()).place(any());
    }

    @Test
    void zeroQuantityIsRejected() throws Exception {
        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"c","items":[{"sku":"S","quantity":0}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownProductReturns422() throws Exception {
        when(placement.place(any())).thenThrow(new UnknownProductException("NOPE"));

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Unknown product NOPE"));
    }

    @Test
    void catalogOutageReturns503() throws Exception {
        when(placement.place(any())).thenThrow(new CatalogUnavailableException("down", null));

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void clientSuppliedPriceFieldIsIgnoredNotTrusted() throws Exception {
        when(placement.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"cust-1\",\"items\":[{\"sku\":\"S\",\"quantity\":1,\"unitPrice\":0.01}]}"))
                .andExpect(status().isAccepted());
        org.mockito.ArgumentCaptor<PlaceOrderRequest> captor = org.mockito.ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placement).place(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().items().get(0))
                .isEqualTo(new PlaceOrderRequest.Item("S", 1));
    }

    @Test
    void cancelReturns202() throws Exception {
        when(orders.get(id)).thenReturn(view);
        when(orders.requestCancellation(id, "oops")).thenReturn(view);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"oops\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void cancelWithoutBodyIsAllowed() throws Exception {
        when(orders.get(id)).thenReturn(view);
        when(orders.requestCancellation(id, null)).thenReturn(view);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(as("cust-1"))).andExpect(status().isAccepted());
    }

    @Test
    void cancelOfNonConfirmedOrderReturns409() throws Exception {
        when(orders.get(id)).thenReturn(view);
        when(orders.requestCancellation(id, null)).thenThrow(new OrderStateConflictException("Only confirmed orders can be cancelled; order is PENDING"));

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(as("cust-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only confirmed orders can be cancelled; order is PENDING"));
    }

    @Test
    void cancelOfUnknownOrderReturns404() throws Exception {
        when(orders.get(id)).thenThrow(new OrderNotFoundException(id));

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(as("cust-1"))).andExpect(status().isNotFound());
    }

    @Test
    void noTokenIs401ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/orders/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(orders, never()).get(any());
    }

    @Test
    void customerCannotPlaceAnOrderForSomeoneElse() throws Exception {
        mvc.perform(post("/api/v1/orders").with(as("mallory")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(placement, never()).place(any());
    }

    @Test
    void someoneElsesOrderLooksLikeItDoesNotExist() throws Exception {
        when(orders.get(id)).thenReturn(view);   // belongs to cust-1

        mvc.perform(get("/api/v1/orders/" + id).with(as("mallory"))).andExpect(status().isNotFound());
    }

    @Test
    void customerCannotCancelSomeoneElsesOrder() throws Exception {
        when(orders.get(id)).thenReturn(view);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(as("mallory"))).andExpect(status().isNotFound());
        verify(orders, never()).requestCancellation(any(), any());
    }

    @Test
    void customerCannotListSomeoneElsesOrders() throws Exception {
        mvc.perform(get("/api/v1/orders").param("customerId", "cust-1").with(as("mallory")))
                .andExpect(status().isForbidden());
        verify(orders, never()).listByCustomer(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void customerCanListTheirOwnOrders() throws Exception {
        when(orders.listByCustomer("cust-1", 0, 20)).thenReturn(List.of(view));

        mvc.perform(get("/api/v1/orders").param("customerId", "cust-1").with(as("cust-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void adminCanReadAndListAnyCustomersOrders() throws Exception {
        when(orders.get(id)).thenReturn(view);
        when(orders.listByCustomer("cust-1", 0, 20)).thenReturn(List.of(view));

        mvc.perform(get("/api/v1/orders/" + id).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders").param("customerId", "cust-1").with(admin())).andExpect(status().isOk());
    }

    @Test
    void idempotencyKeysAreScopedPerCaller() throws Exception {
        when(idempotency.claim("cust-1:shared-key")).thenReturn(new IdempotencyService.Claim.Acquired());
        when(orders.get(any())).thenReturn(view);
        when(placement.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isAccepted());

        verify(idempotency).claim("cust-1:shared-key");   // another customer's identical key would be a different redis key
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        when(orders.get(id)).thenThrow(new OrderNotFoundException(id));

        mvc.perform(get("/api/v1/orders/" + id).with(as("cust-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order " + id + " not found"));
    }

    @Test
    void idempotencyKeyAcquiredCompletesTheClaim() throws Exception {
        when(idempotency.claim("cust-1:k1")).thenReturn(new IdempotencyService.Claim.Acquired());
        when(placement.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());
        verify(idempotency).complete("cust-1:k1", id);
    }

    @Test
    void idempotentReplayReturnsOriginalOrderWithoutCreatingAnother() throws Exception {
        when(idempotency.claim("cust-1:k1")).thenReturn(new IdempotencyService.Claim.Completed(id));
        when(orders.get(id)).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        verify(placement, never()).place(any());
    }

    @Test
    void concurrentRequestWithSameKeyGets409() throws Exception {
        when(idempotency.claim("cust-1:k1")).thenReturn(new IdempotencyService.Claim.InFlight());

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPlacementReleasesTheIdempotencyKeySoClientCanRetry() throws Exception {
        when(idempotency.claim("cust-1:k1")).thenReturn(new IdempotencyService.Claim.Acquired());
        when(placement.place(any())).thenThrow(new IllegalStateException("db down"));

        try {
            mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "k1")
                    .contentType(MediaType.APPLICATION_JSON).content(VALID));
        } catch (Exception expected) {
            // unhandled exception propagates through MockMvc; what matters is the key was released
        }
        verify(idempotency).release("cust-1:k1");
    }

    @Test
    void whenTheIdempotencyStoreIsDownARequestWithAKeyIs503ProblemJson() throws Exception {
        when(idempotency.claim("cust-1:k1")).thenThrow(new com.example.shop.order.redis.IdempotencyUnavailableException(new RuntimeException("redis down")));

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(placement, never()).place(any());   // nothing was created that a retry could then duplicate
    }

    @Test
    void aRequestWithoutAKeyNeverTouchesTheIdempotencyStore() throws Exception {
        when(placement.place(any())).thenReturn(view);

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verifyNoInteractions(idempotency);
    }

    @Test
    void aDatabaseOutageIsA503WithRetryAfterNotAnOpaque500() throws Exception {
        when(placement.place(any())).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("db down"));

        mvc.perform(post("/api/v1/orders").with(as("cust-1")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }
}
