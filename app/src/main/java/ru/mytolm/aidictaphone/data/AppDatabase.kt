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
    exportSchema = true,   // сохраняем schemas/*.json — база для будущих миграций
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DictaphoneDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "dictaphone.db"
                )
                    // Политика миграций (аудит F14): destructive — ТОЛЬКО для легаси
                    // v1..v3, чьи схемы никогда не экспортировались (миграций для них
                    // не существует физически). С v3 схема экспортируется в schemas/,
                    // поэтому начиная со следующей версии БД добавляем сюда реальные
                    // addMigrations(MIGRATION_3_4, ...) и деструктивного пути нет.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3)
                    // .addMigrations(MIGRATION_3_4)  — заготовка; появится с bump v3→v4
                    .build().also { INSTANCE = it }
            }
    }
}
