package com.example.shop.order.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV/CI ONLY (security.dev-token.enabled=true): mints a short-lived token for any customer id so the storefront and
 * the test-suite can sign in without an identity provider. Never enable in production; use Keycloak there.
 */
@RestController
@ConditionalOnProperty(name = "security.dev-token.enabled", havingValue = "true")
class DevTokenController {

    record TokenRequest(@NotBlank @Size(max = 64) String customerId, boolean admin) {
    }

    record TokenResponse(String token, long expiresInSeconds) {
    }

    private static final Duration TTL = Duration.ofHours(1);
    private final JwtEncoder encoder;

    DevTokenController(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    @PostMapping("/dev/token")
    TokenResponse token(@Valid @RequestBody TokenRequest request) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(request.customerId()).issuedAt(now).expiresAt(now.plus(TTL))
                .claim("roles", request.admin() ? List.of("ADMIN", "CUSTOMER") : List.of("CUSTOMER")).build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(jwt, TTL.toSeconds());
    }
}
