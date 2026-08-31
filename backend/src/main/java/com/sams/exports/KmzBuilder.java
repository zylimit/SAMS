package com.sams.exports;

import com.sams.common.JsonMaps;
import com.sams.geo.GeometryCodec;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.locationtech.jts.geom.Geometry;

public final class KmzBuilder {
    private static final Set<String> NP_TYPES = Set.of("Macro NP", "Micro NP");
    private static final Set<String> SITE_RESERVED = Set.of("PROJECT", "SITE ID", "OPTION", "SITE STATUS", "LATI", "LONGI");
    private static final Set<String> LESSOR_RESERVED = Set.of("fid", "Lessor Name", "Lessor Category", "Lessor Cagegory", "Relationship");
    private static final Set<String> ROAD_RESERVED = Set.of("Property");
    private static final Set<String> AREA_RESERVED = Set.of("name", "Name", "operator", "OPERATOR");
    private static final List<String> SITE_CORE = List.of("PROJECT", "SITE ID", "OPTION", "SITE STATUS", "LATI", "LONGI");
    private static final List<String> LESSOR_CORE = List.of("fid", "Lessor Name", "Lessor Category", "Relationship");
    private static final List<String> ROAD_CORE = List.of("Property");
    private static final List<String> AREA_CORE = List.of("name", "operator");

    private static final String STYLES = """
<Style id="point-green">
  <IconStyle><color>FFFFFFFF</color><scale>1.2</scale><Icon>
      <href>http://maps.google.com/mapfiles/kml/pushpin/grn-pushpin.png</href>
    </Icon></IconStyle>
</Style>
<Style id="point-yellow">
  <IconStyle><color>FFFFFFFF</color><scale>1.2</scale><Icon>
      <href>http://maps.google.com/mapfiles/kml/pushpin/ylw-pushpin.png</href>
    </Icon></IconStyle>
</Style>
<Style id="point-red">
  <IconStyle><color>FFFFFFFF</color><scale>1.2</scale><Icon>
      <href>http://maps.google.com/mapfiles/kml/pushpin/red-pushpin.png</href>
    </Icon></IconStyle>
</Style>
<Style id="poly-green">
  <LineStyle><color>FF00FF00</color><width>2</width></LineStyle>
  <PolyStyle><color>9900FF00</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-yellow">
  <LineStyle><color>FF00FFFF</color><width>2</width></LineStyle>
  <PolyStyle><color>9900FFFF</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-red">
  <LineStyle><color>FF0000FF</color><width>2</width></LineStyle>
  <PolyStyle><color>990000FF</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="line-brown">
  <LineStyle><color>FF8B4513</color><width>4</width></LineStyle>
  <PolyStyle><fill>0</fill></PolyStyle>
</Style>
<Style id="poly-np-ring">
  <LineStyle><color>FFF755A8</color><width>2</width></LineStyle>
  <PolyStyle><color>8CF755A8</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-area-globe">
  <LineStyle><color>FFF6823B</color><width>2</width></LineStyle>
  <PolyStyle><color>59F6823B</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-area-smart">
  <LineStyle><color>FF5EC522</color><width>2</width></LineStyle>
  <PolyStyle><color>595EC522</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-area-dito">
  <LineStyle><color>FF4444EF</color><width>2</width></LineStyle>
  <PolyStyle><color>594444EF</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>
<Style id="poly-area-other">
  <LineStyle><color>FFAFA39C</color><width>2</width></LineStyle>
  <PolyStyle><color>59AFA39C</color><fill>1</fill><outline>1</outline></PolyStyle>
</Style>""";

    private KmzBuilder() {}

    public static byte[] pack(String kml) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry("doc.kml"));
                zos.write(kml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String buildKml(List<Map<String, Object>> sites, List<Map<String, Object>> roads,
                                  List<Map<String, Object>> lessors, List<Map<String, Object>> areas, int npRadiusM) {
        List<String> siteFields = fields(sites, SITE_RESERVED, SITE_CORE);
        List<String> roadFields = fields(roads, ROAD_RESERVED, ROAD_CORE);
        List<String> lessorFields = fields(lessors, LESSOR_RESERVED, LESSOR_CORE);
        List<String> areaFields = fields(areas, AREA_RESERVED, AREA_CORE);
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document id=\"root_doc\">\n");
        out.append(schema("site", siteFields)).append('\n');
        out.append(schema("road", roadFields)).append('\n');
        out.append(schema("lessor", lessorFields)).append('\n');
        if (areas != null && !areas.isEmpty()) out.append(schema("area", areaFields)).append('\n');
        out.append(STYLES).append('\n');

        Map<String, List<String>> siteBuckets = Map.of("Positive", new ArrayList<>(), "Negative", new ArrayList<>(), "Unknown", new ArrayList<>());
        int i = 1;
        for (Map<String, Object> r : sites) {
            String geom = geomKml(r);
            if (geom == null) continue;
            String[] b = siteBucket(r.get("site_status"));
            siteBuckets.get(b[1]).add(placemark("site." + GeometryCodec.esc(b[1]) + "." + i, b[0], "site", siteFields, siteVal(r), geom));
            i++;
        }
        out.append("<Folder><name>Site Library</name>\n");
        for (String bucket : List.of("Positive", "Negative", "Unknown")) {
            if (siteBuckets.get(bucket).isEmpty()) continue;
            out.append("  <Folder><name>").append(bucket).append("</name>\n");
            siteBuckets.get(bucket).forEach(p -> out.append(p).append('\n'));
            out.append("  </Folder>\n");
        }
        out.append("</Folder>\n");

        out.append("<Folder><name>Road Library</name>\n");
        i = 1;
        for (Map<String, Object> r : roads) {
            String geom = geomKml(r);
            if (geom == null) continue;
            out.append(placemark("road." + i, "line-brown", "road", roadFields, roadVal(r), geom)).append('\n');
            i++;
        }
        out.append("</Folder>\n");

        Map<String, List<String>> lessorBuckets = Map.of("Friendly", new ArrayList<>(), "Normal", new ArrayList<>(), "Unfriendly", new ArrayList<>());
        i = 1;
        for (Map<String, Object> r : lessors) {
            String geom = geomKml(r);
            if (geom == null) continue;
            String[] b = lessorBucket(r.get("relationship"));
            lessorBuckets.get(b[1]).add(placemark("lessor." + GeometryCodec.esc(b[1]) + "." + i, b[0], "lessor", lessorFields, lessorVal(r), geom));
            i++;
        }
        out.append("<Folder><name>Lessor Library</name>\n");
        for (String bucket : List.of("Friendly", "Normal", "Unfriendly")) {
            if (lessorBuckets.get(bucket).isEmpty()) continue;
            out.append("  <Folder><name>").append(bucket).append("</name>\n");
            lessorBuckets.get(bucket).forEach(p -> out.append(p).append('\n'));
            out.append("  </Folder>\n");
        }
        out.append("</Folder>\n");

        if (areas != null && !areas.isEmpty()) {
            Map<String, List<String>> areaBuckets = new LinkedHashMap<>();
            for (String k : List.of("Globe", "Smart", "Dito", "Other")) areaBuckets.put(k, new ArrayList<>());
            i = 1;
            for (Map<String, Object> r : areas) {
                String geom = geomKml(r);
                if (geom == null) continue;
                String[] b = areaBucket(r.get("operator"));
                areaBuckets.get(b[1]).add(placemark("area." + GeometryCodec.esc(b[1]) + "." + i, b[0], "area", areaFields, areaVal(r), geom));
                i++;
            }
            out.append("<Folder><name>Area Library</name>\n");
            for (String bucket : List.of("Globe", "Smart", "Dito", "Other")) {
                if (areaBuckets.get(bucket).isEmpty()) continue;
                out.append("  <Folder><name>").append(bucket).append("</name>\n");
                areaBuckets.get(bucket).forEach(p -> out.append(p).append('\n'));
                out.append("  </Folder>\n");
            }
            out.append("</Folder>\n");
        }

        List<String> rings = new ArrayList<>();
        i = 1;
        for (Map<String, Object> r : sites) {
            if (geomKml(r) == null) { i++; continue; }
            if (!NP_TYPES.contains(String.valueOf(r.get("type")))) { i++; continue; }
            String pm = npRing(r, i, npRadiusM);
            if (pm != null) rings.add(pm);
            i++;
        }
        if (!rings.isEmpty()) {
            out.append("<Folder id=\"np-radius-rings\"><name>NP 范围圈</name>\n");
            rings.forEach(p -> out.append(p).append('\n'));
            out.append("</Folder>\n");
        }
        out.append("</Document>\n</kml>");
        return out.toString();
    }

    private static String geomKml(Map<String, Object> r) {
        if (r.get("geom_kml") instanceof String s && !s.isBlank()) return s;
        Object g = r.get("geom");
        Geometry geom = g instanceof byte[] b ? GeometryCodec.fromWkb(b) : GeometryCodec.fromWkt(r.get("wkt") == null ? null : String.valueOf(r.get("wkt")));
        return GeometryCodec.toKml(geom);
    }

    private static String schema(String name, List<String> fields) {
        StringBuilder sb = new StringBuilder("<Schema name=\"").append(name).append("\" id=\"").append(name).append("\">\n");
        for (String f : fields) sb.append("  <SimpleField name=\"").append(GeometryCodec.esc(f)).append("\" type=\"string\"></SimpleField>\n");
        sb.append("</Schema>");
        return sb.toString();
    }

    private static List<String> fields(List<Map<String, Object>> rows, Set<String> reserved, List<String> core) {
        java.util.TreeSet<String> extra = new java.util.TreeSet<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                JsonMaps.parse(r.get("extras")).keySet().stream().filter(k -> !reserved.contains(k)).forEach(extra::add);
            }
        }
        List<String> out = new ArrayList<>(core);
        out.addAll(extra);
        return out;
    }

    private static String placemark(String pid, String style, String schema, List<String> fields, Map<String, String> values, String geom) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Placemark id=\"").append(pid).append("\">\n");
        sb.append("  <styleUrl>#").append(style).append("</styleUrl>\n  <ExtendedData>\n");
        sb.append("    <SchemaData schemaUrl=\"#").append(schema).append("\">\n");
        for (String f : fields) {
            String v = values.getOrDefault(f, "");
            if (v.isEmpty()) continue;
            sb.append("      <SimpleData name=\"").append(GeometryCodec.esc(f)).append("\">").append(v).append("</SimpleData>\n");
        }
        sb.append("    </SchemaData>\n  </ExtendedData>\n  ").append(geom).append("\n</Placemark>");
        return sb.toString();
    }

    private static Map<String, String> siteVal(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PROJECT", esc(row.get("project")));
        m.put("SITE ID", esc(row.get("site_id")));
        m.put("OPTION", esc(row.get("option")));
        m.put("SITE STATUS", esc(row.get("site_status")));
        m.put("LATI", GeometryCodec.fmt(toD(row.get("lati"))));
        m.put("LONGI", GeometryCodec.fmt(toD(row.get("longi"))));
        JsonMaps.parse(row.get("extras")).forEach((k, v) -> m.putIfAbsent(k, esc(v)));
        return m;
    }

    private static Map<String, String> lessorVal(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fid", esc(row.get("fid")));
        m.put("Lessor Name", esc(row.get("lessor_name")));
        m.put("Lessor Category", esc(row.get("lessor_category")));
        m.put("Relationship", esc(row.get("relationship")));
        JsonMaps.parse(row.get("extras")).forEach((k, v) -> m.putIfAbsent(k, esc(v)));
        return m;
    }

    private static Map<String, String> roadVal(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Property", esc(row.get("property")));
        JsonMaps.parse(row.get("extras")).forEach((k, v) -> m.putIfAbsent(k, esc(v)));
        return m;
    }

    private static Map<String, String> areaVal(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", esc(row.get("name")));
        m.put("operator", esc(row.get("operator")));
        JsonMaps.parse(row.get("extras")).forEach((k, v) -> m.putIfAbsent(k, esc(v)));
        return m;
    }

    private static String[] siteBucket(Object status) {
        String s = status == null ? "" : String.valueOf(status).strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "positive" -> new String[]{"point-green", "Positive"};
            case "negative" -> new String[]{"point-yellow", "Negative"};
            default -> new String[]{"point-red", "Unknown"};
        };
    }

    private static String[] lessorBucket(Object rel) {
        String r = rel == null ? "" : String.valueOf(rel).strip().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "friendly" -> new String[]{"poly-green", "Friendly"};
            case "normal" -> new String[]{"poly-yellow", "Normal"};
            default -> new String[]{"poly-red", "Unfriendly"};
        };
    }

    private static String[] areaBucket(Object op) {
        String o = op == null ? "" : String.valueOf(op).strip().toLowerCase(Locale.ROOT);
        return switch (o) {
            case "globe" -> new String[]{"poly-area-globe", "Globe"};
            case "smart" -> new String[]{"poly-area-smart", "Smart"};
            case "dito" -> new String[]{"poly-area-dito", "Dito"};
            default -> new String[]{"poly-area-other", "Other"};
        };
    }

    private static String npRing(Map<String, Object> row, int idx, int radiusM) {
        Double lat = toD(row.get("lati"));
        Double lng = toD(row.get("longi"));
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) return null;
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
        double dlat = radiusM / 111320.0;
        double cos = Math.cos(Math.toRadians(lat));
        double dlng = Math.abs(cos) > 1e-9 ? radiusM / (111320.0 * cos) : dlat;
        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            double th = 2 * Math.PI * i / 64;
            if (i > 0) coords.append(' ');
            coords.append(GeometryCodec.fmt(lng + dlng * Math.cos(th))).append(',')
                    .append(GeometryCodec.fmt(lat + dlat * Math.sin(th)));
        }
        coords.append(' ').append(GeometryCodec.fmt(lng + dlng)).append(',').append(GeometryCodec.fmt(lat));
        String ringOf = (row.get("site_id") == null ? "" : row.get("site_id")) + "|" + (row.get("option") == null ? "" : row.get("option"));
        return """
                <Placemark id="np-ring.%d">
                  <styleUrl>#poly-np-ring</styleUrl>
                  <ExtendedData>
                    <Data name="ring_of"><value>%s</value></Data>
                    <Data name="ring_radius_m"><value>%d</value></Data>
                  </ExtendedData>
                  <Polygon><outerBoundaryIs><LinearRing>
                    <coordinates>%s</coordinates>
                  </LinearRing></outerBoundaryIs></Polygon>
                </Placemark>
                """.formatted(idx, GeometryCodec.esc(ringOf), radiusM, coords);
    }

    private static String esc(Object v) { return GeometryCodec.esc(v); }

    private static Double toD(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }
}
