package com.sams.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

class CountryIndexTest {

    @Test
    void classifyPointAndCentroidAgainstTinyCountries() throws Exception {
        Path tmp = Files.createTempFile("countries", ".geojson");
        Files.writeString(tmp, """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"ISO_A2":"PH","NAME_ZH":"菲律宾","NAME_EN":"Philippines"},
                   "geometry":{"type":"Polygon","coordinates":[[[119,5],[127,5],[127,19],[119,19],[119,5]]]}},
                  {"type":"Feature","properties":{"ISO_A2":"MY","NAME_ZH":"马来西亚","NAME_EN":"Malaysia"},
                   "geometry":{"type":"Polygon","coordinates":[[[100,1],[104,1],[104,7],[100,7],[100,1]]]}}
                ]}
                """);
        CountryIndex idx = new CountryIndex(tmp.toString());
        assertTrue(idx.isLoaded());

        CountryIndex.Classify manila = idx.classify(121.0, 14.5, "PH");
        assertEquals("PH", manila.countryIsoA2());
        assertFalse(manila.inSea());
        assertFalse(manila.notInBaseline());

        CountryIndex.Classify sea = idx.classify(0.0, 0.0, "PH");
        assertTrue(sea.inSea());
        assertFalse(sea.notInBaseline());

        CountryIndex.Classify my = idx.classify(102.0, 3.0, "PH");
        assertEquals("MY", my.countryIsoA2());
        assertTrue(my.notInBaseline());

        Geometry area = GeometryCodec.fromWkt("POLYGON((120 10, 121 10, 121 11, 120 11, 120 10))");
        CountryIndex.Classify centroid = idx.classify(area, "PH");
        assertEquals("PH", centroid.countryIsoA2());
        assertFalse(centroid.inSea());
    }
}
