package com.xpengine;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EventRequest(
        @NotNull Long userId,
        @NotBlank String eventId,   // idempotency key, unique per user
        @NotNull EventType type) {}
