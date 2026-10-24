package com.example.shop.order.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Configuration
class ServiceTokensConfig {

    /**
     * Picks how this service authenticates to other services: an identity provider's client-credentials grant when
     * security.service-account.token-uri is set (production), otherwise a self-signed token from the shared dev secret.
     */
    @Bean
    ServiceTokens serviceTokens(ObjectProvider<JwtEncoder> encoder,
                                @Value("${security.service-account.token-uri:}") String tokenUri,
                                @Value("${security.service-account.client-id:order-service}") String clientId,
                                @Value("${security.service-account.client-secret:}") String clientSecret) {
        Clock clock = Clock.systemUTC();
        if (!tokenUri.isBlank()) {
            return new ClientCredentialsServiceTokens(tokenUri, clientId, clientSecret, Duration.ofSeconds(3), clock);
        }
        JwtEncoder jwtEncoder = encoder.getIfAvailable();
        if (jwtEncoder != null) {
            return new SelfSignedServiceTokens(jwtEncoder, clock);
        }
        return new ServiceTokens() {   // nothing configured: callees will answer 401, which surfaces as "pricing unavailable"
            @Override
            public String bearer() {
                return null;
            }

            @Override
            public void invalidate() {
            }
        };
    }
}
