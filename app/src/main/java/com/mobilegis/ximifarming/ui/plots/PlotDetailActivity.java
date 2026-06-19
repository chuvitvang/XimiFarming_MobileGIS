package com.mobilegis.ximifarming.ui.plots;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mobilegis.ximifarming.R;
import com.mobilegis.ximifarming.data.AppDatabase;
import com.mobilegis.ximifarming.data.entity.Crop;
import com.mobilegis.ximifarming.data.entity.Plot;
import com.mobilegis.ximifarming.databinding.ActivityPlotDetailBinding;
import com.mobilegis.ximifarming.databinding.ItemCropBinding;
import com.mobilegis.ximifarming.ui.crops.CropDetailActivity;

import androidx.appcompat.app.AlertDialog;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlotDetailActivity extends AppCompatActivity {
    private ActivityPlotDetailBinding binding;
    private AppDatabase db;
    private long plotId;
    private CropsListAdapter adapter;
    private Plot currentPlot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlotDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AppDatabase.getDatabase(this);
        plotId = getIntent().getLongExtra("PLOT_ID", -1L);

        if (plotId == -1L) {
            finish();
            return;
        }

        setupViews();
        loadPlotDetails();
    }

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnDelete.setOnClickListener(v -> confirmDeletePlot());
        
        binding.rvCrops.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CropsListAdapter(new ArrayList<>(), this::onCropClicked);
        binding.rvCrops.setAdapter(adapter);
    }

    private void confirmDeletePlot() {
        if (currentPlot == null) return;
        
        new AlertDialog.Builder(this)
                .setTitle("Xóa lô đất")
                .setMessage("Bạn có chắc chắn muốn xóa lô đất \"" + currentPlot.getName() + "\" không? Các cây trồng và dữ liệu liên quan sẽ bị xóa vĩnh viễn.")
                .setPositiveButton("Xóa", (dialog, which) -> deletePlot())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void deletePlot() {
        if (currentPlot == null) return;
        if (!currentPlot.isSynced()) {
            new Thread(() -> {
                db.plotDao().delete(currentPlot);
                runOnUiThread(() -> {
                    Toast.makeText(PlotDetailActivity.this, "Đã xóa lô đất cục bộ thành công", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }).start();
        } else {
            com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().deletePlotFromSupabase(
                currentPlot.getId(),
                new com.mobilegis.ximifarming.supabase.SupabaseClient.SyncCallback() {
                    @Override
                    public void onSuccess() {
                        new Thread(() -> {
                            db.plotDao().delete(currentPlot);
                            runOnUiThread(() -> {
                                Toast.makeText(PlotDetailActivity.this, "Đã xóa lô đất trực tuyến thành công", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }).start();
                    }

                    @Override
                    public void onError(String errorMsg) {
                        runOnUiThread(() -> {
                            if (errorMsg != null && errorMsg.contains("401")) {
                                Toast.makeText(PlotDetailActivity.this, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.", Toast.LENGTH_LONG).show();
                                SharedPreferences prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE);
                                prefs.edit().clear().apply();
                                com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().setAccessToken(null);

                                Intent intent = new Intent(PlotDetailActivity.this, com.mobilegis.ximifarming.ui.auth.LoginActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            } else {
                                Toast.makeText(PlotDetailActivity.this, "Lỗi xóa lô đất trực tuyến: " + errorMsg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            );
        }
    }

    private void loadPlotDetails() {
        Plot plot = db.plotDao().getPlotById(plotId);
        if (plot == null) return;
        this.currentPlot = plot;

        binding.txtToolbarTitle.setText("Lô đất: " + plot.getName());
        binding.txtDetailPlotName.setText(plot.getName());
        binding.txtDetailPlotDesc.setText(plot.getDescription().isEmpty() ? "Không có mô tả" : plot.getDescription());
        binding.txtDetailPlotArea.setText(String.format("%.1f m²", plot.getAreaSquareMeters()));

        // Bind health state
        int color = ContextCompat.getColor(this, R.color.health_good);
        String statusText = String.format("Tốt (NDVI %.2f)", plot.getAvgNdvi());
        if ("WARNING".equals(plot.getHealthStatus())) {
            color = ContextCompat.getColor(this, R.color.health_warning);
            statusText = String.format("Cần theo dõi (NDVI %.2f)", plot.getAvgNdvi());
        } else if ("DANGER".equals(plot.getHealthStatus())) {
            color = ContextCompat.getColor(this, R.color.health_danger);
            statusText = String.format("Cảnh báo nguy cơ (NDVI %.2f)", plot.getAvgNdvi());
        }
        binding.txtDetailPlotStatus.setText(statusText);
        binding.txtDetailPlotStatus.setTextColor(color);

        // Bind Custom Chart (Sentinel-2 NDVI historical trend)
        List<Float> ndviHistory = Arrays.asList(0.58f, 0.62f, 0.60f, 0.68f, 0.72f, (float) plot.getAvgNdvi());
        List<String> dates = Arrays.asList("15/04", "22/04", "29/04", "06/05", "13/05", "20/05");
        binding.ndviChart.setData(ndviHistory, dates);

        // Load associated Crops
        List<Crop> crops = db.cropDao().getCropsForPlot(plotId);
        if (crops.isEmpty()) {
            binding.txtEmptyCrops.setVisibility(View.VISIBLE);
            binding.rvCrops.setVisibility(View.GONE);
        } else {
            binding.txtEmptyCrops.setVisibility(View.GONE);
            binding.rvCrops.setVisibility(View.VISIBLE);
            adapter.setCropsList(crops);
        }
    }

    private void onCropClicked(Crop crop) {
        Intent intent = new Intent(this, CropDetailActivity.class);
        intent.putExtra("CROP_ID", crop.getId());
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPlotDetails(); // Reload dynamically if crops status changes
    }

    // --- Nested Crops Adapter ---

    private static class CropsListAdapter extends RecyclerView.Adapter<CropsListAdapter.CropViewHolder> {
        public interface OnCropClickListener {
            void onCropClick(Crop crop);
        }

        private List<Crop> cropsList;
        private final OnCropClickListener listener;

        public CropsListAdapter(List<Crop> crops, OnCropClickListener listener) {
            this.cropsList = crops;
            this.listener = listener;
        }

        public void setCropsList(List<Crop> crops) {
            this.cropsList = crops;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CropViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemCropBinding itemBinding = ItemCropBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new CropViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull CropViewHolder holder, int position) {
            Crop crop = cropsList.get(position);
            holder.bind(crop, listener);
        }

        @Override
        public int getItemCount() {
            return cropsList.size();
        }

        static class CropViewHolder extends RecyclerView.ViewHolder {
            private final ItemCropBinding binding;
            private final Context context;

            public CropViewHolder(ItemCropBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                this.context = binding.getRoot().getContext();
            }

            public void bind(Crop crop, OnCropClickListener listener) {
                binding.txtCropName.setText(crop.getName());
                binding.txtCropType.setText("Loại: " + crop.getType() + " | Trồng ngày: " + formatDate(crop.getPlantingDate()));
                
                binding.txtCropStatus.setText(crop.getStatus());
                int color = ContextCompat.getColor(context, R.color.health_good);
                if ("STRESSED".equals(crop.getStatus())) {
                    color = ContextCompat.getColor(context, R.color.health_warning);
                } else if ("DISEASED".equals(crop.getStatus())) {
                    color = ContextCompat.getColor(context, R.color.health_danger);
                }
                binding.txtCropStatus.setTextColor(color);

                itemView.setOnClickListener(v -> listener.onCropClick(crop));
            }

            private String formatDate(long timestamp) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault());
                return sdf.format(new java.util.Date(timestamp));
            }
        }
    }
}
