package com.example.shop.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private final WebhookSignature signature =
            new WebhookSignature("whsec_test", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    private final String body = "{\"id\":\"evt_1\",\"type\":\"charge.dispute.created\"}";

    private String header(long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature.hmac(timestamp + "." + body));
    }

    @Test
    void acceptsAFreshCorrectlySignedBody() {
        assertThat(signature.isValid(header(NOW.getEpochSecond(), body), body)).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        assertThat(signature.isValid(header(NOW.getEpochSecond(), body), body.replace("evt_1", "evt_2"))).isFalse();
    }

    @Test
    void rejectsTheWrongSecret() {
        var other = new WebhookSignature("another", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(other.isValid(header(NOW.getEpochSecond(), body), body)).isFalse();
    }

    @Test
    void rejectsAReplayedRequestOutsideTheToleranceWindow() {
        long old = NOW.minusSeconds(6 * 60).getEpochSecond();
        assertThat(signature.isValid(header(old, body), body)).isFalse();
        long inside = NOW.minusSeconds(4 * 60).getEpochSecond();
        assertThat(signature.isValid(header(inside, body), body)).isTrue();
    }

    @Test
    void rejectsTimestampsFromTheFuture() {
        assertThat(signature.isValid(header(NOW.plusSeconds(10 * 60).getEpochSecond(), body), body)).isFalse();
    }

    @Test
    void movingTheTimestampForwardInvalidatesTheSignature() {
        String old = header(NOW.minusSeconds(6 * 60).getEpochSecond(), body);
        String forged = old.replaceFirst("t=\\d+", "t=" + NOW.getEpochSecond());
        assertThat(signature.isValid(forged, body)).isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(signature.isValid(null, body)).isFalse();
        assertThat(signature.isValid("", body)).isFalse();
        assertThat(signature.isValid("t=abc,v1=00", body)).isFalse();
        assertThat(signature.isValid("t=" + NOW.getEpochSecond() + ",v1=not-hex", body)).isFalse();
        assertThat(signature.isValid("v1=00", body)).isFalse();
    }
}
