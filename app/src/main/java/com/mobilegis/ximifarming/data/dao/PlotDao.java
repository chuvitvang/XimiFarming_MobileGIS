package com.mobilegis.ximifarming.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.mobilegis.ximifarming.data.entity.Plot;

import java.util.List;

@Dao
public interface PlotDao {
    @Insert
    long insert(Plot plot);

    @Update
    void update(Plot plot);

    @Delete
    void delete(Plot plot);

    @Query("SELECT * FROM plots ORDER BY id DESC")
    List<Plot> getAllPlots();

    @Query("SELECT * FROM plots WHERE id = :id")
    Plot getPlotById(long id);

    @Query("SELECT * FROM plots WHERE coordinatesJson = :coordsJson LIMIT 1")
    Plot getPlotByCoordinates(String coordsJson);
}
