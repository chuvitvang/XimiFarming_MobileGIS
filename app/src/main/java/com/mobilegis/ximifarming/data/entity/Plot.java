package com.mobilegis.ximifarming.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "plots")
public class Plot {
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    private String name;
    private String description;
    private String coordinatesJson; // JSON string representing List of LatLng
    private double areaSquareMeters;
    private String healthStatus; // GOOD, WARNING, DANGER
    private double avgNdvi;
    private boolean isSynced = false; // Mặc định là false cho đến khi đồng bộ thành công

    public Plot(String name, String description, String coordinatesJson, double areaSquareMeters, String healthStatus, double avgNdvi) {
        this.name = name;
        this.description = description;
        this.coordinatesJson = coordinatesJson;
        this.areaSquareMeters = areaSquareMeters;
        this.healthStatus = healthStatus;
        this.avgNdvi = avgNdvi;
        this.isSynced = false;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCoordinatesJson() { return coordinatesJson; }
    public void setCoordinatesJson(String coordinatesJson) { this.coordinatesJson = coordinatesJson; }

    public double getAreaSquareMeters() { return areaSquareMeters; }
    public void setAreaSquareMeters(double areaSquareMeters) { this.areaSquareMeters = areaSquareMeters; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public double getAvgNdvi() { return avgNdvi; }
    public void setAvgNdvi(double avgNdvi) { this.avgNdvi = avgNdvi; }

    public boolean isSynced() { return isSynced; }
    public void setSynced(boolean synced) { this.isSynced = synced; }
}
