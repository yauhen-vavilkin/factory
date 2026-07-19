package org.folio.factory.app.web;

import org.folio.factory.core.registry.model.StepDescriptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Shared formatting and request-parsing helpers for the server-rendered views.
 * All methods are pure so the controllers stay thin and consistent.
 */
final class UiFormat {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private UiFormat() {
    }

    /** Formats an instant as {@code yyyy-MM-dd HH:mm:ss} UTC, or {@code null} when the instant is null. */
    static String format(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant);
    }

    static String abbreviate(String value) {
        return value.length() <= 13 ? value : value.substring(0, 10) + "...";
    }

    static String stepLabel(StepDescriptor step) {
        return switch (step.type()) {
            case AGENT -> step.workerId();
            case HITL_GATE -> step.gate().title();
            case SUB_FLOW -> "Sub-flow: " + step.subFlow().flowId();
        };
    }

    /** Lenient enum parse: blank yields null, and any unknown value yields null rather than throwing. */
    static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String errorRedirect(String path, String message) {
        return "redirect:" + path + "?error=" + encode(message == null ? "Request failed" : message);
    }
}
