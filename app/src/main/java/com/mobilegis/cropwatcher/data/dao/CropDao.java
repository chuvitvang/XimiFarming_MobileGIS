package com.mobilegis.cropwatcher.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.mobilegis.cropwatcher.data.entity.Crop;

import java.util.List;

@Dao
public interface CropDao {
    @Insert
    long insert(Crop crop);

    @Update
    void update(Crop crop);

    @Delete
    void delete(Crop crop);

    @Query("SELECT * FROM crops ORDER BY id DESC")
    List<Crop> getAllCrops();

    @Query("SELECT * FROM crops WHERE plotId = :plotId ORDER BY id DESC")
    List<Crop> getCropsForPlot(int plotId);

    @Query("SELECT * FROM crops WHERE id = :id")
    Crop getCropById(int id);
    
    @Query("SELECT COUNT(*) FROM crops WHERE plotId = :plotId")
    int getCropCountForPlot(int plotId);
}
