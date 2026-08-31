package com.sams.restore;

import com.sams.audit.AuditService;
import com.sams.auth.RequirePerm;
import com.sams.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/restore-points")
public class RestoreController {
    private final JdbcTemplate jdbc;
    private final RestoreService restore;
    private final AuditService audit;

    public RestoreController(JdbcTemplate jdbc, RestoreService restore, AuditService audit) {
        this.jdbc = jdbc;
        this.restore = restore;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        RequirePerm.perm("danger");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM restore_point ORDER BY created_at DESC")) {
            out.add(rowDict(r));
        }
        return out;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("danger");
        String note = body == null ? null : (body.get("note") == null ? null : String.valueOf(body.get("note")));
        long id = restore.createRestorePoint("manual", note, null, true);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM restore_point WHERE id = ?", id);
        audit.write("restore_point_create_manual", Map.of(
                "restore_point_id", id,
                "note", note == null ? "" : note,
                "counts", Map.of("site", row.get("site_count"), "road", row.get("road_count"),
                        "lessor", row.get("lessor_count"), "area", row.get("area_count"))), request);
        return rowDict(row);
    }

    @PostMapping("/{id}/rollback")
    @Transactional
    public Map<String, Object> rollback(@PathVariable long id, HttpServletRequest request) {
        RequirePerm.perm("danger");
        List<Map<String, Object>> t = jdbc.queryForList("SELECT id, reason FROM restore_point WHERE id = ?", id);
        if (t.isEmpty()) throw ApiException.of(404, "恢复点 " + id + " 不存在");
        Map<String, Object> before = counts();
        long pre = restore.createRestorePoint("pre_rollback", null, id, true);
        restore.restoreFromSnapshot(id);
        Map<String, Object> after = counts();
        boolean undoImport = "pre_import".equals(String.valueOf(t.get(0).get("reason")));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restore_point_id", id);
        details.put("target_reason", t.get(0).get("reason"));
        details.put("new_restore_point_id", pre);
        details.put("counts_before", before);
        details.put("counts_after", after);
        if (undoImport) details.put("shortcut", true);
        audit.write(undoImport ? "restore_point_undo_last_import" : "restore_point_rollback", details, request);
        audit.write("restore_point_create_auto", Map.of("restore_point_id", pre, "reason", "pre_rollback"), request);
        return Map.of("ok", true, "rolled_back_to", id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        RequirePerm.perm("danger");
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT id, reason, site_count, road_count, lessor_count, area_count FROM restore_point WHERE id = ?", id);
        if (t.isEmpty()) throw ApiException.of(404, "恢复点 " + id + " 不存在");
        jdbc.update("DELETE FROM restore_point WHERE id = ?", id);
        audit.write("restore_point_delete", Map.of(
                "restore_point_id", id,
                "reason", t.get(0).get("reason"),
                "counts", Map.of("site", t.get(0).get("site_count"), "road", t.get(0).get("road_count"),
                        "lessor", t.get(0).get("lessor_count"), "area", t.get(0).get("area_count"))), request);
        return Map.of("deleted", id);
    }

    private Map<String, Object> counts() {
        return Map.of(
                "site", jdbc.queryForObject("SELECT count(*) FROM site", Integer.class),
                "road", jdbc.queryForObject("SELECT count(*) FROM road", Integer.class),
                "lessor", jdbc.queryForObject("SELECT count(*) FROM lessor", Integer.class),
                "area", jdbc.queryForObject("SELECT count(*) FROM area", Integer.class));
    }

    public static Map<String, Object> rowDict(Map<String, Object> r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id"));
        d.put("created_at", r.get("created_at") == null ? null : r.get("created_at").toString().replace(' ', 'T'));
        d.put("reason", r.get("reason"));
        d.put("note", r.get("note"));
        d.put("site_count", r.get("site_count"));
        d.put("road_count", r.get("road_count"));
        d.put("lessor_count", r.get("lessor_count"));
        d.put("area_count", r.get("area_count"));
        d.put("baseline_iso_a2", r.get("baseline_iso_a2"));
        return d;
    }
}
