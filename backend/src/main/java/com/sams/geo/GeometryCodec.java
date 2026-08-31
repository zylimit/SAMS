package com.sams.geo;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.wololo.jts2geojson.GeoJSONReader;
import org.wololo.jts2geojson.GeoJSONWriter;

public final class GeometryCodec {
    public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final WKTReader WKT_R = new WKTReader(GF);
    private static final WKTWriter WKT_W = new WKTWriter();
    private static final WKBReader WKB_R = new WKBReader(GF);
    private static final WKBWriter WKB_W = new WKBWriter();
    private static final GeoJSONReader GJ_R = new GeoJSONReader();
    private static final GeoJSONWriter GJ_W = new GeoJSONWriter();

    private GeometryCodec() {}

    public static Geometry fromWkt(String wkt) {
        if (wkt == null || wkt.isBlank()) return null;
        try {
            Geometry g = WKT_R.read(wkt);
            g.setSRID(4326);
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    public static Geometry fromWkb(byte[] wkb) {
        if (wkb == null || wkb.length == 0) return null;
        try {
            Geometry g = WKB_R.read(wkb);
            g.setSRID(4326);
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    public static Geometry fromGeoJson(Object geojson) {
        if (geojson == null) return null;
        try {
            String json = geojson instanceof String s ? s : JsonGeo.toJson(geojson);
            Geometry g = GJ_R.read(json);
            g.setSRID(4326);
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] toWkb(Geometry g) {
        if (g == null) return null;
        return WKB_W.write(g);
    }

    public static String toWkt(Geometry g) {
        if (g == null) return null;
        return WKT_W.write(g);
    }

    public static Map<String, Object> toGeoJson(Geometry g) {
        if (g == null) return null;
        try {
            return JsonGeo.toMap(GJ_W.write(g));
        } catch (Exception e) {
            return null;
        }
    }

    public static String toKml(Geometry g) {
        if (g == null) return null;
        String type = g.getGeometryType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "point" -> kmlPoint(g.getCoordinate());
            case "linestring" -> "<LineString><coordinates>" + lineCoords(g) + "</coordinates></LineString>";
            case "polygon" -> kmlPolygon(g);
            case "multipolygon" -> kmlPolygon(largestPolygon(g));
            default -> null;
        };
    }

    private static Geometry largestPolygon(Geometry g) {
        Geometry best = null;
        double bestA = -1;
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry p = g.getGeometryN(i);
            double a = p.getArea();
            if (a > bestA) {
                bestA = a;
                best = p;
            }
        }
        return best;
    }

    private static String kmlPoint(Coordinate c) {
        return "<Point><coordinates>" + fmt(c.x) + "," + fmt(c.y) + "</coordinates></Point>";
    }

    private static String kmlPolygon(Geometry g) {
        if (g == null) return null;
        org.locationtech.jts.geom.Polygon p = (org.locationtech.jts.geom.Polygon) g;
        return "<Polygon><outerBoundaryIs><LinearRing><coordinates>"
                + ringCoords(p.getExteriorRing().getCoordinates())
                + "</coordinates></LinearRing></outerBoundaryIs></Polygon>";
    }

    private static String lineCoords(Geometry g) {
        return ringCoords(g.getCoordinates());
    }

    private static String ringCoords(Coordinate[] cs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(fmt(cs[i].x)).append(',').append(fmt(cs[i].y));
        }
        return sb.toString();
    }

    public static String fmt(double v) {
        String s = String.format(Locale.US, "%.7f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }

    public static boolean containsStrict(Geometry container, Geometry inner) {
        if (container == null || inner == null) return false;
        return container.contains(inner);
    }

    /** Simple XML escape for KML text. */
    public static String esc(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class JsonGeo {
        private static final com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();

        static String toJson(Object o) {
            try { return o instanceof String s ? s : M.writeValueAsString(o); }
            catch (Exception e) { return "{}"; }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> toMap(Object o) {
            try {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
                return M.convertValue(o, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @SuppressWarnings("unused")
    private static List<?> unused() { return List.of(); }
}
