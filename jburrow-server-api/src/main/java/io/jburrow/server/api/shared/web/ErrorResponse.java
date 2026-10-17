package io.jburrow.server.api.shared.web;

import java.time.Instant;
import java.util.List;

/**
 * Body of every failed request. Field names and order are a published contract: the web repository generates its
 * types from them. {@code fieldErrors} is empty, never absent, outside validation failures.
 */
public record ErrorResponse(
        boolean success, String code, String message, List<FieldError> fieldErrors, Instant timestamp, String path) {

    public ErrorResponse {
        fieldErrors = List.copyOf(fieldErrors);
    }
}
