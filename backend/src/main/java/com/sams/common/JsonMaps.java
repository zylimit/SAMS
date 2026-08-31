package com.sams.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonMaps {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private JsonMaps() {}

    public static Map<String, Object> parse(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) return new LinkedHashMap<>();
            try {
                return MAPPER.readValue(s, MAP);
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    public static String stringify(Object raw) {
        try {
            if (raw instanceof String s) return s;
            return MAPPER.writeValueAsString(raw == null ? Map.of() : raw);
        } catch (Exception e) {
            return "{}";
        }
    }
}
