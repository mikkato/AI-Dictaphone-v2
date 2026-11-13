package ru.mytolm.aidictaphone.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Note::class, Segment::class, Highlight::class, TextNote::class,
        SummaryVersion::class, NoteFts::class
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DictaphoneDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "dictaphone.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
