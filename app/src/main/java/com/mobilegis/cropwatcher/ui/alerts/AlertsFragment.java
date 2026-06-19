package com.mobilegis.cropwatcher.ui.alerts;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mobilegis.cropwatcher.R;
import com.mobilegis.cropwatcher.data.AppDatabase;
import com.mobilegis.cropwatcher.data.entity.Plot;
import com.mobilegis.cropwatcher.databinding.FragmentAlertsBinding;
import com.mobilegis.cropwatcher.databinding.ItemPlotBinding;
import com.mobilegis.cropwatcher.ui.plots.PlotDetailActivity;

import java.util.ArrayList;
import java.util.List;

public class AlertsFragment extends Fragment {
    private FragmentAlertsBinding binding;
    private AppDatabase db;
    private AlertsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAlertsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());

        binding.rvAlerts.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AlertsAdapter(new ArrayList<>(), this::onPlotClicked, this::onPlotLocateClicked);
        binding.rvAlerts.setAdapter(adapter);

        loadAlertsData();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAlertsData();
    }

    private void loadAlertsData() {
        if (db == null) return;
        List<Plot> allPlots = db.plotDao().getAllPlots();
        List<Plot> warningPlots = new ArrayList<>();
        for (Plot p : allPlots) {
            if ("WARNING".equals(p.getHealthStatus()) || "DANGER".equals(p.getHealthStatus())) {
                warningPlots.add(p);
            }
        }

        if (warningPlots.isEmpty()) {
            binding.txtEmptyAlerts.setVisibility(View.VISIBLE);
            binding.rvAlerts.setVisibility(View.GONE);
        } else {
            binding.txtEmptyAlerts.setVisibility(View.GONE);
            binding.rvAlerts.setVisibility(View.VISIBLE);
            adapter.setPlotsList(warningPlots);
        }
    }

    private void onPlotClicked(Plot plot) {
        Intent intent = new Intent(getActivity(), PlotDetailActivity.class);
        intent.putExtra("PLOT_ID", plot.getId());
        startActivity(intent);
    }

    private void onPlotLocateClicked(Plot plot) {
        if (getActivity() instanceof com.mobilegis.cropwatcher.MainActivity) {
            ((com.mobilegis.cropwatcher.MainActivity) getActivity()).navigateToPlotOnMap(plot.getId());
        }
    }

    private static class AlertsAdapter extends RecyclerView.Adapter<AlertsAdapter.AlertViewHolder> {
        public interface OnPlotClickListener {
            void onPlotClick(Plot plot);
        }

        public interface OnPlotLocateListener {
            void onPlotLocate(Plot plot);
        }

        private List<Plot> plotsList;
        private final OnPlotClickListener clickListener;
        private final OnPlotLocateListener locateListener;

        public AlertsAdapter(List<Plot> plotsList, OnPlotClickListener clickListener, OnPlotLocateListener locateListener) {
            this.plotsList = plotsList;
            this.clickListener = clickListener;
            this.locateListener = locateListener;
        }

        public void setPlotsList(List<Plot> plots) {
            this.plotsList = plots;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AlertViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPlotBinding itemBinding = ItemPlotBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new AlertViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull AlertViewHolder holder, int position) {
            Plot plot = plotsList.get(position);
            holder.bind(plot, clickListener, locateListener);
        }

        @Override
        public int getItemCount() {
            return plotsList.size();
        }

        static class AlertViewHolder extends RecyclerView.ViewHolder {
            private final ItemPlotBinding binding;
            private final Context context;

            public AlertViewHolder(ItemPlotBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                this.context = binding.getRoot().getContext();
            }

            public void bind(Plot plot, OnPlotClickListener clickListener, OnPlotLocateListener locateListener) {
                binding.txtPlotName.setText(plot.getName());
                binding.txtPlotArea.setText(String.format("Diện tích: %.1f m²", plot.getAreaSquareMeters()));

                int cropCount = AppDatabase.getDatabase(context).cropDao().getCropCountForPlot(plot.getId());
                binding.txtPlotCrops.setText(String.format("Số lượng: %d cây", cropCount));

                binding.txtNdviBadge.setText(String.format("NDVI: %.2f", plot.getAvgNdvi()));

                int indicatorColor;
                if ("WARNING".equals(plot.getHealthStatus())) {
                    indicatorColor = ContextCompat.getColor(context, R.color.health_warning);
                    binding.txtNdviBadge.setTextColor(indicatorColor);
                    binding.cardNdviBadge.setCardBackgroundColor(Color.parseColor("#FFF3E0"));
                } else {
                    indicatorColor = ContextCompat.getColor(context, R.color.health_danger);
                    binding.txtNdviBadge.setTextColor(indicatorColor);
                    binding.cardNdviBadge.setCardBackgroundColor(Color.parseColor("#FFEBEE"));
                }
                binding.viewStatusIndicator.setBackgroundColor(indicatorColor);

                itemView.setOnClickListener(v -> clickListener.onPlotClick(plot));
                binding.btnLocatePlot.setOnClickListener(v -> locateListener.onPlotLocate(plot));
            }
        }
    }
}
