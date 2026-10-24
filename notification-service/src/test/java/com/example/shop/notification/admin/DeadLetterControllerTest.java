package com.example.shop.notification.admin;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.notification.admin.DeadLetterService.DeadLetterRecord;
import com.example.shop.notification.admin.DeadLetterService.DeadLetterTopic;
import com.example.shop.notification.admin.DeadLetterService.ReplayResult;
import com.example.shop.notification.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(DeadLetterController.class)
@Import(SecurityConfig.class)
class DeadLetterControllerTest {

    static final String DLT = "order.created.v1.DLT";

    @Autowired MockMvc mvc;
    @MockitoBean DeadLetterService service;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("caller")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void anAdminListsPeeksReplaysAndAcknowledges() throws Exception {
        when(service.list()).thenReturn(List.of(new DeadLetterTopic(DLT, "order.created.v1", 3)));
        when(service.peek(DLT, 20)).thenReturn(List.of(new DeadLetterRecord(0, 7, "k", 1L, "order.created.v1", "boom", "{}")));
        when(service.replay(DLT, 100)).thenReturn(new ReplayResult(DLT, 3, 0, 0));
        when(service.acknowledge(DLT)).thenReturn(3L);

        mvc.perform(get("/api/v1/admin/dlt").with(role("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic").value(DLT)).andExpect(jsonPath("$[0].pending").value(3));
        mvc.perform(get("/api/v1/admin/dlt/" + DLT + "/records").with(role("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].error").value("boom"));
        mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/replay").with(role("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(3));
        mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/acknowledge").with(role("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(3));
    }

    @Test
    void limitsAreClamped() throws Exception {
        when(service.replay(anyString(), anyInt())).thenReturn(new ReplayResult(DLT, 0, 0, 0));
        when(service.peek(anyString(), anyInt())).thenReturn(List.of());

        mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/replay?max=99999").with(role("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/replay?max=0").with(role("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/dlt/" + DLT + "/records?limit=100000").with(role("ADMIN"))).andExpect(status().isOk());

        verify(service).replay(DLT, 1000);
        verify(service).replay(DLT, 1);
        verify(service).peek(DLT, 200);
    }

    @Test
    void aTopicThatIsNotAPlatformDeadLetterTopicIs404AndNothingHappens() throws Exception {
        for (String bad : new String[] {"order.created.v1", "random.DLT", "__consumer_offsets"}) {
            mvc.perform(post("/api/v1/admin/dlt/" + bad + "/replay").with(role("ADMIN"))).andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", "application/problem+json"));
            mvc.perform(post("/api/v1/admin/dlt/" + bad + "/acknowledge").with(role("ADMIN"))).andExpect(status().isNotFound());
        }
        verify(service, never()).replay(anyString(), anyInt());
        verify(service, never()).acknowledge(anyString());
    }

    @Test
    void onlyAnAdminMayUseIt() throws Exception {
        mvc.perform(get("/api/v1/admin/dlt")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        for (String role : new String[] {"CUSTOMER", "SERVICE"}) {
            mvc.perform(get("/api/v1/admin/dlt").with(role(role))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/replay").with(role(role))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/dlt/" + DLT + "/acknowledge").with(role(role))).andExpect(status().isForbidden());
        }
        verify(service, never()).replay(anyString(), anyInt());
        verify(service, never()).acknowledge(anyString());
    }

    @Test
    void probesStayOpenAndEverythingElseIsDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());   // not part of this slice, but not rejected either
        mvc.perform(get("/api/v1/other").with(role("ADMIN"))).andExpect(status().isForbidden());
    }
}
