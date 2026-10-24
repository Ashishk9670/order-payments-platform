package com.example.shop.order.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Dev/CI only: signs a short-lived SERVICE token with the same shared secret every service validates against.
 * With an identity provider this is replaced by ClientCredentialsServiceTokens.
 */
class SelfSignedServiceTokens extends CachingServiceTokens {

    static final Duration TTL = Duration.ofMinutes(5);
    static final String SUBJECT = "order-service";

    private final JwtEncoder encoder;
    private final Clock clock;

    SelfSignedServiceTokens(JwtEncoder encoder, Clock clock) {
        super(clock);
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    Token fetch() {
        Instant now = clock.instant();
        Instant expires = now.plus(TTL);
        var claims = JwtClaimsSet.builder().subject(SUBJECT).issuedAt(now).expiresAt(expires).claim("roles", List.of("SERVICE")).build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Token(jwt, expires);
    }
}
