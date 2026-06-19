package com.mobilegis.ximifarming.data.entity;

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
    private long id;
    
    private long cropId;
    private long date; // timestamp
    private String status;
    private String notes;
    private String photoPath;
    private boolean isSynced = false;

    public CropLog(long cropId, long date, String status, String notes, String photoPath) {
        this.cropId = cropId;
        this.date = date;
        this.status = status;
        this.notes = notes;
        this.photoPath = photoPath;
        this.isSynced = false;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCropId() { return cropId; }
    public void setCropId(long cropId) { this.cropId = cropId; }

    public long getDate() { return date; }
    public void setDate(long date) { this.date = date; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    public boolean isSynced() { return isSynced; }
    public void setSynced(boolean synced) { this.isSynced = synced; }
}
