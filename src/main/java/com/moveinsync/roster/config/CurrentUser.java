package com.moveinsync.roster.config;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, read from the verified JWT. The tenant comes only from here, never from the URL or request body,
 * so a caller cannot reach another tenant's data by changing a parameter.
 */
public record CurrentUser(String userId, String tenantId, Set<String> roles) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("tenant"),
                roles == null ? Set.of() : Set.copyOf(roles));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
