package com.moveinsync.roster;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/**
 * Base class for tests that need the running app. All subclasses share one Spring context and one Postgres
 * container. The 'dev' profile loads the seed tenants and offices and enables the /dev/token endpoint.
 * Tests create employees with fresh random IDs, so they never interfere with each other.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Gets a real signed token from the dev token endpoint. */
    protected String token(String sub, String tenant, String... roles) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("tenant", tenant); // null leaves the claim out
        claims.put("roles", List.of(roles));
        String body = mockMvc.perform(post("/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(claims)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    protected static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
