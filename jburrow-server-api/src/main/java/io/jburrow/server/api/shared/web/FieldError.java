package io.jburrow.server.api.shared.web;

/** One rejected input in a {@code VALIDATION_FAILED} response. */
public record FieldError(String field, String message) {}
