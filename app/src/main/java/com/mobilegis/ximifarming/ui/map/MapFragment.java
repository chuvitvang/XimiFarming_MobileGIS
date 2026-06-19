package com.mobilegis.ximifarming.ui.map;

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
import com.mobilegis.ximifarming.R;
import com.mobilegis.ximifarming.data.AppDatabase;
import com.mobilegis.ximifarming.data.entity.Crop;
import com.mobilegis.ximifarming.data.entity.Plot;
import com.mobilegis.ximifarming.databinding.FragmentMapBinding;
import com.mobilegis.ximifarming.gee.EarthEngineClient;
import com.mobilegis.ximifarming.gee.EarthEngineTileProvider;
import com.mobilegis.ximifarming.ui.plots.PlotDetailActivity;
import com.mobilegis.ximifarming.utils.GisHelper;

import java.util.ArrayList;
import java.util.List;

public class MapFragment extends Fragment implements OnMapReadyCallback {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    
    private FragmentMapBinding binding;
    private GoogleMap googleMap;
    private AppDatabase db;
    private EarthEngineClient geeClient;
    
    // Map states
    private enum MapMode { NORMAL, DRAW_PLOT }
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
    private Long pendingPlotIdToFocus = null;

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

        // Toggle Layers Menu Popup
        binding.btnLayersToggle.setOnClickListener(v -> {
            if (binding.cardMapLayers.getVisibility() == View.VISIBLE) {
                binding.cardMapLayers.setVisibility(View.GONE);
            } else {
                binding.cardMapLayers.setVisibility(View.VISIBLE);
            }
        });

        // Layer selection: Satellite
        binding.laySatellite.setOnClickListener(v -> {
            isGeeLayerActive = false;
            if (geeTileOverlay != null) {
                geeTileOverlay.remove();
                geeTileOverlay = null;
            }
            if (googleMap != null) {
                googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
            }
            binding.cardMapLayers.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Đã bật lớp Ảnh Vệ Tinh", Toast.LENGTH_SHORT).show();
            // Reset layers button tint to white
            binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            binding.btnLayersToggle.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
            if (selectedPlot != null) {
                updatePlotSelectionDetails(selectedPlot);
            }
        });

        // Layer selection: NDVI
        binding.layNdvi.setOnClickListener(v -> {
            isGeeLayerActive = true;
            if (googleMap != null) {
                googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
            }
            binding.cardMapLayers.setVisibility(View.GONE);
            loadGeeOverlay();
            // Highlight layers button tint to green
            binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
            binding.btnLayersToggle.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        });

        // Custom Zoom In/Out
        binding.btnZoomIn.setOnClickListener(v -> {
            if (googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.zoomIn());
            }
        });

        binding.btnZoomOut.setOnClickListener(v -> {
            if (googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.zoomOut());
            }
        });

        // Custom My Location
        binding.btnMyLocation.setOnClickListener(v -> {
            if (googleMap != null) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    android.location.Location myLoc = googleMap.getMyLocation();
                    if (myLoc != null) {
                        LatLng latLng = new LatLng(myLoc.getLatitude(), myLoc.getLongitude());
                        googleMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
                    } else {
                        Toast.makeText(getContext(), "Đang tìm tín hiệu GPS...", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
                }
            }
        });

        // Close Selection Card
        binding.btnSelectionClose.setOnClickListener(v -> hideSelectionCard());

        // Action on Selection Card
        binding.btnSelectionAction.setOnClickListener(v -> {
            if (selectedPlot != null) {
                Intent intent = new Intent(getActivity(), PlotDetailActivity.class);
                intent.putExtra("PLOT_ID", selectedPlot.getId());
                startActivity(intent);
            } else if (selectedCrop != null) {
                Intent intent = new Intent(getActivity(), com.mobilegis.ximifarming.ui.crops.CropDetailActivity.class);
                intent.putExtra("CROP_ID", selectedCrop.getId());
                startActivity(intent);
            }
        });

        binding.btnHeaderSync.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Đang tiến hành đồng bộ với Supabase...", Toast.LENGTH_SHORT).show();
            com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().syncData(requireContext(), new com.mobilegis.ximifarming.supabase.SupabaseClient.SyncCallback() {
                @Override
                public void onSuccess() {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), "Đồng bộ dữ liệu thành công!", Toast.LENGTH_LONG).show();
                            reloadMapData();
                        });
                    }
                }

                @Override
                public void onError(String errorMsg) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), "Đồng bộ thất bại: " + errorMsg, Toast.LENGTH_LONG).show();
                        });
                    }
                }
            });
        });

        binding.btnHeaderSearch.setOnClickListener(v -> Toast.makeText(getContext(), "Chức năng tìm kiếm sẽ khả dụng trong phiên bản sau", Toast.LENGTH_SHORT).show());
        binding.btnHeaderNotify.setOnClickListener(v -> Toast.makeText(getContext(), "Không có thông báo mới", Toast.LENGTH_SHORT).show());
        binding.btnHeaderProfile.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                .setTitle("Đăng xuất tài khoản")
                .setMessage("Bạn có chắc chắn muốn đăng xuất tài khoản XimiFarming hiện tại không?")
                .setPositiveButton("Đăng xuất", (dialog, which) -> {
                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE);
                    prefs.edit().clear().apply();
                    com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().setAccessToken(null);

                    Intent intent = new Intent(getActivity(), com.mobilegis.ximifarming.ui.auth.LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                })
                .setNegativeButton("Hủy", null)
                .show();
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        this.googleMap = map;
        
        // Setup Map settings
        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        
        // Center camera in Ho Chi Minh City/Vietnam default agricultural zone
        LatLng defaultLoc = new LatLng(10.8231, 106.6297);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 14f));

        checkLocationPermissions();
        
        // Load existing GIS data (Polygons + Crop Markers)
        reloadMapData();

        if (pendingPlotIdToFocus != null) {
            focusOnPlot(pendingPlotIdToFocus);
            pendingPlotIdToFocus = null;
        }

        // Map Click Listener for drawing/placing items
        googleMap.setOnMapClickListener(latLng -> {
            if (currentMode == MapMode.DRAW_PLOT) {
                handleDrawPlotClick(latLng);
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

        new Thread(() -> {
            final List<Plot> plots = db.plotDao().getAllPlots();
            final List<Crop> crops = db.cropDao().getAllCrops();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (googleMap == null) return;

                    // Clear existing overlays
                    for (Polygon p : activePolygons) p.remove();
                    activePolygons.clear();
                    
                    for (Marker m : activeCropMarkers) m.remove();
                    activeCropMarkers.clear();

                    // Load Plots
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

                    // Load Crops
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
                });
            }
        }).start();
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

            // Save to DB in background
            final Plot newPlot = new Plot(name, desc, jsonPoints, area, "GOOD", 0.65);
            final String finalName = name;
            new Thread(() -> {
                db.plotDao().insert(newPlot);
                
                // Tự động đồng bộ lên Supabase
                com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().syncData(requireContext(), new com.mobilegis.ximifarming.supabase.SupabaseClient.SyncCallback() {
                    @Override
                    public void onSuccess() {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "Đã tự động đồng bộ lên Supabase!", Toast.LENGTH_SHORT).show();
                                reloadMapData();
                            });
                        }
                    }

                    @Override
                    public void onError(String errorMsg) {
                        android.util.Log.e("MapFragment", "Tự động đồng bộ lỗi: " + errorMsg);
                    }
                });

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Đã lưu lô đất: " + finalName, Toast.LENGTH_SHORT).show();
                        cancelDrawingMode();
                        reloadMapData();
                    });
                }
            }).start();
        });

        builder.setNegativeButton("Hủy", (dialog, which) -> cancelDrawingMode());
        builder.show();
    }

    private void cancelDrawingMode() {
        currentMode = MapMode.NORMAL;
        
        // Reset buttons states
        binding.btnDrawPlot.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        binding.btnDrawPlot.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_edit));

        binding.instructionPanel.setVisibility(View.GONE);
        
        // Clear drawing geometry
        for (Marker m : drawMarkers) m.remove();
        drawMarkers.clear();
        drawPoints.clear();
        if (drawPolyline != null) { drawPolyline.remove(); drawPolyline = null; }
        if (drawPolygon != null) { drawPolygon.remove(); drawPolygon = null; }
    }

    // --- GEE Integration Overlay logic ---

    private void loadGeeOverlay() {
        if (googleMap == null) return;
        if (geeTileOverlay != null) geeTileOverlay.remove();

        if (selectedPlot == null) {
            Toast.makeText(getContext(), "Vui lòng chạm chọn một lô đất trên bản đồ trước để xem ảnh vệ tinh NDVI!", Toast.LENGTH_LONG).show();
            isGeeLayerActive = false;
            binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            binding.btnLayersToggle.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
            return;
        }

        // Update plot selection details to show "Đang tải..." or cached date if not checked yet
        updatePlotSelectionDetails(selectedPlot);

        final long plotId = selectedPlot.getId();
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
            binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
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
                    .transparency(0.35f));
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
                                        .transparency(0.35f));
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
                                                    .transparency(0.35f));
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
                                            
                                            final String coordsJson = selectedPlot != null ? selectedPlot.getCoordinatesJson() : null;
                                            if (coordsJson == null) return;

                                            String healthStatus = "GOOD";
                                            if (meanNdvi < 0.5) {
                                                healthStatus = "DANGER";
                                            } else if (meanNdvi < 0.7) {
                                                healthStatus = "WARNING";
                                            }
                                            
                                            final String finalHealthStatus = healthStatus;
                                            
                                            // Save to DB in background
                                            new Thread(() -> {
                                                Plot currentDbPlot = db.plotDao().getPlotByCoordinates(coordsJson);
                                                if (currentDbPlot != null) {
                                                    currentDbPlot.setAvgNdvi(meanNdvi);
                                                    currentDbPlot.setHealthStatus(finalHealthStatus);
                                                    db.plotDao().update(currentDbPlot);
                                                    selectedPlot = currentDbPlot;
                                                    if (currentDbPlot.isSynced()) {
                                                        com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().updatePlotOnSupabase(currentDbPlot, getNdviSyncCallback());
                                                    }
                                                } else {
                                                    selectedPlot.setAvgNdvi(meanNdvi);
                                                    selectedPlot.setHealthStatus(finalHealthStatus);
                                                    db.plotDao().update(selectedPlot);
                                                    if (selectedPlot.isSynced()) {
                                                        com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().updatePlotOnSupabase(selectedPlot, getNdviSyncCallback());
                                                    }
                                                }
                                            }).start();
                                            
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
                                                        .transparency(0.35f));
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
                                                        .transparency(0.35f));
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
                        binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
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
                            .transparency(0.35f));
                    
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
        
        final double finalMockNdvi = mockNdvi;
        final String finalHealthStatus = healthStatus;
        final String coordsJson = plot.getCoordinatesJson();
        
        new Thread(() -> {
            Plot currentDbPlot = db.plotDao().getPlotByCoordinates(coordsJson);
            if (currentDbPlot != null) {
                currentDbPlot.setAvgNdvi(finalMockNdvi);
                currentDbPlot.setHealthStatus(finalHealthStatus);
                db.plotDao().update(currentDbPlot);
                selectedPlot = currentDbPlot;
                if (currentDbPlot.isSynced()) {
                    com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().updatePlotOnSupabase(currentDbPlot, getNdviSyncCallback());
                }
            } else {
                plot.setAvgNdvi(finalMockNdvi);
                plot.setHealthStatus(finalHealthStatus);
                db.plotDao().update(plot);
                if (plot.isSynced()) {
                    com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().updatePlotOnSupabase(plot, getNdviSyncCallback());
                }
            }
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Chế độ Mock GEE: Đã giả lập NDVI ranh giới lô đất!", Toast.LENGTH_SHORT).show();
                    updatePlotSelectionDetails(selectedPlot != null ? selectedPlot : plot);
                    reloadMapData();
                });
            }
        }).start();
    }

    private com.mobilegis.ximifarming.supabase.SupabaseClient.SyncCallback getNdviSyncCallback() {
        return new com.mobilegis.ximifarming.supabase.SupabaseClient.SyncCallback() {
            @Override
            public void onSuccess() {
                android.util.Log.d("MapFragment", "Đã cập nhật NDVI lên Supabase thành công!");
            }

            @Override
            public void onError(String errorMsg) {
                android.util.Log.e("MapFragment", "Lỗi cập nhật NDVI lên Supabase: " + errorMsg);
            }
        };
    }

    private void showError(String message) {
        isGeeLayerActive = false;
        binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
        binding.btnLayersToggle.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        updatePlotSelectionDetails(selectedPlot);
    }

    // --- Dynamic Details Display ---

    private void updatePlotSelectionDetails(Plot plot) {
        if (plot == null) return;
        
        // Populate Grid Details
        binding.txtGridName.setText(plot.getName());
        
        double ha = plot.getAreaSquareMeters() / 10000.0;
        binding.txtGridArea.setText(String.format("%.2f Ha", ha));
        
        // Get Crop Type
        new Thread(() -> {
            List<Crop> crops = db.cropDao().getCropsForPlot(plot.getId());
            String cropText = "Không có cây";
            if (!crops.isEmpty()) {
                cropText = crops.get(0).getType();
            }
            final String finalCropText = cropText;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    binding.txtGridCrop.setText(finalCropText);
                });
            }
        }).start();
        
        // Health Status translation
        String statusText = "Bình thường";
        int statusColor = ContextCompat.getColor(requireContext(), R.color.health_good);
        if ("WARNING".equals(plot.getHealthStatus())) {
            statusText = "Cần theo dõi";
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_warning);
        } else if ("DANGER".equals(plot.getHealthStatus())) {
            statusText = "Cảnh báo";
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_danger);
        }
        binding.txtGridStatus.setText(statusText);
        binding.txtGridStatus.setTextColor(statusColor);
        
        // Bind NDVI mini chart
        List<Float> ndviHistory = java.util.Arrays.asList(0.58f, 0.62f, 0.60f, 0.68f, 0.72f, (float) plot.getAvgNdvi());
        List<String> dates = java.util.Arrays.asList("15/04", "22/04", "29/04", "06/05", "13/05", "20/05");
        binding.ndviMiniChart.setData(ndviHistory, dates);
    }

    private void showSelectedPlot(Plot plot) {
        selectedPlot = plot;
        selectedCrop = null;

        binding.selectionPanel.setVisibility(View.VISIBLE);
        binding.txtSelectionTitle.setText(plot.getName());
        
        // Reset layout visibility and labels to Plot context
        binding.layoutChartWeather.setVisibility(View.VISIBLE);
        binding.txtLabelName.setText("Tên Ô Đất");
        binding.txtLabelArea.setText("Diện Tích");
        binding.txtLabelCrop.setText("Cây Trồng");
        binding.txtLabelStatus.setText("Hiện Trạng");
        
        binding.btnSelectionAction.setText("Xem Chi Tiết Lô Đất");
        
        updatePlotSelectionDetails(plot);

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
        
        // Hide NDVI trend and weather layout for crops
        binding.layoutChartWeather.setVisibility(View.GONE);
        
        // Relabel grid titles for crop context
        binding.txtLabelName.setText("Tên Cây");
        binding.txtGridName.setText(crop.getName());
        
        binding.txtLabelArea.setText("Loại Cây");
        binding.txtGridArea.setText(crop.getType());
        
        binding.txtLabelCrop.setText("Ngày Trồng");
        binding.txtGridCrop.setText(formatDate(crop.getPlantingDate()));
        
        binding.txtLabelStatus.setText("Tình Trạng");
        String statusText = crop.getStatus();
        int statusColor = ContextCompat.getColor(requireContext(), R.color.health_good);
        if ("STRESSED".equals(crop.getStatus())) {
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_warning);
        } else if ("DISEASED".equals(crop.getStatus())) {
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_danger);
        }
        binding.txtGridStatus.setText(statusText);
        binding.txtGridStatus.setTextColor(statusColor);
        
        binding.btnSelectionAction.setText("Xem Chi Tiết Cây");
        binding.btnSelectionAction.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), com.mobilegis.ximifarming.ui.crops.CropDetailActivity.class);
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
            binding.btnLayersToggle.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            binding.btnLayersToggle.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.primary));
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

    public void focusOnPlot(long plotId) {
        if (googleMap == null) {
            pendingPlotIdToFocus = plotId;
            return;
        }
        new Thread(() -> {
            Plot plot = db.plotDao().getPlotById(plotId);
            if (plot != null) {
                List<LatLng> points = parsePoints(plot.getCoordinatesJson());
                if (!points.isEmpty()) {
                    double sumLat = 0, sumLng = 0;
                    for (LatLng p : points) {
                        sumLat += p.latitude;
                        sumLng += p.longitude;
                    }
                    LatLng center = new LatLng(sumLat / points.size(), sumLng / points.size());
                    
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (googleMap != null) {
                                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 16f));
                                showSelectedPlot(plot);
                            }
                        });
                    }
                }
            }
        }).start();
    }
}
