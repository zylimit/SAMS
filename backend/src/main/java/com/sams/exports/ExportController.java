package com.sams.exports;

import com.sams.audit.AuditService;
import com.sams.auth.AuthContext;
import com.sams.auth.RequirePerm;
import com.sams.auth.ScopeService;
import com.sams.common.ApiException;
import com.sams.feature.GeoJsonFeatures;
import com.sams.geo.GeometryCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
public class ExportController {
    private static final Set<Integer> NP_OPTS = Set.of(50, 100, 150, 200, 250);
    private static final Set<String> MODES = Set.of("polygon", "rect", "circle");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ExportController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/all")
    public ResponseEntity<byte[]> all(@RequestParam(name = "np_radius_m", defaultValue = "200") int radius,
                                      HttpServletRequest request) {
        RequirePerm.perm("export");
        radius = sanitize(radius);
        Map<String, List<Map<String, Object>>> data = fetch(null, AuthContext.require().visibleScopes());
        return kmz("full", data, radius, "export_full", null, request);
    }

    @PostMapping("/selection")
    public ResponseEntity<byte[]> selection(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("export");
        Object poly = body.get("polygon");
        if (!(poly instanceof Map<?, ?> m) || !"Polygon".equals(m.get("type"))) {
            throw ApiException.of(400, "polygon 必须是 GeoJSON Polygon 对象");
        }
        Object coords = m.get("coordinates");
        if (!(coords instanceof List<?> rings) || rings.isEmpty() || !(rings.get(0) instanceof List<?> r0) || r0.isEmpty()) {
            throw ApiException.of(400, "polygon.coordinates 必须是非空 LinearRing 列表");
        }
        Geometry sel = GeometryCodec.fromGeoJson(poly);
        if (sel == null) throw ApiException.of(400, "polygon 必须是 GeoJSON Polygon 对象");
        int radius = sanitize(body.get("np_radius_m") instanceof Number n ? n.intValue() : 200);
        String mode = String.valueOf(body.getOrDefault("mode", "polygon"));
        if (!MODES.contains(mode)) mode = "polygon";
        Map<String, List<Map<String, Object>>> data = fetch(sel, AuthContext.require().visibleScopes());
        return kmz("region", data, radius, "export_region", mode, request);
    }

    @PostMapping("/selection_ids")
    public ResponseEntity<byte[]> selectionIds(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("export");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        if (keys == null || keys.isEmpty()) throw ApiException.of(400, "keys 不能为空");
        int radius = sanitize(body.get("np_radius_m") instanceof Number n ? n.intValue() : 200);
        List<String> scopes = AuthContext.require().visibleScopes();
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Map<String, Object> k : keys) {
            String sid = String.valueOf(k.get("site_id"));
            String opt = String.valueOf(k.getOrDefault("option", ""));
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM site WHERE site_id = ? AND `option` = ?", sid, opt);
            if (rows.isEmpty()) continue;
            Map<String, Object> r = rows.get(0);
            if (!ScopeService.siteRowVisible(scopes, str(r.get("operator")), str(r.get("category")))) continue;
            sites.add(r);
        }
        Map<String, List<Map<String, Object>>> data = Map.of("site", sites, "road", List.of(), "lessor", List.of(), "area", List.of());
        return kmz("region", data, radius, "export_region", "list", request);
    }

    private Map<String, List<Map<String, Object>>> fetch(Geometry sel, List<String> scopes) {
        List<Map<String, Object>> sites = new ArrayList<>();
        List<ScopeService.OpCat> pairs = ScopeService.siteScopePairs(scopes);
        if (pairs == null || !pairs.isEmpty()) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM site")) {
                if (pairs != null && !ScopeService.siteRowVisible(scopes, str(r.get("operator")), str(r.get("category")))) continue;
                if (sel != null && !contained(sel, GeoJsonFeatures.geomOf(r), false)) continue;
                sites.add(r);
            }
        }
        List<Map<String, Object>> roads = new ArrayList<>();
        if (ScopeService.canSeeRoad(scopes)) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM road")) {
                if (sel != null && !contained(sel, GeoJsonFeatures.geomOf(r), false)) continue;
                roads.add(r);
            }
        }
        List<Map<String, Object>> lessors = new ArrayList<>();
        if (ScopeService.canSeeLessor(scopes)) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM lessor")) {
                if (sel != null && !contained(sel, GeoJsonFeatures.geomOf(r), false)) continue;
                lessors.add(r);
            }
        }
        List<Map<String, Object>> areas = new ArrayList<>();
        Set<String> ops = ScopeService.areaScopeOperators(scopes);
        if (ops == null || !ops.isEmpty()) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM area")) {
                if (ops != null && !ops.contains(String.valueOf(r.get("operator")))) continue;
                if (sel != null && !contained(sel, GeoJsonFeatures.geomOf(r), true)) continue;
                areas.add(r);
            }
        }
        return Map.of("site", sites, "road", roads, "lessor", lessors, "area", areas);
    }

    private static boolean contained(Geometry sel, Geometry g, boolean centroid) {
        if (g == null) return false;
        Geometry inner = centroid ? g.getCentroid() : g;
        return GeometryCodec.containsStrict(sel, inner);
    }

    private ResponseEntity<byte[]> kmz(String label, Map<String, List<Map<String, Object>>> data, int radius,
                                       String action, String mode, HttpServletRequest request) {
        String kml = KmzBuilder.buildKml(data.get("site"), data.get("road"), data.get("lessor"), data.get("area"), radius);
        byte[] bytes = KmzBuilder.pack(kml);
        String fname = "export_" + label + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".kmz";
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("file_name", fname);
        details.put("counts", Map.of("site", data.get("site").size(), "road", data.get("road").size(),
                "lessor", data.get("lessor").size(), "area", data.get("area").size()));
        details.put("bytes", bytes.length);
        if (mode != null) details.put("mode", mode);
        audit.write(action, details, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .header("X-Filename", fname)
                .contentType(MediaType.parseMediaType("application/vnd.google-earth.kmz"))
                .body(bytes);
    }

    private static int sanitize(int v) { return NP_OPTS.contains(v) ? v : 200; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
