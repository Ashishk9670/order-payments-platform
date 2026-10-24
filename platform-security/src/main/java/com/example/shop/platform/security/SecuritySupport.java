package com.example.shop.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** What every service's security configuration has in common: role mapping and RFC 7807 401/403 responses. */
public final class SecuritySupport {

    /** Paths that must stay reachable without a token (probes and metrics scraping). */
    public static final String[] OPEN_PATHS = {"/actuator/health/**", "/actuator/info", "/actuator/prometheus"};

    private SecuritySupport() {
    }

    public static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecuritySupport::roles);
        return converter;
    }

    static Collection<GrantedAuthority> roles(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        List<String> flat = jwt.getClaimAsStringList("roles");
        if (flat != null) {
            flat.forEach(r -> out.add(authority(r)));
        }
        Object realm = jwt.getClaim("realm_access");
        if (realm instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> c) {
            c.forEach(r -> out.add(authority(String.valueOf(r))));
        }
        return out;
    }

    private static GrantedAuthority authority(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role.toUpperCase());
    }

    public static AuthenticationEntryPoint unauthorized(ObjectMapper mapper) {
        return (request, response, ex) -> problem(response, mapper, HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
    }

    public static AccessDeniedHandler forbidden(ObjectMapper mapper) {
        return (request, response, ex) -> problem(response, mapper, HttpStatus.FORBIDDEN, ex.getMessage());
    }

    private static void problem(HttpServletResponse response, ObjectMapper mapper, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
