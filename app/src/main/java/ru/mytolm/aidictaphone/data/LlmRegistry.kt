package ru.mytolm.aidictaphone.data

/**
 * Каталог локальных LLM для суммаризации (лама-джидже, llama.cpp runtime, фаза 2+).
 * Малые.multilingual GGUF (RU-качество у Qwen3 приемлемое) — под 16 ГБ Pixel 10 Pro.
 * Размеры и SHA-256 (LFS oid) сняты с HF API 03.11.2026, repo ggml-org (открытые зеркала).
 */
data class LlmModel(
    val id: String,
    val fileName: String,      // в modelsDir (один GGUF)
    val sizeBytes: Long,
    val quant: String,
    val label: String,
    val repo: String,
    val repoPath: String,
    val sha256: String,
)

data class LlmFamily(
    val id: String,
    val title: String,
    val subtitle: String,
    val hfUrl: String,
    val models: List<LlmModel>,
)

object LlmRegistry {
    val FAMILIES = listOf(
        LlmFamily(
            id = "qwen3-0.6b", title = "Qwen3 0.6B",
            subtitle = "Минимальная; быстрая, базовое RU — для теста",
            hfUrl = "https://huggingface.co/Qwen/Qwen3-0.6B",
            models = listOf(
                LlmModel("qwen3-0.6b-q4", "qwen3-0.6b-q4_0.gguf", 428_970_080L, "Q4_0",
                    "Q4_0 · 410 МБ", "ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf",
                    "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"),
                LlmModel("qwen3-0.6b-q8", "qwen3-0.6b-q8_0.gguf", 804_753_632L, "Q8_0",
                    "Q8_0 · 768 МБ", "ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf",
                    "361cc68159042c36ebff7715dc5a2e4612153e88f3e9c9c234820849d6dc9e1d"),
            ),
        ),
        LlmFamily(
            id = "qwen3-1.7b", title = "Qwen3 1.7B",
            subtitle = "Баланс качества RU и скорости — рекомендуемая",
            hfUrl = "https://huggingface.co/Qwen/Qwen3-1.7B",
            models = listOf(
                LlmModel("qwen3-1.7b-q4", "qwen3-1.7b-q4_k_m.gguf", 1_282_439_264L, "Q4_K_M",
                    "Q4_K_M · 1.2 ГБ", "ggml-org/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf",
                    "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"),
                LlmModel("qwen3-1.7b-q8", "qwen3-1.7b-q8_0.gguf", 2_165_039_200L, "Q8_0",
                    "Q8_0 · 2.0 ГБ", "ggml-org/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf",
                    "9860780f3a1fab1f8f909a1b549ea3e62c22d19ab1a492b3a1026b38c5bd3ec3"),
            ),
        ),
        LlmFamily(
            id = "qwen3-4b", title = "Qwen3 4B",
            subtitle = "Лучшее качество RU на устройстве; медленнее",
            hfUrl = "https://huggingface.co/Qwen/Qwen3-4B",
            models = listOf(
                LlmModel("qwen3-4b-q4", "qwen3-4b-q4_k_m.gguf", 2_497_280_640L, "Q4_K_M",
                    "Q4_K_M · 2.3 ГБ", "ggml-org/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf",
                    "ab27b9bfa375a178d6cba48f3ad892b94b7739659dcc7aae8058ce0ffed6b328"),
                LlmModel("qwen3-4b-q8", "qwen3-4b-q8_0.gguf", 4_280_405_120L, "Q8_0",
                    "Q8_0 · 4.0 ГБ", "ggml-org/Qwen3-4B-GGUF", "Qwen3-4B-Q8_0.gguf",
                    "865fbdcb8119c5cd5ba557b139d49351aa2c4e33bf93bdd7b08e24b573dd1dd4"),
            ),
        ),
    )

    val MODELS: List<LlmModel> = FAMILIES.flatMap { it.models }

    fun find(id: String): LlmModel? = MODELS.firstOrNull { it.id == id }

    fun locate(modelId: String): Pair<LlmFamily, LlmModel>? {
        for (f in FAMILIES) f.models.firstOrNull { it.id == modelId }?.let { return f to it }
        return null
    }

    fun urlFor(m: LlmModel): String =
        "https://huggingface.co/${m.repo}/resolve/main/${m.repoPath}"
}
