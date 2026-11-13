package ru.mytolm.aidictaphone

import org.junit.Assert.*
import org.junit.Test
import ru.mytolm.aidictaphone.voice.ChunkPlanner
import ru.mytolm.aidictaphone.voice.WavParser
import ru.mytolm.aidictaphone.data.LlmClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** unit-тесты ТЗ §7: парсер WAV, сшивка чанков, сборка промпта/map-reduce. */
class CoreTests {

    private fun wav(samples: FloatArray, rate: Int, channels: Int = 1,
                    bits: Int = 16): ByteArray {
        val bytesPerSample = bits / 8
        val dataLen = samples.size * channels * bytesPerSample
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray()); buf.put("fmt ".toByteArray())
        buf.putInt(16); buf.putShort(1); buf.putShort(channels.toShort())
        buf.putInt(rate); buf.putInt(rate * channels * bytesPerSample)
        buf.putShort((channels * bytesPerSample).toShort()); buf.putShort(bits.toShort())
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (s in samples) for (c in 0 until channels) {
            when (bits) {
                16 -> buf.putShort((s * 32767).toInt().toShort())
                32 -> buf.putFloat(s)
            }
        }
        return buf.array()
    }

    @Test fun `wav 16bit mono passthrough`() {
        val sig = FloatArray(16000) { (0.5 * sin(2 * PI * 440 * it / 16000)).toFloat() }
        val w = WavParser.parse(wav(sig, 16000))
        assertEquals(16000, w.sampleRate)
        assertEquals(16000, w.samples.size)
        assertEquals(sig[100], w.samples[100], 0.001f)
    }

    @Test fun `wav 48k stereo downmix and resample`() {
        val sig = FloatArray(48000) { (0.4 * sin(2 * PI * 100 * it / 48000)).toFloat() }
        val w = WavParser.parse(wav(sig, 48000, 1))
        assertEquals(16000, w.sampleRate)
        assertEquals(16000, w.samples.size) // 48k -> 16k
    }

    @Test fun `wav header duration lie resampled by actual`() {
        // заголовок vrёт: data содержит ровно 32000 отсчётов 32 кГц -> на выходе 16000
        val sig = FloatArray(32000) { 0.1f }
        val w = WavParser.parse(wav(sig, 32000))
        assertEquals(16000, w.samples.size)
    }

    @Test fun `not a wav rejected`() {
        try {
            WavParser.parse("OggS____________".toByteArray())
            fail("должно бросить")
        } catch (e: WavParser.WavFormatException) { /* ok */ }
    }

    @Test fun `compressed wav rejected with clear error`() {
        val bytes = wav(FloatArray(100), 16000).copyOf()
        // ставим формат 0xFFFE (EXTENSIBLE) без подпарсера -> понятная ошибка
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 0xFFFE.toShort())
        try {
            WavParser.parse(bytes)
            fail("должно бросить")
        } catch (e: WavParser.WavFormatException) {
            assertTrue(e.message!!.contains("не поддерживается"))
        }
    }

    // ---------- ChunkPlanner ----------

    @Test fun `plan 25min gives three chunks with overlap`() {
        val chunks = ChunkPlanner.plan(25 * 60_000L)
        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(600_000L, chunks[0].endMs)
        assertEquals(598_000L, chunks[1].startMs) // перекрытие 2 с
        assertEquals(1_196_000L, chunks[2].startMs) // 2*HOP = 2*(600000-2000)
        assertEquals(25 * 60_000L, chunks[2].endMs)
    }

    @Test fun `stitch drops overlap duplicates`() {
        val accepted = listOf(
            ChunkPlanner.AcceptedSegment(0, 1000, "раз"),
            ChunkPlanner.AcceptedSegment(597_000, 599_000, "пять"),
        )
        // новый чанк начинается с 598000; сегмент 598500 — дубль из перекрытия
        val out = ChunkPlanner.stitch(accepted, 598_000, listOf(
            Pair(598_500L, "пять ещё"),
            Pair(600_200L, "шесть"),
        ))
        assertEquals(3, out.size)
        assertEquals("пять", out[1].text)
        assertEquals("шесть", out[2].text)
        assertEquals(600_200L, out[2].startMs)
    }

    @Test fun `stitch first chunk keeps everything`() {
        val out = ChunkPlanner.stitch(emptyList(), 0, listOf(
            Pair(100L, "а"), Pair(5000L, "б"),
        ))
        assertEquals(2, out.size)
    }

    // ---------- LLM промпт / map-reduce ----------

    @Test fun `transcript text includes highlights and notes`() {
        val t = LlmClient.buildTranscriptText(
            listOf(Pair("00:10 Спикер 1", "Обсудили бюджет")),
            listOf(Pair("00:10", "про бюджет")),
            listOf(Pair("00:12", "уточнить")),
        )
        assertTrue(t.contains("Ключевые моменты"))
        assertTrue(t.contains("про бюджет"))
        assertTrue(t.contains("Текстовые заметки"))
        assertTrue(t.contains("уточнить"))
        assertTrue(t.contains("Обсудили бюджет"))
    }

    @Test fun `mapreduce chunking never truncates`() {
        val text = (1..500).joinToString("\n") { "строка ${it} содержимое" }
        val limit = 800
        val chunks = LlmClient.chunkForMapReduce(text, limit)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= limit) }
        val rejoined = chunks.joinToString("\n")
        // каждое слово исходника присутствует в чанках (без молчаливой обрезки)
        for (probe in listOf("строка 1 ", "строка 250 ", "строка 500 ")) {
            assertTrue("потеря: $probe", rejoined.contains(probe))
        }
    }

    @Test fun `short text single chunk`() {
        assertEquals(1, LlmClient.chunkForMapReduce("короткий", 100).size)
    }

    @Test fun `system prompts standard and detailed differ`() {
        val s = LlmClient.systemPrompt("protocol", false)
        val d = LlmClient.systemPrompt("protocol", true)
        assertTrue(s.contains("Краткое резюме") && s.contains("Открытые вопросы"))
        assertNotEquals(s, d)
        assertTrue(d.contains("Detailed"))
    }

    @Test fun `templates protocol and call bind distinct prompt suffixes`() {
        assertEquals(listOf("protocol", "call"),
            LlmClient.BUILTIN_TEMPLATES.keys.toList())
        val protocol = LlmClient.systemPrompt("protocol", false)
        val call = LlmClient.systemPrompt("call", false)
        assertTrue(protocol.contains("протокол совещания"))
        assertTrue(call.contains("телефонный звонок"))
        assertNotEquals(protocol, call)
        // неизвестный шаблон откатывается на протокол
        assertEquals(protocol, LlmClient.systemPrompt("lecture", false))
    }

    @Test fun `context char limit reserves output and system prompt`() {
        // 4096 ctx, 1024 output -> (4096-1024-256)*3 = 8448 символов на чанк
        assertEquals(8448, LlmClient.contextCharLimit(4096, 1024))
        // малый контекст не уходит в минус
        assertTrue(LlmClient.contextCharLimit(1024, 1024) > 0)
        // большее окно даёт больше места под чанк
        assertTrue(LlmClient.contextCharLimit(8192, 1024) > LlmClient.contextCharLimit(4096, 1024))
    }

    // ---------- parseFile: потоковый разбор длинного 48 кГц ----------
    // регрессия: при ratio 48к→16к ровно 3.0-guard читал кадр за границей окна
    // (win[n]) -> ArrayIndexOutOfBoundsException на записи длиннее ~22 с окон 2 МБ
    @Test fun `parseFile 48k longer than resample window does not crash`() {
        val frames = 1_500_000 // ~31 с при 48 кГц; окно = 1 048 576 кадров
        val sig = FloatArray(frames) { (0.3 * sin(2 * PI * 100 * (it % 480) / 48000)).toFloat() }
        val f = java.io.File.createTempFile("regress_48k", ".wav")
        try {
            f.writeBytes(wav(sig, 48000))
            val w = WavParser.parseFile(f)
            assertEquals(16000, w.sampleRate)
            assertEquals(frames / 3, w.samples.size)
        } finally {
            f.delete()
        }
    }

    @Test fun `plain format strips markdown into editor text`() {
        val md = "**Решения**\n- **Иван** купит *сервер*\n1. подготовить отчёт\n> цитата\n[ссылка](https://x.io/a)"
        val out = ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(md)
        assertTrue(out.contains("РЕШЕНИЯ"))
        assertTrue(out.contains("• Иван купит сервер"))
        assertTrue(out.contains("1. подготовить отчёт"))
        assertTrue(out.contains("цитата"))
        assertTrue(out.contains("ссылка (https://x.io/a)"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("> "))
    }

    @Test fun `plain format aligns md tables`() {
        val md = "| Сотрудник | Срок |\n|---|---|\n| Василиса | 05.12 |\n| Пётр | завтра |"
        val out = ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(md)
        val rows = out.lines()
        // header, подчёркивание, 2 данных строки
        assertEquals(4, rows.size)
        assertTrue(rows[1].matches(Regex("[-]+-\\+[-]+")))
        // колонки выровнены: вертикальная черта во всех строках на одной позиции
        assertEquals(rows[0].indexOf('|'), rows[2].indexOf('|'))
        assertEquals(rows[0].indexOf('|'), rows[3].indexOf('|'))
        assertTrue(rows[2].contains("Василиса"))
    }
}
