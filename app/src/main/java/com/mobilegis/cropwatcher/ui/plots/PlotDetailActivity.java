package com.mobilegis.cropwatcher.ui.plots;

import android.content.Context;
import android.content.Intent;
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

import com.mobilegis.cropwatcher.R;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Crop;
import com.mobilegis.cropwatcher.data.entity.Plot;
import com.mobilegis.cropwatcher.databinding.ActivityPlotDetailBinding;
import com.mobilegis.cropwatcher.databinding.ItemCropBinding;
import com.mobilegis.cropwatcher.ui.crops.CropDetailActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlotDetailActivity extends AppCompatActivity {
    private ActivityPlotDetailBinding binding;
    private AppDatabase db;
    private int plotId;
    private CropsListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlotDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AppDatabase.getDatabase(this);
        plotId = getIntent().getIntExtra("PLOT_ID", -1);

        if (plotId == -1) {
            finish();
            return;
        }

        setupViews();
        loadPlotDetails();
    }

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> finish());
        
        binding.rvCrops.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CropsListAdapter(new ArrayList<>(), this::onCropClicked);
        binding.rvCrops.setAdapter(adapter);
    }

    private void loadPlotDetails() {
        Plot plot = db.plotDao().getPlotById(plotId);
        if (plot == null) return;

        binding.txtToolbarTitle.setText("Lô đất: " + plot.getName());
        binding.txtDetailPlotName.setText(plot.getName());
        binding.txtDetailPlotDesc.setText(plot.getDescription().isEmpty() ? "Không có mô tả" : plot.getDescription());
        binding.txtDetailPlotArea.setText(String.format("%.1f m²", plot.getAreaSquareMeters()));

        // Bind health state
        int color = ContextCompat.getColor(this, R.color.health_good);
        String statusText = "Tốt (NDVI " + plot.getAvgNdvi() + ")";
        if ("WARNING".equals(plot.getHealthStatus())) {
            color = ContextCompat.getColor(this, R.color.health_warning);
            statusText = "Cần theo dõi (NDVI " + plot.getAvgNdvi() + ")";
        } else if ("DANGER".equals(plot.getHealthStatus())) {
            color = ContextCompat.getColor(this, R.color.health_danger);
            statusText = "Cảnh báo nguy cơ (NDVI " + plot.getAvgNdvi() + ")";
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
                return android.text.format.DateFormat.format("dd/MM/yyyy", new java.util.Date(timestamp)).toString();
            }
        }
    }
}
