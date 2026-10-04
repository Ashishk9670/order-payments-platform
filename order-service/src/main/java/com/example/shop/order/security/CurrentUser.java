package com.example.shop.order.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The authenticated caller, and the one place that decides who may touch which customer's data:
 * a customer sees only their own orders; an ADMIN sees everything.
 */
@Component
public class CurrentUser {

    private final String customerIdClaim;

    public CurrentUser(@Value("${security.customer-id-claim:sub}") String customerIdClaim) {
        this.customerIdClaim = customerIdClaim;
    }

    /** The customer id of the caller (the JWT claim configured by security.customer-id-claim). */
    public String customerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String value = jwt.getClaimAsString(customerIdClaim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new AccessDeniedException("No authenticated customer");
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public boolean canAccess(String ownerCustomerId) {
        return isAdmin() || customerId().equals(ownerCustomerId);
    }

    /** @throws AccessDeniedException (rendered as 403) when the caller may not act for this customer. */
    public void requireAccess(String ownerCustomerId) {
        if (!canAccess(ownerCustomerId)) {
            throw new AccessDeniedException("You can only access your own orders");
        }
    }
}
