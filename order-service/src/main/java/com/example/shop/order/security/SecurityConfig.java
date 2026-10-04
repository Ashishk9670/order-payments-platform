package com.example.shop.order.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless OAuth2 resource server. Tokens are validated either against an identity provider (set
 * spring.security.oauth2.resourceserver.jwt.issuer-uri, e.g. Keycloak) or, for local/dev/CI, against a shared HS256
 * secret (security.jwt.secret). Errors are RFC 7807 problem+json like the rest of the API.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> problem(res, mapper, HttpStatus.UNAUTHORIZED,
                "A valid bearer token is required");
        AccessDeniedHandler forbidden = (req, res, ex) -> problem(res, mapper, HttpStatus.FORBIDDEN, ex.getMessage());
        http
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer-token API: no cookies, so no CSRF surface
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/graphiql/**", "/dev/token",
                                "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .headers(Customizer.withDefaults());
        return http.build();
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                String detail) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** Roles come from a flat "roles" claim or from Keycloak's realm_access.roles. */
    static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::roles);
        return converter;
    }

    private static Collection<GrantedAuthority> roles(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        addAll(out, jwt.getClaimAsStringList("roles"));
        Object realm = jwt.getClaim("realm_access");
        if (realm instanceof java.util.Map<?, ?> m && m.get("roles") instanceof Collection<?> c) {
            c.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + String.valueOf(r).toUpperCase())));
        }
        return out;
    }

    private static void addAll(List<GrantedAuthority> out, List<String> roles) {
        if (roles != null) {
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
        }
    }

    @Configuration
    @ConditionalOnExpression("!'${security.jwt.secret:}'.isEmpty()")
    static class SharedSecretJwt {

        @Bean
        SecretKey jwtSecretKey(@org.springframework.beans.factory.annotation.Value("${security.jwt.secret}") String secret) {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("security.jwt.secret must be at least 32 bytes for HS256");
            }
            return new SecretKeySpec(bytes, "HmacSHA256");
        }

        @Bean
        JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
            return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        }

        @Bean
        JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
            return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
        }
    }
}
