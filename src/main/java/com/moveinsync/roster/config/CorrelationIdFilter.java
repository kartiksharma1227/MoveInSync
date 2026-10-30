package com.moveinsync.roster.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request one ID that appears in every log line it produces, in the response header and in any error
 * body. Someone reporting a failure quotes the ID, and on-call finds all the related logs with it.
 * Runs before everything else, including security, so even a 401 carries the ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** A caller-supplied ID is reused only if it's harmless: no spaces or newlines that could forge log lines. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // threads are reused, so never leave an ID behind for the next request
        }
    }

    /** The current request's ID, or null outside a request (e.g. in background jobs). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
