package com.ero.eai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class LocalModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val license: String,
    val minRamBytes: Long,
    val minFreeBytes: Long,
    val description: String
) {
    val sizeLabel: String get() = "${sizeBytes / 1_000_000} MB"
}

data class LocalDeviceCapacity(val totalRamBytes: Long, val freeStorageBytes: Long)

data class LocalModelDownload(val modelId: String, val downloaded: Long, val total: Long, val error: String? = null) {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

class LocalModelManager(context: Context) {
    companion object {
        private const val MB = 1_000_000L
        const val MODEL_NAME = "Qwen3 0.6B · On-device"
        val CATALOG = listOf(
            LocalModelSpec(
                id = "qwen3-0.6b", name = MODEL_NAME, fileName = "Qwen3-0.6B.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                sizeBytes = 586 * MB, license = "Apache-2.0", minRamBytes = 2L * 1024 * 1024 * 1024,
                minFreeBytes = 900 * MB, description = "Small and suitable for most phones. Text only; works offline after download."
            ),
            LocalModelSpec(
                id = "qwen3-1.7b-q4", name = "Qwen3 1.7B Q4 · On-device", fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
                sizeBytes = 932 * MB, license = "Apache-2.0", minRamBytes = 6L * 1024 * 1024 * 1024,
                minFreeBytes = 1_500 * MB, description = "Larger, more capable model. Recommended for phones with at least 6 GB RAM."
            ),
            LocalModelSpec(
                id = "lfm25-350m-q4", name = "LFM2.5 350M Q4 · Fast", fileName = "LFM2.5-350M_int4.litertlm",
                url = "https://huggingface.co/litert-community/LFM2.5-350M/resolve/main/LFM2.5-350M_int4.litertlm",
                sizeBytes = 248 * MB, license = "LFM Open License v1.0", minRamBytes = 2L * 1024 * 1024 * 1024,
                minFreeBytes = 450 * MB, description = "Very small and quick; best for extraction, structured answers, and tool use."
            ),
            LocalModelSpec(
                id = "lfm2-700m-q4", name = "LFM2 700M Q4 · Multilingual", fileName = "LFM2-700M_int4.litertlm",
                url = "https://huggingface.co/litert-community/LFM2-700M/resolve/main/LFM2-700M_int4.litertlm",
                sizeBytes = 481 * MB, license = "LFM Open License v1.0", minRamBytes = 3L * 1024 * 1024 * 1024,
                minFreeBytes = 700 * MB, description = "Compact multilingual model for extraction, writing, and conversations."
            ),
            LocalModelSpec(
                id = "qwen25-coder-15b-q4", name = "Qwen2.5 Coder 1.5B · Coding", fileName = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm",
                url = "https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/main/Qwen2.5-Coder-1.5B-Instruct_int4.litertlm",
                sizeBytes = 1_120 * MB, license = "Apache-2.0", minRamBytes = 6L * 1024 * 1024 * 1024,
                minFreeBytes = 1_500 * MB, description = "Specialized for code questions and writing small code snippets."
            ),
            LocalModelSpec(
                id = "lfm2-26b-q4", name = "LFM2 2.6B Q4 · Advanced", fileName = "LFM2-2.6B_int4.litertlm",
                url = "https://huggingface.co/litert-community/LFM2-2.6B/resolve/main/LFM2-2.6B_int4.litertlm",
                sizeBytes = 1_530 * MB, license = "LFM Open License v1.0", minRamBytes = 8L * 1024 * 1024 * 1024,
                minFreeBytes = 2_200 * MB, description = "Largest option here with stronger general responses; needs a high-memory phone."
            ),
            LocalModelSpec(
                id = "qwen3-4b-q4", name = "Qwen3 4B Q4 · Advanced", fileName = "qwen3_4b_mixed_int4.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
                sizeBytes = 2_274 * MB, license = "Apache-2.0", minRamBytes = 12L * 1024 * 1024 * 1024,
                minFreeBytes = 3_000 * MB, description = "Most capable option; requires substantial memory and storage. CPU backend only with current runtime."
            )
        )
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "local-models").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val mutableDownload = MutableStateFlow<LocalModelDownload?>(null)
    val download = mutableDownload.asStateFlow()

    fun deviceCapacity(): LocalDeviceCapacity {
        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val storage = StatFs(appContext.filesDir.absolutePath)
        return LocalDeviceCapacity(memory.totalMem, storage.availableBytes)
    }

    fun isInstalled(model: LocalModelSpec) = file(model).exists() && file(model).length() > 0
    fun modelPath(model: LocalModelSpec): String? = file(model).takeIf { isInstalled(model) }?.absolutePath
    fun installedModelName(path: String?): String = CATALOG.firstOrNull { modelPath(it) == path }?.name ?: MODEL_NAME

    suspend fun install(model: LocalModelSpec) = withContext(Dispatchers.IO) {
        val target = file(model)
        val temp = File(dir, "${model.fileName}.part")
        try {
            if (deviceCapacity().freeStorageBytes < model.sizeBytes + 128 * 1024 * 1024L) {
                error("Not enough free storage. Free at least ${(model.sizeBytes + 128 * 1024 * 1024L) / MB} MB and try again.")
            }
            mutableDownload.value = LocalModelDownload(model.id, 0, 0)
            val request = Request.Builder().url(model.url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Model download failed (HTTP ${response.code})")
                val body = response.body ?: error("Empty model download")
                val total = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1024 * 256)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            mutableDownload.value = LocalModelDownload(model.id, downloaded, total)
                        }
                    }
                }
            }
            if (temp.length() < model.sizeBytes * 9 / 10) error("Downloaded file is smaller than expected; please retry.")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Could not save downloaded model")
            mutableDownload.value = null
        } catch (e: Exception) {
            temp.delete()
            mutableDownload.value = LocalModelDownload(model.id, 0, 0, e.message ?: "Download failed")
        }
    }

    suspend fun remove(model: LocalModelSpec) = withContext(Dispatchers.IO) {
        file(model).delete()
        File(dir, "${model.fileName}.part").delete()
        if (mutableDownload.value?.modelId == model.id) mutableDownload.value = null
    }

    private fun file(model: LocalModelSpec) = File(dir, model.fileName)
}
