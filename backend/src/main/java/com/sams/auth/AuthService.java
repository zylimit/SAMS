package com.sams.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sams.audit.AuditService;
import com.sams.common.ApiException;
import com.sams.common.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public static final int PASSWORD_MAX_BYTES = 72;
    private static final int LOCK_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final int sessionDays;
    private final ConcurrentHashMap<String, FailEntry> fails = new ConcurrentHashMap<>();

    public AuthService(
            JdbcTemplate jdbc,
            JwtService jwt,
            AuditService audit,
            @Value("${sams.session-days}") int sessionDays) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.audit = audit;
        this.sessionDays = sessionDays;
    }

    public String hashPassword(String pw) {
        byte[] data = pw.getBytes(StandardCharsets.UTF_8);
        if (data.length > PASSWORD_MAX_BYTES) throw new IllegalArgumentException("password exceeds 72 bytes");
        return encoder.encode(pw);
    }

    public boolean verifyPassword(String pw, String hashed) {
        byte[] data = pw.getBytes(StandardCharsets.UTF_8);
        if (data.length > PASSWORD_MAX_BYTES) return false;
        try {
            return encoder.matches(pw, hashed);
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> login(String username, String password, HttpServletRequest request) {
        String ip = HttpUtil.clientIp(request);
        String key = username + "|" + ip;
        if (isLocked(key)) {
            audit.write("login_failed", Map.of("username", username, "reason", "locked"), "failure", null, request, username);
            throw ApiException.of(401, "account locked, try again later");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT u.id AS user_id, u.username, u.password_hash, u.disabled,
                       u.must_change_password, u.role_id, r.is_admin, r.perms
                FROM app_user u JOIN app_role r ON r.id = u.role_id
                WHERE u.username = ?
                """,
                username);
        boolean ok = false;
        Map<String, Object> row = null;
        if (!rows.isEmpty()) {
            row = rows.get(0);
            ok = !asBool(row.get("disabled")) && verifyPassword(password, String.valueOf(row.get("password_hash")));
        }
        if (!ok) {
            recordFail(key);
            audit.write("login_failed", Map.of("username", username), "failure", null, request, username);
            throw ApiException.of(401, "invalid credentials");
        }
        fails.remove(key);
        long userId = ((Number) row.get("user_id")).longValue();
        JwtService.Issued issued = jwt.issue(userId, username);
        Instant exp = Instant.now().plus(sessionDays, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO auth_session (jti, user_id, expires_at) VALUES (?,?,?)",
                issued.jti(), userId, java.sql.Timestamp.from(exp));
        CurrentUser user = toUser(row, issued.jti());
        audit.write("login", Map.of("username", username), "success", null, request, username);
        return Map.of("token", issued.token(), "user", publicUser(user));
    }

    public void logout(CurrentUser user, HttpServletRequest request) {
        if (user != null && user.jti() != null) {
            jdbc.update("DELETE FROM auth_session WHERE jti = ?", user.jti());
        }
        if (user != null) {
            audit.write("logout", Map.of("username", user.username()), "success", null, request, user.username());
        }
    }

    public void changePassword(CurrentUser user, String oldPw, String newPw) {
        byte[] nb = newPw.getBytes(StandardCharsets.UTF_8);
        if (nb.length < 8) throw ApiException.of(400, "new password must be at least 8 bytes");
        if (nb.length > 72) throw ApiException.of(400, "new password exceeds 72 bytes");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, user.userId());
        if (hash == null || !verifyPassword(oldPw, hash)) {
            throw ApiException.of(401, "invalid credentials");
        }
        jdbc.update("UPDATE app_user SET password_hash = ?, must_change_password = 0 WHERE id = ?",
                hashPassword(newPw), user.userId());
        revokeSessions(user.userId(), user.jti());
    }

    public CurrentUser resolve(String token) {
        if (token == null || token.isBlank()) return null;
        io.jsonwebtoken.Claims claims;
        try {
            claims = jwt.parse(token);
        } catch (Exception e) {
            return null;
        }
        String jti = claims.getId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT s.jti, s.expires_at,
                       u.id AS user_id, u.username, u.disabled, u.must_change_password,
                       u.role_id, r.is_admin, r.perms
                FROM auth_session s
                JOIN app_user u ON u.id = s.user_id
                JOIN app_role r ON r.id = u.role_id
                WHERE s.jti = ?
                """,
                jti);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Instant exp = toInstant(row.get("expires_at"));
        if (exp == null || exp.isBefore(Instant.now())) return null;
        if (asBool(row.get("disabled"))) return null;
        jdbc.update("UPDATE auth_session SET expires_at = ? WHERE jti = ?",
                java.sql.Timestamp.from(Instant.now().plus(sessionDays, ChronoUnit.DAYS)), jti);
        return toUser(row, jti);
    }

    public void revokeSessions(long userId, String keepJti) {
        if (keepJti != null) {
            jdbc.update("DELETE FROM auth_session WHERE user_id = ? AND jti <> ?", userId, keepJti);
        } else {
            jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        }
    }

    public List<String> loadScopes(long roleId) {
        return jdbc.query("SELECT scope_node FROM app_role_scope WHERE role_id = ? ORDER BY id",
                (rs, i) -> rs.getString(1), roleId);
    }

    public Map<String, Object> publicUser(CurrentUser u) {
        return Map.of(
                "username", u.username(),
                "is_admin", u.isAdmin(),
                "perms", u.perms(),
                "scopes", u.scopes(),
                "must_change_password", u.mustChangePassword());
    }

    @Scheduled(fixedDelay = 24 * 3600 * 1000L)
    public void cleanupExpired() {
        jdbc.update("DELETE FROM auth_session WHERE expires_at <= NOW(3)");
    }

    private CurrentUser toUser(Map<String, Object> row, String jti) {
        long roleId = ((Number) row.get("role_id")).longValue();
        return new CurrentUser(
                ((Number) row.get("user_id")).longValue(),
                String.valueOf(row.get("username")),
                roleId,
                asBool(row.get("is_admin")),
                coercePerms(row.get("perms")),
                loadScopes(roleId),
                asBool(row.get("must_change_password")),
                jti);
    }

    public static Map<String, Boolean> coercePerms(Object raw) {
        try {
            if (raw instanceof String s) {
                return MAPPER.readValue(s, new TypeReference<>() {});
            }
            if (raw instanceof Map<?, ?> m) {
                Map<String, Boolean> out = new java.util.LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), asBool(v)));
                return out;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp t) return t.toInstant();
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (v instanceof java.util.Date d) return d.toInstant();
        return null;
    }

    public static boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private void recordFail(String key) {
        FailEntry e = fails.computeIfAbsent(key, k -> new FailEntry());
        synchronized (e) {
            e.count++;
            if (e.count >= LOCK_THRESHOLD) {
                e.lockedUntil = Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES);
                e.count = 0;
            }
        }
    }

    private boolean isLocked(String key) {
        FailEntry e = fails.get(key);
        if (e == null || e.lockedUntil == null) return false;
        if (e.lockedUntil.isAfter(Instant.now())) return true;
        fails.remove(key);
        return false;
    }

    private static final class FailEntry {
        int count;
        Instant lockedUntil;
    }
}
