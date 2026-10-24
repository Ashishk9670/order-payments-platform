package com.example.shop.order.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Production: the OAuth2 client-credentials grant against the identity provider's token endpoint. The client's service account
 * must carry the SERVICE role. The secret comes from the environment / secret store and is never logged.
 */
class ClientCredentialsServiceTokens extends CachingServiceTokens {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    ClientCredentialsServiceTokens(String tokenUri, String clientId, String clientSecret, Duration timeout, Clock clock) {
        super(clock);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    @Override
    Token fetch() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        String raw = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
        try {
            JsonNode json = JSON.readTree(raw);
            String token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("token endpoint returned no access_token");
            }
            long seconds = json.path("expires_in").asLong(60);
            return new Token(token, clock.instant().plusSeconds(seconds));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable token endpoint response", e);
        }
    }
}
