package com.mobilegis.ximifarming;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.mobilegis.ximifarming.databinding.ActivityMainBinding;
import com.mobilegis.ximifarming.ui.map.MapFragment;
import com.mobilegis.ximifarming.ui.plots.PlotsFragment;
import com.mobilegis.ximifarming.ui.tasks.TasksFragment;
import com.mobilegis.ximifarming.ui.alerts.AlertsFragment;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private final Fragment mapFragment = new MapFragment();
    private final Fragment plotsFragment = new PlotsFragment();
    private final Fragment tasksFragment = new TasksFragment();
    private final Fragment alertsFragment = new AlertsFragment();
    private final FragmentManager fm = getSupportFragmentManager();
    private Fragment activeFragment = mapFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Kiểm tra Session đăng nhập
        android.content.SharedPreferences prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE);
        String token = prefs.getString("access_token", null);
        if (token == null) {
            android.content.Intent intent = new android.content.Intent(this, com.mobilegis.ximifarming.ui.auth.LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        
        // Cấu hình token cho client kết nối Supabase
        com.mobilegis.ximifarming.supabase.SupabaseClient.getInstance().setAccessToken(token);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize bottom navigation and fragment manager
        fm.beginTransaction().add(R.id.fragment_container, alertsFragment, "4").hide(alertsFragment).commit();
        fm.beginTransaction().add(R.id.fragment_container, tasksFragment, "3").hide(tasksFragment).commit();
        fm.beginTransaction().add(R.id.fragment_container, plotsFragment, "2").hide(plotsFragment).commit();
        fm.beginTransaction().add(R.id.fragment_container, mapFragment, "1").commit();

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_map) {
                fm.beginTransaction().hide(activeFragment).show(mapFragment).commit();
                activeFragment = mapFragment;
                // Trigger reload in map fragment if needed
                ((MapFragment) mapFragment).reloadMapData();
                return true;
            } else if (itemId == R.id.nav_plots) {
                fm.beginTransaction().hide(activeFragment).show(plotsFragment).commit();
                activeFragment = plotsFragment;
                ((PlotsFragment) plotsFragment).loadPlotsData();
                return true;
            } else if (itemId == R.id.nav_tasks) {
                fm.beginTransaction().hide(activeFragment).show(tasksFragment).commit();
                activeFragment = tasksFragment;
                return true;
            } else if (itemId == R.id.nav_alerts) {
                fm.beginTransaction().hide(activeFragment).show(alertsFragment).commit();
                activeFragment = alertsFragment;
                return true;
            }
            return false;
        });
    }

    public void navigateToPlotOnMap(long plotId) {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_map);
        if (mapFragment instanceof MapFragment) {
            ((MapFragment) mapFragment).focusOnPlot(plotId);
        }
    }
}
