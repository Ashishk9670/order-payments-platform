package com.example.shop.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.inventory.config.SecurityConfig;
import com.example.shop.inventory.domain.InventoryItem;
import com.example.shop.inventory.domain.InventoryItemRepository;
import java.util.Optional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;   // the real decoder checks signatures; these tests inject an already-authenticated JWT

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ops")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("order-service")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @MockitoBean InventoryItemRepository items;

    @Test
    void returnsStockLevels() throws Exception {
        when(items.findById("A")).thenReturn(Optional.of(new InventoryItem("A", 7)));

        mvc.perform(get("/api/v1/inventory/A").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7)).andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void unknownSkuIs404ProblemJson() throws Exception {
        when(items.findById("X")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/inventory/X").with(admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown SKU X"));
    }

    @Test
    void restockOfAnExistingSkuIncrementsIt() throws Exception {
        when(items.addStock("A", 5)).thenReturn(1);
        when(items.findById("A")).thenReturn(Optional.of(new InventoryItem("A", 12)));

        mvc.perform(post("/api/v1/inventory/A/restock").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(12));
        verify(items, never()).save(any());
    }

    @Test
    void restockOfANewSkuCreatesIt() throws Exception {
        when(items.addStock(eq("N"), anyInt())).thenReturn(0);
        when(items.findById("N")).thenReturn(Optional.of(new InventoryItem("N", 3)));

        mvc.perform(post("/api/v1/inventory/N/restock").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk());
        verify(items).save(any(InventoryItem.class));
    }

    @Test
    void restockRejectsNonPositiveQuantity() throws Exception {
        mvc.perform(post("/api/v1/inventory/A/restock").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        verify(items, never()).addStock(any(), anyInt());
    }

    // ---- who may call this internal API ---------------------------------------------------------------------------

    @Test
    void noTokenIs401ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/inventory/A")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verify(items, never()).findById(any());
    }

    @Test
    void aCustomerTokenIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/inventory/A").with(customer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/inventory/A/restock").with(customer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}")).andExpect(status().isForbidden());
        verify(items, never()).addStock(any(), anyInt());
    }

    @Test
    void anotherPlatformServiceMayCallIt() throws Exception {
        when(items.findById("A")).thenReturn(Optional.of(new InventoryItem("A", 7)));

        mvc.perform(get("/api/v1/inventory/A").with(service())).andExpect(status().isOk());
    }

    @Test
    void aServiceMayReadStockButOnlyAnAdminMayChangeIt() throws Exception {
        mvc.perform(post("/api/v1/inventory/A/restock").with(service()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}")).andExpect(status().isForbidden());
        verify(items, never()).addStock(any(), anyInt());
    }

    @Test
    void anythingNotListedIsDenied() throws Exception {
        mvc.perform(get("/api/v1/secret")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/secret").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    void probesStayOpenWithoutAToken() throws Exception {
        // the actuator is not part of this slice, so "open" shows up as 404 (not found) rather than 401 (rejected)
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }
}
