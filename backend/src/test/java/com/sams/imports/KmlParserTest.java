package com.sams.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KmlParserTest {
    private static final String NS = "xmlns=\"http://www.opengis.net/kml/2.2\"";
    private final KmlParser parser = new KmlParser();

    @Test
    void dataNamePolygonBecomesArea() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8"?>
                <kml %s><Document>
                  <Placemark>
                    <ExtendedData>
                      <Data name="Name"><value>AREA_A</value></Data>
                      <Data name="geozone_pr"><value>QUEZON CITY</value></Data>
                      <Data name="empty_col"><value></value></Data>
                    </ExtendedData>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>
                      121.0,14.6 121.1,14.6 121.1,14.7 121.0,14.6
                    </coordinates></LinearRing></outerBoundaryIs></Polygon>
                  </Placemark>
                </Document></kml>
                """.formatted(NS);
        var r = parser.parse(kml.getBytes());
        assertEquals(1, r.areas.size());
        assertEquals("AREA_A", r.areas.get(0).name);
        assertEquals("QUEZON CITY", r.areas.get(0).extras.get("geozone_pr"));
        assertTrue(!r.areas.get(0).extras.containsKey("Name"));
        assertTrue(r.sites.isEmpty() && r.roads.isEmpty() && r.lessors.isEmpty());
    }

    @Test
    void multiGeometryPicksLargestPolygon() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8"?>
                <kml %s><Document>
                  <Placemark>
                    <ExtendedData>
                      <Data name="Name"><value>MG_BIG</value></Data>
                    </ExtendedData>
                    <MultiGeometry>
                      <Polygon><outerBoundaryIs><LinearRing><coordinates>
                        121.0,14.6 121.1,14.6 121.1,14.7 121.0,14.6
                      </coordinates></LinearRing></outerBoundaryIs></Polygon>
                      <Polygon><outerBoundaryIs><LinearRing><coordinates>
                        120.0,10.0 121.0,10.0 121.0,11.0 120.0,11.0 120.0,10.0
                      </coordinates></LinearRing></outerBoundaryIs></Polygon>
                    </MultiGeometry>
                  </Placemark>
                </Document></kml>
                """.formatted(NS);
        var r = parser.parse(kml.getBytes());
        assertEquals(1, r.areas.size());
        assertTrue(r.areas.get(0).wkt.startsWith("POLYGON((120.0 10.0")
                || r.areas.get(0).wkt.startsWith("POLYGON((120 10"));
    }

    @Test
    void areaSchemaRoundtripDropsOperatorFromExtras() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8"?>
                <kml %s><Document>
                  <Schema name="area" id="area">
                    <SimpleField name="name" type="string"></SimpleField>
                    <SimpleField name="operator" type="string"></SimpleField>
                    <SimpleField name="polygon_id" type="string"></SimpleField>
                  </Schema>
                  <Placemark>
                    <ExtendedData>
                      <SchemaData schemaUrl="#area">
                        <SimpleData name="name">QC_NORTH</SimpleData>
                        <SimpleData name="operator">Globe</SimpleData>
                        <SimpleData name="polygon_id">002-0019</SimpleData>
                      </SchemaData>
                    </ExtendedData>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>
                      121.0,14.6 121.1,14.6 121.1,14.7 121.0,14.6
                    </coordinates></LinearRing></outerBoundaryIs></Polygon>
                  </Placemark>
                </Document></kml>
                """.formatted(NS);
        var r = parser.parse(kml.getBytes());
        assertEquals(1, r.areas.size());
        assertEquals("QC_NORTH", r.areas.get(0).name);
        assertEquals("002-0019", r.areas.get(0).extras.get("polygon_id"));
        assertTrue(!r.areas.get(0).extras.containsKey("operator"));
        assertTrue(!r.areas.get(0).extras.containsKey("name"));
    }

    @Test
    void namelessPolygonSkipped() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8"?>
                <kml %s><Document>
                  <Placemark>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>
                      121.0,14.6 121.1,14.6 121.1,14.7 121.0,14.6
                    </coordinates></LinearRing></outerBoundaryIs></Polygon>
                  </Placemark>
                </Document></kml>
                """.formatted(NS);
        var r = parser.parse(kml.getBytes());
        assertTrue(r.areas.isEmpty());
        assertTrue(r.lessors.isEmpty());
    }

    @Test
    void npRingFolderSkipped() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8"?>
                <kml %s><Document>
                  <Folder id="np-radius-rings"><name>NP 范围圈</name>
                    <Placemark>
                      <ExtendedData><Data name="ring_of"><value>S1|A</value></Data></ExtendedData>
                      <Polygon><outerBoundaryIs><LinearRing><coordinates>
                        121.0,14.6 121.1,14.6 121.1,14.7 121.0,14.6
                      </coordinates></LinearRing></outerBoundaryIs></Polygon>
                    </Placemark>
                  </Folder>
                </Document></kml>
                """.formatted(NS);
        var r = parser.parse(kml.getBytes());
        assertTrue(r.areas.isEmpty());
    }
}
