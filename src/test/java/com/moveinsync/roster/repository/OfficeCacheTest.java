package com.moveinsync.roster.repository;

import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.config.CacheConfig;

/** The office-code cache works, and its staleness is real but bounded (evicted on expiry, or explicitly). */
class OfficeCacheTest extends IntegrationTest {

    @Autowired
    OfficeRepository offices;

    @Autowired
    CacheManager cacheManager;

    @Test
    void codesAreCachedPerTenantAndANewOfficeAppearsOnlyAfterEviction() {
        String tenant = uniqueId("C");
        createTenant(jdbc, tenant);

        assertThat(offices.findCodes(tenant)).containsExactlyInAnyOrder("BLR-01", "BLR-02", "HYD-01");
        assertThat(cacheManager.getCache(CacheConfig.OFFICE_CODES).get(tenant)).isNotNull();

        jdbc.update("INSERT INTO office (tenant_id, code, name) VALUES (?, 'PUN-09', 'New office')", tenant);
        assertThat(offices.findCodes(tenant)).doesNotContain("PUN-09"); // stale: served from the cache

        cacheManager.getCache(CacheConfig.OFFICE_CODES).evict(tenant);  // what expiry (5 min) does automatically
        assertThat(offices.findCodes(tenant)).contains("PUN-09");
    }

    @Test
    void cachedSetCannotBeModifiedByACaller() {
        String tenant = uniqueId("C");
        createTenant(jdbc, tenant);

        Set<String> codes = offices.findCodes(tenant);

        assertThatThrownBy(() -> codes.add("HACK"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
