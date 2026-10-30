package com.moveinsync.roster.repository;

import java.util.Set;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.moveinsync.roster.config.CacheConfig;

/** Office codes are a small reference list, so plain SQL is simpler than a JPA entity. */
@Repository
public class OfficeRepository {

    private final JdbcTemplate jdbc;

    public OfficeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cached per tenant for up to 5 minutes (see CacheConfig). Immutable, so no caller can alter the cached copy. */
    @Cacheable(CacheConfig.OFFICE_CODES)
    public Set<String> findCodes(String tenantId) {
        return Set.copyOf(jdbc.queryForList("SELECT code FROM office WHERE tenant_id = ?", String.class, tenantId));
    }
}
