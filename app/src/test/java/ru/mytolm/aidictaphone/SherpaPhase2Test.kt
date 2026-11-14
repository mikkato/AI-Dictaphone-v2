package ru.mytolm.aidictaphone

import org.junit.Assert.*
import org.junit.Test
import ru.mytolm.aidictaphone.data.ModelRegistry
import ru.mytolm.aidictaphone.voice.SherpaPlan

class SherpaPhase2Test {

    @Test
    fun `registry families have six entries and quants`() {
        assertEquals(6, ModelRegistry.FAMILIES.size)
        val ids = ModelRegistry.FAMILIES.map { it.id }
        assertTrue(ids.containsAll(listOf("whisper-turbo", "whisper-large",
            "gigaam", "parakeet", "qwen3-asr", "whisper-tiny")))
        assertTrue("whisper-ru должен быть убран (нет GGML как есть)",
            ModelRegistry.FAMILIES.none { it.id == "whisper-ru" })
        for (f in ModelRegistry.FAMILIES)
            assertTrue("no quants in ${f.id}", f.quants.isNotEmpty())
    }

    @Test
    fun `sherpa model part sizes sum to model size`() {
        for (m in ModelRegistry.MODELS) {
            val parts = ModelRegistry.partsOf(m)
            assertEquals("size mismatch ${m.id}", m.sizeBytes, parts.sumOf { it.sizeBytes })
            assertTrue("empty parts ${m.id}", parts.isNotEmpty())
            for (p in parts) {
                assertTrue("bad size ${m.id}/${p.destName}", p.sizeBytes > 0)
                assertFalse("empty path ${m.id}", p.repoPath.isBlank())
                p.sha256?.let {
                    assertEquals("bad sha len ${m.id}/${p.destName}", 64, it.length)
                    assertTrue("non-hex sha ${m.id}/${p.destName}",
                        it.matches(Regex("[0-9a-f]{64}")))
                }
            }
        }
    }

    @Test
    fun `urls built per part repo`() {
        val m = ModelRegistry.find("parakeet-int8")!!
        val part = m.parts.first()
        val url = ModelRegistry.urlFor(m, part, null)
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx",
            url)
        // зеркало применяется только к whisper.cpp реестру
        val giga = ModelRegistry.find("gigaam-ctc-int8")!!
        val urlG = ModelRegistry.urlFor(giga, giga.parts.first(), "https://mirror.example")
        assertTrue(urlG.startsWith("https://huggingface.co/Smirnov75/"))
    }

    @Test
    fun `chunk plan 25s no overlap covers duration`() {
        val chunks = SherpaPlan.chunkPlan(91_000L)
        assertEquals(4, chunks.size)
        assertEquals(0L, chunks.first().startMs)
        assertEquals(91_000L, chunks.last().endMs)
        for (i in 1 until chunks.size) {
            assertEquals("no gap/overlap", chunks[i - 1].endMs, chunks[i].startMs)
        }
        assertTrue(SherpaPlan.chunkPlan(0L).isEmpty())
    }

    @Test
    fun `group sentences splits on punctuation with absolute offsets`() {
        val toks = arrayOf(" Привет", " мир.", " Как", " дела?")
        val ts = floatArrayOf(0.5f, 1.2f, 3.0f, 4.1f)
        val segs = SherpaPlan.groupSentences(toks, ts, offsetMs = 10_000L)
        assertEquals(2, segs.size)
        assertEquals(10_500L, segs[0].startMs)
        assertEquals(11_200L, segs[0].endMs)   // конец = таймстамп последнего токена
        assertEquals("Привет мир.", segs[0].text)
        assertEquals(13_000L, segs[1].startMs)
        assertEquals(14_100L, segs[1].endMs)
        assertEquals("Как дела?", segs[1].text)
    }

    @Test
    fun `group sentences keeps dangling tail`() {
        val segs = SherpaPlan.groupSentences(arrayOf(" хвост"), floatArrayOf(2.0f), 5_000L)
        assertEquals(1, segs.size)
        assertEquals(7_000L, segs[0].startMs)
        assertEquals(7_001L, segs[0].endMs)    // endMs >= startMs+1
        assertEquals("хвост", segs[0].text)
    }

    @Test
    fun `whisper ggml ids keep single GGML part in models root`() {
        val m = ModelRegistry.find("tiny")!!
        assertEquals("whisper", m.kind)
        val parts = ModelRegistry.partsOf(m)
        assertEquals(1, parts.size)
        assertEquals("ggml-tiny.bin", parts[0].destName)
    }

    @Test
    fun `qwen3 tokenizer parts keep subdirectory`() {
        val m = ModelRegistry.find("qwen3-int8")!!
        val tokParts = ModelRegistry.partsOf(m).filter { it.repoPath.startsWith("tokenizer/") }
        assertTrue("нет tokenizer-частей", tokParts.isNotEmpty())
        tokParts.forEach {
            assertTrue("destName=${it.destName}", it.destName.startsWith("tokenizer/"))
        }
    }
}
