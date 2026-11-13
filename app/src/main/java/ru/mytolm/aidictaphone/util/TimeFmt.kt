package ru.mytolm.aidictaphone.util

/**
 * Единая утилита форматирования времени: таймстампы сегментов, длительность,
 * человекочитаемая длительность и SRT-время. Раньше одни и те же форматы
 * дублировались в [ru.mytolm.aidictaphone.ui.DetailScreen],
 * [ru.mytolm.aidictaphone.ui.LibraryScreen] и
 * [ru.mytolm.aidictaphone.voice.TranscriptionEngine] — теперь один источник правды.
 */
object TimeFmt {

    /** «мм:сс» — таймстамп сегмента/хайлайта (минуты могут превышать 59). */
    fun clock(ms: Long): String = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    /** Длительность: «ч:мм:сс» при ≥ 1 часа, иначе «мм:сс». */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Человекочитаемая длительность: «2 ч 05 мин» при ≥ 1 часа, иначе «5:03». */
    fun durationHuman(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d ч %02d мин".format(s / 3600, (s / 60) % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** SRT-таймкод: «чч:мм:сс,мс». */
    fun srt(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val frac = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, frac)
    }
}
