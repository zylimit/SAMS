package com.sams.geo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * 对齐现网 test_st_contains_47：严格包含（边界不算）。
 * 谓词走 JTS contains，不进 MySQL ST_Contains。
 */
class ContainsStrictTest {
    private static final double CENTER_LNG = 121.0;
    private static final double CENTER_LAT = 14.5;
    private static final double RADIUS_DEG = 0.01;
    private static final int SEGMENTS = 32;

    @Test
    void circleCenterInsideFarAwayAndVertexExcluded() {
        Coordinate[] ring = new Coordinate[SEGMENTS + 1];
        for (int i = 0; i < SEGMENTS; i++) {
            double th = 2.0 * Math.PI * i / SEGMENTS;
            ring[i] = new Coordinate(
                    CENTER_LNG + RADIUS_DEG * Math.cos(th),
                    CENTER_LAT + RADIUS_DEG * Math.sin(th));
        }
        ring[SEGMENTS] = ring[0];
        LinearRing lr = GeometryCodec.GF.createLinearRing(ring);
        Polygon poly = GeometryCodec.GF.createPolygon(lr);

        Geometry inside = GeometryCodec.GF.createPoint(new Coordinate(CENTER_LNG, CENTER_LAT));
        Geometry outside = GeometryCodec.GF.createPoint(new Coordinate(122.0, 15.0));
        Geometry vertex = GeometryCodec.GF.createPoint(new Coordinate(CENTER_LNG + RADIUS_DEG, CENTER_LAT));

        assertTrue(GeometryCodec.containsStrict(poly, inside));
        assertFalse(GeometryCodec.containsStrict(poly, outside));
        assertFalse(GeometryCodec.containsStrict(poly, vertex));
    }
}
