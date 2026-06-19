package com.mobilegis.ximifarming.data.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "crops",
    foreignKeys = @ForeignKey(
        entity = Plot.class,
        parentColumns = "id",
        childColumns = "plotId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index(value = {"plotId"})}
)
public class Crop {
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    private long plotId;
    private String name;
    private String type;
    private long plantingDate; // timestamp
    private double latitude;
    private double longitude;
    private String status; // HEALTHY, STRESSED, DISEASED
    private boolean isSynced = false;

    public Crop(long plotId, String name, String type, long plantingDate, double latitude, double longitude, String status) {
        this.plotId = plotId;
        this.name = name;
        this.type = type;
        this.plantingDate = plantingDate;
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = status;
        this.isSynced = false;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPlotId() { return plotId; }
    public void setPlotId(long plotId) { this.plotId = plotId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getPlantingDate() { return plantingDate; }
    public void setPlantingDate(long plantingDate) { this.plantingDate = plantingDate; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isSynced() { return isSynced; }
    public void setSynced(boolean synced) { this.isSynced = synced; }
}
