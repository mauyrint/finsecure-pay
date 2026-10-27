package com.finsecure.pay.model;

public record ValidationError(String code, String field, String message) {
}
