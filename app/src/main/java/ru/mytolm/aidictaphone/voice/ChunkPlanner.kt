package ru.mytolm.aidictaphone.voice

/**
 * Чанкование аудио на окна 10 мин с перекрытием 2 с (ТЗ §5.3)
 * и сшивка сегментов соседних чанков по времени.
 */
object ChunkPlanner {

    const val CHUNK_MS = 600_000L
    const val OVERLAP_MS = 2_000L
    const val HOP_MS = CHUNK_MS - OVERLAP_MS
    const val SR = 16_000

    data class Chunk(val index: Int, val startMs: Long, val endMs: Long) {
        val startSample: Int get() = (startMs * SR / 1000).toInt()
    }

    fun plan(totalMs: Long): List<Chunk> {
        if (totalMs <= 0) return emptyList()
        val chunks = ArrayList<Chunk>()
        var start = 0L
        var i = 0
        while (start < totalMs) {
            val end = minOf(start + CHUNK_MS, totalMs)
            chunks.add(Chunk(i, start, end))
            if (end >= totalMs) break
            start += HOP_MS
            i++
        }
        return chunks
    }

    /**
     * Сегменты нового чанка добавляются к уже принятым: отбрасываются сегменты,
     * начинающиеся строго раньше конца предыдущего принятого (попадание в зону
     * перекрытия). Принятые сегменты не изменяются.
     * Чистая функция — покрыта unit-тестом.
     */
    fun stitch(
        accepted: List<AcceptedSegment>,
        chunkStartMs: Long,
        newSegments: List<Pair<Long, String>>, // startMs (уже абсоютный), text
    ): List<AcceptedSegment> {
        val prevEnd = accepted.maxOfOrNull { it.endMs } ?: chunkStartMs
        val out = ArrayList(accepted)
        var cursor = prevEnd
        for ((startMs, text) in newSegments) {
            if (startMs < cursor && text.isBlank()) continue
            if (startMs < cursor) continue // дубль из перекрытия
            val t = text.trim()
            if (t.isEmpty()) continue
            out.add(AcceptedSegment(startMs, maxOf(startMs, cursor), t))
            cursor = maxOf(cursor, startMs)
        }
        return out
    }

    data class AcceptedSegment(val startMs: Long, val endMs: Long, val text: String)
}
