package com.example.shop.order.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.order.domain.OrderStatus;
import com.example.shop.order.redis.RateLimitInterceptor;
import com.example.shop.order.reconcile.StuckOrdersMonitor;
import com.example.shop.order.reconcile.StuckOrdersMonitor.StuckOrder;
import com.example.shop.order.security.CurrentUser;
import com.example.shop.order.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, CurrentUser.class})
class AdminControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean StuckOrdersMonitor stuck;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RateLimitInterceptor rateLimit;

    @BeforeEach
    void allowRateLimit() throws Exception {
        when(rateLimit.preHandle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("caller")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void anAdminSeesTheStuckOrders() throws Exception {
        var id = UUID.randomUUID();
        when(stuck.list(100)).thenReturn(List.of(new StuckOrder(id, "alice", OrderStatus.PENDING, 1800)));

        mvc.perform(get("/api/v1/admin/stuck-orders").with(role("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].ageSeconds").value(1800));
    }

    @Test
    void theLimitIsClamped() throws Exception {
        when(stuck.list(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/admin/stuck-orders?limit=100000").with(role("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/stuck-orders?limit=0").with(role("ADMIN"))).andExpect(status().isOk());

        verify(stuck).list(500);
        verify(stuck).list(1);
    }

    @Test
    void customersAndServicesAreKeptOutAndAnonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/admin/stuck-orders")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        for (String role : new String[] {"CUSTOMER", "SERVICE"}) {
            mvc.perform(get("/api/v1/admin/stuck-orders").with(role(role))).andExpect(status().isForbidden());
        }
        verify(stuck, never()).list(anyInt());
    }
}
