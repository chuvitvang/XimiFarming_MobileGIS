package com.mobilegis.ximifarming.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.mobilegis.ximifarming.data.dao.CropDao;
import com.mobilegis.ximifarming.data.dao.CropLogDao;
import com.mobilegis.ximifarming.data.dao.PlotDao;
import com.mobilegis.ximifarming.data.entity.Crop;
import com.mobilegis.ximifarming.data.entity.CropLog;
import com.mobilegis.ximifarming.data.entity.Plot;

@Database(entities = {Plot.class, Crop.class, CropLog.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract PlotDao plotDao();
    public abstract CropDao cropDao();
    public abstract CropLogDao cropLogDao();

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "crop_watcher_database")
                            .fallbackToDestructiveMigration()
                            .allowMainThreadQueries() // Simple Room queries can be run on main thread for demo, but we should make sure we don't block in production. 
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
