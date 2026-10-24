package com.example.shop.order.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClientException;

class ServiceTokensTest {

    /** A clock the test can move. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static final SecretKey KEY = new SecretKeySpec("service-token-test-secret-0123456789ab".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

    // ---- self-signed (dev / CI) ---------------------------------------------------------------------------------------

    private final AtomicInteger signed = new AtomicInteger();
    private final JwtEncoder countingEncoder = new JwtEncoder() {
        private final JwtEncoder real = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));

        @Override
        public org.springframework.security.oauth2.jwt.Jwt encode(JwtEncoderParameters parameters) {
            signed.incrementAndGet();
            return real.encode(parameters);
        }
    };

    @Test
    void selfSignedTokenCarriesTheServiceRoleAndIsValidForTheSharedSecret() {
        var clock = Clock.systemUTC();   // the decoder validates expiry against real time
        var tokens = new SelfSignedServiceTokens(countingEncoder, clock);

        var jwt = decoder.decode(tokens.bearer());

        assertThat(jwt.getSubject()).isEqualTo("order-service");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(jwt.getExpiresAt()).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(310));
    }

    @Test
    void aTokenIsReusedUntilShortlyBeforeItExpires() {
        var clock = new MutableClock();
        var tokens = new SelfSignedServiceTokens(countingEncoder, clock);

        String first = tokens.bearer();
        clock.advance(Duration.ofMinutes(3));            // still comfortably valid
        assertThat(tokens.bearer()).isEqualTo(first);
        assertThat(signed.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(100));          // 4m40s of 5m: inside the refresh margin
        assertThat(tokens.bearer()).isNotEqualTo(first);
        assertThat(signed.get()).isEqualTo(2);
    }

    @Test
    void invalidateForcesANewToken() {
        var tokens = new SelfSignedServiceTokens(countingEncoder, new MutableClock());
        tokens.bearer();

        tokens.invalidate();
        tokens.bearer();

        assertThat(signed.get()).isEqualTo(2);
    }

    // ---- client credentials (production) -------------------------------------------------------------------------------

    HttpServer idp;
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicReference<String> lastForm = new AtomicReference<>();
    volatile int status = 200;
    volatile String body = "{\"access_token\":\"idp-token-1\",\"expires_in\":300,\"token_type\":\"Bearer\",\"scope\":\"x\"}";

    private String startIdp() throws IOException {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            lastForm.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        idp.start();
        return "http://127.0.0.1:" + idp.getAddress().getPort() + "/token";
    }

    @AfterEach
    void stopIdp() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    @Test
    void clientCredentialsGrantSendsTheClientAndReturnsTheProvidersToken() throws Exception {
        var tokens = new ClientCredentialsServiceTokens(startIdp(), "order-service", "s3cret", Duration.ofSeconds(2), new MutableClock());

        assertThat(tokens.bearer()).isEqualTo("idp-token-1");
        assertThat(lastForm.get()).contains("grant_type=client_credentials", "client_id=order-service", "client_secret=s3cret");
    }

    @Test
    void theProvidersTokenIsCachedForItsLifetime() throws Exception {
        var clock = new MutableClock();
        var tokens = new ClientCredentialsServiceTokens(startIdp(), "c", "s", Duration.ofSeconds(2), clock);

        tokens.bearer();
        clock.advance(Duration.ofMinutes(2));
        tokens.bearer();
        assertThat(tokenCalls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(130));   // past expires_in minus the margin
        tokens.bearer();
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void aShortLivedTokenIsNeverCachedBeyondItsMargin() throws Exception {
        body = "{\"access_token\":\"short\",\"expires_in\":30}";
        var tokens = new ClientCredentialsServiceTokens(startIdp(), "c", "s", Duration.ofSeconds(2), new MutableClock());

        tokens.bearer();
        tokens.bearer();

        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void aRejectedClientOrAMalformedAnswerFailsLoudly() throws Exception {
        var tokens = new ClientCredentialsServiceTokens(startIdp(), "c", "wrong", Duration.ofSeconds(2), new MutableClock());

        status = 401;
        body = "{\"error\":\"unauthorized_client\"}";
        assertThatThrownBy(tokens::bearer).isInstanceOf(RestClientException.class);

        status = 200;
        body = "{\"expires_in\":300}";
        assertThatThrownBy(tokens::bearer).isInstanceOf(IllegalStateException.class).hasMessageContaining("no access_token");

        body = "<html>";
        assertThatThrownBy(tokens::bearer).isInstanceOf(IllegalStateException.class);
    }

    // ---- which implementation is chosen ---------------------------------------------------------------------------------

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ServiceTokensConfig.class);
    }

    @Test
    void anIdentityProviderTokenEndpointWinsOverTheSharedSecret() {
        runner().withBean(JwtEncoder.class, () -> countingEncoder)
                .withPropertyValues("security.service-account.token-uri=http://idp/token", "security.service-account.client-secret=x")
                .run(ctx -> assertThat(ctx.getBean(ServiceTokens.class)).isInstanceOf(ClientCredentialsServiceTokens.class));
    }

    @Test
    void withoutAnIdentityProviderTheSharedSecretIsUsed() {
        runner().withBean(JwtEncoder.class, () -> countingEncoder)
                .run(ctx -> assertThat(ctx.getBean(ServiceTokens.class)).isInstanceOf(SelfSignedServiceTokens.class));
    }

    @Test
    void withNeitherThereIsNoTokenAndNothingBreaks() {
        runner().run(ctx -> {
            var tokens = ctx.getBean(ServiceTokens.class);
            assertThat(tokens.bearer()).isNull();
            tokens.invalidate();
        });
    }
}
