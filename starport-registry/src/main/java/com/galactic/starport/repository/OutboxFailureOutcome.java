package com.galactic.starport.repository;

/**
 * Result of recording a delivery failure against an outbox event: the attempt count after the bump
 * and whether that attempt exhausted the retry budget (dead-lettered). Lets the relay log and emit
 * its dead-letter metric without reaching into the entity.
 */
public record OutboxFailureOutcome(int attempts, boolean deadLettered) {}
