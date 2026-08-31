package com.sams.imports;

import com.sams.imports.ParseModels.AreaRow;
import com.sams.imports.ParseModels.LessorRow;
import com.sams.imports.ParseModels.ParseResult;
import com.sams.imports.ParseModels.RoadRow;
import com.sams.imports.ParseModels.SiteRow;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class KmlParser {
    private static final Set<String> SITE_CORE = Set.of(
            "SITE ID", "OPTION", "PROJECT", "SITE STATUS", "LATI", "LONGI",
            "OPERATOR", "CATEGORY", "TYPE", "SITE TYPE");
    private static final Set<String> ROAD_CORE = Set.of("Property");
    private static final Set<String> LESSOR_CORE = Set.of("fid", "Lessor Name", "Lessor Category", "Lessor Cagegory", "Relationship");
    private static final Set<String> AREA_CORE = Set.of("name", "Name", "operator", "OPERATOR");
    private static final Set<String> RING_IDS = Set.of("np-radius-rings");
    private static final Set<String> RING_NAMES = Set.of("np-radius-rings", "NP 范围圈");

    public ParseResult parse(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        ParseResult result = new ParseResult();
        NodeList pms = doc.getElementsByTagNameNS("http://www.opengis.net/kml/2.2", "Placemark");
        if (pms.getLength() == 0) pms = doc.getElementsByTagName("Placemark");
        for (int i = 0; i < pms.getLength(); i++) {
            Element pm = (Element) pms.item(i);
            if (isNpRing(pm)) continue;
            SchemaData sd = schemaData(pm);
            Map<String, String> dataOnly = dataFields(pm);
            dataOnly.forEach(sd.data::putIfAbsent);
            String kind = sd.schemaUrl == null || sd.schemaUrl.isBlank()
                    ? null : sd.schemaUrl.replaceFirst("^#", "").toLowerCase();
            if (kind == null) {
                if (first(pm, "Point") != null) kind = "site";
                else if (first(pm, "LineString") != null) kind = "road";
                else if (first(pm, "Polygon") != null) {
                    String areaName = sd.data.get("Name");
                    if (areaName == null) areaName = sd.data.get("name");
                    AreaRow area = areaRow(areaName, sd.data, pm);
                    if (area != null) {
                        result.areas.add(area);
                        continue;
                    }
                    kind = "lessor";
                }
            }
            if ("site".equals(kind)) {
                String wkt = pointWkt(pm);
                if (wkt == null) continue;
                String siteId = sd.data.getOrDefault("SITE ID", "").strip();
                if (siteId.isEmpty()) continue;
                SiteRow s = new SiteRow();
                s.siteId = siteId;
                s.option = sd.data.getOrDefault("OPTION", "").strip();
                s.project = emptyToNull(sd.data.get("PROJECT"));
                s.siteStatus = emptyToNull(sd.data.get("SITE STATUS"));
                s.lati = toFloat(sd.data.get("LATI"));
                s.longi = toFloat(sd.data.get("LONGI"));
                s.extras = extras(sd.data, SITE_CORE);
                s.wkt = wkt;
                result.sites.add(s);
            } else if ("road".equals(kind)) {
                String wkt = lineWkt(pm);
                if (wkt == null) continue;
                RoadRow r = new RoadRow();
                r.property = emptyToNull(sd.data.get("Property"));
                r.extras = extras(sd.data, ROAD_CORE);
                r.wkt = wkt;
                result.roads.add(r);
            } else if ("lessor".equals(kind)) {
                String wkt = polygonWkt(pm);
                if (wkt == null) continue;
                String fid = sd.data.getOrDefault("fid", "").strip();
                if (fid.isEmpty()) continue;
                LessorRow l = new LessorRow();
                l.fid = fid;
                l.lessorName = emptyToNull(sd.data.get("Lessor Name"));
                String cat = sd.data.get("Lessor Category");
                if (cat == null) cat = sd.data.get("Lessor Cagegory");
                l.lessorCategory = emptyToNull(cat);
                l.relationship = emptyToNull(sd.data.get("Relationship"));
                l.extras = extras(sd.data, LESSOR_CORE);
                l.wkt = wkt;
                result.lessors.add(l);
            } else if ("area".equals(kind)) {
                String name = sd.data.get("name");
                if (name == null) name = sd.data.get("Name");
                AreaRow area = areaRow(name, sd.data, pm);
                if (area != null) result.areas.add(area);
            }
        }
        return result;
    }

    private static AreaRow areaRow(String name, Map<String, String> fields, Element pm) {
        if (name == null || name.strip().isEmpty()) return null;
        String wkt = polygonWkt(pm);
        if (wkt == null) return null;
        AreaRow a = new AreaRow();
        a.name = name.strip();
        a.extras = extras(fields, AREA_CORE);
        a.wkt = wkt;
        return a;
    }

    private static boolean isNpRing(Element pm) {
        NodeList data = pm.getElementsByTagName("*");
        for (int i = 0; i < data.getLength(); i++) {
            Node n = data.item(i);
            if (n instanceof Element el) {
                String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
                if (("Data".equals(local) || "SimpleData".equals(local)) && "ring_of".equals(el.getAttribute("name"))) {
                    return true;
                }
            }
        }
        Node node = pm.getParentNode();
        while (node != null) {
            if (node instanceof Element el) {
                String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
                if ("Folder".equals(local)) {
                    if (RING_IDS.contains(el.getAttribute("id"))) return true;
                    Element name = first(el, "name");
                    if (name != null && RING_NAMES.contains(text(name).strip())) return true;
                }
            }
            node = node.getParentNode();
        }
        return false;
    }

    private record SchemaData(String schemaUrl, Map<String, String> data) {}

    private static SchemaData schemaData(Element pm) {
        Element sd = first(pm, "SchemaData");
        Map<String, String> data = new LinkedHashMap<>();
        if (sd == null) return new SchemaData(null, data);
        NodeList kids = sd.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element el) {
                String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
                if ("SimpleData".equals(local) && el.getAttribute("name") != null && el.getTextContent() != null) {
                    data.put(el.getAttribute("name"), el.getTextContent().strip());
                }
            }
        }
        return new SchemaData(sd.getAttribute("schemaUrl"), data);
    }

    private static Map<String, String> dataFields(Element pm) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList all = pm.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el) {
                String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
                if ("Data".equals(local)) {
                    String name = el.getAttribute("name");
                    Element val = first(el, "value");
                    if (name != null && val != null && !text(val).isBlank()) out.put(name, text(val).strip());
                }
            }
        }
        return out;
    }

    private static String pointWkt(Element pm) {
        Element coords = first(first(pm, "Point"), "coordinates");
        if (coords == null) return null;
        String[] parts = text(coords).strip().split(",");
        if (parts.length < 2) return null;
        return "POINT(" + parts[0].strip() + " " + parts[1].strip() + ")";
    }

    private static String lineWkt(Element pm) {
        Element coords = first(first(pm, "LineString"), "coordinates");
        if (coords == null) return null;
        java.util.List<String> pts = new java.util.ArrayList<>();
        for (String tok : text(coords).strip().split("\\s+")) {
            String[] p = tok.split(",");
            if (p.length >= 2) pts.add(p[0].strip() + " " + p[1].strip());
        }
        if (pts.size() < 2) return null;
        return "LINESTRING(" + String.join(", ", pts) + ")";
    }

    private static String polygonWkt(Element pm) {
        NodeList rings = pm.getElementsByTagName("*");
        java.util.List<String> bestPts = null;
        double bestArea = -1;
        for (int i = 0; i < rings.getLength(); i++) {
            if (!(rings.item(i) instanceof Element el)) continue;
            String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
            if (!"coordinates".equals(local)) continue;
            Node p = el.getParentNode();
            if (p == null) continue;
            String pl = p.getLocalName() == null ? p.getNodeName() : p.getLocalName();
            if (!"LinearRing".equals(pl)) continue;
            java.util.List<String> pts = new java.util.ArrayList<>();
            java.util.List<double[]> xy = new java.util.ArrayList<>();
            for (String tok : text(el).strip().split("\\s+")) {
                String[] parts = tok.split(",");
                if (parts.length >= 2) {
                    pts.add(parts[0].strip() + " " + parts[1].strip());
                    try { xy.add(new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])}); }
                    catch (Exception e) { xy.add(new double[]{0, 0}); }
                }
            }
            if (pts.size() < 4) continue;
            if (!pts.get(0).equals(pts.get(pts.size() - 1))) {
                pts.add(pts.get(0));
                xy.add(xy.get(0));
            }
            double area = 0;
            for (int j = 0; j < xy.size() - 1; j++) {
                area += xy.get(j)[0] * xy.get(j + 1)[1] - xy.get(j + 1)[0] * xy.get(j)[1];
            }
            area = Math.abs(area) / 2;
            if (area > bestArea) {
                bestArea = area;
                bestPts = pts;
            }
        }
        if (bestPts == null) return null;
        return "POLYGON((" + String.join(", ", bestPts) + "))";
    }

    private static Map<String, Object> extras(Map<String, String> data, Set<String> core) {
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (v != null && !v.isEmpty() && !core.contains(k)) out.put(k, v);
        });
        return out;
    }

    private static Element first(Element parent, String local) {
        if (parent == null) return null;
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el) {
                String l = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
                if (local.equals(l) || local.equalsIgnoreCase(l)) return el;
            }
        }
        return null;
    }

    private static String text(Element el) {
        return el == null || el.getTextContent() == null ? "" : el.getTextContent();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Double toFloat(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.strip()); } catch (Exception e) { return null; }
    }
}
