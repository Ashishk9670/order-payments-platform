package com.example.shop.payment.webhook;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.payment.config.SecurityConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Without a configured secret the endpoint must not exist: "accept anything" is never the fallback. */
@WebMvcTest(WebhookController.class)
@Import({SecurityConfig.class, WebhookDisabledTest.Fixtures.class})
class WebhookDisabledTest {

    @TestConfiguration
    static class Fixtures {
        @Bean Clock clock() {
            return Clock.systemUTC();
        }

        @Bean SimpleMeterRegistry meters() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean PspWebhookEventRepository events;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void isNotFoundWhenNoSecretIsConfigured() throws Exception {
        mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"1\",\"type\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
