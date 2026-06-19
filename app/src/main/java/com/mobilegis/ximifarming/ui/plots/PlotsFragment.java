package com.mobilegis.ximifarming.ui.plots;

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

import com.mobilegis.ximifarming.R;
import com.mobilegis.ximifarming.data.AppDatabase;
import com.mobilegis.ximifarming.data.entity.Plot;
import com.mobilegis.ximifarming.databinding.FragmentPlotsBinding;
import com.mobilegis.ximifarming.databinding.ItemPlotBinding;

import java.util.ArrayList;
import java.util.List;

public class PlotsFragment extends Fragment {
    private FragmentPlotsBinding binding;
    private AppDatabase db;
    private PlotsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPlotsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());
        
        binding.rvPlots.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new PlotsAdapter(new ArrayList<>(), this::onPlotClicked, this::onPlotLocateClicked);
        binding.rvPlots.setAdapter(adapter);

        loadPlotsData();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPlotsData();
    }

    public void loadPlotsData() {
        if (db == null) return;
        List<Plot> plots = db.plotDao().getAllPlots();
        
        if (plots.isEmpty()) {
            binding.txtEmptyPlots.setVisibility(View.VISIBLE);
            binding.rvPlots.setVisibility(View.GONE);
        } else {
            binding.txtEmptyPlots.setVisibility(View.GONE);
            binding.rvPlots.setVisibility(View.VISIBLE);
            adapter.setPlotsList(plots);
        }
    }

    private void onPlotClicked(Plot plot) {
        Intent intent = new Intent(getActivity(), PlotDetailActivity.class);
        intent.putExtra("PLOT_ID", plot.getId());
        startActivity(intent);
    }

    private void onPlotLocateClicked(Plot plot) {
        if (getActivity() instanceof com.mobilegis.ximifarming.MainActivity) {
            ((com.mobilegis.ximifarming.MainActivity) getActivity()).navigateToPlotOnMap(plot.getId());
        }
    }

    // --- Inner Adapter Class ---
    
    private static class PlotsAdapter extends RecyclerView.Adapter<PlotsAdapter.PlotViewHolder> {
        public interface OnPlotClickListener {
            void onPlotClick(Plot plot);
        }

        public interface OnPlotLocateListener {
            void onPlotLocate(Plot plot);
        }

        private List<Plot> plotsList;
        private final OnPlotClickListener clickListener;
        private final OnPlotLocateListener locateListener;

        public PlotsAdapter(List<Plot> plotsList, OnPlotClickListener clickListener, OnPlotLocateListener locateListener) {
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
        public PlotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPlotBinding itemBinding = ItemPlotBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new PlotViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull PlotViewHolder holder, int position) {
            Plot plot = plotsList.get(position);
            holder.bind(plot, clickListener, locateListener);
        }

        @Override
        public int getItemCount() {
            return plotsList.size();
        }

        static class PlotViewHolder extends RecyclerView.ViewHolder {
            private final ItemPlotBinding binding;
            private final Context context;

            public PlotViewHolder(ItemPlotBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                this.context = binding.getRoot().getContext();
            }

            public void bind(Plot plot, OnPlotClickListener clickListener, OnPlotLocateListener locateListener) {
                binding.txtPlotName.setText(plot.getName());
                binding.txtPlotArea.setText(String.format("Diện tích: %.1f m²", plot.getAreaSquareMeters()));
                
                // Get Crop Count from DB dynamically
                int cropCount = AppDatabase.getDatabase(context).cropDao().getCropCountForPlot(plot.getId());
                binding.txtPlotCrops.setText(String.format("Số lượng: %d cây", cropCount));
                
                binding.txtNdviBadge.setText(String.format("NDVI: %.2f", plot.getAvgNdvi()));

                // Set health colors
                int indicatorColor;
                if ("GOOD".equals(plot.getHealthStatus())) {
                    indicatorColor = ContextCompat.getColor(context, R.color.health_good);
                    binding.txtNdviBadge.setTextColor(indicatorColor);
                    binding.cardNdviBadge.setCardBackgroundColor(Color.parseColor("#E8F5E9"));
                } else if ("WARNING".equals(plot.getHealthStatus())) {
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
