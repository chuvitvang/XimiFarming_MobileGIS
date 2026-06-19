package com.mobilegis.ximifarming.ui.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mobilegis.ximifarming.MainActivity;
import com.mobilegis.ximifarming.databinding.ActivityLoginBinding;
import com.mobilegis.ximifarming.supabase.SupabaseClient;

public class LoginActivity extends AppCompatActivity {
    private ActivityLoginBinding binding;
    private boolean isLoginMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Kiểm tra xem đã đăng nhập chưa
        SharedPreferences prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE);
        String savedToken = prefs.getString("access_token", null);
        if (savedToken != null) {
            SupabaseClient.getInstance().setAccessToken(savedToken);
            navigateToMain();
            return;
        }

        setupListeners();
    }

    private void setupListeners() {
        // Chuyển đổi qua lại giữa Đăng nhập và Đăng ký
        binding.txtSwitchMode.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            if (isLoginMode) {
                binding.txtAuthTitle.setText("Đăng Nhập");
                binding.btnAuthAction.setText("Đăng Nhập");
                binding.txtSwitchMode.setText("Chưa có tài khoản? Đăng ký ngay");
            } else {
                binding.txtAuthTitle.setText("Đăng Ký");
                binding.btnAuthAction.setText("Đăng Ký Tài Khoản");
                binding.txtSwitchMode.setText("Đã có tài khoản? Đăng nhập");
            }
        });

        // Xử lý nút hành động chính
        binding.btnAuthAction.setOnClickListener(v -> {
            String email = binding.edtEmail.getText().toString().trim();
            String password = binding.edtPassword.getText().toString().trim();

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập đầy đủ Email và Mật khẩu", Toast.LENGTH_SHORT).show();
                return;
            }

            if (password.length() < 6) {
                Toast.makeText(this, "Mật khẩu phải dài tối thiểu 6 ký tự", Toast.LENGTH_SHORT).show();
                return;
            }

            showLoading(true);

            if (isLoginMode) {
                // Thực hiện Đăng nhập
                SupabaseClient.getInstance().signIn(email, password, new SupabaseClient.AuthCallback() {
                    @Override
                    public void onSuccess(String token, String userEmail) {
                        saveSessionAndFinish(token, userEmail);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        handleAuthError(errorMsg);
                    }
                });
            } else {
                // Thực hiện Đăng ký
                SupabaseClient.getInstance().signUp(email, password, new SupabaseClient.AuthCallback() {
                    @Override
                    public void onSuccess(String token, String userEmail) {
                        saveSessionAndFinish(token, userEmail);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        handleAuthError(errorMsg);
                    }
                });
            }
        });
    }

    private void saveSessionAndFinish(String token, String email) {
        runOnUiThread(() -> {
            showLoading(false);
            
            // Lưu token cục bộ
            SharedPreferences prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE);
            prefs.edit()
                    .putString("access_token", token)
                    .putString("email", email)
                    .apply();

            Toast.makeText(LoginActivity.this, "Đăng nhập thành công! Chào mừng " + email, Toast.LENGTH_LONG).show();
            navigateToMain();
        });
    }

    private void handleAuthError(String errorMsg) {
        runOnUiThread(() -> {
            showLoading(false);
            Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
        });
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private void showLoading(boolean isLoading) {
        binding.loadingOverlay.setVisibility(isLoading ? View.VISIBLE : View.GONE);
    }
}
