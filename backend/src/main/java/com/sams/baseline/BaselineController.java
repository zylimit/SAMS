package com.sams.baseline;

import com.sams.audit.AuditService;
import com.sams.auth.RequirePerm;
import com.sams.geo.CountryIndex;
import com.sams.restore.RestoreService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BaselineController {
    private final JdbcTemplate jdbc;
    private final RestoreService restore;
    private final AuditService audit;
    private final CountryIndex countries;

    public BaselineController(JdbcTemplate jdbc, RestoreService restore, AuditService audit, CountryIndex countries) {
        this.jdbc = jdbc;
        this.restore = restore;
        this.audit = audit;
        this.countries = countries;
    }

    @GetMapping("/baseline-state")
    public Map<String, Object> state() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM baseline_state WHERE id = 1");
        if (rows.isEmpty()) return Map.of("established", false);
        Map<String, Object> r = rows.get(0);
        CountryIndex.Country c = countries.byIso(String.valueOf(r.get("iso_a2")));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("established", true);
        d.put("iso_a2", r.get("iso_a2"));
        d.put("name_zh", r.get("name_zh"));
        d.put("name_en", c == null ? null : c.nameEn());
        d.put("coverage_pct", r.get("coverage_pct"));
        d.put("points_used", r.get("points_used"));
        d.put("established_at", r.get("established_at") == null ? null : r.get("established_at").toString().replace(' ', 'T'));
        return d;
    }

    @DeleteMapping("/baseline")
    @Transactional
    public Map<String, Object> clear(HttpServletRequest request) {
        RequirePerm.perm("danger");
        long rpId = restore.createRestorePoint("pre_clear", null, null, true);
        int siteN = n("site");
        int roadN = n("road");
        int lessorN = n("lessor");
        int areaN = n("area");
        int baseN = n("baseline_state");
        jdbc.update("DELETE FROM site");
        jdbc.update("DELETE FROM road");
        jdbc.update("DELETE FROM lessor");
        jdbc.update("DELETE FROM area");
        jdbc.update("DELETE FROM baseline_state");
        audit.write("clear_baseline", Map.of(
                "counts_before", Map.of("site", siteN, "road", roadN, "lessor", lessorN, "area", areaN, "baseline_state", baseN),
                "restore_point_id", rpId), request);
        audit.write("restore_point_create_auto", Map.of("restore_point_id", rpId, "reason", "pre_clear"), request);
        return Map.of("deleted", Map.of("site", siteN, "road", roadN, "lessor", lessorN, "area", areaN, "baseline_state", baseN));
    }

    private int n(String table) {
        Integer v = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return v == null ? 0 : v;
    }
}
