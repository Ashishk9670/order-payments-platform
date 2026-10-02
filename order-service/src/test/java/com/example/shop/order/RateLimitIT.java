package com.example.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ratelimit.requests-per-minute=3")
@Import(Containers.class)
class RateLimitIT {

    @Autowired TestRestTemplate http;

    @Test
    void fourthRequestInTheWindowGets429WithRetryAfter() {
        String url = "/api/v1/orders/" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(http.getForEntity(url, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        var limited = http.getForEntity(url, String.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
