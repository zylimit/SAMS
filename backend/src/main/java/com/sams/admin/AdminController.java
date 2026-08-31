package com.sams.admin;

import com.sams.audit.AuditService;
import com.sams.auth.AuthService;
import com.sams.auth.RequirePerm;
import com.sams.auth.ScopeService;
import com.sams.common.ApiException;
import com.sams.common.JsonMaps;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final List<String> PERM_KEYS = List.of("import", "export", "edit_delete", "danger");
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final AuditService audit;

    public AdminController(JdbcTemplate jdbc, AuthService auth, AuditService audit) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.audit = audit;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        RequirePerm.admin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT u.id, u.username, u.role_id, r.name AS role_name, u.disabled, u.created_at
                FROM app_user u LEFT JOIN app_role r ON r.id = u.role_id ORDER BY u.id
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(userDict(r));
        return out;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.admin();
        String username = String.valueOf(body.getOrDefault("username", "")).strip();
        if (username.isEmpty()) throw ApiException.of(400, "username 不能为空");
        checkPassword(String.valueOf(body.getOrDefault("password", "")));
        long roleId = ((Number) body.get("role_id")).longValue();
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT id, name FROM app_role WHERE id = ?", roleId);
        if (roles.isEmpty()) throw ApiException.of(400, "角色 " + roleId + " 不存在");
        Integer dup = jdbc.query("SELECT 1 FROM app_user WHERE username = ?", rs -> rs.next() ? 1 : null, username);
        if (dup != null) throw ApiException.of(400, "用户名 " + username + " 已存在");
        jdbc.update("INSERT INTO app_user (username, password_hash, role_id) VALUES (?,?,?)",
                username, auth.hashPassword(String.valueOf(body.get("password"))), roleId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT u.id, u.username, u.role_id, u.disabled, u.created_at FROM app_user u WHERE u.username = ?",
                username);
        row.put("role_name", roles.get(0).get("name"));
        audit.write("user_manage", Map.of("target", username, "action", "create", "role_id", roleId), request);
        return userDict(row);
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> reset(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.admin();
        checkPassword(String.valueOf(body.getOrDefault("password", "")));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, username FROM app_user WHERE id = ?", id);
        if (rows.isEmpty()) throw ApiException.of(404, "用户 " + id + " 不存在");
        jdbc.update("UPDATE app_user SET password_hash = ?, must_change_password = 1 WHERE id = ?",
                auth.hashPassword(String.valueOf(body.get("password"))), id);
        auth.revokeSessions(id, null);
        audit.write("user_manage", Map.of("target", rows.get(0).get("username"), "action", "reset"), request);
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/users/{id}/toggle-disabled")
    public Map<String, Object> toggle(@PathVariable long id, HttpServletRequest request) {
        RequirePerm.admin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT u.id, u.username, u.disabled, r.is_admin
                FROM app_user u JOIN app_role r ON r.id = u.role_id WHERE u.id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.of(404, "用户 " + id + " 不存在");
        if (AuthService.asBool(rows.get(0).get("is_admin"))) {
            throw ApiException.of(400, "admin 角色的用户不可禁用");
        }
        boolean newVal = !AuthService.asBool(rows.get(0).get("disabled"));
        jdbc.update("UPDATE app_user SET disabled = ? WHERE id = ?", newVal ? 1 : 0, id);
        if (newVal) auth.revokeSessions(id, null);
        audit.write("user_manage", Map.of(
                "target", rows.get(0).get("username"),
                "action", newVal ? "disable" : "enable"), request);
        return Map.of("id", id, "disabled", newVal);
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        RequirePerm.admin();
        List<Map<String, Object>> roles = jdbc.queryForList(
                """
                SELECT r.id, r.name, r.is_admin, r.perms, r.created_at,
                       (SELECT count(*) FROM app_user u WHERE u.role_id = r.id) AS user_count
                FROM app_role r ORDER BY r.id
                """);
        List<Map<String, Object>> scopes = jdbc.queryForList("SELECT role_id, scope_node FROM app_role_scope ORDER BY id");
        Map<Long, List<String>> byRole = new LinkedHashMap<>();
        for (Map<String, Object> s : scopes) {
            long rid = ((Number) s.get("role_id")).longValue();
            byRole.computeIfAbsent(rid, k -> new ArrayList<>()).add(String.valueOf(s.get("scope_node")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : roles) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", r.get("id"));
            d.put("name", r.get("name"));
            d.put("is_admin", AuthService.asBool(r.get("is_admin")));
            d.put("perms", AuthService.coercePerms(r.get("perms")));
            d.put("scopes", byRole.getOrDefault(((Number) r.get("id")).longValue(), List.of()));
            d.put("user_count", r.get("user_count"));
            d.put("created_at", r.get("created_at") == null ? null : r.get("created_at").toString());
            out.add(d);
        }
        return out;
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRole(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.admin();
        String name = String.valueOf(body.getOrDefault("name", "")).strip();
        if (name.isEmpty()) throw ApiException.of(400, "角色名不能为空");
        Map<String, Boolean> perms = checkPerms(body.get("perms"));
        List<String> scopes = checkScopes(body.get("scopes"));
        Integer dup = jdbc.query("SELECT 1 FROM app_role WHERE name = ?", rs -> rs.next() ? 1 : null, name);
        if (dup != null) throw ApiException.of(400, "角色名 " + name + " 已存在");
        jdbc.update("INSERT INTO app_role (name, perms) VALUES (?, CAST(? AS JSON))", name, JsonMaps.stringify(perms));
        Long roleId = jdbc.queryForObject("SELECT id FROM app_role WHERE name = ?", Long.class, name);
        insertScopes(roleId, scopes);
        audit.write("role_manage", Map.of("target", name, "action", "create", "perms", perms, "scopes", scopes), request);
        return Map.of("id", roleId, "name", name, "is_admin", false, "perms", perms, "scopes", scopes, "user_count", 0);
    }

    @PatchMapping("/roles/{id}")
    public Map<String, Object> updateRole(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.admin();
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT id, name, is_admin, perms FROM app_role WHERE id = ?", id);
        if (roles.isEmpty()) throw ApiException.of(404, "角色 " + id + " 不存在");
        if (AuthService.asBool(roles.get(0).get("is_admin"))) throw ApiException.of(400, "内置 admin 角色不可修改");
        List<String> changed = new ArrayList<>();
        String newName = body.containsKey("name") ? String.valueOf(body.get("name")).strip() : null;
        if (newName != null && newName.isEmpty()) throw ApiException.of(400, "角色名不能为空");
        Map<String, Boolean> perms = body.containsKey("perms") ? checkPerms(body.get("perms")) : null;
        List<String> scopes = body.containsKey("scopes") ? checkScopes(body.get("scopes")) : null;
        if (newName != null && !newName.equals(roles.get(0).get("name"))) {
            Integer dup = jdbc.query("SELECT 1 FROM app_role WHERE name = ? AND id <> ?", rs -> rs.next() ? 1 : null, newName, id);
            if (dup != null) throw ApiException.of(400, "角色名 " + newName + " 已存在");
            jdbc.update("UPDATE app_role SET name = ? WHERE id = ?", newName, id);
            changed.add("name");
        }
        if (perms != null) {
            jdbc.update("UPDATE app_role SET perms = CAST(? AS JSON) WHERE id = ?", JsonMaps.stringify(perms), id);
            changed.add("perms");
        }
        if (scopes != null) {
            jdbc.update("DELETE FROM app_role_scope WHERE role_id = ?", id);
            insertScopes(id, scopes);
            changed.add("scopes");
        }
        List<String> curScopes = scopes != null ? scopes : jdbc.query(
                "SELECT scope_node FROM app_role_scope WHERE role_id = ? ORDER BY id", (rs, i) -> rs.getString(1), id);
        Map<String, Boolean> curPerms = perms != null ? perms : AuthService.coercePerms(roles.get(0).get("perms"));
        audit.write("role_manage", Map.of(
                "target", newName != null ? newName : roles.get(0).get("name"),
                "action", "update",
                "changed", changed,
                "perms", curPerms,
                "scopes", curScopes), request);
        return Map.of("id", id, "changed", changed);
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable long id, HttpServletRequest request) {
        RequirePerm.admin();
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT id, name, is_admin, perms FROM app_role WHERE id = ?", id);
        if (roles.isEmpty()) throw ApiException.of(404, "角色 " + id + " 不存在");
        if (AuthService.asBool(roles.get(0).get("is_admin"))) throw ApiException.of(400, "内置 admin 角色不可删除");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role_id = ?", Integer.class, id);
        if (n != null && n > 0) throw ApiException.of(400, "角色仍挂载 " + n + " 个用户，请先迁移用户");
        List<String> scopes = jdbc.query("SELECT scope_node FROM app_role_scope WHERE role_id = ? ORDER BY id",
                (rs, i) -> rs.getString(1), id);
        jdbc.update("DELETE FROM app_role WHERE id = ?", id);
        audit.write("role_manage", Map.of(
                "target", roles.get(0).get("name"),
                "action", "delete",
                "perms", AuthService.coercePerms(roles.get(0).get("perms")),
                "scopes", scopes), request);
        return Map.of("deleted", id);
    }

    private void insertScopes(long roleId, List<String> scopes) {
        for (String s : scopes) {
            jdbc.update("INSERT IGNORE INTO app_role_scope (role_id, scope_node) VALUES (?,?)", roleId, s);
        }
    }

    private static void checkPassword(String pw) {
        int n = pw.getBytes(StandardCharsets.UTF_8).length;
        if (n < 8) throw ApiException.of(400, "password must be at least 8 bytes");
        if (n > 72) throw ApiException.of(400, "password exceeds 72 bytes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> checkPerms(Object raw) {
        if (raw == null) return Map.of("import", false, "export", false, "edit_delete", false, "danger", false);
        if (!(raw instanceof Map<?, ?> m)) throw ApiException.of(400, "perms 必须是对象");
        for (Object k : m.keySet()) {
            if (!PERM_KEYS.contains(String.valueOf(k))) {
                throw ApiException.of(400, "非法权限键 " + k + "（允许：" + PERM_KEYS + "）");
            }
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : PERM_KEYS) out.put(k, AuthService.asBool(m.get(k)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> checkScopes(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw ApiException.of(400, "scopes 必须是数组");
        List<String> out = new ArrayList<>();
        for (Object s : list) {
            if (!ScopeService.validateScopeNode(s)) throw ApiException.of(400, "非法 scope 节点：" + s);
            if (!out.contains(s.toString())) out.add(s.toString());
        }
        return out;
    }

    private static Map<String, Object> userDict(Map<String, Object> r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id"));
        d.put("username", r.get("username"));
        d.put("role_id", r.get("role_id"));
        d.put("role_name", r.get("role_name"));
        d.put("disabled", AuthService.asBool(r.get("disabled")));
        d.put("created_at", r.get("created_at") == null ? null : r.get("created_at").toString());
        return d;
    }
}
