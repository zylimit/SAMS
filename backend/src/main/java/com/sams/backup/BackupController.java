package com.sams.backup;

import com.sams.audit.AuditService;
import com.sams.auth.RequirePerm;
import com.sams.common.ApiException;
import com.sams.restore.RestoreController;
import com.sams.restore.RestoreService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final JdbcTemplate jdbc;
    private final RestoreService restore;
    private final AuditService audit;

    public BackupController(JdbcTemplate jdbc, RestoreService restore, AuditService audit) {
        this.jdbc = jdbc;
        this.restore = restore;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        RequirePerm.admin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT * FROM restore_point WHERE reason = 'auto_backup' ORDER BY created_at DESC")) {
            out.add(RestoreController.rowDict(r));
        }
        return out;
    }

    @PostMapping("/{id}/restore")
    @Transactional
    public Map<String, Object> restore(@PathVariable long id, HttpServletRequest request) {
        RequirePerm.admin();
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT id FROM restore_point WHERE id = ? AND reason = 'auto_backup'", id);
        if (t.isEmpty()) throw ApiException.of(404, "备份 " + id + " 不存在");
        long pre = restore.createRestorePoint("pre_rollback", null, id, true);
        restore.restoreFromSnapshot(id);
        audit.write("backup_restore", Map.of("backup_id", id, "pre_rollback_id", pre), request);
        audit.write("restore_point_create_auto", Map.of("restore_point_id", pre, "reason", "pre_rollback"), request);
        return Map.of("ok", true, "restored_from", id);
    }
}
