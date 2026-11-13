package ru.mytolm.aidictaphone.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class NoteStatus { AUDIO, QUEUED, TRANSCRIBING, TRANSCRIPT, READY, ERROR }

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val audioPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val status: NoteStatus = NoteStatus.AUDIO,
    val transcriptProgress: Int = 0,
    val errorMessage: String? = null,
    val summaryPreview: String? = null,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val startMs: Long,
    val endMs: Long,
    val speakerId: Int = 0,        // 0 = без диаризации
    val speakerName: String? = null,
    val text: String,
    val speakerOrigin: String = SOURCE_ASR, // asr | diarized | renamed (PLAUD SpeakerLabelOrigin)
) {
    companion object {
        const val SOURCE_ASR = "asr"
        const val SOURCE_DIARIZED = "diarized"
        const val SOURCE_RENAMED = "renamed"
    }
}

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val timeMs: Long,
    val label: String = "",
)

@Entity(
    tableName = "text_notes",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TextNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val timeMs: Long,
    val text: String,
)

@Entity(
    tableName = "summaries",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SummaryVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val style: String,      // standard | detailed
    val templateId: String, // protocol | call
    val model: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** FTS4 по заголовку + предпросмотру саммари (полнотекстовый поиск по библиотеке). */
@Fts4(contentEntity = Note::class, tokenizer = "unicode61")
@Entity(tableName = "notes_fts")
data class NoteFts(val title: String, val summaryPreview: String)

data class NoteWithContent(
    @Embedded val note: Note,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val segments: List<Segment>,
)
