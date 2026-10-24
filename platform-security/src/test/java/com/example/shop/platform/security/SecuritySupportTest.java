package com.example.shop.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class SecuritySupportTest {

    private Jwt jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("s").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void flatRolesClaimBecomesUppercaseAuthorities() {
        var auth = SecuritySupport.jwtAuthenticationConverter().convert(jwt(Map.of("roles", List.of("admin", "Customer"))));

        assertThat(auth.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER");
    }

    @Test
    void keycloakRealmRolesAreMappedToo() {
        var auth = SecuritySupport.jwtAuthenticationConverter()
                .convert(jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "service")))));

        assertThat(auth.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SERVICE");
    }

    @Test
    void aTokenWithoutRolesGetsNoAuthorities() {
        assertThat(SecuritySupport.jwtAuthenticationConverter().convert(jwt(Map.of())).getAuthorities()).isEmpty();
    }

    @Test
    void unauthorizedAndForbiddenAreProblemJson() throws Exception {
        var mapper = new ObjectMapper();
        var r401 = new MockHttpServletResponse();
        SecuritySupport.unauthorized(mapper).commence(new MockHttpServletRequest(), r401, new BadCredentialsException("x"));
        var r403 = new MockHttpServletResponse();
        SecuritySupport.forbidden(mapper).handle(new MockHttpServletRequest(), r403, new AccessDeniedException("admins only"));

        assertThat(r401.getStatus()).isEqualTo(401);
        assertThat(r401.getContentType()).isEqualTo("application/problem+json");
        assertThat(r403.getStatus()).isEqualTo(403);
        assertThat(r403.getContentAsString()).contains("admins only");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(SharedSecretJwtConfiguration.class);
    }

    @Test
    void sharedSecretBeansExistOnlyWhenASecretIsConfigured() {
        runner().withPropertyValues("security.jwt.secret=" + "s".repeat(40)).run(ctx -> {
            assertThat(ctx).hasSingleBean(JwtDecoder.class).hasSingleBean(JwtEncoder.class);
        });
        runner().run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class).doesNotHaveBean(JwtEncoder.class));
        runner().withPropertyValues("security.jwt.secret=").run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void aShortSecretRefusesToStart() {
        runner().withPropertyValues("security.jwt.secret=too-short").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("at least 32 bytes"));
    }

    @Test
    void whatTheEncoderSignsTheDecoderAccepts_andAnotherKeyIsRejected() {
        runner().withPropertyValues("security.jwt.secret=" + "k".repeat(40)).run(ctx -> {
            var encoder = ctx.getBean(JwtEncoder.class);
            var decoder = ctx.getBean(JwtDecoder.class);
            var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("svc").expiresAt(Instant.now().plusSeconds(60)).build();
            var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
            String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();

            assertThat(decoder.decode(token).getSubject()).isEqualTo("svc");
            assertThatThrownBy(() -> decoder.decode(token.substring(0, token.length() - 4) + "AAAA")).isInstanceOf(Exception.class);
        });
    }
}
