package com.example.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ratelimit.requests-per-minute=3",
        "security.jwt.secret=integration-test-secret-integration-test-0123456789",
        "security.dev-token.enabled=true"})
@Import(Containers.class)
class RateLimitIT {

    @Autowired TestRestTemplate http;

    @Test
    void fourthRequestInTheWindowGets429WithRetryAfter() {
        // /dev/token is outside /api/** so it does not consume the budget
        var headers = new HttpHeaders();
        headers.setBearerAuth(http.postForObject("/dev/token", Map.of("customerId", "limited"), JsonNode.class).get("token").asText());
        String url = "/api/v1/orders/" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        var limited = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
