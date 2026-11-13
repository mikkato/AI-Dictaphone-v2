package ru.mytolm.aidictaphone.data

/**
 * Каталог ASR-моделей (ТЗ §3 + фаза 2).
 * kind="whisper"          — GGML .bin, движок whisper.cpp (нативный JNI).
 * kind="sherpa_transducer" — ONNX encoder/decoder/joiner, sherpa-onnx (Parakeet, GigaAM RNNT).
 * kind="sherpa_nemo_ctc"   — ONNX один файл CTC, sherpa-onnx (GigaAM CTC).
 * kind="sherpa_qwen3"      — conv_frontend/encoder/decoder + tokenizer/, sherpa-onnx.
 * Размеры и SHA-256 (LFS oid) сняты с HF API 03.11.2026.
 */
data class ModelPart(
    val repoPath: String,      // путь в HF-репозитории
    val destName: String,      // имя файла в modelsDir/<modelId>/ (может содержать подкаталог, напр. tokenizer/vocab.json)
    val sizeBytes: Long,
    val sha256: String? = null,
)

data class WhisperModel(
    val id: String,
    val fileName: String,           // whisper: единственный GGML-файл в modelsDir
    val sizeBytes: Long,            // суммарный размер всех частей
    val label: String,
    val sha256: String? = null,     // whisper: эталон GGML
    val quant: String = "f16",
    val repo: String = "ggerganov/whisper.cpp",
    val kind: String = "whisper",
    val parts: List<ModelPart> = emptyList(),
)

data class ModelFamily(
    val id: String,
    val title: String,
    val subtitle: String,
    val hfUrl: String,
    val engine: String,
    val note: String? = null,
    val quants: List<WhisperModel>,
)

private fun sherpaParts(vararg t: Triple<String, Long, String?>) =
    t.map { (p, s, sha) -> ModelPart(p, p, s, sha?.takeIf { it.isNotEmpty() }) }

object ModelRegistry {
    const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    const val KIND_WHISPER = "whisper"
    const val KIND_TRANSDUCER = "sherpa_transducer"
    const val KIND_NEMO_CTC = "sherpa_nemo_ctc"
    const val KIND_QWEN3 = "sherpa_qwen3"

    val FAMILIES = listOf(
        ModelFamily(
            id = "whisper-turbo", title = "Whisper large-v3-turbo (OpenAI)",
            subtitle = "Быстрая, высокое качество RU — рекомендуемая",
            hfUrl = "https://huggingface.co/openai/whisper-large-v3-turbo",
            engine = "whisper.cpp",
            quants = listOf(
                WhisperModel("turbo-f16", "ggml-large-v3-turbo.bin", 1_624_555_275L, "f16",
                    "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", quant = "f16"),
                WhisperModel("turbo-q8_0", "ggml-large-v3-turbo-q8_0.bin", 874_188_075L, "q8_0",
                    "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", quant = "q8_0"),
                WhisperModel("turbo-q5_0", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "q5_0",
                    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", quant = "q5_0"),
            ),
        ),
        ModelFamily(
            id = "whisper-large", title = "Whisper large-v3 (OpenAI)",
            subtitle = "Максимальное качество, медленнее turbo",
            hfUrl = "https://huggingface.co/openai/whisper-large-v3",
            engine = "whisper.cpp",
            quants = listOf(
                WhisperModel("large-v3-f16", "ggml-large-v3.bin", 3_095_033_483L, "f16",
                    "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2", quant = "f16"),
                WhisperModel("large-v3-q5_0", "ggml-large-v3-q5_0.bin", 1_081_140_203L, "q5_0",
                    "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1", quant = "q5_0"),
            ),
        ),
        ModelFamily(
            id = "gigaam", title = "GigaAM-v3 (Sber AI)",
            subtitle = "RU end-to-end с пунктуацией, ONNX через sherpa-onnx",
            hfUrl = "https://huggingface.co/ai-sage/GigaAM-v3",
            engine = "sherpa-onnx",
            quants = listOf(
                WhisperModel(
                    id = "gigaam-ctc-int8", fileName = "gigaam-ctc-int8", sizeBytes = 319871127L,
                    label = "E2E CTC int8 — быстрая", quant = "CTC int8",
                    repo = "Smirnov75/GigaAM-v3-sherpa-onnx", kind = KIND_NEMO_CTC,
                    parts = sherpaParts(
                        Triple("gigaam_v3_e2e_ctc_int8.onnx", 319869121L,
                            "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0"),
                        Triple("gigaam_v3_e2e_ctc_tokens.txt", 2006L,
                            ""),
                    ),
                ),
                WhisperModel(
                    id = "gigaam-rnnt-int8", fileName = "gigaam-rnnt-int8", sizeBytes = 326322304L,
                    label = "E2E RNNT int8 — качество", quant = "RNNT int8",
                    repo = "Smirnov75/GigaAM-v3-sherpa-onnx", kind = KIND_TRANSDUCER,
                    parts = sherpaParts(
                        Triple("gigaam_v3_e2e_rnnt_encoder_int8.onnx", 318995997L,
                            "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
                        Triple("gigaam_v3_e2e_rnnt_decoder.onnx", 4600058L,
                            "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
                        Triple("gigaam_v3_e2e_rnnt_joint.onnx", 2712896L,
                            "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                        Triple("gigaam_v3_e2e_rnnt_tokens.txt", 13353L,
                            ""),
                    ),
                ),
            ),
        ),
        ModelFamily(
            id = "parakeet", title = "Parakeet TDT 0.6B v3 (NVIDIA)",
            subtitle = "25 языков вкл. RU, пунктуация и таймстампы",
            hfUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
            engine = "sherpa-onnx",
            quants = listOf(
                WhisperModel(
                    id = "parakeet-int8", fileName = "parakeet-int8", sizeBytes = 670478772L,
                    label = "int8 — экспорт sherpa-onnx", quant = "int8",
                    repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", kind = KIND_TRANSDUCER,
                    parts = sherpaParts(
                        Triple("encoder.int8.onnx", 652184281L,
                            "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                        Triple("decoder.int8.onnx", 11845275L,
                            "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                        Triple("joiner.int8.onnx", 6355277L,
                            "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                        Triple("tokens.txt", 93939L,
                            ""),
                    ),
                ),
            ),
        ),
        ModelFamily(
            id = "qwen3-asr", title = "Qwen3-ASR 0.6B",
            subtitle = "52 языка, LLM-архитектура, ONNX через sherpa-onnx",
            hfUrl = "https://huggingface.co/Qwen/Qwen3-ASR-0.6B",
            engine = "sherpa-onnx",
            quants = listOf(
                WhisperModel(
                    id = "qwen3-int8", fileName = "qwen3-int8", sizeBytes = 987015347L,
                    label = "int8 — основной квант", quant = "int8",
                    repo = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25", kind = KIND_QWEN3,
                    parts = sherpaParts(
                        Triple("conv_frontend.onnx", 44148281L,
                            "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                        Triple("encoder.int8.onnx", 182491662L,
                            "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                        Triple("decoder.int8.onnx", 755914231L,
                            "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
                        Triple("tokenizer/vocab.json", 2776833L,
                            "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                        Triple("tokenizer/merges.txt", 1671853L,
                            ""),
                        Triple("tokenizer/tokenizer_config.json", 12487L,
                            "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                    ),
                ),
            ),
        ),
        ModelFamily(
            id = "whisper-tiny", title = "Whisper tiny (демо)",
            subtitle = "Для быстрой проверки и эмулятора",
            hfUrl = "https://huggingface.co/ggerganov/whisper.cpp",
            engine = "whisper.cpp",
            quants = listOf(
                WhisperModel("tiny", "ggml-tiny.bin", 77_691_713L, "f16",
                    "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", quant = "f16"),
                WhisperModel("tiny-q8_0", "ggml-tiny-q8_0.bin", 43_537_433L, "q8_0",
                    "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca", quant = "q8_0"),
                WhisperModel("tiny-q5_1", "ggml-tiny-q5_1.bin", 32_152_673L, "q5_1",
                    "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", quant = "q5_1"),
            ),
        ),
    )

    val MODELS: List<WhisperModel> = FAMILIES.flatMap { it.quants }

    fun find(id: String): WhisperModel? = MODELS.firstOrNull { it.id == id }

    /** Семейство + квант по id активной модели. */
    fun locate(modelId: String): Pair<ModelFamily, WhisperModel>? {
        for (f in FAMILIES) f.quants.firstOrNull { it.id == modelId }?.let { return f to it }
        return null
    }

    fun urlFor(model: WhisperModel, part: ModelPart, mirror: String? = null): String {
        val useMirror = mirror?.isNotBlank() == true && model.repo == "ggerganov/whisper.cpp"
        return if (useMirror) mirror!!.trimEnd('/') + "/" + part.repoPath
               else "https://huggingface.co/${model.repo}/resolve/main/${part.repoPath}"
    }

    /** Части модели: у whisper одна (GGML в modelsDir корнем), у sherpa — набор в modelsDir/<id>/ */
    fun partsOf(model: WhisperModel): List<ModelPart> =
        model.parts.ifEmpty {
            listOf(ModelPart(model.fileName, model.fileName, model.sizeBytes, model.sha256))
        }
}
