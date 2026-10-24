package com.example.shop.platform.security;

/** The roles the platform knows. A token's roles come from a flat "roles" claim or from Keycloak's realm_access.roles. */
public final class Roles {
    public static final String ADMIN = "ADMIN";        // operators: everything
    public static final String SERVICE = "SERVICE";    // another platform service calling an internal API
    public static final String CUSTOMER = "CUSTOMER";  // an end user: only their own data (order service)

    private Roles() {
    }
}
