package com.mobilegis.cropwatcher.ui.crops;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mobilegis.cropwatcher.R;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Crop;
import com.mobilegis.cropwatcher.data.entity.CropLog;
import com.mobilegis.cropwatcher.databinding.ActivityCropDetailBinding;
import com.mobilegis.cropwatcher.databinding.ItemCropLogBinding;

import java.util.ArrayList;
import java.util.List;

public class CropDetailActivity extends AppCompatActivity {
    private ActivityCropDetailBinding binding;
    private AppDatabase db;
    private int cropId;
    private CropLogsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCropDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AppDatabase.getDatabase(this);
        cropId = getIntent().getIntExtra("CROP_ID", -1);

        if (cropId == -1) {
            finish();
            return;
        }

        setupViews();
        loadCropDetails();
    }

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnAddLog.setOnClickListener(v -> showAddLogDialog());

        binding.rvLogs.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CropLogsAdapter(new ArrayList<>());
        binding.rvLogs.setAdapter(adapter);
    }

    private void loadCropDetails() {
        Crop crop = db.cropDao().getCropById(cropId);
        if (crop == null) return;

        binding.txtToolbarTitle.setText("Chi tiết: " + crop.getName());
        binding.txtDetailCropName.setText(crop.getName());
        binding.txtDetailCropType.setText("Loại: " + crop.getType());
        binding.txtDetailCropDate.setText(formatDate(crop.getPlantingDate()));
        binding.txtDetailCropGps.setText(String.format("%.5f, %.5f", crop.getLatitude(), crop.getLongitude()));

        // Load logs
        List<CropLog> logs = db.cropLogDao().getLogsForCrop(cropId);
        if (logs.isEmpty()) {
            binding.txtEmptyLogs.setVisibility(View.VISIBLE);
            binding.rvLogs.setVisibility(View.GONE);
        } else {
            binding.txtEmptyLogs.setVisibility(View.GONE);
            binding.rvLogs.setVisibility(View.VISIBLE);
            adapter.setLogsList(logs);
        }
    }

    private void showAddLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Thêm Nhật Ký Thủ Công");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        final EditText notesInput = new EditText(this);
        notesInput.setHint("Ghi chú chăm sóc / tình trạng");
        layout.addView(notesInput);

        final Spinner statusSpinner = new Spinner(this);
        String[] statuses = {"Khỏe mạnh (HEALTHY)", "Bị stress (STRESSED)", "Nhiễm bệnh (DISEASED)"};
        ArrayAdapter<String> statusAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, statuses);
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusSpinner.setAdapter(statusAdapter);
        layout.addView(statusSpinner);

        builder.setView(layout);

        builder.setPositiveButton("Lưu", (dialog, which) -> {
            String notes = notesInput.getText().toString().trim();
            if (notes.isEmpty()) notes = "Cập nhật định kỳ";

            int statusPos = statusSpinner.getSelectedItemPosition();
            String status = "HEALTHY";
            if (statusPos == 1) {
                status = "STRESSED";
            } else if (statusPos == 2) {
                status = "DISEASED";
            }

            // Save log
            CropLog log = new CropLog(cropId, System.currentTimeMillis(), status, notes, "");
            db.cropLogDao().insert(log);

            // Update crop overall status
            Crop crop = db.cropDao().getCropById(cropId);
            if (crop != null) {
                crop.setStatus(status);
                db.cropDao().update(crop);
            }

            Toast.makeText(this, "Đã thêm nhật ký chăm sóc", Toast.LENGTH_SHORT).show();
            loadCropDetails();
        });

        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private String formatDate(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }



    // --- Timeline Logs Adapter ---

    private static class CropLogsAdapter extends RecyclerView.Adapter<CropLogsAdapter.LogViewHolder> {
        private List<CropLog> logsList;

        public CropLogsAdapter(List<CropLog> logsList) {
            this.logsList = logsList;
        }

        public void setLogsList(List<CropLog> logs) {
            this.logsList = logs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemCropLogBinding itemBinding = ItemCropLogBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new LogViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            CropLog log = logsList.get(position);
            holder.bind(log);
        }

        @Override
        public int getItemCount() {
            return logsList.size();
        }

        static class LogViewHolder extends RecyclerView.ViewHolder {
            private final ItemCropLogBinding binding;
            private final Context context;

            public LogViewHolder(ItemCropLogBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                this.context = binding.getRoot().getContext();
            }

            public void bind(CropLog log) {
                binding.txtLogDate.setText(formatDate(log.getDate()));
                binding.txtLogNotes.setText(log.getNotes());

                String statusStr = log.getStatus();
                String statusText = "Khỏe mạnh";
                int color = ContextCompat.getColor(context, R.color.health_good);
                
                if ("STRESSED".equals(statusStr)) {
                    statusText = "Bị stress";
                    color = ContextCompat.getColor(context, R.color.health_warning);
                } else if ("DISEASED".equals(statusStr)) {
                    statusText = "Nhiễm bệnh";
                    color = ContextCompat.getColor(context, R.color.health_danger);
                }
                
                binding.txtLogIndex.setText(statusText);
                binding.txtLogIndex.setTextColor(color);
            }

            private String formatDate(long timestamp) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault());
                return sdf.format(new java.util.Date(timestamp));
            }
        }
    }
}
