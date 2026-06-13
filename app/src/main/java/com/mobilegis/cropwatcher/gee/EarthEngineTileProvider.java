package com.mobilegis.cropwatcher.gee;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mobilegis.cropwatcher.data.entity.Plot;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EarthEngineTileProvider implements TileProvider {
    private static final String TAG = "EETileProvider";
    private static final int TILE_SIZE = 256;
    
    private final String tileUrlTemplate;
    private final List<Plot> plotsList;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public EarthEngineTileProvider(String tileUrlTemplate, List<Plot> plots) {
        this.tileUrlTemplate = tileUrlTemplate;
        this.plotsList = plots != null ? plots : new ArrayList<>();
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
    }

    @Override
    public Tile getTile(int x, int y, int zoom) {
        if ("MOCK_GEE_MODE".equals(tileUrlTemplate)) {
            return getMockTile(x, y, zoom);
        }

        // Real GEE Tile Fetching
        String tileUrl = tileUrlTemplate
                .replace("{z}", String.valueOf(zoom))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));

        try {
            Request request = new Request.Builder().url(tileUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] data = response.body().bytes();
                    return new Tile(TILE_SIZE, TILE_SIZE, data);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching real GEE tile: " + e.getMessage());
        }

        return NO_TILE;
    }

    /**
     * Renders a mock NDVI heatmap tile based on Plot boundaries to work completely offline
     */
    private Tile getMockTile(int x, int y, int zoom) {
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);

        // Find if any plot intersects with this tile bounding box
        List<Plot> intersectingPlots = new ArrayList<>();
        for (Plot plot : plotsList) {
            List<double[]> coords = parseCoordinates(plot.getCoordinatesJson());
            if (coords == null || coords.isEmpty()) continue;
            
            // Basic bounding box check for intersection
            boolean intersects = false;
            for (double[] latLng : coords) {
                double lat = latLng[0];
                double lng = latLng[1];
                if (lat >= south && lat <= north && lng >= west && lng <= east) {
                    intersects = true;
                    break;
                }
            }
            if (intersects) {
                intersectingPlots.add(plot);
            }
        }

        if (intersectingPlots.isEmpty()) {
            return NO_TILE;
        }

        // Render mock NDVI heatmap
        try {
            Bitmap bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            
            // Draw transparent green-yellow-red heatmap for each plot
            for (Plot plot : intersectingPlots) {
                List<double[]> coords = parseCoordinates(plot.getCoordinatesJson());
                if (coords == null || coords.size() < 3) continue;

                Path path = new Path();
                boolean first = true;
                for (double[] latLng : coords) {
                    double lat = latLng[0];
                    double lng = latLng[1];
                    
                    // Convert GPS to tile pixel
                    float px = (float) ((lng - west) / (east - west) * TILE_SIZE);
                    float py = (float) ((north - lat) / (north - south) * TILE_SIZE);

                    if (first) {
                        path.moveTo(px, py);
                        first = false;
                    } else {
                        path.lineTo(px, py);
                    }
                }
                path.close();

                // Plot Health colors (semi-transparent)
                int plotColor;
                if ("GOOD".equals(plot.getHealthStatus())) {
                    plotColor = Color.argb(120, 46, 125, 50); // Green
                } else if ("WARNING".equals(plot.getHealthStatus())) {
                    plotColor = Color.argb(120, 239, 108, 0); // Orange
                } else {
                    plotColor = Color.argb(120, 198, 40, 40); // Red
                }

                Paint paint = new Paint();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(plotColor);
                canvas.drawPath(path, paint);

                // Add NDVI texture pattern (satellite noise simulation)
                Paint noisePaint = new Paint();
                noisePaint.setStyle(Paint.Style.FILL);
                noisePaint.setColor(Color.argb(40, 255, 255, 255)); // highlights
                canvas.drawCircle((float)(TILE_SIZE * 0.4), (float)(TILE_SIZE * 0.5), 25, noisePaint);
                
                noisePaint.setColor(Color.argb(40, 0, 0, 0)); // shadowed spots (stressed)
                canvas.drawCircle((float)(TILE_SIZE * 0.7), (float)(TILE_SIZE * 0.3), 15, noisePaint);

                // Stroke border
                Paint borderPaint = new Paint();
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(2f);
                borderPaint.setColor(Color.argb(180, 255, 255, 255));
                canvas.drawPath(path, borderPaint);
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();
            bitmap.recycle();

            return new Tile(TILE_SIZE, TILE_SIZE, byteArray);

        } catch (Exception e) {
            Log.e(TAG, "Error creating mock tile: " + e.getMessage());
        }

        return NO_TILE;
    }

    private double tile2lon(int x, int z) {
        return x / Math.pow(2.0, z) * 360.0 - 180.0;
    }

    private double tile2lat(int y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / Math.pow(2.0, z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private List<double[]> parseCoordinates(String json) {
        try {
            Type listType = new TypeToken<List<double[]>>(){}.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            return null;
        }
    }
}
