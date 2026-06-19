package com.mobilegis.ximifarming.supabase;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.mobilegis.ximifarming.data.AppDatabase;
import com.mobilegis.ximifarming.data.entity.Crop;
import com.mobilegis.ximifarming.data.entity.CropLog;
import com.mobilegis.ximifarming.data.entity.Plot;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SupabaseClient {
    private static final String TAG = "SupabaseClient";
    
    // Sử dụng BuildConfig để đọc cấu hình bảo mật từ local.properties
    private static final String SUPABASE_URL = com.mobilegis.ximifarming.BuildConfig.SUPABASE_URL;
    private static final String SUPABASE_ANON_KEY = com.mobilegis.ximifarming.BuildConfig.SUPABASE_ANON_KEY;

    private static SupabaseClient instance;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private String accessToken;

    private SupabaseClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public static synchronized SupabaseClient getInstance() {
        if (instance == null) {
            instance = new SupabaseClient();
        }
        return instance;
    }

    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    public String getAccessToken() {
        return this.accessToken;
    }

    // --- AUTHENTICATION API ---

    public interface AuthCallback {
        void onSuccess(String token, String email);
        void onError(String errorMsg);
    }

    public void signUp(String email, String password, AuthCallback callback) {
        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("email", email);
        bodyMap.put("password", password);
        String json = gson.toJson(bodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(SUPABASE_URL + "/auth/v1/signup")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Kết nối mạng thất bại: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String respStr = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful() && !respStr.isEmpty()) {
                    AuthResponse authRes = gson.fromJson(respStr, AuthResponse.class);
                    accessToken = authRes.accessToken;
                    if (accessToken == null || accessToken.isEmpty()) {
                        callback.onError("Đăng ký thành công nhưng cần xác nhận Email. Vui lòng kiểm tra hộp thư hoặc tắt tính năng 'Confirm Email' trên Supabase Dashboard.");
                    } else {
                        callback.onSuccess(accessToken, email);
                    }
                } else {
                    callback.onError("Đăng ký thất bại (Code " + response.code() + "): " + (!respStr.isEmpty() ? respStr : response.message()));
                }
            }
        });
    }

    public void signIn(String email, String password, AuthCallback callback) {
        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("email", email);
        bodyMap.put("password", password);
        String json = gson.toJson(bodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(SUPABASE_URL + "/auth/v1/token?grant_type=password")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Kết nối mạng thất bại: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String respStr = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful() && !respStr.isEmpty()) {
                    AuthResponse authRes = gson.fromJson(respStr, AuthResponse.class);
                    accessToken = authRes.accessToken;
                    callback.onSuccess(accessToken, email);
                } else {
                    callback.onError("Đăng nhập thất bại (Code " + response.code() + "): " + (!respStr.isEmpty() ? respStr : response.message()));
                }
            }
        });
    }

    // --- STORAGE UPLOAD API ---

    public interface UploadCallback {
        void onSuccess(String url);
        void onError(String errorMsg);
    }

    public void uploadPhoto(String fileName, byte[] fileBytes, UploadCallback callback) {
        if (accessToken == null) {
            callback.onError("Chưa đăng nhập Supabase");
            return;
        }

        RequestBody body = RequestBody.create(fileBytes, MediaType.parse("image/jpeg"));
        Request request = new Request.Builder()
                .url(SUPABASE_URL + "/storage/v1/object/crop-photos/" + fileName)
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "image/jpeg")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Không thể upload ảnh: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    // Trả về URL công khai của tệp ảnh
                    String publicUrl = SUPABASE_URL + "/storage/v1/object/public/crop-photos/" + fileName;
                    callback.onSuccess(publicUrl);
                } else {
                    callback.onError("Lỗi upload ảnh: HTTP " + response.code());
                }
            }
        });
    }

    // --- OFFLINE-FIRST SYNC MANAGER (PULL & PUSH) ---

    public interface SyncCallback {
        void onSuccess();
        void onError(String errorMsg);
    }

    /**
     * Đồng bộ 2 chiều: Tải dữ liệu từ Supabase về Room (Pull) và Đẩy dữ liệu chưa sync lên Supabase (Push)
     */
    public void syncData(Context context, SyncCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Chưa đăng nhập Supabase. Vui lòng đăng nhập trước khi đồng bộ.");
            return;
        }

        new Thread(() -> {
            AppDatabase db = AppDatabase.getDatabase(context);
            try {
                // --- 1. CHIỀU XUỐNG (PULL) ---
                Log.d(TAG, "Đang tải dữ liệu từ Supabase về...");
                
                // Pull Plots
                Request pullPlotsRequest = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/plots?select=*")
                        .get()
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();
                
                try (Response response = httpClient.newCall(pullPlotsRequest).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyStr = response.body().string();
                        List<Map<String, Object>> rawPlots = gson.fromJson(bodyStr, new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>(){}.getType());
                        if (rawPlots != null) {
                            for (Map<String, Object> rawPlot : rawPlots) {
                                long id = ((Double) rawPlot.get("id")).longValue();
                                String name = (String) rawPlot.get("name");
                                String desc = (String) rawPlot.get("description");
                                String coordsJson = gson.toJson(rawPlot.get("coordinates_json"));
                                double area = ((Double) rawPlot.get("area_sq_meters"));
                                String health = (String) rawPlot.get("health_status");
                                double ndvi = ((Double) rawPlot.get("avg_ndvi"));

                                Plot localPlot = db.plotDao().getPlotById(id);
                                if (localPlot == null) {
                                    Plot newPlot = new Plot(name, desc, coordsJson, area, health, ndvi);
                                    newPlot.setId(id);
                                    newPlot.setSynced(true);
                                    db.plotDao().insert(newPlot);
                                } else {
                                    localPlot.setName(name);
                                    localPlot.setDescription(desc);
                                    localPlot.setCoordinatesJson(coordsJson);
                                    localPlot.setAreaSquareMeters(area);
                                    localPlot.setHealthStatus(health);
                                    localPlot.setAvgNdvi(ndvi);
                                    localPlot.setSynced(true);
                                    db.plotDao().update(localPlot);
                                }
                            }
                        }
                    }
                }

                // Pull Crops
                Request pullCropsRequest = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/crops?select=*")
                        .get()
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                try (Response response = httpClient.newCall(pullCropsRequest).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyStr = response.body().string();
                        List<Map<String, Object>> rawCrops = gson.fromJson(bodyStr, new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>(){}.getType());
                        if (rawCrops != null) {
                            for (Map<String, Object> rawCrop : rawCrops) {
                                long id = ((Double) rawCrop.get("id")).longValue();
                                long plotId = ((Double) rawCrop.get("plot_id")).longValue();
                                String name = (String) rawCrop.get("name");
                                String type = (String) rawCrop.get("type");
                                
                                long plantingDate = System.currentTimeMillis();
                                try {
                                    String dateStr = (String) rawCrop.get("planting_date");
                                    plantingDate = java.time.Instant.parse(dateStr).toEpochMilli();
                                } catch (Exception ignored) {}
                                
                                double lat = ((Double) rawCrop.get("latitude"));
                                double lng = ((Double) rawCrop.get("longitude"));
                                String status = (String) rawCrop.get("status");

                                Crop localCrop = db.cropDao().getCropById(id);
                                if (localCrop == null) {
                                    Crop newCrop = new Crop(plotId, name, type, plantingDate, lat, lng, status);
                                    newCrop.setId(id);
                                    newCrop.setSynced(true);
                                    db.cropDao().insert(newCrop);
                                } else {
                                    localCrop.setPlotId(plotId);
                                    localCrop.setName(name);
                                    localCrop.setType(type);
                                    localCrop.setPlantingDate(plantingDate);
                                    localCrop.setLatitude(lat);
                                    localCrop.setLongitude(lng);
                                    localCrop.setStatus(status);
                                    localCrop.setSynced(true);
                                    db.cropDao().update(localCrop);
                                }
                            }
                        }
                    }
                }

                // --- 2. CHIỀU LÊN (PUSH) ---
                Log.d(TAG, "Đang đẩy dữ liệu cục bộ chưa đồng bộ lên...");
                
                // Đồng bộ Plots chưa sync
                List<Plot> unsyncedPlots = db.plotDao().getAllPlots();
                for (Plot plot : unsyncedPlots) {
                    if (!plot.isSynced()) {
                        syncPlotToSupabase(plot, db);
                    }
                }

                // Đồng bộ Crops chưa sync
                List<Crop> unsyncedCrops = db.cropDao().getAllCrops();
                for (Crop crop : unsyncedCrops) {
                    if (!crop.isSynced()) {
                        syncCropToSupabase(crop, db);
                    }
                }

                if (callback != null) callback.onSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Lỗi trong quá trình đồng bộ: " + e.getMessage());
                if (callback != null) callback.onError("Lỗi đồng bộ: " + e.getMessage());
            }
        }).start();
    }

    private void syncPlotToSupabase(Plot plot, AppDatabase db) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("name", plot.getName());
        data.put("description", plot.getDescription());
        data.put("coordinates_json", gson.fromJson(plot.getCoordinatesJson(), Object.class));
        data.put("area_sq_meters", plot.getAreaSquareMeters());
        data.put("health_status", plot.getHealthStatus());
        data.put("avg_ndvi", plot.getAvgNdvi());

        String json = gson.toJson(data);
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        String url = SUPABASE_URL + "/rest/v1/plots";
        
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                Plot[] returnedPlots = gson.fromJson(responseBody, Plot[].class);
                if (returnedPlots != null && returnedPlots.length > 0) {
                    long oldPlotId = plot.getId();
                    long newSupabaseId = returnedPlots[0].getId();
                    
                    db.runInTransaction(() -> {
                        // 1. Lấy tất cả các crops thuộc plot cũ
                        List<Crop> crops = db.cropDao().getCropsForPlot(oldPlotId);
                        
                        // 2. Chèn plot mới với ID từ Supabase
                        Plot newPlot = new Plot(plot.getName(), plot.getDescription(), plot.getCoordinatesJson(), 
                                                plot.getAreaSquareMeters(), plot.getHealthStatus(), plot.getAvgNdvi());
                        newPlot.setId(newSupabaseId);
                        newPlot.setSynced(true);
                        db.plotDao().insert(newPlot);
                        
                        // 3. Cập nhật plot_id của các crops thuộc plot này
                        for (Crop c : crops) {
                            c.setPlotId(newSupabaseId);
                            db.cropDao().update(c);
                        }
                        
                        // 4. Xóa plot cũ (CASCADE lúc này sẽ không ảnh hưởng vì các crops liên quan đã đổi plotId thành newSupabaseId)
                        db.plotDao().delete(plot);
                    });
                    
                    Log.d(TAG, "Đã đồng bộ lô đất lên Supabase với ID mới: " + newSupabaseId);
                }
            } else {
                Log.e(TAG, "Lỗi đồng bộ lô đất: HTTP " + response.code());
            }
        }
    }

    private void syncCropToSupabase(Crop crop, AppDatabase db) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("plot_id", crop.getPlotId());
        data.put("name", crop.getName());
        data.put("type", crop.getType());
        data.put("planting_date", new java.util.Date(crop.getPlantingDate()));
        data.put("latitude", crop.getLatitude());
        data.put("longitude", crop.getLongitude());
        data.put("status", crop.getStatus());

        String json = gson.toJson(data);
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(SUPABASE_URL + "/rest/v1/crops")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                Crop[] returnedCrops = gson.fromJson(responseBody, Crop[].class);
                if (returnedCrops != null && returnedCrops.length > 0) {
                    long oldCropId = crop.getId();
                    long newSupabaseId = returnedCrops[0].getId();
                    
                    db.runInTransaction(() -> {
                        // 1. Lấy tất cả các logs thuộc crop cũ
                        List<CropLog> logs = db.cropLogDao().getLogsForCrop(oldCropId);
                        
                        // 2. Chèn crop mới với ID từ Supabase
                        Crop newCrop = new Crop(crop.getPlotId(), crop.getName(), crop.getType(), 
                                                crop.getPlantingDate(), crop.getLatitude(), crop.getLongitude(), crop.getStatus());
                        newCrop.setId(newSupabaseId);
                        newCrop.setSynced(true);
                        db.cropDao().insert(newCrop);
                        
                        // 3. Cập nhật cropId của các logs thuộc crop này
                        for (CropLog l : logs) {
                            l.setCropId(newSupabaseId);
                            db.cropLogDao().update(l);
                        }
                        
                        // 4. Xóa crop cũ (CASCADE lúc này sẽ không ảnh hưởng)
                        db.cropDao().delete(crop);
                    });
                    
                    Log.d(TAG, "Đã đồng bộ cây trồng lên Supabase với ID mới: " + newSupabaseId);
                }
            }
        }
    }

    // --- UPDATE API (PATCH) ---
    
    public void updatePlotOnSupabase(Plot plot, SyncCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Chưa đăng nhập Supabase");
            return;
        }
        new Thread(() -> {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("health_status", plot.getHealthStatus());
                data.put("avg_ndvi", plot.getAvgNdvi());

                String json = gson.toJson(data);
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/plots?id=eq." + plot.getId())
                        .patch(body)
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .addHeader("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Đã cập nhật Plot lên Supabase thành công: id=" + plot.getId());
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errMsg = "Lỗi cập nhật Plot: HTTP " + response.code();
                        Log.e(TAG, errMsg);
                        if (callback != null) callback.onError(errMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi update plot: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    public void updateCropOnSupabase(Crop crop, SyncCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Chưa đăng nhập Supabase");
            return;
        }
        new Thread(() -> {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("status", crop.getStatus());

                String json = gson.toJson(data);
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/crops?id=eq." + crop.getId())
                        .patch(body)
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .addHeader("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Đã cập nhật Crop lên Supabase thành công: id=" + crop.getId());
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errMsg = "Lỗi cập nhật Crop: HTTP " + response.code();
                        Log.e(TAG, errMsg);
                        if (callback != null) callback.onError(errMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi update crop: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    // --- DELETE API (DELETE) ---

    public void deletePlotFromSupabase(long plotId, SyncCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Chưa đăng nhập Supabase");
            return;
        }
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/plots?id=eq." + plotId)
                        .delete()
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Đã xóa Plot trên Supabase thành công: id=" + plotId);
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errMsg = "Lỗi xóa Plot: HTTP " + response.code();
                        Log.e(TAG, errMsg);
                        if (callback != null) callback.onError(errMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi xóa plot: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    public void deleteCropFromSupabase(long cropId, SyncCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Chưa đăng nhập Supabase");
            return;
        }
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/crops?id=eq." + cropId)
                        .delete()
                        .addHeader("apikey", SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Đã xóa Crop trên Supabase thành công: id=" + cropId);
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errMsg = "Lỗi xóa Crop: HTTP " + response.code();
                        Log.e(TAG, errMsg);
                        if (callback != null) callback.onError(errMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi xóa crop: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    // --- DTO CLASS FOR GSON MAPPING ---

    private static class AuthResponse {
        @SerializedName("access_token")
        String accessToken;
        @SerializedName("token_type")
        String tokenType;
        @SerializedName("expires_in")
        int expiresIn;
    }
}
