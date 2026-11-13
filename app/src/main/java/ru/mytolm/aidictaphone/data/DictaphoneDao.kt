package ru.mytolm.aidictaphone.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DictaphoneDao {

    // ---- notes ----
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun allNotes(): Flow<List<Note>>

    @Query(
        "SELECT n.* FROM notes n JOIN notes_fts f ON n.id = f.docid " +
            "WHERE notes_fts MATCH :query ORDER BY n.createdAt DESC"
    )
    fun searchNotes(query: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun noteFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: Long): Note?

    @Insert
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("UPDATE notes SET status = :status, errorMessage = :err, transcriptProgress = :prog WHERE id = :id")
    suspend fun setNoteStatus(id: Long, status: NoteStatus, err: String? = null, prog: Int = 0)

    @Query("UPDATE notes SET transcriptProgress = :prog WHERE id = :id")
    suspend fun setProgress(id: Long, prog: Int)

    @Query("UPDATE notes SET summaryPreview = :preview WHERE id = :id")
    suspend fun setSummaryPreview(id: Long, preview: String)

    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    // ---- segments ----
    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs ASC")
    fun segmentsFlow(noteId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs ASC")
    suspend fun segments(noteId: Long): List<Segment>

    @Insert
    suspend fun insertSegments(segments: List<Segment>)

    @Update
    suspend fun updateSegment(segment: Segment)

    @Query("DELETE FROM segments WHERE noteId = :noteId")
    suspend fun clearSegments(noteId: Long)

    /** Переименование спикера применяется ко всем сегментам; origin = renamed (§11.3). */
    @Query("UPDATE segments SET speakerName = :name, speakerOrigin = 'renamed' " +
           "WHERE noteId = :noteId AND speakerId = :speakerId")
    suspend fun renameSpeaker(noteId: Long, speakerId: Int, name: String)

    /** Сегменты чанка для продолжения прерванной транскрипции. */
    @Query("SELECT MAX(endMs) FROM segments WHERE noteId = :noteId")
    suspend fun lastSegmentEndMs(noteId: Long): Long?

    // ---- highlights ----
    @Query("SELECT * FROM highlights WHERE noteId = :noteId ORDER BY timeMs ASC")
    fun highlightsFlow(noteId: Long): Flow<List<Highlight>>

    @Insert
    suspend fun insertHighlight(h: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    // ---- text notes ----
    @Query("SELECT * FROM text_notes WHERE noteId = :noteId ORDER BY timeMs ASC")
    fun textNotesFlow(noteId: Long): Flow<List<TextNote>>

    @Insert
    suspend fun insertTextNote(tn: TextNote)

    // ---- summaries ----
    @Query("SELECT * FROM summaries WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun summariesFlow(noteId: Long): Flow<List<SummaryVersion>>

    @Insert
    suspend fun insertSummary(s: SummaryVersion)

    @Query("DELETE FROM summaries WHERE noteId = :noteId AND createdAt < " +
           "(SELECT createdAt FROM summaries WHERE noteId = :noteId ORDER BY createdAt DESC LIMIT 1 OFFSET :keep)")
    suspend fun trimSummaries(noteId: Long, keep: Int)
}
