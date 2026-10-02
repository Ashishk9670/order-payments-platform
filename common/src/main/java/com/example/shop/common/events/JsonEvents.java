package com.example.shop.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonEvents {

    private JsonEvents() {
    }

    /** A malformed payload will never succeed on retry, so it is surfaced as a non-retryable exception (-> DLT). */
    public static <T extends DomainEvent> T read(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize " + type.getSimpleName(), e);
        }
    }
}
