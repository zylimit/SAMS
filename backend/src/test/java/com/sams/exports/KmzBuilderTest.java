package com.sams.exports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KmzBuilderTest {

    @Test
    void areaSchemaAndOperatorStyles() {
        List<Map<String, Object>> areas = List.of(
                area("A1", "Globe"),
                area("A2", "Smart"),
                area("A3", "Dito"),
                area("A4", "OtherOp"));
        String kml = KmzBuilder.buildKml(List.of(), List.of(), List.of(), areas, 200);
        assertTrue(kml.contains("<Schema name=\"area\" id=\"area\">"));
        assertTrue(kml.contains("schemaUrl=\"#area\""));
        assertTrue(kml.contains("Area Library"));
        assertTrue(kml.contains("59F6823B"));
        assertTrue(kml.contains("595EC522"));
        assertTrue(kml.contains("594444EF"));
        assertTrue(kml.contains("#poly-area-globe"));
        assertTrue(kml.contains("<SimpleData name=\"name\">A1</SimpleData>"));
        assertTrue(kml.contains("<SimpleData name=\"operator\">Globe</SimpleData>"));
        assertTrue(kml.contains("<SimpleData name=\"polygon_id\">002-0019</SimpleData>"));
    }

    @Test
    void emptyAreasOmitsSchema() {
        String kml = KmzBuilder.buildKml(List.of(), List.of(), List.of(), List.of(), 200);
        assertFalse(kml.contains("<Schema name=\"area\""));
        assertFalse(kml.contains("Area Library"));
    }

    @Test
    void npRingForMacroNp() {
        Map<String, Object> site = new java.util.LinkedHashMap<>();
        site.put("site_id", "NP1");
        site.put("option", "A");
        site.put("site_status", "undetermined");
        site.put("type", "Macro NP");
        site.put("lati", 14.5);
        site.put("longi", 121.0);
        site.put("extras", Map.of());
        site.put("wkt", "POINT(121 14.5)");
        String kml = KmzBuilder.buildKml(List.of(site), List.of(), List.of(), List.of(), 200);
        assertTrue(kml.contains("np-radius-rings"));
        assertTrue(kml.contains("ring_of"));
        assertTrue(kml.contains("NP1|A"));
    }

    @Test
    void reimportParsesAreaNames() throws Exception {
        String kml = KmzBuilder.buildKml(List.of(), List.of(), List.of(),
                List.of(area("QC_NORTH", "Globe"), area("QC_SOUTH", "Globe")), 200);
        var parsed = new com.sams.imports.KmlParser().parse(kml.getBytes());
        assertTrue(parsed.areas.stream().map(a -> a.name).toList().containsAll(List.of("QC_NORTH", "QC_SOUTH")));
        assertTrue(parsed.sites.isEmpty() && parsed.roads.isEmpty() && parsed.lessors.isEmpty());
    }

    private static Map<String, Object> area(String name, String op) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("name", name);
        r.put("operator", op);
        r.put("extras", Map.of("polygon_id", "002-0019"));
        r.put("wkt", "POLYGON((121.0 14.6, 121.1 14.6, 121.1 14.7, 121.0 14.6))");
        return r;
    }
}
