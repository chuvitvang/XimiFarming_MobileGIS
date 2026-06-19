package com.mobilegis.ximifarming.utils;

import java.util.List;

public class GisHelper {

    /**
     * Calculates the spherical area of a polygon in square meters on Earth.
     * Uses WGS84 standard spherical approximation.
     */
    public static double calculateArea(List<double[]> coordinates) {
        if (coordinates == null || coordinates.size() < 3) {
            return 0;
        }

        double radius = 6378137.0; // WGS84 equatorial radius in meters
        double total = 0;
        int len = coordinates.size();

        for (int i = 0; i < len; i++) {
            double[] p1 = coordinates.get(i);
            double[] p2 = coordinates.get((i + 1) % len);

            double lat1 = Math.toRadians(p1[0]);
            double lon1 = Math.toRadians(p1[1]);
            double lat2 = Math.toRadians(p2[0]);
            double lon2 = Math.toRadians(p2[1]);

            total += (lon2 - lon1) * (2 + Math.sin(lat1) + Math.sin(lat2));
        }

        return Math.abs(total * radius * radius / 2.0);
    }
}
