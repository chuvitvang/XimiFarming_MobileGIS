package com.mobilegis.cropwatcher.ui.map;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.gson.Gson;
import com.mobilegis.cropwatcher.R;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Crop;
import com.mobilegis.cropwatcher.data.entity.Plot;
import com.mobilegis.cropwatcher.databinding.FragmentMapBinding;
import com.mobilegis.cropwatcher.gee.EarthEngineClient;
import com.mobilegis.cropwatcher.gee.EarthEngineTileProvider;
import com.mobilegis.cropwatcher.ui.plots.PlotDetailActivity;
import com.mobilegis.cropwatcher.utils.GisHelper;

import java.util.ArrayList;
import java.util.List;

public class MapFragment extends Fragment implements OnMapReadyCallback {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    
    private FragmentMapBinding binding;
    private GoogleMap googleMap;
    private AppDatabase db;
    private EarthEngineClient geeClient;
    
    // Map states
    private enum MapMode { NORMAL, DRAW_PLOT, ADD_CROP }
    private MapMode currentMode = MapMode.NORMAL;
    
    // Drawing references
    private final List<LatLng> drawPoints = new ArrayList<>();
    private final List<Marker> drawMarkers = new ArrayList<>();
    private Polyline drawPolyline;
    private Polygon drawPolygon;
    
    // GIS overlays loaded in map
    private final List<Polygon> activePolygons = new ArrayList<>();
    private final List<Marker> activeCropMarkers = new ArrayList<>();
    private TileOverlay geeTileOverlay;
    private boolean isGeeLayerActive = false;

    // Selection details
    private Plot selectedPlot;
    private Crop selectedCrop;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMapBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());
        geeClient = new EarthEngineClient(requireContext());

        // Initialize Google Map
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        setupUIListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (googleMap != null) {
            reloadMapData();
            hideSelectionCard();
        }
    }

    private void setupUIListeners() {
        // Toggle Drawing Plot
        binding.btnDrawPlot.setOnClickListener(v -> {
            if (currentMode == MapMode.DRAW_PLOT) {
                cancelDrawingMode();
            } else {
                startDrawPlotMode();
            }
        });

        // Toggle Adding Crop
        binding.btnAddCrop.setOnClickListener(v -> {
            if (currentMode == MapMode.ADD_CROP) {
                cancelDrawingMode();
            } else {
                startAddCropMode();
            }
        });

        // Toggle GEE Layer
        binding.btnToggleGee.setOnClickListener(v -> toggleGeeNdviLayer());

        // Close Selection Card
        binding.btnSelectionClose.setOnClickListener(v -> hideSelectionCard());

        // Action on Selection Card
        binding.btnSelectionAction.setOnClickListener(v -> {
            if (selectedPlot != null) {
                Intent intent = new Intent(getActivity(), PlotDetailActivity.class);
                intent.putExtra("PLOT_ID", selectedPlot.getId());
                startActivity(intent);
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        this.googleMap = map;
        
        // Setup Map settings
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        
        // Center camera in Ho Chi Minh City/Vietnam default agricultural zone
        LatLng defaultLoc = new LatLng(10.8231, 106.6297);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 14f));

        checkLocationPermissions();
        
        // Load existing GIS data (Polygons + Crop Markers)
        reloadMapData();

        // Map Click Listener for drawing/placing items
        googleMap.setOnMapClickListener(latLng -> {
            if (currentMode == MapMode.DRAW_PLOT) {
                handleDrawPlotClick(latLng);
            } else if (currentMode == MapMode.ADD_CROP) {
                handlePlacedCropClick(latLng);
            } else {
                hideSelectionCard();
            }
        });

        // Polygon click listener
        googleMap.setOnPolygonClickListener(polygon -> {
            if (currentMode == MapMode.NORMAL) {
                Plot plot = (Plot) polygon.getTag();
                if (plot != null) {
                    showSelectedPlot(plot);
                }
            }
        });

        // Marker click listener
        googleMap.setOnMarkerClickListener(marker -> {
            if (currentMode == MapMode.NORMAL) {
                Object tag = marker.getTag();
                if (tag instanceof Crop) {
                    showSelectedCrop((Crop) tag);
                    return true;
                }
            }
            return false;
        });
    }

    public void reloadMapData() {
        if (googleMap == null) return;

        // Clear existing overlays
        for (Polygon p : activePolygons) p.remove();
        activePolygons.clear();
        
        for (Marker m : activeCropMarkers) m.remove();
        activeCropMarkers.clear();

        // Load Plots from Room
        List<Plot> plots = db.plotDao().getAllPlots();
        for (Plot plot : plots) {
            List<LatLng> pts = parsePoints(plot.getCoordinatesJson());
            if (pts.size() < 3) continue;

            // Health color mapping
            int fillColor;
            int strokeColor;
            if ("GOOD".equals(plot.getHealthStatus())) {
                fillColor = Color.argb(60, 46, 125, 50); // Semi-trans Green
                strokeColor = Color.rgb(46, 125, 50);
            } else if ("WARNING".equals(plot.getHealthStatus())) {
                fillColor = Color.argb(60, 239, 108, 0); // Orange
                strokeColor = Color.rgb(239, 108, 0);
            } else {
                fillColor = Color.argb(60, 198, 40, 40); // Red
                strokeColor = Color.rgb(198, 40, 40);
            }

            Polygon polygon = googleMap.addPolygon(new PolygonOptions()
                    .addAll(pts)
                    .strokeColor(strokeColor)
                    .strokeWidth(5)
                    .fillColor(fillColor)
                    .clickable(true));
            
            polygon.setTag(plot);
            activePolygons.add(polygon);
        }

        // Load Crops from Room
        List<Crop> crops = db.cropDao().getAllCrops();
        for (Crop crop : crops) {
            LatLng pos = new LatLng(crop.getLatitude(), crop.getLongitude());
            
            // Custom marker colors depending on crop status
            float hue;
            if ("HEALTHY".equals(crop.getStatus())) {
                hue = BitmapDescriptorFactory.HUE_GREEN;
            } else if ("STRESSED".equals(crop.getStatus())) {
                hue = BitmapDescriptorFactory.HUE_ORANGE;
            } else {
                hue = BitmapDescriptorFactory.HUE_RED;
            }

            Marker marker = googleMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(crop.getName())
                    .snippet("Loại: " + crop.getType())
                    .icon(BitmapDescriptorFactory.defaultMarker(hue)));
            
            if (marker != null) {
                marker.setTag(crop);
                activeCropMarkers.add(marker);
            }
        }
        
        // Refresh GEE Tiles if currently active
        if (isGeeLayerActive) {
            loadGeeOverlay();
        }
    }

    private void checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap.setMyLocationEnabled(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    googleMap.setMyLocationEnabled(true);
                }
            }
        }
    }

    // --- Drawing Plot Mode Logic ---

    private void startDrawPlotMode() {
        cancelDrawingMode();
        currentMode = MapMode.DRAW_PLOT;
        binding.btnDrawPlot.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
        binding.btnDrawPlot.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_close_clear_cancel));
        
        binding.instructionPanel.setVisibility(View.VISIBLE);
        binding.txtInstruction.setText("VẼ LÔ ĐẤT: Chạm để vẽ các đỉnh. Click OK ở dưới để hoàn tất.");
        
        // Add dynamic OK Button inside layout
        binding.instructionPanel.setOnClickListener(v -> finishDrawPlot());
    }

    private void handleDrawPlotClick(LatLng latLng) {
        drawPoints.add(latLng);
        
        // Place point marker
        Marker m = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        drawMarkers.add(m);

        // Update Polyline
        if (drawPolyline != null) drawPolyline.remove();
        drawPolyline = googleMap.addPolyline(new PolylineOptions().addAll(drawPoints).color(Color.BLUE).width(5));

        // Update Polygon preview
        if (drawPoints.size() >= 3) {
            if (drawPolygon != null) drawPolygon.remove();
            drawPolygon = googleMap.addPolygon(new PolygonOptions()
                    .addAll(drawPoints)
                    .fillColor(Color.argb(50, 0, 0, 255))
                    .strokeColor(Color.BLUE)
                    .strokeWidth(2));
        }
    }

    private void finishDrawPlot() {
        if (drawPoints.size() < 3) {
            Toast.makeText(getContext(), "Lô đất cần tối thiểu 3 đỉnh!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show Input Dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Lưu Lô Đất Mới");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final EditText nameInput = new EditText(getContext());
        nameInput.setHint("Tên lô đất (ví dụ: Lô Bưởi Đông)");
        layout.addView(nameInput);

        final EditText descInput = new EditText(getContext());
        descInput.setHint("Mô tả");
        layout.addView(descInput);

        builder.setView(layout);

        builder.setPositiveButton("Lưu", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String desc = descInput.getText().toString().trim();

            if (name.isEmpty()) name = "Lô Đất Mới";

            // Calculate area
            List<double[]> listCoords = new ArrayList<>();
            for (LatLng p : drawPoints) {
                listCoords.add(new double[]{p.latitude, p.longitude});
            }
            double area = GisHelper.calculateArea(listCoords);

            // Serialize points to JSON
            String jsonPoints = new Gson().toJson(listCoords);

            // Save to DB
            Plot newPlot = new Plot(name, desc, jsonPoints, area, "GOOD", 0.65);
            db.plotDao().insert(newPlot);

            Toast.makeText(getContext(), "Đã lưu lô đất: " + name, Toast.LENGTH_SHORT).show();
            cancelDrawingMode();
            reloadMapData();
        });

        builder.setNegativeButton("Hủy", (dialog, which) -> cancelDrawingMode());
        builder.show();
    }

    // --- Adding Crop Mode Logic ---

    private void startAddCropMode() {
        cancelDrawingMode();
        currentMode = MapMode.ADD_CROP;
        binding.btnAddCrop.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.secondary));
        binding.btnAddCrop.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_close_clear_cancel));
        
        binding.instructionPanel.setVisibility(View.VISIBLE);
        binding.txtInstruction.setText("THÊM CÂY TRỒNG: Chạm bất kỳ đâu trên bản đồ để ghim cây.");
    }

    private void handlePlacedCropClick(LatLng latLng) {
        // Show Input Dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Thêm Cây Trồng Định Vị");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final EditText nameInput = new EditText(getContext());
        nameInput.setHint("Tên/Mã Cây (ví dụ: Cây Cam #12)");
        layout.addView(nameInput);

        final EditText typeInput = new EditText(getContext());
        typeInput.setHint("Loại cây (ví dụ: Cam sành)");
        layout.addView(typeInput);

        builder.setView(layout);

        builder.setPositiveButton("Thêm", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String type = typeInput.getText().toString().trim();

            if (name.isEmpty()) name = "Cây Mới";
            if (type.isEmpty()) type = "Cây ăn quả";

            // Find which plot this crop belongs to (GIS point in polygon check)
            int plotId = findPlotForLatLng(latLng);
            if (plotId == -1) {
                // Not inside any plot, prompt warning or save to first plot
                List<Plot> allPlots = db.plotDao().getAllPlots();
                if (!allPlots.isEmpty()) {
                    plotId = allPlots.get(0).getId();
                } else {
                    Toast.makeText(getContext(), "Vui lòng vẽ ít nhất 1 lô đất trước khi đặt cây!", Toast.LENGTH_LONG).show();
                    cancelDrawingMode();
                    return;
                }
            }

            // Save to DB
            Crop crop = new Crop(plotId, name, type, System.currentTimeMillis(), latLng.latitude, latLng.longitude, "HEALTHY");
            db.cropDao().insert(crop);

            Toast.makeText(getContext(), "Đã định vị cây: " + name, Toast.LENGTH_SHORT).show();
            cancelDrawingMode();
            reloadMapData();
        });

        builder.setNegativeButton("Hủy", (dialog, which) -> cancelDrawingMode());
        builder.show();
    }

    private int findPlotForLatLng(LatLng latLng) {
        // Simple bounding/ray casting algorithm check
        List<Plot> plots = db.plotDao().getAllPlots();
        for (Plot plot : plots) {
            List<LatLng> polygon = parsePoints(plot.getCoordinatesJson());
            if (containsLocation(latLng, polygon)) {
                return plot.getId();
            }
        }
        return -1;
    }

    private boolean containsLocation(LatLng point, List<LatLng> polygon) {
        int intersectCount = 0;
        for (int i = 0; i < polygon.size(); i++) {
            LatLng vertex1 = polygon.get(i);
            LatLng vertex2 = polygon.get((i + 1) % polygon.size());
            if (charIntersect(point, vertex1, vertex2)) {
                intersectCount++;
            }
        }
        return (intersectCount % 2 == 1);
    }

    private boolean charIntersect(LatLng point, LatLng v1, LatLng v2) {
        if (v1.longitude > v2.longitude) {
            LatLng temp = v1; v1 = v2; v2 = temp;
        }
        if (point.longitude == v1.longitude || point.longitude == v2.longitude) {
            point = new LatLng(point.latitude, point.longitude + 0.00001);
        }
        if (point.longitude < v1.longitude || point.longitude > v2.longitude) {
            return false;
        }
        if (point.latitude >= Math.max(v1.latitude, v2.latitude)) {
            return false;
        }
        if (point.latitude < Math.min(v1.latitude, v2.latitude)) {
            return true;
        }
        double red = (point.longitude - v1.longitude) / (v2.longitude - v1.longitude);
        double blue = v1.latitude + red * (v2.latitude - v1.latitude);
        return (point.latitude < blue);
    }

    private void cancelDrawingMode() {
        currentMode = MapMode.NORMAL;
        
        // Reset buttons states
        binding.btnDrawPlot.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        binding.btnDrawPlot.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_edit));
        
        binding.btnAddCrop.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        binding.btnAddCrop.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_input_add));

        binding.instructionPanel.setVisibility(View.GONE);
        
        // Clear drawing geometry
        for (Marker m : drawMarkers) m.remove();
        drawMarkers.clear();
        drawPoints.clear();
        if (drawPolyline != null) { drawPolyline.remove(); drawPolyline = null; }
        if (drawPolygon != null) { drawPolygon.remove(); drawPolygon = null; }
    }

    // --- GEE Integration Overlay logic ---

    private void toggleGeeNdviLayer() {
        if (isGeeLayerActive) {
            // Remove GEE Layer
            if (geeTileOverlay != null) {
                geeTileOverlay.remove();
                geeTileOverlay = null;
            }
            isGeeLayerActive = false;
            binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            Toast.makeText(getContext(), "Đã tắt lớp vệ tinh GEE", Toast.LENGTH_SHORT).show();
        } else {
            // Load GEE Layer
            isGeeLayerActive = true;
            binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.accent));
            loadGeeOverlay();
        }
        if (selectedPlot != null) {
            updatePlotSelectionDetails(selectedPlot);
        }
    }

    private void loadGeeOverlay() {
        if (googleMap == null) return;
        if (geeTileOverlay != null) geeTileOverlay.remove();

        if (selectedPlot == null) {
            Toast.makeText(getContext(), "Vui lòng chạm chọn một lô đất trên bản đồ trước để xem ảnh vệ tinh NDVI!", Toast.LENGTH_LONG).show();
            isGeeLayerActive = false;
            binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            return;
        }

        // Update plot selection details to show "Đang tải..." or cached date if not checked yet
        updatePlotSelectionDetails(selectedPlot);

        final int plotId = selectedPlot.getId();
        final String plotName = selectedPlot.getName();
        final android.content.SharedPreferences prefs = requireContext().getSharedPreferences("gee_cache", android.content.Context.MODE_PRIVATE);
        
        long lastCheckTime = prefs.getLong("gee_last_check_time_" + plotId, 0);
        final long cachedLatestImageTime = prefs.getLong("gee_latest_image_time_" + plotId, 0);
        String cachedTileUrlTemplate = prefs.getString("gee_tile_template_" + plotId, null);
        long cachedTileUrlExpiry = prefs.getLong("gee_tile_expiry_" + plotId, 0);
        
        long currentTime = System.currentTimeMillis();
        boolean hasValidUrl = cachedTileUrlTemplate != null && currentTime < cachedTileUrlExpiry;
        boolean checkExpired = currentTime - lastCheckTime > 12 * 60 * 60 * 1000; // 12 hours check interval

        final List<Plot> singlePlotList = new ArrayList<>();
        singlePlotList.add(selectedPlot);

        // Calculate bounding box of the selected plot
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        List<LatLng> pts = parsePoints(selectedPlot.getCoordinatesJson());
        if (pts.isEmpty()) {
            isGeeLayerActive = false;
            binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            Toast.makeText(getContext(), "Không tìm thấy tọa độ của lô đất!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        for (LatLng latLng : pts) {
            minLat = Math.min(minLat, latLng.latitude);
            maxLat = Math.max(maxLat, latLng.latitude);
            minLng = Math.min(minLng, latLng.longitude);
            maxLng = Math.max(maxLng, latLng.longitude);
        }
        
        final double finalMinLat = minLat;
        final double finalMinLng = minLng;
        final double finalMaxLat = maxLat;
        final double finalMaxLng = maxLng;

        // Case 1: Within check interval AND we have a valid, unexpired tile URL template.
        // We can render immediately from local cache & pre-fetched tiles, making absolutely zero API calls.
        if (!checkExpired && hasValidUrl && cachedLatestImageTime > 0) {
            String dateStr = formatDate(cachedLatestImageTime);
            Toast.makeText(getContext(), "Sử dụng ảnh vệ tinh lưu cục bộ (chụp ngày " + dateStr + ")", Toast.LENGTH_SHORT).show();
            
            EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), cachedTileUrlTemplate, singlePlotList, geeClient);
            geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                    .tileProvider(tileProvider)
                    .transparency(0.1f));
            updatePlotSelectionDetails(selectedPlot);
            return;
        }

        // Case 2: We need to verify if there's a new image on GEE
        if (checkExpired || cachedLatestImageTime == 0) {
            Toast.makeText(getContext(), "Đang kiểm tra ảnh vệ tinh mới từ GEE cho lô " + plotName + "...", Toast.LENGTH_SHORT).show();
            
            geeClient.getLatestImageTimestamp(singlePlotList, finalMinLat - 0.01, finalMinLng - 0.01, finalMaxLat + 0.01, finalMaxLng + 0.01, new EarthEngineClient.TimestampCallback() {
                @Override
                public void onSuccess(final long latestImageTime) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (!isGeeLayerActive) return;
                        
                        // Compare GEE timestamp with cached timestamp
                        if (latestImageTime == cachedLatestImageTime && cachedLatestImageTime > 0) {
                            // No new image! We can use our cached tile template if it is still valid.
                            prefs.edit().putLong("gee_last_check_time_" + plotId, System.currentTimeMillis()).apply();
                            
                            String dateStr = formatDate(cachedLatestImageTime);
                            Toast.makeText(getContext(), "Không có ảnh vệ tinh mới. Sử dụng ảnh cũ lưu cục bộ (ngày " + dateStr + ")", Toast.LENGTH_LONG).show();
                            updatePlotSelectionDetails(selectedPlot);
                            
                            boolean isUrlStillValid = cachedTileUrlTemplate != null && System.currentTimeMillis() < cachedTileUrlExpiry;
                            if (isUrlStillValid) {
                                EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), cachedTileUrlTemplate, singlePlotList, geeClient);
                                geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                                        .tileProvider(tileProvider)
                                        .transparency(0.1f));
                            } else {
                                // Template is expired, request a new session but DO NOT clear tile cache files
                                geeClient.getNdviTileUrl(singlePlotList, finalMinLat - 0.01, finalMinLng - 0.01, finalMaxLat + 0.01, finalMaxLng + 0.01, new EarthEngineClient.Callback() {
                                    @Override
                                    public void onSuccess(String tileUrlTemplate) {
                                        if (getActivity() == null) return;
                                        getActivity().runOnUiThread(() -> {
                                            if (!isGeeLayerActive) return;
                                            
                                            // Cache the new template (valid for 2 hours)
                                            prefs.edit()
                                                    .putString("gee_tile_template_" + plotId, tileUrlTemplate)
                                                    .putLong("gee_tile_expiry_" + plotId, System.currentTimeMillis() + 2 * 60 * 60 * 1000)
                                                    .apply();
                                            
                                            EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), tileUrlTemplate, singlePlotList, geeClient);
                                            geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                                                    .tileProvider(tileProvider)
                                                    .transparency(0.1f));
                                            updatePlotSelectionDetails(selectedPlot);
                                        });
                                    }

                                    @Override
                                    public void onError(String errorMessage) {
                                        showError(errorMessage);
                                    }
                                });
                            }
                        } else {
                            // Case 2b: A new image is available (or no cache exists yet)
                            // We call GEE maps API to get a new session, and we will clear the tile cache files
                            Toast.makeText(getContext(), "Có ảnh vệ tinh mới! Đang tải...", Toast.LENGTH_SHORT).show();
                            
                            geeClient.getNdviTileUrl(singlePlotList, finalMinLat - 0.01, finalMinLng - 0.01, finalMaxLat + 0.01, finalMaxLng + 0.01, new EarthEngineClient.Callback() {
                                @Override
                                public void onSuccess(String tileUrlTemplate) {
                                    if (getActivity() == null) return;
                                    
                                    // 1. Clear old tile files on disk in background
                                    new Thread(() -> EarthEngineTileProvider.clearTileCacheForPlot(requireContext(), plotId)).start();
                                    
                                    // 2. Fetch mean NDVI from GEE
                                    geeClient.getMeanNdvi(singlePlotList, finalMinLat - 0.01, finalMinLng - 0.01, finalMaxLat + 0.01, finalMaxLng + 0.01, new EarthEngineClient.NdviCallback() {
                                        @Override
                                        public void onSuccess(double meanNdvi) {
                                            if (getActivity() == null) return;
                                            
                                            String healthStatus = "GOOD";
                                            if (meanNdvi < 0.5) {
                                                healthStatus = "DANGER";
                                            } else if (meanNdvi < 0.7) {
                                                healthStatus = "WARNING";
                                            }
                                            
                                            selectedPlot.setAvgNdvi(meanNdvi);
                                            selectedPlot.setHealthStatus(healthStatus);
                                            
                                            // Save to DB in background
                                            new Thread(() -> db.plotDao().update(selectedPlot)).start();
                                            
                                            getActivity().runOnUiThread(() -> {
                                                if (!isGeeLayerActive) return;
                                                
                                                // Cache new metadata
                                                prefs.edit()
                                                        .putLong("gee_last_check_time_" + plotId, System.currentTimeMillis())
                                                        .putLong("gee_latest_image_time_" + plotId, latestImageTime)
                                                        .putString("gee_tile_template_" + plotId, tileUrlTemplate)
                                                        .putLong("gee_tile_expiry_" + plotId, System.currentTimeMillis() + 2 * 60 * 60 * 1000)
                                                        .apply();
                                                
                                                String dateStr = formatDate(latestImageTime);
                                                Toast.makeText(getContext(), String.format("Đã cập nhật ảnh vệ tinh %s! NDVI trung bình: %.2f", dateStr, meanNdvi), Toast.LENGTH_LONG).show();
                                                updatePlotSelectionDetails(selectedPlot);
                                                reloadMapData(); // Redraw polygons with new health status color!
                                                
                                                EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), tileUrlTemplate, singlePlotList, geeClient);
                                                geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                                                        .tileProvider(tileProvider)
                                                        .transparency(0.1f));
                                            });
                                        }

                                        @Override
                                        public void onError(String errorMsg) {
                                            if (getActivity() == null) return;
                                            getActivity().runOnUiThread(() -> {
                                                Toast.makeText(getContext(), "Không tính được NDVI thực tế: " + errorMsg + ". Sử dụng giả lập.", Toast.LENGTH_SHORT).show();
                                                triggerMockGeeeMode(selectedPlot);
                                                
                                                // Still cache metadata and load overlay
                                                prefs.edit()
                                                        .putLong("gee_last_check_time_" + plotId, System.currentTimeMillis())
                                                        .putLong("gee_latest_image_time_" + plotId, latestImageTime)
                                                        .putString("gee_tile_template_" + plotId, tileUrlTemplate)
                                                        .putLong("gee_tile_expiry_" + plotId, System.currentTimeMillis() + 2 * 60 * 60 * 1000)
                                                        .apply();
                                                
                                                EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), tileUrlTemplate, singlePlotList, geeClient);
                                                geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                                                        .tileProvider(tileProvider)
                                                        .transparency(0.1f));
                                            });
                                        }
                                    });
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    showError(errorMessage);
                                }
                            });
                        }
                    });
                }

                @Override
                public void onError(final String errorMessage) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Không kết nối được GEE API. Kích hoạt Mock GEE.", Toast.LENGTH_SHORT).show();
                        triggerMockGeeeMode(selectedPlot);
                        isGeeLayerActive = false;
                        binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
                    });
                }
            });
            return;
        }

        // Case 3: Within check interval but the template url is expired (we reuse the cached image timestamp,
        // and fetch a new session from GEE without clearing the cache)
        Toast.makeText(getContext(), "Đang mở phiên bản đồ GEE cho ảnh vệ tinh chụp ngày " + formatDate(cachedLatestImageTime) + "...", Toast.LENGTH_SHORT).show();
        
        geeClient.getNdviTileUrl(singlePlotList, finalMinLat - 0.01, finalMinLng - 0.01, finalMaxLat + 0.01, finalMaxLng + 0.01, new EarthEngineClient.Callback() {
            @Override
            public void onSuccess(final String tileUrlTemplate) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (!isGeeLayerActive) return;
                    
                    // Save new template (valid for 2 hours)
                    prefs.edit()
                            .putString("gee_tile_template_" + plotId, tileUrlTemplate)
                            .putLong("gee_tile_expiry_" + plotId, System.currentTimeMillis() + 2 * 60 * 60 * 1000)
                            .apply();
                    
                    EarthEngineTileProvider tileProvider = new EarthEngineTileProvider(requireContext(), tileUrlTemplate, singlePlotList, geeClient);
                    geeTileOverlay = googleMap.addTileOverlay(new TileOverlayOptions()
                            .tileProvider(tileProvider)
                            .transparency(0.1f));
                    
                    Toast.makeText(getContext(), "Đã nạp bản đồ lưu cục bộ từ GEE!", Toast.LENGTH_SHORT).show();
                    updatePlotSelectionDetails(selectedPlot);
                });
            }

            @Override
            public void onError(final String errorMessage) {
                showError(errorMessage);
            }
        });
    }

    private void triggerMockGeeeMode(Plot plot) {
        if (plot == null) return;
        double mockNdvi = 0.5 + (Math.abs(plot.getName().hashCode()) % 36) / 100.0; // Stable between 0.50 and 0.85
        String healthStatus = "GOOD";
        if (mockNdvi < 0.5) {
            healthStatus = "DANGER";
        } else if (mockNdvi < 0.7) {
            healthStatus = "WARNING";
        }
        plot.setAvgNdvi(mockNdvi);
        plot.setHealthStatus(healthStatus);
        
        new Thread(() -> {
            db.plotDao().update(plot);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Chế độ Mock GEE: Đã giả lập NDVI ranh giới lô đất!", Toast.LENGTH_SHORT).show();
                    updatePlotSelectionDetails(plot);
                    reloadMapData();
                });
            }
        }).start();
    }

    private void showError(String message) {
        isGeeLayerActive = false;
        binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        updatePlotSelectionDetails(selectedPlot);
    }

    // --- Dynamic Details Display ---

    private void updatePlotSelectionDetails(Plot plot) {
        if (plot == null) return;
        int cropCount = db.cropDao().getCropCountForPlot(plot.getId());
        String details = String.format("Diện tích: %.1f m² | Số lượng: %d cây", plot.getAreaSquareMeters(), cropCount);
        if (plot.getAvgNdvi() > 0) {
            details += String.format(" | NDVI: %.2f", plot.getAvgNdvi());
        }
        if (isGeeLayerActive) {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("gee_cache", android.content.Context.MODE_PRIVATE);
            long cachedLatestImageTime = prefs.getLong("gee_latest_image_time_" + plot.getId(), 0);
            if (cachedLatestImageTime > 0) {
                details += " | Ảnh vệ tinh: " + formatDate(cachedLatestImageTime);
            } else {
                details += " | Ảnh vệ tinh: Đang tải...";
            }
        }
        binding.txtSelectionDetails.setText(details);
    }

    private void showSelectedPlot(Plot plot) {
        selectedPlot = plot;
        selectedCrop = null;

        binding.selectionPanel.setVisibility(View.VISIBLE);
        binding.txtSelectionTitle.setText(plot.getName());
        updatePlotSelectionDetails(plot);
        
        binding.txtSelectionStatus.setText("LÔ ĐẤT");
        binding.txtSelectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary));
        binding.btnSelectionAction.setText("Xem Chi Tiết Lô Đất");

        // Dynamic reload GEE layer for this specific plot if the layer is active
        if (isGeeLayerActive) {
            loadGeeOverlay();
        }
    }

    private void showSelectedCrop(Crop crop) {
        selectedCrop = crop;
        selectedPlot = null;

        binding.selectionPanel.setVisibility(View.VISIBLE);
        binding.txtSelectionTitle.setText(crop.getName());
        binding.txtSelectionDetails.setText(String.format("Loại: %s | Ngày trồng: %s", crop.getType(), formatDate(crop.getPlantingDate())));
        
        binding.txtSelectionStatus.setText(crop.getStatus());
        if ("HEALTHY".equals(crop.getStatus())) {
            binding.txtSelectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.health_good));
        } else if ("STRESSED".equals(crop.getStatus())) {
            binding.txtSelectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.health_warning));
        } else {
            binding.txtSelectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.health_danger));
        }
        
        binding.btnSelectionAction.setText("Xem Chi Tiết Cây");
        binding.btnSelectionAction.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), com.mobilegis.cropwatcher.ui.crops.CropDetailActivity.class);
            intent.putExtra("CROP_ID", crop.getId());
            startActivity(intent);
        });
    }

    private void hideSelectionCard() {
        binding.selectionPanel.setVisibility(View.GONE);
        selectedPlot = null;
        selectedCrop = null;
        
        // Remove active GEE tiles overlay on deselecting plot
        if (isGeeLayerActive) {
            if (geeTileOverlay != null) {
                geeTileOverlay.remove();
                geeTileOverlay = null;
            }
            isGeeLayerActive = false;
            binding.btnToggleGee.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        }
    }

    // --- Helper JSON parsers ---

    private List<LatLng> parsePoints(String json) {
        List<LatLng> list = new ArrayList<>();
        try {
            double[][] pts = new Gson().fromJson(json, double[][].class);
            for (double[] pt : pts) {
                list.add(new LatLng(pt[0], pt[1]));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private String formatDate(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }
}
