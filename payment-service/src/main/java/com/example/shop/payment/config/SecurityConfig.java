package com.example.shop.payment.config;

import com.example.shop.platform.security.Roles;
import com.example.shop.platform.security.SecuritySupport;
import com.example.shop.platform.security.SharedSecretJwtConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This service's API is internal: callers must present a valid token with the right role (an operator, or another platform
 * service). Anything not listed is denied. Probes and metrics stay open for the platform.
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
                        .requestMatchers(SecuritySupport.OPEN_PATHS).permitAll()
                        .requestMatchers("/api/v1/payments/**").hasAnyRole(Roles.ADMIN, Roles.SERVICE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(SecuritySupport.jwtAuthenticationConverter()))
                        .authenticationEntryPoint(SecuritySupport.unauthorized(mapper))
                        .accessDeniedHandler(SecuritySupport.forbidden(mapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecuritySupport.unauthorized(mapper))
                        .accessDeniedHandler(SecuritySupport.forbidden(mapper)));
        return http.build();
    }
}
