package com.example.goldiefitnesstracker2.Data

import androidx.room.Entity
import androidx.room.PrimaryKey

// @Entity tells Room this class describes a database table
@Entity(tableName = "sessions") // SQL table name Room creates
data class SessionEntity(
    // @PrimaryKey marks this column as the unique row identifier
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    val timestamp: Long, // System.currentTimeMillis() when session is saved
    val steps: Int,      // sessionStepCount at the moment of saving
    val calories: Float, // result of calculateCalories(steps)
    val distanceKm: Float // result of calculateDistance(steps)
)