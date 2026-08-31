package com.sams.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CountryIndex {
    private static final Logger log = LoggerFactory.getLogger(CountryIndex.class);
    public static final double BUFFER_DEG = 0.01;

    public record Country(String isoA2, String nameZh, String nameEn, Geometry geom) {}

    private final List<Country> countries = new ArrayList<>();
    private final Map<String, Country> byIso = new HashMap<>();
    private final STRtree tree = new STRtree();
    private volatile boolean loaded;

    public CountryIndex(@Value("${sams.geo-data}") String path) {
        load(path);
    }

    private void load(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            log.warn("countries GeoJSON 不存在：{}，地理判定将退化", path);
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(f);
            for (JsonNode feat : root.path("features")) {
                JsonNode props = feat.path("properties");
                JsonNode geomNode = feat.get("geometry");
                if (geomNode == null || geomNode.isNull()) continue;
                Geometry g = GeometryCodec.fromGeoJson(geomNode.toString());
                if (g == null || g.isEmpty()) continue;
                String iso = text(props, "ISO_A2");
                Country c = new Country(
                        iso,
                        text(props, "NAME_ZH"),
                        text(props, "NAME_EN"),
                        g);
                countries.add(c);
                if (iso != null) byIso.put(iso, c);
                Envelope env = g.getEnvelopeInternal();
                tree.insert(env, c);
            }
            tree.build();
            loaded = true;
            log.info("已加载 {} 个国家面到内存空间索引", countries.size());
        } catch (Exception e) {
            log.error("countries 加载失败：{}", e.toString());
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Country byIso(String iso) {
        return iso == null ? null : byIso.get(iso);
    }

    public record Classify(
            String countryIsoA2,
            String countryNameZh,
            String countryNameEn,
            boolean inSea,
            boolean notInBaseline) {}

    public Classify classify(double lng, double lat, String baselineIso) {
        Point p = GeometryCodec.GF.createPoint(new org.locationtech.jts.geom.Coordinate(lng, lat));
        return classify(p, baselineIso);
    }

    public Classify classify(Geometry geom, String baselineIso) {
        if (!loaded || geom == null) {
            return new Classify(null, null, null, false, false);
        }
        Geometry probe = geom.getGeometryType().equalsIgnoreCase("Point") ? geom : geom.getCentroid();
        Envelope q = probe.getEnvelopeInternal();
        q.expandBy(BUFFER_DEG);
        @SuppressWarnings("unchecked")
        List<Country> cands = tree.query(q);
        Country best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Country c : cands) {
            double d = c.geom().distance(probe);
            if (d <= BUFFER_DEG && d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null) {
            return new Classify(null, null, null, true, false);
        }
        boolean notIn = baselineIso != null && !baselineIso.equals(best.isoA2());
        return new Classify(best.isoA2(), best.nameZh(), best.nameEn(), false, notIn);
    }

    private static String text(JsonNode props, String key) {
        JsonNode n = props.get(key);
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }
}
