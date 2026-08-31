package com.sams.feature;

import com.sams.audit.AuditService;
import com.sams.auth.AuthContext;
import com.sams.auth.RequirePerm;
import com.sams.auth.ScopeService;
import com.sams.common.ApiException;
import com.sams.common.JsonMaps;
import com.sams.geo.GeometryCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeatureController {
    private static final Set<String> FORBIDDEN = Set.of("operator", "category", "type", "site_id", "option");
    private static final int UNDO_KEEP = 200;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FeatureController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/api/sites")
    public Map<String, Object> sites() {
        List<String> scopes = AuthContext.require().visibleScopes();
        List<ScopeService.OpCat> pairs = ScopeService.siteScopePairs(scopes);
        if (pairs != null && pairs.isEmpty()) return GeoJsonFeatures.empty();
        StringBuilder sql = new StringBuilder("SELECT * FROM site");
        List<Object> params = new ArrayList<>();
        if (pairs != null) {
            sql.append(" WHERE ");
            List<String> clauses = new ArrayList<>();
            for (ScopeService.OpCat p : pairs) {
                if (p.category() == null) {
                    clauses.add("operator = ?");
                    params.add(p.operator());
                } else {
                    clauses.add("(operator = ? AND category = ?)");
                    params.add(p.operator());
                    params.add(p.category());
                }
            }
            sql.append(String.join(" OR ", clauses));
        }
        return GeoJsonFeatures.collection(GeoJsonFeatures.mapSites(jdbc.queryForList(sql.toString(), params.toArray())));
    }

    @GetMapping("/api/roads")
    public Map<String, Object> roads() {
        if (!ScopeService.canSeeRoad(AuthContext.require().visibleScopes())) return GeoJsonFeatures.empty();
        List<Map<String, Object>> feats = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM road")) feats.add(GeoJsonFeatures.roadFeature(r));
        return GeoJsonFeatures.collection(feats);
    }

    @GetMapping("/api/lessors")
    public Map<String, Object> lessors() {
        if (!ScopeService.canSeeLessor(AuthContext.require().visibleScopes())) return GeoJsonFeatures.empty();
        List<Map<String, Object>> feats = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM lessor")) feats.add(GeoJsonFeatures.lessorFeature(r));
        return GeoJsonFeatures.collection(feats);
    }

    @GetMapping("/api/areas")
    public Map<String, Object> areas() {
        Set<String> ops = ScopeService.areaScopeOperators(AuthContext.require().visibleScopes());
        if (ops != null && ops.isEmpty()) return GeoJsonFeatures.empty();
        List<Map<String, Object>> rows;
        if (ops == null) rows = jdbc.queryForList("SELECT * FROM area");
        else {
            String in = String.join(",", ops.stream().map(o -> "?").toList());
            rows = jdbc.queryForList("SELECT * FROM area WHERE operator IN (" + in + ")", ops.toArray());
        }
        List<Map<String, Object>> feats = new ArrayList<>();
        for (Map<String, Object> r : rows) feats.add(GeoJsonFeatures.areaFeature(r));
        return GeoJsonFeatures.collection(feats);
    }

    @PatchMapping("/api/sites")
    public Map<String, Object> updateSite(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("edit_delete");
        String siteId = String.valueOf(body.get("site_id"));
        String option = String.valueOf(body.getOrDefault("option", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) body.get("patch");
        if (raw == null) throw ApiException.of(400, "patch 字段非法");
        Set<String> bad = new java.util.HashSet<>(raw.keySet());
        bad.retainAll(FORBIDDEN);
        if (!bad.isEmpty()) {
            throw ApiException.of(400, "禁止修改字段 " + bad.stream().sorted().toList()
                    + "：盖戳列(operator/category/type)与主键(site_id/option)不可改");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        if (raw.containsKey("project")) updates.put("project", raw.get("project"));
        if (raw.containsKey("site_status")) updates.put("site_status", raw.get("site_status"));
        if (raw.containsKey("lati")) updates.put("lati", requireFinite(raw.get("lati"), "lati", -90, 90));
        if (raw.containsKey("longi")) updates.put("longi", requireFinite(raw.get("longi"), "longi", -180, 180));
        if (updates.isEmpty()) throw ApiException.of(400, "无可更新字段（可改：project/site_status/lati/longi）");

        List<String> scopes = AuthContext.require().visibleScopes();
        if (ScopeService.siteScopePairs(scopes) != null) {
            List<Map<String, Object>> t = jdbc.queryForList(
                    "SELECT operator, category FROM site WHERE site_id = ? AND `option` = ?", siteId, option);
            if (t.isEmpty()) throw ApiException.of(404, "site " + siteId + "/" + option + " 不存在");
            if (!ScopeService.siteRowVisible(scopes, str(t.get(0).get("operator")), str(t.get(0).get("category")))) {
                throw ApiException.of(403, "site " + siteId + "/" + option + " 不在数据权限范围内");
            }
        }

        StringBuilder set = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (updates.containsKey("project")) { set.append("project = ?,"); args.add(updates.get("project")); }
        if (updates.containsKey("site_status")) { set.append("site_status = ?,"); args.add(updates.get("site_status")); }
        if (updates.containsKey("lati") || updates.containsKey("longi")) {
            Double lat = (Double) updates.get("lati");
            Double lng = (Double) updates.get("longi");
            set.append("lati = COALESCE(?, lati), longi = COALESCE(?, longi),");
            args.add(lat);
            args.add(lng);
            Map<String, Object> cur = jdbc.queryForMap("SELECT lati, longi FROM site WHERE site_id = ? AND `option` = ?", siteId, option);
            double plat = lat != null ? lat : toD(cur.get("lati"));
            double plng = lng != null ? lng : toD(cur.get("longi"));
            Point p = GeometryCodec.GF.createPoint(new Coordinate(plng, plat));
            set.append("geom = ?,");
            args.add(GeometryCodec.toWkb(p));
        }
        set.append("updated_at = NOW(3)");
        args.add(siteId);
        args.add(option);
        int n = jdbc.update("UPDATE site SET " + set + " WHERE site_id = ? AND `option` = ?", args.toArray());
        if (n == 0) throw ApiException.of(404, "site " + siteId + "/" + option + " 不存在");
        audit.write("edit_site", Map.of("site_id", siteId, "option", option, "changed_fields", updates.keySet().stream().sorted().toList()), request);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM site WHERE site_id = ? AND `option` = ?", siteId, option);
        return GeoJsonFeatures.siteFeature(row);
    }

    @PostMapping("/api/sites/delete")
    @Transactional
    public Map<String, Object> deleteSites(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("edit_delete");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        if (keys == null || keys.isEmpty()) throw ApiException.of(400, "keys 不能为空");
        List<String> scopes = AuthContext.require().visibleScopes();
        boolean scoped = ScopeService.siteScopePairs(scopes) != null;
        List<String[]> kept = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> k : keys) {
            String sid = String.valueOf(k.get("site_id"));
            String opt = String.valueOf(k.getOrDefault("option", ""));
            if (scoped) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT operator, category FROM site WHERE site_id = ? AND `option` = ?", sid, opt);
                if (rows.isEmpty()) continue;
                if (!ScopeService.siteRowVisible(scopes, str(rows.get(0).get("operator")), str(rows.get(0).get("category")))) {
                    skipped++;
                    continue;
                }
            }
            kept.add(new String[]{sid, opt});
        }
        if (kept.isEmpty()) {
            audit.write("delete_site", Map.of("deleted", 0, "undo_id", "", "requested", keys.size(), "skipped", skipped), request);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("deleted", 0);
            resp.put("undo_id", null);
            if (scoped) resp.put("skipped", skipped);
            return resp;
        }
        jdbc.update("INSERT INTO site_delete_undo_batch VALUES ()");
        Long undoId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        int deleted = 0;
        for (String[] k : kept) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM site WHERE site_id = ? AND `option` = ?", k[0], k[1]);
            if (rows.isEmpty()) continue;
            Map<String, Object> r = rows.get(0);
            jdbc.update(
                    """
                    INSERT INTO site_delete_undo
                      (undo_id, site_id, `option`, project, site_status, operator, category, type,
                       lati, longi, extras, source_file, created_at, updated_at, geom)
                    VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)
                    """,
                    undoId, r.get("site_id"), r.get("option"), r.get("project"), r.get("site_status"),
                    r.get("operator"), r.get("category"), r.get("type"), r.get("lati"), r.get("longi"),
                    JsonMaps.stringify(r.get("extras")), r.get("source_file"), r.get("created_at"), r.get("updated_at"), r.get("geom"));
            deleted += jdbc.update("DELETE FROM site WHERE site_id = ? AND `option` = ?", k[0], k[1]);
        }
        try {
            jdbc.update(
                    """
                    DELETE FROM site_delete_undo WHERE undo_id < (
                      SELECT mn FROM (SELECT MIN(undo_id) AS mn FROM (
                        SELECT DISTINCT undo_id FROM site_delete_undo ORDER BY undo_id DESC LIMIT ?
                      ) keep) t)
                    """, UNDO_KEEP);
        } catch (Exception ignored) {
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deleted", deleted);
        details.put("undo_id", undoId);
        details.put("requested", keys.size());
        if (scoped) details.put("skipped", skipped);
        audit.write("delete_site", details, request);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", deleted);
        resp.put("undo_id", undoId);
        if (scoped) resp.put("skipped", skipped);
        return resp;
    }

    @GetMapping("/api/sites/delete-history")
    public List<Map<String, Object>> deleteHistory() {
        RequirePerm.perm("edit_delete");
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT undo_id, MAX(deleted_at) AS deleted_at, COUNT(*) AS cnt,
                       MIN(undone) AS undone,
                       GROUP_CONCAT(DISTINCT COALESCE(operator,'—') SEPARATOR '/') AS operators,
                       GROUP_CONCAT(DISTINCT COALESCE(category,'—') SEPARATOR '/') AS categories,
                       GROUP_CONCAT(DISTINCT COALESCE(type,'—') SEPARATOR '/') AS types
                FROM site_delete_undo GROUP BY undo_id
                ORDER BY MAX(deleted_at) DESC LIMIT ?
                """, UNDO_KEEP);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<String> sample = jdbc.query(
                    "SELECT site_id FROM site_delete_undo WHERE undo_id = ? ORDER BY site_id LIMIT 3",
                    (rs, i) -> rs.getString(1), r.get("undo_id"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("undo_id", r.get("undo_id"));
            d.put("deleted_at", r.get("deleted_at") == null ? null : r.get("deleted_at").toString());
            d.put("count", r.get("cnt"));
            d.put("layer", r.get("operators") + " / " + r.get("categories") + " / " + r.get("types"));
            d.put("sample", sample);
            d.put("undone", AuthServiceBool(r.get("undone")));
            out.add(d);
        }
        return out;
    }

    @PostMapping("/api/sites/undo-delete/{undoId}")
    @Transactional
    public Map<String, Object> undo(@PathVariable long undoId, HttpServletRequest request) {
        RequirePerm.perm("edit_delete");
        Integer requested = jdbc.queryForObject("SELECT count(*) FROM site_delete_undo WHERE undo_id = ?", Integer.class, undoId);
        if (requested == null || requested == 0) throw ApiException.of(404, "删除批次 " + undoId + " 不存在");
        List<String> scopes = AuthContext.require().visibleScopes();
        List<ScopeService.OpCat> pairs = ScopeService.siteScopePairs(scopes);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM site_delete_undo WHERE undo_id = ? AND undone = 0", undoId);
        int restored = 0;
        int skipped = 0;
        for (Map<String, Object> r : rows) {
            if (pairs != null && !ScopeService.siteRowVisible(scopes, str(r.get("operator")), str(r.get("category")))) {
                skipped++;
                continue;
            }
            int n = jdbc.update(
                    """
                    INSERT IGNORE INTO site
                      (site_id, `option`, project, site_status, operator, category, type,
                       lati, longi, extras, source_file, created_at, updated_at, geom)
                    VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)
                    """,
                    r.get("site_id"), r.get("option"), r.get("project"), r.get("site_status"),
                    r.get("operator"), r.get("category"), r.get("type"), r.get("lati"), r.get("longi"),
                    JsonMaps.stringify(r.get("extras")), r.get("source_file"), r.get("created_at"), r.get("updated_at"), r.get("geom"));
            restored += n;
            jdbc.update("UPDATE site_delete_undo SET undone = 1 WHERE undo_id = ? AND site_id = ? AND `option` = ?",
                    undoId, r.get("site_id"), r.get("option"));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("undo_id", undoId);
        details.put("restored", restored);
        if (pairs != null) details.put("skipped", skipped);
        audit.write("undo_delete_site", details, request);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("restored", restored);
        resp.put("requested", requested);
        if (pairs != null) resp.put("skipped", skipped);
        return resp;
    }

    private static Double requireFinite(Object v, String name, double min, double max) {
        if (v == null) return null;
        double d = toD(v);
        if (!Double.isFinite(d)) throw ApiException.of(400, "patch 字段非法：" + name + " 必须是有限数字（拒 nan/inf）");
        if (d < min || d > max) throw ApiException.of(400, "patch 字段非法：" + name + " 必须在 [" + (int) min + ", " + (int) max + "]");
        return d;
    }

    private static double toD(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static boolean AuthServiceBool(Object v) {
        return com.sams.auth.AuthService.asBool(v);
    }
}
