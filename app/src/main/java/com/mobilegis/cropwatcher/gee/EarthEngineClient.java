package com.mobilegis.cropwatcher.gee;

import android.content.Context;
import android.util.Log;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Plot;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class EarthEngineClient {
    private static final String TAG = "EarthEngineClient";
    private static final String GEE_SCOPE = "https://www.googleapis.com/auth/earthengine";
    
    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private String cachedToken = null;
    private long tokenExpiryTime = 0;

    public interface Callback {
        void onSuccess(String tileUrlTemplate);
        void onError(String errorMessage);
    }

    public EarthEngineClient(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    /**
     * Gets a valid OAuth2 access token using the Service Account key from assets.
     */
    public synchronized String getAccessToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime - 60000) {
            return cachedToken;
        }

        InputStream stream = context.getAssets().open("service_account_key.json");
        GoogleCredentials credentials = GoogleCredentials.fromStream(stream)
                .createScoped(Collections.singleton(GEE_SCOPE));
        
        credentials.refreshIfExpired();
        cachedToken = credentials.getAccessToken().getTokenValue();
        tokenExpiryTime = credentials.getAccessToken().getExpirationTime().getTime();
        
        Log.d(TAG, "OAuth2 Token refreshed successfully");
        return cachedToken;
    }

    /**
     * Fetches a Google Earth Engine tile template URL for Sentinel-2 NDVI of a given region.
     */
    public void getNdviTileUrl(final double minLat, final double minLng, final double maxLat, final double maxLng, final Callback callback) {
        new Thread(() -> {
            try {
                // Try to load Service Account. If missing, this will throw FnFE which falls to Mock Mode.
                String token;
                try {
                    token = getAccessToken();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load GEE Service Account, falling back to Mock GEE mode. Error: " + e.getMessage());
                    // Delay slightly to simulate network
                    Thread.sleep(1000);
                    callback.onSuccess("MOCK_GEE_MODE");
                    return;
                }

                // GEE Project ID (usually read from service account JSON)
                InputStream stream = context.getAssets().open("service_account_key.json");
                Map<String, Object> keyMap = gson.fromJson(new java.io.InputStreamReader(stream), Map.class);
                String projectId = (String) keyMap.get("project_id");
                if (projectId == null || projectId.isEmpty()) {
                    projectId = "earthengine-legacy";
                }

                // Setup NDVI computation expression for GEE REST API v1alpha
                // We fetch Sentinel-2 Surface Reflectance, filter by boundary, compute NDVI = (B8 - B4) / (B8 + B4)
                JsonObject jsonPayload = createGeeNdviExpressionPayload(minLat, minLng, maxLat, maxLng);

                String url = "https://earthengine.googleapis.com/v1alpha/projects/" + projectId + "/maps";
                RequestBody body = RequestBody.create(
                        gson.toJson(jsonPayload),
                        MediaType.get("application/json; charset=utf-8")
                );

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Authorization", "Bearer " + token)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                        String mapName = jsonResponse.get("name").getAsString(); // e.g., projects/earthengine-legacy/maps/MAP_ID
                        
                        // Tile URL template for Google Maps TileProvider
                        String tileUrlTemplate = "https://earthengine.googleapis.com/v1alpha/" + mapName + "/tiles/{z}/{x}/{y}";
                        Log.d(TAG, "GEE Tile URL fetched: " + tileUrlTemplate);
                        callback.onSuccess(tileUrlTemplate);
                    } else {
                        String errBody = response.body() != null ? response.body().string() : "No response body";
                        Log.e(TAG, "GEE REST API Error: " + errBody);
                        callback.onError("GEE API Error: " + response.code() + " - " + response.message());
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error fetching NDVI from GEE", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Builds the GEE AST payload to calculate NDVI on Sentinel-2 image collection
     */
    private JsonObject createGeeNdviExpressionPayload(double minLat, double minLng, double maxLat, double maxLng) {
        JsonObject payload = new JsonObject();
        JsonObject expression = new JsonObject();
        JsonObject element = new JsonObject();
        
        // Load all plots from database to construct MultiPolygon clipping geometry
        AppDatabase db = AppDatabase.getDatabase(context);
        List<Plot> plots = db.plotDao().getAllPlots();
        JsonObject clipGeometry = null;
        
        if (plots != null && !plots.isEmpty()) {
            JsonArray multiPolygonCoords = new JsonArray();
            for (Plot plot : plots) {
                try {
                    double[][] pts = gson.fromJson(plot.getCoordinatesJson(), double[][].class);
                    if (pts != null && pts.length >= 3) {
                        JsonArray polygonCoords = new JsonArray();
                        JsonArray exteriorRing = new JsonArray();
                        
                        // Add points as [lng, lat] (EE uses longitude first)
                        for (double[] pt : pts) {
                            JsonArray coordPair = new JsonArray();
                            coordPair.add(pt[1]); // Longitude
                            coordPair.add(pt[0]); // Latitude
                            exteriorRing.add(coordPair);
                        }
                        // Close exterior ring (first point = last point)
                        JsonArray firstCoordPair = new JsonArray();
                        firstCoordPair.add(pts[0][1]);
                        firstCoordPair.add(pts[0][0]);
                        exteriorRing.add(firstCoordPair);
                        
                        polygonCoords.add(exteriorRing);
                        multiPolygonCoords.add(polygonCoords);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing plot coordinates for GEE clipping: " + e.getMessage());
                }
            }
            
            if (multiPolygonCoords.size() > 0) {
                JsonObject geoJsonObj = new JsonObject();
                geoJsonObj.addProperty("type", "MultiPolygon");
                geoJsonObj.add("coordinates", multiPolygonCoords);
                
                JsonObject geometryWrapper = new JsonObject();
                geometryWrapper.addProperty("type", "Geometry");
                geometryWrapper.add("geometry", geoJsonObj);
                
                clipGeometry = new JsonObject();
                clipGeometry.add("constantValue", geometryWrapper);
            }
        }

        // Image.visualize
        JsonObject funcInvocation = new JsonObject();
        funcInvocation.addProperty("functionName", "Image.visualize");
        JsonObject args = new JsonObject();
        
        // Image.normalizedDifference
        JsonObject ndviFunc = new JsonObject();
        JsonObject ndviInvocation = new JsonObject();
        ndviInvocation.addProperty("functionName", "Image.normalizedDifference");
        JsonObject ndviArgs = new JsonObject();
        
        // ImageCollection.mosaic
        JsonObject inputImage = new JsonObject();
        JsonObject mosaicInvocation = new JsonObject();
        mosaicInvocation.addProperty("functionName", "ImageCollection.mosaic");
        JsonObject mosaicArgs = new JsonObject();
        
        // ImageCollection.load
        JsonObject loadedCollection = new JsonObject();
        JsonObject loadInvocation = new JsonObject();
        loadInvocation.addProperty("functionName", "ImageCollection.load");
        JsonObject loadArgs = new JsonObject();
        JsonObject collectionId = new JsonObject();
        collectionId.addProperty("constantValue", "COPERNICUS/S2_SR_HARMONIZED");
        loadArgs.add("id", collectionId);
        loadInvocation.add("arguments", loadArgs);
        loadedCollection.add("functionInvocationValue", loadInvocation);
        
        mosaicArgs.add("collection", loadedCollection);
        mosaicInvocation.add("arguments", mosaicArgs);
        inputImage.add("functionInvocationValue", mosaicInvocation);
        
        ndviArgs.add("input", inputImage);
        
        JsonArray bandsArray = new JsonArray();
        bandsArray.add("B8");
        bandsArray.add("B4");
        JsonObject bands = new JsonObject();
        bands.add("constantValue", bandsArray);
        ndviArgs.add("bandNames", bands);
        
        ndviInvocation.add("arguments", ndviArgs);
        ndviFunc.add("functionInvocationValue", ndviInvocation);
        
        // Wrap NDVI computation with Image.clip if geometry is available
        JsonObject imageToVisualize = ndviFunc;
        if (clipGeometry != null) {
            JsonObject clipFunc = new JsonObject();
            JsonObject clipInvocation = new JsonObject();
            clipInvocation.addProperty("functionName", "Image.clip");
            
            JsonObject clipArgs = new JsonObject();
            clipArgs.add("input", ndviFunc);
            clipArgs.add("geometry", clipGeometry);
            
            clipInvocation.add("arguments", clipArgs);
            clipFunc.add("functionInvocationValue", clipInvocation);
            imageToVisualize = clipFunc;
        }
        
        args.add("image", imageToVisualize);
        
        // Visualization parameters as direct arguments to Image.visualize
        JsonObject minParam = new JsonObject();
        minParam.addProperty("constantValue", 0.1);
        args.add("min", minParam);

        JsonObject maxParam = new JsonObject();
        maxParam.addProperty("constantValue", 0.8);
        args.add("max", maxParam);

        JsonArray paletteArray = new JsonArray();
        paletteArray.add("red");
        paletteArray.add("yellow");
        paletteArray.add("green");
        JsonObject paletteParam = new JsonObject();
        paletteParam.add("constantValue", paletteArray);
        args.add("palette", paletteParam);
        
        funcInvocation.add("arguments", args);
        element.add("functionInvocationValue", funcInvocation);
        
        JsonObject values = new JsonObject();
        values.add("ndvi_image", element);
        
        expression.add("values", values);
        expression.addProperty("result", "ndvi_image");
        
        payload.add("expression", expression);
        payload.addProperty("fileFormat", "PNG");
        return payload;
    }
}
