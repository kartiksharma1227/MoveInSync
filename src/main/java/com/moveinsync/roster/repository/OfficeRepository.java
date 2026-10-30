package com.moveinsync.roster.repository;

import java.util.HashSet;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Office codes are a small reference list, so plain SQL is simpler than a JPA entity. */
@Repository
public class OfficeRepository {

    private final JdbcTemplate jdbc;

    public OfficeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findCodes(String tenantId) {
        return new HashSet<>(jdbc.queryForList("SELECT code FROM office WHERE tenant_id = ?", String.class, tenantId));
    }
}
