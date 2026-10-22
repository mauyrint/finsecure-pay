package com.finsecure.pay.model;

/** A single failed business rule. {@code code} is stable and safe to use as a metric label. */
public record ValidationError(String code, String field, String message) {
}
