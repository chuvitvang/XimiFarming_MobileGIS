package com.mobilegis.cropwatcher.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

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

    /**
     * Struct class representing the results of leaf analysis
     */
    public static class AnalysisResult {
        public double averageExg;
        public Bitmap heatmapBitmap;
        public String statusLabel;
        public String statusDesc;

        public AnalysisResult(double averageExg, Bitmap heatmapBitmap, String statusLabel, String statusDesc) {
            this.averageExg = averageExg;
            this.heatmapBitmap = heatmapBitmap;
            this.statusLabel = statusLabel;
            this.statusDesc = statusDesc;
        }
    }

    /**
     * Analyzes an input Bitmap image using Excess Green Index (ExG) algorithm.
     * Generates a health heatmap bitmap (pixels color-mapped based on ExG value).
     */
    public static AnalysisResult analyzeLeafImage(Bitmap srcBitmap) {
        // Downscale image to process faster and prevent memory issues
        int width = srcBitmap.getWidth();
        int height = srcBitmap.getHeight();
        
        int scaleWidth = 300;
        int scaleHeight = (int) (((double) height / width) * scaleWidth);
        
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(srcBitmap, scaleWidth, scaleHeight, false);
        Bitmap heatmap = Bitmap.createBitmap(scaleWidth, scaleHeight, Bitmap.Config.ARGB_8888);
        
        double sumExg = 0;
        int processedPixels = 0;

        for (int x = 0; x < scaleWidth; x++) {
            for (int y = 0; y < scaleHeight; y++) {
                int pixel = scaledBitmap.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                double total = r + g + b;
                if (total == 0) total = 1; // avoid divide by zero

                double nr = r / total;
                double ng = g / total;
                double nb = b / total;

                // Excess Green Index calculation
                double exg = 2 * ng - nr - nb;
                
                sumExg += exg;
                processedPixels++;

                // Map ExG value to heatmap color
                // ExG ranges roughly from -1 to 1. Leaf greenness is typically between 0.0 and 0.6.
                int heatColor;
                if (exg > 0.2) {
                    // Healthy foliage: Green spectrum
                    int intensity = (int) (Math.min((exg - 0.2) / 0.4, 1.0) * 155) + 100;
                    heatColor = Color.rgb(0, intensity, 0); // Vibrant Green
                } else if (exg > 0.0) {
                    // Stressed / Transition foliage: Orange/Yellow
                    int intensity = (int) (exg / 0.2 * 255);
                    heatColor = Color.rgb(255, intensity, 0); // Yellow/Orange
                } else {
                    // Unhealthy / Soil / Background: Red
                    int intensity = (int) (Math.min(Math.abs(exg) / 0.5, 1.0) * 155) + 100;
                    heatColor = Color.rgb(intensity, 0, 0); // Red
                }

                heatmap.setPixel(x, y, heatColor);
            }
        }

        double avgExg = sumExg / processedPixels;
        scaledBitmap.recycle();

        // Interpret average index
        String label;
        String desc;
        if (avgExg > 0.25) {
            label = "MẬT ĐỘ DIỆP LỤC TỐT";
            desc = "Lá cây có màu xanh tự nhiên đậm, quang hợp hiệu quả và sinh trưởng khỏe mạnh.";
        } else if (avgExg > 0.08) {
            label = "CẢNH BÁO: DINH DƯỠNG TRUNG BÌNH";
            desc = "Lá cây có màu hơi nhạt. Có khả năng thiếu vi lượng hoặc thiếu nước nhẹ. Nên theo dõi và tưới thêm.";
        } else {
            label = "CẢNH BÁO NGUY CƠ CAO";
            desc = "Chỉ số độ xanh rất thấp. Phát hiện lá vàng, khô úa hoặc bệnh hại nặng. Cần bón phân đạm hoặc kiểm tra thuốc trừ sâu.";
        }

        return new AnalysisResult(avgExg, heatmap, label, desc);
    }
}
