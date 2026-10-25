package com.example.shop.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the processor's webhook signature: header {@code t=<unix seconds>,v1=<hex>} where v1 is HMAC-SHA256 over
 * {@code <t>.<raw body>} with the shared secret. The timestamp is part of what is signed, so a captured request cannot be
 * replayed after the tolerance window, and an attacker cannot move it forward without invalidating the signature.
 */
public class WebhookSignature {

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignature(String secret, Duration tolerance, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public boolean isValid(String header, String body) {
        if (header == null || body == null) {
            return false;
        }
        String t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                t = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(t);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs().compareTo(tolerance) > 0) {
            return false;   // too old (replay) or too far in the future
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(v1);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // constant-time comparison: a plain equals would leak how many leading bytes matched
        return MessageDigest.isEqual(given, hmac(t + "." + body));
    }

    /** What the sender computes; public so tests (and a local processor stand-in) sign the same way. */
    public byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
