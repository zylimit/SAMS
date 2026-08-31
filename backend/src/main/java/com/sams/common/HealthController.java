package com.sams.common;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean db = false;
        try {
            Integer n = jdbc.queryForObject("SELECT 1", Integer.class);
            db = n != null && n == 1;
        } catch (Exception ignored) {
        }
        return Map.of("status", "ok", "db", db);
    }
}
