package com.moveinsync.roster.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * The only cache: each tenant's office codes, read for every event batch and nightly file.
 * Staleness is bounded: a newly added office may be rejected as UNKNOWN_OFFICE for at most 5 minutes. Nothing wrong is
 * ever written, since the worst case is a rejected record that HR retries. Hit and miss counts are exported as metrics.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String OFFICE_CODES = "officeCodes";

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(OFFICE_CODES);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1_000)                        // one entry per tenant; least-recently-used evicted beyond that
                .expireAfterWrite(Duration.ofMinutes(5))  // the staleness bound
                .recordStats());
        return manager;
    }
}
