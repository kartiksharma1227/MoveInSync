package com.moveinsync.roster.controller.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues test tokens for demos and tests. Exists only in the 'dev' profile: in production an identity provider
 * (e.g. Keycloak) issues tokens and this service only verifies them.
 */
@RestController
@Profile("dev")
public class DevTokenController {

    public record TokenRequest(String sub, String tenant, List<String> roles, Integer ttlMinutes) {
    }

    private final JwtEncoder encoder;

    public DevTokenController(SecretKey jwtSigningKey) {
        this.encoder = NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
    }

    @PostMapping("/dev/token")
    public Map<String, String> issue(@RequestBody TokenRequest request) {
        Instant now = Instant.now();
        int ttl = request.ttlMinutes() == null ? 60 : request.ttlMinutes();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(request.sub())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(ttl)))
                .claim("roles", request.roles() == null ? List.of() : request.roles());
        if (request.tenant() != null) {
            claims.claim("tenant", request.tenant());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return Map.of("token", token);
    }
}
