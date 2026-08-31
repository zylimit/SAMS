package com.sams.feature;

import com.sams.common.JsonMaps;
import com.sams.geo.GeometryCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;

public final class GeoJsonFeatures {
    private GeoJsonFeatures() {}

    public static Map<String, Object> collection(List<Map<String, Object>> features) {
        return Map.of("type", "FeatureCollection", "features", features);
    }

    public static Map<String, Object> empty() {
        return collection(List.of());
    }

    public static Map<String, Object> siteFeature(Map<String, Object> r) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "site");
        props.put("site_id", r.get("site_id"));
        props.put("option", r.get("option"));
        props.put("project", r.get("project"));
        props.put("site_status", r.get("site_status"));
        props.put("operator", r.get("operator"));
        props.put("category", r.get("category"));
        props.put("type", r.get("type"));
        props.put("lati", r.get("lati"));
        props.put("longi", r.get("longi"));
        props.put("source_file", r.get("source_file"));
        mergeExtras(props, r.get("extras"));
        return feature("site:" + r.get("site_id") + ":" + r.get("option"), geomOf(r), props);
    }

    public static Map<String, Object> roadFeature(Map<String, Object> r) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "road");
        props.put("id", r.get("id"));
        props.put("property", r.get("property"));
        props.put("source_file", r.get("source_file"));
        mergeExtras(props, r.get("extras"));
        return feature("road:" + r.get("id"), geomOf(r), props);
    }

    public static Map<String, Object> lessorFeature(Map<String, Object> r) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "lessor");
        props.put("fid", r.get("fid"));
        props.put("lessor_name", r.get("lessor_name"));
        props.put("lessor_category", r.get("lessor_category"));
        props.put("relationship", r.get("relationship"));
        props.put("source_file", r.get("source_file"));
        mergeExtras(props, r.get("extras"));
        return feature("lessor:" + r.get("fid"), geomOf(r), props);
    }

    public static Map<String, Object> areaFeature(Map<String, Object> r) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "area");
        props.put("id", r.get("id"));
        props.put("name", r.get("name"));
        props.put("operator", r.get("operator"));
        mergeExtras(props, r.get("extras"));
        return feature("area:" + r.get("id"), geomOf(r), props);
    }

    public static Geometry geomOf(Map<String, Object> r) {
        Object g = r.get("geom");
        if (g instanceof byte[] b) return GeometryCodec.fromWkb(b);
        if (g instanceof String s) return GeometryCodec.fromWkt(s);
        return null;
    }

    private static Map<String, Object> feature(String id, Geometry g, Map<String, Object> props) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("id", id);
        f.put("geometry", GeometryCodec.toGeoJson(g));
        f.put("properties", props);
        return f;
    }

    private static void mergeExtras(Map<String, Object> props, Object extras) {
        Map<String, Object> m = JsonMaps.parse(extras);
        for (var e : m.entrySet()) props.putIfAbsent(e.getKey(), e.getValue());
    }

    public static List<Map<String, Object>> mapSites(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(siteFeature(r));
        return out;
    }
}
