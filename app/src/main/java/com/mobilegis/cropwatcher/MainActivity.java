package com.mobilegis.cropwatcher;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.mobilegis.cropwatcher.databinding.ActivityMainBinding;
import com.mobilegis.cropwatcher.ui.map.MapFragment;
import com.mobilegis.cropwatcher.ui.plots.PlotsFragment;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private final Fragment mapFragment = new MapFragment();
    private final Fragment plotsFragment = new PlotsFragment();
    private final FragmentManager fm = getSupportFragmentManager();
    private Fragment activeFragment = mapFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize bottom navigation and fragment manager
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
            }
            return false;
        });
    }
}
