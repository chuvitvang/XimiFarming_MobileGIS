package com.mobilegis.cropwatcher.ui.analysis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Crop;
import com.mobilegis.cropwatcher.data.entity.CropLog;
import com.mobilegis.cropwatcher.databinding.FragmentAnalysisBinding;
import com.mobilegis.cropwatcher.utils.GisHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AnalysisFragment extends Fragment {
    private static final String TAG = "AnalysisFragment";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 2001;

    private FragmentAnalysisBinding binding;
    private AppDatabase db;
    private ImageCapture imageCapture;
    private Bitmap processedBitmap;
    private GisHelper.AnalysisResult currentResult;
    private List<Crop> cropsList = new ArrayList<>();
    
    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processSelectedImage(imageUri);
                    }
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAnalysisBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());

        setupListeners();
        checkCameraPermission();
        loadCropsSpinner();
    }

    private void setupListeners() {
        binding.btnShutter.setOnClickListener(v -> takePhoto());
        binding.btnGallery.setOnClickListener(v -> openGallery());
        binding.btnRetryAnalysis.setOnClickListener(v -> resetUiAndResumeCamera());
        binding.btnSaveAnalysis.setOnClickListener(v -> saveAnalysisResult());
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(getContext(), "Ứng dụng cần quyền Camera để chụp lá cây phân tích!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (Exception e) {
                Log.e(TAG, "CameraX initialization failed", e);
                // Fallback indication
                Toast.makeText(getContext(), "Không mở được camera (Chạy trên máy ảo?). Có thể tải ảnh lên từ Thư viện.", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            // Camera failed or emulator environment fallback: generate simulated leaf photo
            Log.w(TAG, "Camera not ready, using simulated leaf fallback");
            Bitmap simulatedLeaf = createSimulatedLeafBitmap();
            processBitmap(simulatedLeaf);
            return;
        }

        File cacheDir = requireContext().getCacheDir();
        File photoFile = new File(cacheDir, "temp_leaf_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        binding.viewScanLine.setVisibility(View.VISIBLE);
        // Basic scanline visual effect animation simulation (translating Y)
        binding.viewScanLine.animate().translationY(binding.cameraPreview.getHeight()).setDuration(1000).withEndAction(() -> {
            binding.viewScanLine.setVisibility(View.GONE);
            binding.viewScanLine.setTranslationY(0);
        }).start();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(requireContext()), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                if (bitmap != null) {
                    processBitmap(bitmap);
                } else {
                    Toast.makeText(getContext(), "Lỗi giải mã hình ảnh", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed", exception);
                Toast.makeText(getContext(), "Lỗi chụp ảnh: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        galleryLauncher.launch(intent);
    }

    private void processSelectedImage(Uri uri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                processBitmap(bitmap);
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Lỗi tải ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void processBitmap(Bitmap bitmap) {
        this.processedBitmap = bitmap;
        
        // Analyze leaf health using Excess Green Index
        currentResult = GisHelper.analyzeLeafImage(bitmap);

        // Update UI state
        binding.cameraPreview.setVisibility(View.GONE);
        binding.imgCapturedPreview.setVisibility(View.VISIBLE);
        binding.imgCapturedPreview.setImageBitmap(currentResult.heatmapBitmap);
        
        binding.cardAnalysisResult.setVisibility(View.VISIBLE);
        binding.txtExgValue.setText(String.format("ExG: %+.2f", currentResult.averageExg));
        binding.txtExgLabel.setText(currentResult.statusLabel);
        binding.txtExgDesc.setText(currentResult.statusDesc);

        // Map status color
        int statusColor = Color.RED;
        if (currentResult.averageExg > 0.25) {
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_good);
        } else if (currentResult.averageExg > 0.08) {
            statusColor = ContextCompat.getColor(requireContext(), R.color.health_warning);
        }
        binding.txtExgValue.setTextColor(statusColor);
    }

    private void loadCropsSpinner() {
        cropsList = db.cropDao().getAllCrops();
        List<String> cropNames = new ArrayList<>();
        
        for (Crop crop : cropsList) {
            cropNames.add(crop.getName() + " (" + crop.getType() + ")");
        }

        if (cropNames.isEmpty()) {
            cropNames.add("Chưa có cây trồng nào");
            binding.btnSaveAnalysis.setEnabled(false);
        } else {
            binding.btnSaveAnalysis.setEnabled(true);
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, cropNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerCrops.setAdapter(spinnerAdapter);
    }

    private void saveAnalysisResult() {
        if (cropsList.isEmpty() || currentResult == null) return;
        
        int selectedPosition = binding.spinnerCrops.getSelectedItemPosition();
        Crop selectedCrop = cropsList.get(selectedPosition);

        // Save image locally to persistent storage
        String photoPath = "";
        try {
            File picturesDir = requireContext().getExternalFilesDir("Pictures");
            File file = new File(picturesDir, "crop_" + selectedCrop.getId() + "_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            photoPath = file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save photo persistently", e);
        }

        // Determine health label based on ExG
        String healthStatus = "HEALTHY";
        if (currentResult.averageExg <= 0.08) {
            healthStatus = "DISEASED";
        } else if (currentResult.averageExg <= 0.25) {
            healthStatus = "STRESSED";
        }

        // Insert CropLog
        CropLog log = new CropLog(
                selectedCrop.getId(),
                System.currentTimeMillis(),
                healthStatus,
                currentResult.statusLabel + ": " + currentResult.statusDesc,
                photoPath,
                currentResult.averageExg
        );
        db.cropLogDao().insert(log);

        // Update Crop entity general status
        selectedCrop.setStatus(healthStatus);
        db.cropDao().update(selectedCrop);

        Toast.makeText(getContext(), "Đã lưu kết quả phân tích cho " + selectedCrop.getName(), Toast.LENGTH_SHORT).show();
        resetUiAndResumeCamera();
    }

    private void resetUiAndResumeCamera() {
        binding.imgCapturedPreview.setVisibility(View.GONE);
        binding.cardAnalysisResult.setVisibility(View.GONE);
        binding.cameraPreview.setVisibility(View.VISIBLE);
        
        if (processedBitmap != null) {
            processedBitmap.recycle();
            processedBitmap = null;
        }
        currentResult = null;
        loadCropsSpinner(); // Reload spinner in case new crops were added
    }

    /**
     * Generates a mock leaf bitmap for emulator testing where CameraX might fail
     */
    private Bitmap createSimulatedLeafBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.parseColor("#4E342E")); // Soil Brown background

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Draw a simulated leaf polygon
        paint.setColor(Color.parseColor("#388E3C")); // Leaf Green
        android.graphics.Path leafPath = new android.graphics.Path();
        leafPath.moveTo(200, 50);
        leafPath.cubicTo(320, 100, 320, 300, 200, 350);
        leafPath.cubicTo(80, 300, 80, 100, 200, 50);
        leafPath.close();
        canvas.drawPath(leafPath, paint);

        // Draw some yellow spots representing nitrogen deficiency (stressed areas)
        paint.setColor(Color.parseColor("#FBC02D"));
        canvas.drawCircle(220, 150, 15, paint);
        canvas.drawCircle(150, 220, 20, paint);

        return bitmap;
    }
}
