package com.example.shop.payment.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.payment.config.SecurityConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebhookController.class)
@Import({SecurityConfig.class, WebhookControllerTest.Fixtures.class})
@TestPropertySource(properties = "psp.webhook-secret=whsec_test")
class WebhookControllerTest {

    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean SimpleMeterRegistry meters() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SimpleMeterRegistry meters;
    @MockitoBean PspWebhookEventRepository events;
    @MockitoBean JwtDecoder jwtDecoder;

    private final WebhookSignature signer = new WebhookSignature("whsec_test", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    private final String body = "{\"id\":\"evt_1\",\"type\":\"charge.dispute.created\",\"amount\":1200}";

    private String sign(String body) {
        long t = NOW.getEpochSecond();
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(signer.hmac(t + "." + body));
    }

    @Test
    void aSignedEventIsStoredOnceAndAcknowledged() throws Exception {
        when(events.insertIfAbsent(eq("evt_1"), eq("charge.dispute.created"), eq(body), any())).thenReturn(1);

        mvc.perform(post("/webhooks/psp").header("PSP-Signature", sign(body)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("received"));
    }

    @Test
    void redeliveryOrARaceIsAcknowledgedAsADuplicate() throws Exception {
        when(events.insertIfAbsent(any(), any(), any(), any())).thenReturn(0);   // the row was already there

        mvc.perform(post("/webhooks/psp").header("PSP-Signature", sign(body)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
        org.assertj.core.api.Assertions.assertThat(meters.counter("psp.webhooks.duplicates", "type", "charge.dispute.created").count())
                .isEqualTo(1.0);
    }

    @Test
    void missingOrWrongSignatureIs401AndNothingIsStored() throws Exception {
        mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/psp").header("PSP-Signature", sign(body)).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("1200", "1"))).andExpect(status().isUnauthorized());
        verify(events, never()).insertIfAbsent(any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(meters.counter("psp.webhooks.rejected", "reason", "signature").count()).isEqualTo(2.0);
    }

    @Test
    void aSignedBodyWithoutIdAndTypeIs400() throws Exception {
        String bad = "{\"amount\":1}";
        mvc.perform(post("/webhooks/psp").header("PSP-Signature", sign(bad)).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
        String notJson = "nope";
        mvc.perform(post("/webhooks/psp").header("PSP-Signature", sign(notJson)).contentType(MediaType.TEXT_PLAIN).content(notJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storedEventsAreVisibleToAdminsOnly() throws Exception {
        when(events.findAllByOrderByReceivedAtDesc(any())).thenReturn(List.of(new PspWebhookEvent("evt_1", "t", "{}", NOW)));

        mvc.perform(get("/api/v1/payments/webhook-events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/webhook-events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/webhook-events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("evt_1"));
    }
}
