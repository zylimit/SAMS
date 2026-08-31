package com.sams.imports;

import com.sams.audit.AuditService;
import com.sams.auth.AuthContext;
import com.sams.auth.RequirePerm;
import com.sams.auth.ScopeService;
import com.sams.common.ApiException;
import com.sams.common.JsonMaps;
import com.sams.exports.ConflictsXlsx;
import com.sams.geo.CleaningRules;
import com.sams.geo.CountryIndex;
import com.sams.geo.GeometryCodec;
import com.sams.imports.ParseModels.ParseResult;
import com.sams.restore.RestoreService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class ImportController {
    private final ImportSessionStore store;
    private final JdbcTemplate jdbc;
    private final CountryIndex countries;
    private final RestoreService restore;
    private final AuditService audit;
    private final KmlParser kml = new KmlParser();
    private final KmzParser kmz = new KmzParser();
    private final XlsxParser xlsx = new XlsxParser();

    public ImportController(ImportSessionStore store, JdbcTemplate jdbc, CountryIndex countries,
                            RestoreService restore, AuditService audit) {
        this.store = store;
        this.jdbc = jdbc;
        this.countries = countries;
        this.restore = restore;
        this.audit = audit;
    }

    @PostMapping
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String target_kind,
            HttpServletRequest request) throws Exception {
        RequirePerm.perm("import");
        if (target_kind != null && !List.of("site", "road", "lessor", "area").contains(target_kind)) {
            throw ApiException.of(400, "非法 target_kind：" + target_kind);
        }
        if ("area".equals(target_kind) && (operator == null || operator.isBlank())) {
            throw ApiException.of(403, "forbidden: area 导入必须盖戳运营商");
        }
        if (!ScopeService.importTargetVisible(AuthContext.require().visibleScopes(), target_kind, operator, category)) {
            throw ApiException.of(403, "forbidden: 目标图层不在数据权限范围内");
        }
        String kind = detect(file.getOriginalFilename());
        if ("unknown".equals(kind)) throw ApiException.of(400, "不支持的文件类型，仅支持 .kml / .kmz / .xlsx");

        ParseResult parsed;
        try {
            byte[] data = file.getBytes();
            parsed = switch (kind) {
                case "kml" -> kml.parse(data);
                case "kmz" -> kmz.parse(data);
                default -> xlsx.parse(data);
            };
        } catch (XlsxParser.ParseError e) {
            throw ApiException.of(400, e.getMessage());
        } catch (Exception e) {
            throw ApiException.of(400, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Map<String, Integer> skipped = new LinkedHashMap<>(Map.of("site", 0, "road", 0, "lessor", 0, "area", 0));
        if ("site".equals(target_kind)) {
            skipped.put("road", parsed.roads.size()); skipped.put("lessor", parsed.lessors.size()); skipped.put("area", parsed.areas.size());
            parsed.roads.clear(); parsed.lessors.clear(); parsed.areas.clear();
        } else if ("road".equals(target_kind)) {
            skipped.put("site", parsed.sites.size()); skipped.put("lessor", parsed.lessors.size()); skipped.put("area", parsed.areas.size());
            parsed.sites.clear(); parsed.lessors.clear(); parsed.areas.clear();
        } else if ("lessor".equals(target_kind)) {
            skipped.put("site", parsed.sites.size()); skipped.put("road", parsed.roads.size()); skipped.put("area", parsed.areas.size());
            parsed.sites.clear(); parsed.roads.clear(); parsed.areas.clear();
        } else if ("area".equals(target_kind)) {
            skipped.put("site", parsed.sites.size()); skipped.put("road", parsed.roads.size()); skipped.put("lessor", parsed.lessors.size());
            parsed.sites.clear(); parsed.roads.clear(); parsed.lessors.clear();
        }

        Map<String, Map<String, Object>> sitePool = new LinkedHashMap<>();
        Map<String, Integer> seenSite = new HashMap<>();
        for (var s : parsed.sites) {
            String k = siteKey(s.siteId, s.option);
            sitePool.put(k, siteDict(s, file.getOriginalFilename()));
            seenSite.merge(k, 1, Integer::sum);
        }
        int siteDupDiscarded = parsed.sites.size() - sitePool.size();
        int siteDupGroups = (int) seenSite.values().stream().filter(v -> v > 1).count();

        List<Map<String, Object>> roadPool = new ArrayList<>();
        for (var r : parsed.roads) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("property", r.property);
            d.put("extras", r.extras);
            d.put("wkt", r.wkt);
            d.put("source_file", file.getOriginalFilename());
            roadPool.add(d);
        }

        Map<String, Map<String, Object>> lessorPool = new LinkedHashMap<>();
        Map<String, Integer> seenL = new HashMap<>();
        for (var l : parsed.lessors) {
            String k = lessorKey(l.fid);
            lessorPool.put(k, lessorDict(l, file.getOriginalFilename()));
            seenL.merge(k, 1, Integer::sum);
        }
        int lessorDupDiscarded = parsed.lessors.size() - lessorPool.size();
        int lessorDupGroups = (int) seenL.values().stream().filter(v -> v > 1).count();

        Map<String, Map<String, Object>> areaPool = new LinkedHashMap<>();
        Map<String, Integer> seenA = new HashMap<>();
        for (var a : parsed.areas) {
            String k = areaKey(a.name);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", a.name);
            d.put("extras", a.extras);
            d.put("wkt", a.wkt);
            d.put("source_file", file.getOriginalFilename());
            areaPool.put(k, d);
            seenA.merge(k, 1, Integer::sum);
        }
        int areaDupDiscarded = parsed.areas.size() - areaPool.size();
        int areaDupGroups = (int) seenA.values().stream().filter(v -> v > 1).count();

        List<Map<String, Object>> cleanings = new ArrayList<>();
        List<Map<String, Object>> geoPoints = new ArrayList<>();
        for (var e : sitePool.entrySet()) {
            Map<String, Object> row = e.getValue();
            String rid = "site:" + e.getKey();
            String issue = CleaningRules.detectSwapOrMissingDecimal(toD(row.get("lati")), toD(row.get("longi")));
            if ("swap_latlong".equals(issue)) {
                cleanings.add(cleaning(rid, "site", nameOfSite(row), row, "swap_latlong", "auto_fix",
                        Map.of("lat", row.get("longi"), "lng", row.get("lati"))));
            } else if ("missing_decimal".equals(issue)) {
                cleanings.add(cleaning(rid, "site", nameOfSite(row), row, "missing_decimal", "discard", null));
            } else if (row.get("lati") != null && row.get("longi") != null) {
                geoPoints.add(Map.of("row_id", rid, "lat", row.get("lati"), "lng", row.get("longi")));
            }
        }

        Map<String, Object> baseline = computeBaseline(geoPoints);
        String baselineIso = baseline == null ? null : str(baseline.get("country_iso_a2"));
        Map<String, CountryIndex.Classify> classified = new LinkedHashMap<>();
        for (Map<String, Object> p : geoPoints) {
            classified.put(str(p.get("row_id")),
                    countries.classify(toD(p.get("lng")), toD(p.get("lat")), baselineIso));
        }
        Map<String, CountryIndex.Classify> classifiedAreas = new LinkedHashMap<>();
        List<Map<String, Object>> geoAreas = new ArrayList<>();
        for (var e : areaPool.entrySet()) {
            if (e.getValue().get("wkt") != null) {
                String rid = "area:" + e.getKey();
                geoAreas.add(Map.of("row_id", rid, "wkt", e.getValue().get("wkt")));
                Geometry g = GeometryCodec.fromWkt(str(e.getValue().get("wkt")));
                classifiedAreas.put(rid, countries.classify(g, baselineIso));
            }
        }

        for (Map<String, Object> p : geoPoints) {
            CountryIndex.Classify cls = classified.get(str(p.get("row_id")));
            if (cls == null) continue;
            String k = str(p.get("row_id")).split(":", 2)[1];
            Map<String, Object> row = sitePool.get(k);
            if (row == null) continue;
            if (cls.inSea()) {
                Map<String, Object> c = cleaning(str(p.get("row_id")), "site", nameOfSite(row), row, "in_sea", "discard", null);
                c.put("country_iso_a2", null);
                cleanings.add(c);
            } else if (cls.notInBaseline()) {
                Map<String, Object> c = cleaning(str(p.get("row_id")), "site", nameOfSite(row), row, "not_in_baseline", "discard", null);
                c.put("country_iso_a2", cls.countryIsoA2());
                c.put("country_name_zh", cls.countryNameZh());
                c.put("country_name_en", cls.countryNameEn());
                cleanings.add(c);
            }
        }
        for (Map<String, Object> g : geoAreas) {
            CountryIndex.Classify cls = classifiedAreas.get(str(g.get("row_id")));
            if (cls == null) continue;
            Map<String, Object> row = areaPool.get(str(g.get("row_id")).split(":", 2)[1]);
            if (row == null) continue;
            if (cls.inSea()) {
                Map<String, Object> c = cleaning(str(g.get("row_id")), "area", str(row.get("name")), row, "in_sea", "discard", null);
                c.put("current_coord", null);
                c.put("country_iso_a2", null);
                cleanings.add(c);
            } else if (cls.notInBaseline()) {
                Map<String, Object> c = cleaning(str(g.get("row_id")), "area", str(row.get("name")), row, "not_in_baseline", "discard", null);
                c.put("current_coord", null);
                c.put("country_iso_a2", cls.countryIsoA2());
                c.put("country_name_zh", cls.countryNameZh());
                c.put("country_name_en", cls.countryNameEn());
                cleanings.add(c);
            }
        }

        int totalSkipped = skipped.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> geometryGuard = new LinkedHashMap<>();
        geometryGuard.put("target_kind", target_kind);
        geometryGuard.put("skipped", skipped);
        geometryGuard.put("total_skipped", totalSkipped);
        Map<String, String> labels = Map.of("site", "非点要素", "road", "非线要素", "lessor", "非面要素", "area", "非面要素");
        geometryGuard.put("message", target_kind != null && totalSkipped > 0
                ? "几何护栏：跳过 " + totalSkipped + " 个" + labels.get(target_kind) : null);

        boolean warnAll = false;
        if (baseline != null && "baseline".equals(baseline.get("source")) && baseline.get("country_iso_a2") != null) {
            String bIso = str(baseline.get("country_iso_a2"));
            long inside = classified.values().stream().filter(c -> bIso.equals(c.countryIsoA2())).count();
            warnAll = !geoPoints.isEmpty() && inside == 0;
        }

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("file_name", file.getOriginalFilename());
        session.put("site_pool", sitePool);
        session.put("lessor_pool", lessorPool);
        session.put("road_pool", roadPool);
        session.put("area_pool", areaPool);
        session.put("cleanings", cleanings);
        session.put("baseline_region", baseline);
        session.put("target_kind", target_kind);
        session.put("stamp_operator", operator);
        session.put("stamp_category", category);
        session.put("stamp_type", type);
        String sid = store.create(session, "cleaning");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_parsed", parsed.sites.size() + parsed.roads.size() + parsed.lessors.size() + parsed.areas.size());
        summary.put("intra_file_duplicates", Map.of(
                "site_groups", siteDupGroups, "site_discarded", siteDupDiscarded,
                "lessor_groups", lessorDupGroups, "lessor_discarded", lessorDupDiscarded,
                "area_groups", areaDupGroups, "area_discarded", areaDupDiscarded));
        summary.put("after_dedup", Map.of("site", sitePool.size(), "road", roadPool.size(), "lessor", lessorPool.size(), "area", areaPool.size()));
        summary.put("geometry_guard", geometryGuard);
        summary.put("cleanings_count", cleanings.size());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("session_id", sid);
        resp.put("file", Map.of("name", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), "type", kind,
                "parsed", Map.of("site", parsed.sites.size(), "road", parsed.roads.size(), "lessor", parsed.lessors.size(), "area", parsed.areas.size())));
        resp.put("summary", summary);
        resp.put("baseline_region", baseline);
        resp.put("cleanings", cleanings);
        resp.put("geometry_guard", geometryGuard);
        resp.put("warn_all_outside_baseline", warnAll);
        return resp;
    }

    @PostMapping("/{sid}/proceed-to-conflicts")
    public Map<String, Object> proceed(@PathVariable String sid, @RequestBody Map<String, Object> body) {
        RequirePerm.perm("import");
        Map<String, Object> s = store.get(sid);
        if (s == null) throw ApiException.of(404, "session 不存在或已过期");
        String err = store.transition(sid, "conflicts");
        if (err != null) throw new ApiException(400, Map.of("error", "invalid_state", "msg", err));

        Map<String, String> decisions = decisionsOf(body);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> sitePool = copyPool(s.get("site_pool"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> areaPool = copyPool(s.get("area_pool"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cleanings = (List<Map<String, Object>>) s.get("cleanings");
        Map<String, Integer> cleaningStats = new LinkedHashMap<>(Map.of("auto_fixed", 0, "kept", 0, "discarded", 0));
        for (Map<String, Object> c : cleanings) {
            String rid = str(c.get("row_id"));
            String action = decisions.getOrDefault(rid, str(c.get("default_action")));
            String key = rid.contains(":") ? rid.split(":", 2)[1] : rid;
            if ("area".equals(c.get("kind"))) {
                if ("discard".equals(action)) { areaPool.remove(key); cleaningStats.merge("discarded", 1, Integer::sum); }
                else cleaningStats.merge("kept", 1, Integer::sum);
                continue;
            }
            if (!"site".equals(c.get("kind"))) continue;
            if ("discard".equals(action)) {
                sitePool.remove(key);
                cleaningStats.merge("discarded", 1, Integer::sum);
            } else if ("auto_fix".equals(action) && "swap_latlong".equals(c.get("issue"))) {
                Map<String, Object> row = sitePool.get(key);
                if (row != null) {
                    Object ol = row.get("lati"), og = row.get("longi");
                    row.put("lati", og);
                    row.put("longi", ol);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ex = new LinkedHashMap<>((Map<String, Object>) row.getOrDefault("extras", Map.of()));
                    if (ex.containsKey("LATI") && ex.containsKey("LONGI")) {
                        Object a = ex.get("LATI"); ex.put("LATI", ex.get("LONGI")); ex.put("LONGI", a);
                    }
                    row.put("extras", ex);
                    if (row.get("lati") != null && row.get("longi") != null) {
                        row.put("wkt", "POINT(" + row.get("longi") + " " + row.get("lati") + ")");
                    }
                }
                cleaningStats.merge("auto_fixed", 1, Integer::sum);
            } else {
                cleaningStats.merge("kept", 1, Integer::sum);
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roadPool = (List<Map<String, Object>>) s.get("road_pool");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> lessorPool = copyPool(s.get("lessor_pool"));

        Map<String, Map<String, Object>> existingSites = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT site_id, `option`, project, site_status, lati, longi, extras, source_file FROM site")) {
            existingSites.put(siteKey(str(r.get("site_id")), str(r.get("option"))), r);
        }
        Map<String, Map<String, Object>> existingRoads = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, property, extras, source_file FROM road")) {
            String pk = roadKey(str(r.get("property")));
            if (!pk.isEmpty()) existingRoads.put(pk, r);
        }
        Map<String, Map<String, Object>> existingLessors = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT fid, lessor_name, lessor_category, relationship, extras, source_file FROM lessor")) {
            existingLessors.put(lessorKey(str(r.get("fid"))), r);
        }
        Map<String, Map<String, Object>> existingAreas = new HashMap<>();
        if (!areaPool.isEmpty() && s.get("stamp_operator") != null) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT id, name, operator, extras FROM area WHERE operator = ?", s.get("stamp_operator"))) {
                existingAreas.put(areaKey(str(r.get("name"))), r);
            }
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Map<String, Object>>> non = Map.of("site", new ArrayList<>(), "road", new ArrayList<>(), "lessor", new ArrayList<>(), "area", new ArrayList<>());
        for (var e : sitePool.entrySet()) {
            Map<String, Object> existing = existingSites.get(e.getKey());
            if (existing == null) non.get("site").add(e.getValue());
            else conflicts.add(conflict("site:" + e.getValue().get("site_id") + ":" + e.getValue().get("option"),
                    "site", nameOfSite(e.getValue()), existing, e.getValue()));
        }
        for (Map<String, Object> row : roadPool) {
            String pk = roadKey(str(row.get("property")));
            Map<String, Object> existing = pk.isEmpty() ? null : existingRoads.get(pk);
            if (existing == null) non.get("road").add(row);
            else conflicts.add(conflict("road:" + row.get("property"), "road",
                    row.get("property") == null ? "(无 Property)" : str(row.get("property")), existing, row));
        }
        for (var e : lessorPool.entrySet()) {
            Map<String, Object> existing = existingLessors.get(e.getKey());
            if (existing == null) non.get("lessor").add(e.getValue());
            else conflicts.add(conflict("lessor:" + e.getValue().get("fid"), "lessor",
                    e.getValue().get("lessor_name") == null ? str(e.getValue().get("fid")) : str(e.getValue().get("lessor_name")),
                    existing, e.getValue()));
        }
        for (var e : areaPool.entrySet()) {
            Map<String, Object> existing = existingAreas.get(e.getKey());
            if (existing == null) non.get("area").add(e.getValue());
            else conflicts.add(conflict("area:" + s.get("stamp_operator") + ":" + e.getValue().get("name"),
                    "area", str(e.getValue().get("name")), existing, e.getValue()));
        }

        store.update(sid, Map.of(
                "site_pool_cleaned", sitePool,
                "area_pool_cleaned", areaPool,
                "non_conflicts", non,
                "conflicts", conflicts,
                "cleaning_decisions", decisions,
                "cleaning_stats", cleaningStats));

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String k : List.of("site", "road", "lessor", "area")) {
            int cc = (int) conflicts.stream().filter(c -> k.equals(c.get("kind"))).count();
            summary.put(k, Map.of("non_conflict", non.get(k).size(), "conflict", cc));
        }
        return Map.of("session_id", sid, "summary", summary, "conflicts", conflicts, "cleaning_stats", cleaningStats);
    }

    @PostMapping("/{sid}/back-to-cleaning")
    public Map<String, Object> back(@PathVariable String sid) {
        RequirePerm.perm("import");
        Map<String, Object> s = store.get(sid);
        if (s == null) throw ApiException.of(404, "session 不存在或已过期");
        String err = store.transition(sid, "cleaning");
        if (err != null) throw new ApiException(400, Map.of("error", "invalid_state", "msg", err));
        return Map.of("session_id", sid, "cleanings", s.get("cleanings"), "baseline_region", s.get("baseline_region"),
                "cleaning_decisions", s.getOrDefault("cleaning_decisions", Map.of()));
    }

    @PostMapping("/{sid}/commit")
    @Transactional
    public Map<String, Object> commit(@PathVariable String sid, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        RequirePerm.perm("import");
        Map<String, Object> s = store.get(sid);
        if (s == null) throw new ApiException(409, Map.of("error", "already_committed", "msg", "该导入已提交或已过期，请勿重复提交"));
        if (!s.containsKey("non_conflicts")) throw new ApiException(400, Map.of("error", "invalid_state", "msg", "尚未通过 proceed-to-conflicts"));
        String err = store.transition(sid, "committing");
        if (err != null) throw new ApiException(400, Map.of("error", "invalid_state", "msg", err));

        Map<String, String> decisions = decisionsOf(body);
        Map<String, String> stamp = new LinkedHashMap<>();
        stamp.put("operator", str(s.get("stamp_operator")));
        stamp.put("category", str(s.get("stamp_category")));
        stamp.put("type", str(s.get("stamp_type")));
        Map<String, Map<String, Integer>> stats = new LinkedHashMap<>();
        for (String k : List.of("site", "road", "lessor", "area")) {
            stats.put(k, new LinkedHashMap<>(Map.of("inserted", 0, "updated", 0, "ignored", 0)));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> non = (Map<String, List<Map<String, Object>>>) s.get("non_conflicts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) s.get("conflicts");
        int total = non.get("site").size() + non.get("road").size() + non.get("lessor").size()
                + non.getOrDefault("area", List.of()).size() + conflicts.size();
        store.setProgress(sid, 0, total, "committing");
        try {
            long rpId = restore.createRestorePoint("pre_import", null, null, true);
            int done = 0;
            for (Map<String, Object> r : non.get("site")) { insertSite(r, stamp); stats.get("site").merge("inserted", 1, Integer::sum); done++; if (done % 500 == 0) store.setProgress(sid, done, total, "committing"); }
            for (Map<String, Object> r : non.get("road")) { insertRoad(r); stats.get("road").merge("inserted", 1, Integer::sum); done++; }
            for (Map<String, Object> r : non.get("lessor")) { insertLessor(r); stats.get("lessor").merge("inserted", 1, Integer::sum); done++; }
            for (Map<String, Object> r : non.getOrDefault("area", List.of())) { insertArea(r, stamp); stats.get("area").merge("inserted", 1, Integer::sum); done++; }
            for (Map<String, Object> c : conflicts) {
                String action = decisions.getOrDefault(str(c.get("key")), "ignore");
                if ("overwrite".equals(action)) {
                    @SuppressWarnings("unchecked") Map<String, Object> incoming = (Map<String, Object>) c.get("incoming");
                    @SuppressWarnings("unchecked") Map<String, Object> existing = (Map<String, Object>) c.get("existing");
                    switch (str(c.get("kind"))) {
                        case "site" -> { updateSite(existing, incoming, stamp); stats.get("site").merge("updated", 1, Integer::sum); }
                        case "road" -> { updateRoad(existing, incoming); stats.get("road").merge("updated", 1, Integer::sum); }
                        case "lessor" -> { updateLessor(existing, incoming); stats.get("lessor").merge("updated", 1, Integer::sum); }
                        case "area" -> { updateArea(existing, incoming, stamp); stats.get("area").merge("updated", 1, Integer::sum); }
                    }
                } else {
                    stats.get(str(c.get("kind"))).merge("ignored", 1, Integer::sum);
                }
                done++;
                if (done % 500 == 0) store.setProgress(sid, done, total, "committing");
            }
            Map<String, Object> baselineEstablished = null;
            int siteAdded = stats.get("site").get("inserted") + stats.get("site").get("updated");
            if (siteAdded > 0) {
                Integer exists = jdbc.query("SELECT 1 FROM baseline_state WHERE id = 1", rs -> rs.next() ? 1 : null);
                if (exists == null) {
                    Map<String, Object> country = countryDistInDb();
                    if (country != null && country.get("country_iso_a2") != null) {
                        jdbc.update("INSERT IGNORE INTO baseline_state (id, iso_a2, name_zh, coverage_pct, points_used) VALUES (1,?,?,?,?)",
                                country.get("country_iso_a2"), country.get("country_name_zh"), country.get("coverage_pct"), country.get("points_used"));
                        baselineEstablished = Map.of(
                                "iso_a2", country.get("country_iso_a2"),
                                "name_zh", country.get("country_name_zh") == null ? "" : country.get("country_name_zh"),
                                "name_en", country.get("country_name_en") == null ? "" : country.get("country_name_en"),
                                "coverage_pct", country.get("coverage_pct"),
                                "points_used", country.get("points_used"));
                    }
                }
            }
            store.setProgress(sid, total, total, "done");
            audit.write("import", Map.of(
                    "file_name", s.get("file_name"),
                    "stats", stats,
                    "cleaning_stats", s.getOrDefault("cleaning_stats", Map.of()),
                    "restore_point_id", rpId,
                    "baseline_established", baselineEstablished == null ? Map.of() : baselineEstablished), request);
            audit.write("restore_point_create_auto", Map.of("restore_point_id", rpId, "reason", "pre_import"), request);
            store.drop(sid);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("stats", stats);
            resp.put("cleaning_stats", s.getOrDefault("cleaning_stats", Map.of()));
            resp.put("baseline_established", baselineEstablished);
            return resp;
        } catch (RuntimeException e) {
            store.clearProgress(sid);
            throw e;
        }
    }

    @DeleteMapping("/{sid}")
    public Map<String, Object> cancel(@PathVariable String sid) {
        RequirePerm.perm("import");
        return Map.of("dropped", store.drop(sid));
    }

    @GetMapping("/{sid}/progress")
    public Map<String, Object> progress(@PathVariable String sid) {
        RequirePerm.perm("import");
        Map<String, Object> p = store.getProgress(sid);
        if (p == null) return Map.of("done", 0, "total", 0, "pct", 0, "phase", "idle");
        int done = ((Number) p.get("done")).intValue();
        int total = ((Number) p.get("total")).intValue();
        return Map.of("done", done, "total", total, "pct", total > 0 ? done * 100 / total : 0, "phase", p.get("phase"));
    }

    @GetMapping("/{sid}/conflicts.xlsx")
    public ResponseEntity<byte[]> conflictsXlsx(@PathVariable String sid, HttpServletRequest request) {
        RequirePerm.perm("import");
        Map<String, Object> s = store.get(sid);
        if (s == null) throw ApiException.of(404, "session 不存在或已过期");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) s.getOrDefault("conflicts", List.of());
        byte[] data = ConflictsXlsx.build(conflicts);
        String fname = "conflicts_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        audit.write("export_conflicts", Map.of(
                "file_name", fname,
                "source_file", s.get("file_name") == null ? "" : s.get("file_name"),
                "counts", Map.of(
                        "total", conflicts.size(),
                        "site", conflicts.stream().filter(c -> "site".equals(c.get("kind"))).count(),
                        "road", conflicts.stream().filter(c -> "road".equals(c.get("kind"))).count(),
                        "lessor", conflicts.stream().filter(c -> "lessor".equals(c.get("kind"))).count(),
                        "area", conflicts.stream().filter(c -> "area".equals(c.get("kind"))).count()),
                "bytes", data.length), request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .header("X-Filename", fname)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    private Map<String, Object> computeBaseline(List<Map<String, Object>> currentPoints) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM baseline_state WHERE id = 1");
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            CountryIndex.Country c = countries.byIso(str(r.get("iso_a2")));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("country_iso_a2", r.get("iso_a2"));
            d.put("country_name_zh", r.get("name_zh"));
            d.put("country_name_en", c == null ? null : c.nameEn());
            d.put("source", "baseline");
            d.put("coverage_pct", r.get("coverage_pct"));
            d.put("points_used", r.get("points_used"));
            d.put("points_total", r.get("points_used"));
            d.put("established_at", r.get("established_at") == null ? null : r.get("established_at").toString());
            return d;
        }
        if (currentPoints == null || currentPoints.isEmpty()) return null;
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> p : currentPoints) {
            CountryIndex.Classify cls = countries.classify(toD(p.get("lng")), toD(p.get("lat")), null);
            if (cls.countryIsoA2() != null) counts.merge(cls.countryIsoA2(), 1, Integer::sum);
        }
        Map<String, Object> picked = pickCountry(counts);
        if (picked == null) {
            return Map.of("country_iso_a2", "", "country_name_zh", "", "country_name_en", "",
                    "source", "current_file", "coverage_pct", 0, "points_used", 0, "points_total", currentPoints.size());
        }
        picked.put("source", "current_file");
        picked.put("points_total", currentPoints.size());
        return picked;
    }

    private Map<String, Object> countryDistInDb() {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT lati, longi FROM site WHERE lati IS NOT NULL AND longi IS NOT NULL")) {
            CountryIndex.Classify cls = countries.classify(toD(r.get("longi")), toD(r.get("lati")), null);
            if (cls.countryIsoA2() != null) counts.merge(cls.countryIsoA2(), 1, Integer::sum);
        }
        return pickCountry(counts);
    }

    private Map<String, Object> pickCountry(Map<String, Integer> counts) {
        if (counts.isEmpty()) return null;
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).toList();
        Map.Entry<String, Integer> chosen = sorted.get(0);
        for (var e : sorted) {
            if (e.getValue() / (double) total >= CleaningRules.BASELINE_THRESHOLD) {
                chosen = e;
                break;
            }
        }
        CountryIndex.Country c = countries.byIso(chosen.getKey());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("country_iso_a2", chosen.getKey());
        d.put("country_name_zh", c == null ? null : c.nameZh());
        d.put("country_name_en", c == null ? null : c.nameEn());
        d.put("coverage_pct", Math.round(chosen.getValue() * 100.0 / total));
        d.put("points_used", chosen.getValue());
        return d;
    }

    private void insertSite(Map<String, Object> row, Map<String, String> stamp) {
        jdbc.update(
                """
                INSERT IGNORE INTO site (site_id, `option`, project, site_status, operator, category, type,
                  lati, longi, extras, source_file, geom)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?)
                """,
                row.get("site_id"), row.get("option"), row.get("project"), row.get("site_status"),
                emptyToNull(stamp.get("operator")), emptyToNull(stamp.get("category")), emptyToNull(stamp.get("type")),
                row.get("lati"), row.get("longi"), JsonMaps.stringify(row.get("extras")),
                row.get("source_file"), GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))));
    }

    private void updateSite(Map<String, Object> existing, Map<String, Object> row, Map<String, String> stamp) {
        jdbc.update(
                """
                UPDATE site SET site_id=?, `option`=?, project=?, site_status=?, operator=?, category=?, type=?,
                  lati=?, longi=?, extras=CAST(? AS JSON), source_file=?, updated_at=NOW(3), geom=?
                WHERE site_id=? AND `option`=?
                """,
                row.get("site_id"), row.get("option"), row.get("project"), row.get("site_status"),
                emptyToNull(stamp.get("operator")), emptyToNull(stamp.get("category")), emptyToNull(stamp.get("type")),
                row.get("lati"), row.get("longi"), JsonMaps.stringify(row.get("extras")),
                row.get("source_file"), GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))),
                existing.get("site_id"), existing.get("option"));
    }

    private void insertRoad(Map<String, Object> row) {
        jdbc.update("INSERT INTO road (property, extras, source_file, geom) VALUES (?,CAST(? AS JSON),?,?)",
                row.get("property"), JsonMaps.stringify(row.get("extras")), row.get("source_file"),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))));
    }

    private void updateRoad(Map<String, Object> existing, Map<String, Object> row) {
        jdbc.update("UPDATE road SET property=?, extras=CAST(? AS JSON), source_file=?, geom=? WHERE id=?",
                row.get("property"), JsonMaps.stringify(row.get("extras")), row.get("source_file"),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))), existing.get("id"));
    }

    private void insertLessor(Map<String, Object> row) {
        jdbc.update(
                """
                INSERT IGNORE INTO lessor (fid, lessor_name, lessor_category, relationship, extras, source_file, geom)
                VALUES (?,?,?,?,CAST(? AS JSON),?,?)
                """,
                row.get("fid"), row.get("lessor_name"), row.get("lessor_category"), row.get("relationship"),
                JsonMaps.stringify(row.get("extras")), row.get("source_file"),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))));
    }

    private void updateLessor(Map<String, Object> existing, Map<String, Object> row) {
        jdbc.update(
                """
                UPDATE lessor SET fid=?, lessor_name=?, lessor_category=?, relationship=?, extras=CAST(? AS JSON),
                  source_file=?, updated_at=NOW(3), geom=? WHERE fid=?
                """,
                row.get("fid"), row.get("lessor_name"), row.get("lessor_category"), row.get("relationship"),
                JsonMaps.stringify(row.get("extras")), row.get("source_file"),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))), existing.get("fid"));
    }

    private void insertArea(Map<String, Object> row, Map<String, String> stamp) {
        jdbc.update("INSERT IGNORE INTO area (name, operator, extras, geom) VALUES (?, ?, CAST(? AS JSON), ?)",
                row.get("name"), emptyToNull(stamp.get("operator")), JsonMaps.stringify(row.get("extras")),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))));
    }

    private void updateArea(Map<String, Object> existing, Map<String, Object> row, Map<String, String> stamp) {
        String op = emptyToNull(stamp.get("operator"));
        if (op == null) op = str(existing.get("operator"));
        jdbc.update("UPDATE area SET name=?, operator=?, extras=CAST(? AS JSON), geom=? WHERE operator=? AND name=?",
                row.get("name"), op, JsonMaps.stringify(row.get("extras")),
                GeometryCodec.toWkb(GeometryCodec.fromWkt(str(row.get("wkt")))),
                existing.get("operator"), existing.get("name"));
    }

    private static String detect(String name) {
        if (name == null) return "unknown";
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".kmz")) return "kmz";
        if (n.endsWith(".kml")) return "kml";
        if (n.endsWith(".xlsx")) return "xlsx";
        return "unknown";
    }

    private static String siteKey(String id, String opt) {
        return (id == null ? "" : id).strip().toLowerCase(Locale.ROOT) + "|" + (opt == null ? "" : opt).strip().toLowerCase(Locale.ROOT);
    }
    private static String roadKey(String p) { return p == null ? "" : p.strip().toLowerCase(Locale.ROOT); }
    private static String lessorKey(String f) { return f == null ? "" : f.strip().toLowerCase(Locale.ROOT); }
    private static String areaKey(String n) { return n == null ? "" : n.strip().toLowerCase(Locale.ROOT); }

    private static String normStatus(String v) {
        if (v == null) return null;
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return "unknown".equals(s) ? "undetermined" : s;
    }

    private static String normRel(String v) {
        if (v != null && "friendly".equals(v.strip().toLowerCase(Locale.ROOT))) return "Normal";
        return v;
    }

    private static Map<String, Object> siteDict(ParseModels.SiteRow s, String source) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("site_id", s.siteId);
        d.put("option", s.option);
        d.put("project", s.project);
        d.put("site_status", normStatus(s.siteStatus));
        d.put("lati", s.lati);
        d.put("longi", s.longi);
        d.put("extras", s.extras);
        d.put("wkt", s.wkt);
        d.put("source_file", source);
        return d;
    }

    private static Map<String, Object> lessorDict(ParseModels.LessorRow l, String source) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fid", l.fid);
        d.put("lessor_name", l.lessorName);
        d.put("lessor_category", l.lessorCategory);
        d.put("relationship", normRel(l.relationship));
        d.put("extras", l.extras);
        d.put("wkt", l.wkt);
        d.put("source_file", source);
        return d;
    }

    private static Map<String, Object> cleaning(String rid, String kind, String name, Map<String, Object> row,
                                                String issue, String def, Map<String, Object> preview) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("row_id", rid);
        c.put("kind", kind);
        c.put("name", name);
        c.put("file_name", row.get("source_file"));
        c.put("issue", issue);
        Map<String, Object> coord = new LinkedHashMap<>();
        coord.put("lat", row.get("lati"));
        coord.put("lng", row.get("longi"));
        c.put("current_coord", coord);
        c.put("fixed_coord_preview", preview);
        c.put("default_action", def);
        return c;
    }

    private static String nameOfSite(Map<String, Object> row) {
        String opt = str(row.get("option"));
        return row.get("site_id") + (opt == null || opt.isEmpty() ? "" : " / " + opt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> copyPool(Object raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), new LinkedHashMap<>((Map<String, Object>) v)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> decisionsOf(Map<String, Object> body) {
        Map<String, String> d = new LinkedHashMap<>();
        Object list = body.get("decisions");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object key = m.get("row_id") != null ? m.get("row_id") : m.get("key");
                    Object action = m.get("action");
                    if (key != null && action != null) d.put(String.valueOf(key), String.valueOf(action));
                }
            }
        }
        return d;
    }

    private static Map<String, Object> conflict(String key, String kind, String name, Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("kind", kind);
        c.put("name", name);
        Map<String, Object> ex = new LinkedHashMap<>(existing);
        if (ex.get("extras") instanceof String) ex.put("extras", JsonMaps.parse(ex.get("extras")));
        c.put("existing", ex);
        c.put("incoming", incoming);
        c.put("source_file", incoming.get("source_file"));
        return c;
    }

    private static String str(Object o) { return o == null || "null".equals(String.valueOf(o)) ? null : String.valueOf(o); }
    private static String emptyToNull(String s) { return s == null || s.isBlank() || "null".equals(s) ? null : s; }
    private static Double toD(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
