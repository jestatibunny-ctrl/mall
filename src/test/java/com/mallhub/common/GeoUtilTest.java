package com.mallhub.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeoUtilTest {

    @Test
    void samePointIsZero() {
        assertEquals(0.0, GeoUtil.distanceKm(12.97, 77.59, 12.97, 77.59), 1e-9);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111.19, GeoUtil.distanceKm(0, 0, 1, 0), 0.5);
    }

    @Test
    void distanceIsSymmetric() {
        double a = GeoUtil.distanceKm(12.99, 77.72, 12.93, 77.61);
        double b = GeoUtil.distanceKm(12.93, 77.61, 12.99, 77.72);
        assertEquals(a, b, 1e-9);
    }
}
