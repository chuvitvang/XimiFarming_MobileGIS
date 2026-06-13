package com.mobilegis.cropwatcher.data.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "crop_logs",
    foreignKeys = @ForeignKey(
        entity = Crop.class,
        parentColumns = "id",
        childColumns = "cropId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index(value = {"cropId"})}
)
public class CropLog {
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private int cropId;
    private long date; // timestamp
    private String status;
    private String notes;
    private String photoPath;
    private double vegetationIndexValue; // ExG calculated value

    public CropLog(int cropId, long date, String status, String notes, String photoPath, double vegetationIndexValue) {
        this.cropId = cropId;
        this.date = date;
        this.status = status;
        this.notes = notes;
        this.photoPath = photoPath;
        this.vegetationIndexValue = vegetationIndexValue;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getCropId() { return cropId; }
    public void setCropId(int cropId) { this.cropId = cropId; }

    public long getDate() { return date; }
    public void setDate(long date) { this.date = date; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    public double getVegetationIndexValue() { return vegetationIndexValue; }
    public void setVegetationIndexValue(double vegetationIndexValue) { this.vegetationIndexValue = vegetationIndexValue; }
}
