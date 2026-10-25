package com.example.shop.shipping.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.shipping.config.SecurityConfig;
import com.example.shop.shipping.domain.Shipment;
import com.example.shop.shipping.domain.ShipmentRepository;
import com.example.shop.shipping.domain.ShippingService;
import com.example.shop.shipping.domain.ShippingService.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ShipmentController.class)
@Import(SecurityConfig.class)
class ShipmentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ShipmentRepository shipments;
    @MockitoBean ShippingService shipping;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID orderId = UUID.randomUUID();
    private final Shipment pending = Shipment.pending(orderId, Instant.parse("2026-01-01T00:00:00Z"));

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("op")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void anAdminSeesAShipmentByOrder() throws Exception {
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(pending));

        mvc.perform(get("/api/v1/shipments/order/" + orderId).with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void anUnknownOrderIs404ProblemJson() throws Exception {
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/shipments/order/" + orderId).with(role("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void theQueueDefaultsToPendingOldestFirst() throws Exception {
        when(shipments.findByStatusOrderByCreatedAtAsc(eq(Shipment.Status.PENDING), any())).thenReturn(List.of(pending));

        mvc.perform(get("/api/v1/shipments").with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(orderId.toString()));
    }

    @Test
    void shippingReturnsTheUpdatedShipment() throws Exception {
        when(shipping.ship(orderId, "DHL", "TRACK-1")).thenReturn(Outcome.DONE);
        pending.ship("DHL", "TRACK-1", Instant.now());
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(pending));

        mvc.perform(post("/api/v1/shipments/order/" + orderId + "/ship").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\" DHL \",\"trackingNumber\":\"TRACK-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.trackingNumber").value("TRACK-1"));
        verify(shipping).ship(orderId, "DHL", "TRACK-1");   // input is trimmed
    }

    @Test
    void shippingRequiresCarrierAndTrackingNumber() throws Exception {
        mvc.perform(post("/api/v1/shipments/order/" + orderId + "/ship").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"\",\"trackingNumber\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shipments/order/" + orderId + "/ship").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNotAllowedTransitionIs409AndAnUnknownOrderIs404() throws Exception {
        when(shipping.deliver(orderId)).thenReturn(Outcome.NOT_ALLOWED);
        mvc.perform(post("/api/v1/shipments/order/" + orderId + "/deliver").with(role("ADMIN")))
                .andExpect(status().isConflict());

        UUID ghost = UUID.randomUUID();
        when(shipping.ship(eq(ghost), any(), any())).thenReturn(Outcome.NOT_FOUND);
        mvc.perform(post("/api/v1/shipments/order/" + ghost + "/ship").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"DHL\",\"trackingNumber\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deliveringAShippedParcelSucceeds() throws Exception {
        when(shipping.deliver(orderId)).thenReturn(Outcome.DONE);
        pending.ship("DHL", "1", Instant.now());
        pending.deliver(Instant.now());
        when(shipments.findByOrderId(orderId)).thenReturn(Optional.of(pending));

        mvc.perform(post("/api/v1/shipments/order/" + orderId + "/deliver").with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void onlyAdminsMayUseTheApi() throws Exception {
        mvc.perform(get("/api/v1/shipments")).andExpect(status().isUnauthorized());
        for (String role : new String[] {"CUSTOMER", "SERVICE"}) {
            mvc.perform(get("/api/v1/shipments").with(role(role))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/shipments/order/" + orderId + "/deliver").with(role(role))).andExpect(status().isForbidden());
        }
    }
}
