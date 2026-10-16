package com.example.goldiefitnesstracker2.Data

import android.content.Context
import android.graphics.Color
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// @Database - entities list every @Entity class this database contains
// Version = 1 - bump this number and write a migration if the table shape ever changes.
// exportSchema = false - skips writing a schema history file to disk
@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    // abstract - we declare the shape, Room fills in the real implementation
    abstract fun sessionDao(): SessionDao

    companion object{
        // guarantees every thread sees the latest value of INSTANCE immediately,
        @Volatile // not a stale cached copy
        private var INSTANCE: AppDatabase? = null

        // the ONLY way anything in the app should obtain a database reference
        fun getInstance(context: Context): AppDatabase {
            // if INSTANCE exists, return it immediately
            // if not synchronized makes sure 2 threads cant be created at once
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,     // outlives any single activity/screen
                    AppDatabase::class.java, // which database class to build
                    "fitness_tracker_db"     // the actual .db filename on disk
                ).build().also { INSTANCE = it }    // cache it so next call skips straight to top
            }
        }
    }
}