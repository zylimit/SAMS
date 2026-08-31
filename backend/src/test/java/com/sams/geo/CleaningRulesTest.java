package com.sams.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

class CleaningRulesTest {

    @Test
    void swapWhenLatOutOfRangeButSwapWorks() {
        assertEquals("swap_latlong", CleaningRules.detectSwapOrMissingDecimal(121.0, 14.5));
    }

    @Test
    void missingDecimalWhenBothInvalid() {
        assertEquals("missing_decimal", CleaningRules.detectSwapOrMissingDecimal(1234.0, 5678.0));
    }

    @Test
    void okCoords() {
        assertNull(CleaningRules.detectSwapOrMissingDecimal(14.5, 121.0));
    }

    @Test
    void containsStrictExcludesBoundary() {
        Coordinate[] ring = new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1, 0), new Coordinate(1, 1),
                new Coordinate(0, 1), new Coordinate(0, 0)
        };
        LinearRing lr = GeometryCodec.GF.createLinearRing(ring);
        Polygon poly = GeometryCodec.GF.createPolygon(lr);
        Geometry inside = GeometryCodec.GF.createPoint(new Coordinate(0.5, 0.5));
        Geometry onBoundary = GeometryCodec.GF.createPoint(new Coordinate(0, 0));
        Geometry outside = GeometryCodec.GF.createPoint(new Coordinate(2, 2));
        assertTrue(GeometryCodec.containsStrict(poly, inside));
        assertFalse(GeometryCodec.containsStrict(poly, onBoundary));
        assertFalse(GeometryCodec.containsStrict(poly, outside));
    }
}
