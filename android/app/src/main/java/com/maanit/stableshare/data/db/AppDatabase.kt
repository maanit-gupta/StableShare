package com.maanit.stableshare.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TransferEntity::class, ChunkEntity::class, TransferEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao
    abstract fun chunkDao(): ChunkDao
    abstract fun eventDao(): EventDao

    companion object {
        const val NAME = "stableshare.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
