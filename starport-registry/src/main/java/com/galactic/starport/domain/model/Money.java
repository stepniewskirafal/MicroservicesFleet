package com.galactic.starport.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount.stripTrailingZeros();
        this.currency = Objects.requireNonNull(currency);
    }

    public Money plus(Money other) {
        ensureSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(this.amount.multiply(factor), currency);
    }

    private void ensureSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) throw new IllegalArgumentException("Currency mismatch");
    }

    @Override
    public String toString() {
        return amount + " " + currency.getCurrencyCode();
    }
}
