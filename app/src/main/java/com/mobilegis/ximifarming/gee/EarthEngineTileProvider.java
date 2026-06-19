package com.mobilegis.ximifarming.gee;

import android.util.Log;

import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mobilegis.ximifarming.data.entity.Plot;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EarthEngineTileProvider implements TileProvider {
    private static final String TAG = "EETileProvider";
    private static final int TILE_SIZE = 256;
    
    private static final int MEM_CACHE_SIZE = 128;
    private static final android.util.LruCache<String, Tile> memoryCache = new android.util.LruCache<>(MEM_CACHE_SIZE);
    
    private final android.content.Context context;
    private final String tileUrlTemplate;
    private final List<Plot> plotsList;
    private final long plotId;
    private final EarthEngineClient geeClient;
    private final OkHttpClient httpClient;
    private final Gson gson;

    private double plotMinLat = 90;
    private double plotMaxLat = -90;
    private double plotMinLng = 180;
    private double plotMaxLng = -180;
    private boolean hasPlotBounds = false;

    public EarthEngineTileProvider(android.content.Context context, String tileUrlTemplate, List<Plot> plots, EarthEngineClient geeClient) {
        this.context = context.getApplicationContext();
        this.tileUrlTemplate = tileUrlTemplate;
        this.plotsList = plots != null ? plots : new ArrayList<>();
        this.plotId = this.plotsList.isEmpty() ? -1L : this.plotsList.get(0).getId();
        this.geeClient = geeClient;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();

        // Compute overall plot bounding box
        for (Plot plot : this.plotsList) {
            List<double[]> coords = parseCoordinates(plot.getCoordinatesJson());
            if (coords != null) {
                for (double[] latLng : coords) {
                    plotMinLat = Math.min(plotMinLat, latLng[0]);
                    plotMaxLat = Math.max(plotMaxLat, latLng[0]);
                    plotMinLng = Math.min(plotMinLng, latLng[1]);
                    plotMaxLng = Math.max(plotMaxLng, latLng[1]);
                    hasPlotBounds = true;
                }
            }
        }
    }

    @Override
    public Tile getTile(int x, int y, int zoom) {
        // Calculate tile boundaries
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);

        // Check if tile bounds intersect with plot bounding box (extended by 0.01 degree padding for safety)
        if (hasPlotBounds) {
            double padding = 0.01;
            boolean intersects = !(west > plotMaxLng + padding || east < plotMinLng - padding 
                                  || south > plotMaxLat + padding || north < plotMinLat - padding);
            if (!intersects) {
                return NO_TILE;
            }
        }

        // 1. Try memory cache first
        String cacheKey = plotId + "_" + zoom + "_" + x + "_" + y;
        Tile cachedTile = memoryCache.get(cacheKey);
        if (cachedTile != null) {
            Log.d(TAG, "Loaded tile from memory cache: " + zoom + "/" + x + "/" + y);
            return cachedTile;
        }

        // 2. Try local disk cache second
        java.io.File cacheFile = getCacheFile(plotId, x, y, zoom);
        if (cacheFile.exists()) {
            byte[] cachedData = readFile(cacheFile);
            if (cachedData != null && cachedData.length > 0) {
                Log.d(TAG, "Loaded tile from local cache: " + zoom + "/" + x + "/" + y);
                Tile tile = new Tile(TILE_SIZE, TILE_SIZE, cachedData);
                memoryCache.put(cacheKey, tile);
                return tile;
            }
        }

        // 3. Real GEE Tile Fetching
        String tileUrl = tileUrlTemplate
                .replace("{z}", String.valueOf(zoom))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));

        try {
            String token = null;
            if (geeClient != null) {
                token = geeClient.getAccessToken();
            }
            
            Request.Builder requestBuilder = new Request.Builder().url(tileUrl);
            if (token != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + token);
            }
            
            Request request = requestBuilder.build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] data = response.body().bytes();
                    writeFile(cacheFile, data);
                    Tile tile = new Tile(TILE_SIZE, TILE_SIZE, data);
                    memoryCache.put(cacheKey, tile);
                    return tile;
                } else {
                    Log.e(TAG, "GEE tile fetch failed: HTTP " + response.code() + " - " + response.message());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching real GEE tile: " + e.getMessage());
        }

        return NO_TILE;
    }

    private java.io.File getCacheFile(long plotId, int x, int y, int zoom) {
        java.io.File cacheDir = new java.io.File(context.getCacheDir(), "gee_tiles/" + plotId);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new java.io.File(cacheDir, zoom + "_" + x + "_" + y + ".png");
    }

    private byte[] readFile(java.io.File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error reading cached tile: " + e.getMessage());
            return null;
        }
    }

    private void writeFile(java.io.File file, byte[] data) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(data);
        } catch (Exception e) {
            Log.e(TAG, "Error writing tile to cache: " + e.getMessage());
        }
    }

    public static void clearTileCacheForPlot(android.content.Context context, long plotId) {
        try {
            java.io.File cacheDir = new java.io.File(context.getCacheDir(), "gee_tiles/" + plotId);
            if (cacheDir.exists() && cacheDir.isDirectory()) {
                java.io.File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        file.delete();
                    }
                }
                cacheDir.delete();
                Log.d(TAG, "Cleared cached tiles for plot " + plotId);
            }
            if (memoryCache != null) {
                memoryCache.evictAll();
                Log.d(TAG, "Cleared memory cache");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing cached tiles: " + e.getMessage());
        }
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
