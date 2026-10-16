package com.example.goldiefitnesstracker2.Data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao // marks this interface as a set of database operations Room should implement
interface SessionDao {
    // @Insert - Room writes the INSERT INTO sessions (...) SQL for us automatically,
    @Insert // just from the shape of the SessionEntity parameter
    fun insertSession(session: SessionEntity)

    // @Query - for anything beyond a plain insert, we write the raw SQL
    // Newest session first, capped at 10 in the list
    @Query("SELECT * FROM sessions ORDER BY timestamp DESC LIMIT 10")
    fun getRecentSessions(): List<SessionEntity>
}