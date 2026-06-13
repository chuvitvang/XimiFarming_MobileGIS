package com.mobilegis.cropwatcher.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.mobilegis.cropwatcher.data.entity.CropLog;

import java.util.List;

@Dao
public interface CropLogDao {
    @Insert
    long insert(CropLog log);

    @Update
    void update(CropLog log);

    @Delete
    void delete(CropLog log);

    @Query("SELECT * FROM crop_logs WHERE cropId = :cropId ORDER BY date DESC")
    List<CropLog> getLogsForCrop(int cropId);

    @Query("SELECT * FROM crop_logs WHERE cropId = :cropId ORDER BY date ASC")
    List<CropLog> getLogsForCropAsc(int cropId);
}
