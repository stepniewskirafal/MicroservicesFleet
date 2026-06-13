package com.galactic.starport.service;

/**
 * Bounds the cardinality of the {@code starport} metric tag. A raw request-supplied starport code
 * must never be tagged directly — an unbounded stream of codes would explode Prometheus series
 * (ADR-0030). Implementations map unknown codes to a single low-cardinality bucket.
 */
@FunctionalInterface
public interface StarportTagSanitizer {
    String sanitize(String starportCode);
}
