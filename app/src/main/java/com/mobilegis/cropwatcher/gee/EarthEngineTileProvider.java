package com.mobilegis.cropwatcher.gee;

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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EarthEngineTileProvider implements TileProvider {
    private static final String TAG = "EETileProvider";
    private static final int TILE_SIZE = 256;
    
    private final String tileUrlTemplate;
    private final List<Plot> plotsList;
    private final EarthEngineClient geeClient;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public EarthEngineTileProvider(String tileUrlTemplate, List<Plot> plots, EarthEngineClient geeClient) {
        this.tileUrlTemplate = tileUrlTemplate;
        this.plotsList = plots != null ? plots : new ArrayList<>();
        this.geeClient = geeClient;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    @Override
    public Tile getTile(int x, int y, int zoom) {


        // Calculate tile boundaries
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);

        // Check if the requested tile intersects with any plot area
        boolean intersects = false;
        for (Plot plot : plotsList) {
            List<double[]> coords = parseCoordinates(plot.getCoordinatesJson());
            if (coords == null || coords.isEmpty()) continue;
            
            for (double[] latLng : coords) {
                double lat = latLng[0];
                double lng = latLng[1];
                if (lat >= south && lat <= north && lng >= west && lng <= east) {
                    intersects = true;
                    break;
                }
            }
            if (intersects) break;
        }

        // If it does not cover any agricultural plot, skip GEE query to avoid unnecessary timeouts
        if (!intersects) {
            return NO_TILE;
        }

        // Real GEE Tile Fetching
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
                    return new Tile(TILE_SIZE, TILE_SIZE, data);
                } else {
                    Log.e(TAG, "GEE tile fetch failed: HTTP " + response.code() + " - " + response.message());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching real GEE tile: " + e.getMessage());
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
