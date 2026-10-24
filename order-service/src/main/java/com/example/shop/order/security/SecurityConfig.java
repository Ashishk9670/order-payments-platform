package com.example.shop.order.security;

import com.example.shop.platform.security.Roles;
import com.example.shop.platform.security.SecuritySupport;
import com.example.shop.platform.security.SharedSecretJwtConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource server. Tokens are validated either against an identity provider (set
 * spring.security.oauth2.resourceserver.jwt.issuer-uri or jwk-set-uri, e.g. Keycloak) or, for local/dev/CI, against a shared
 * HS256 secret (security.jwt.secret). Errors are RFC 7807 problem+json like the rest of the API.
 */
@Configuration
@EnableWebSecurity
@Import(SharedSecretJwtConfiguration.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer-token API: no cookies, so no CSRF surface
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/graphiql/**", "/dev/token").permitAll()
                        .requestMatchers(SecuritySupport.OPEN_PATHS).permitAll()
                        .requestMatchers("/error").permitAll()   // error dispatch must not turn a 4xx into a 401/403
                        .requestMatchers("/api/v1/admin/**").hasRole(Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(SecuritySupport.jwtAuthenticationConverter()))
                        .authenticationEntryPoint(SecuritySupport.unauthorized(mapper))
                        .accessDeniedHandler(SecuritySupport.forbidden(mapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecuritySupport.unauthorized(mapper))
                        .accessDeniedHandler(SecuritySupport.forbidden(mapper)))
                .headers(Customizer.withDefaults());
        return http.build();
    }
}
