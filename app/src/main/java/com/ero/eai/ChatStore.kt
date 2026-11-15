package com.ero.eai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ApiProvider(val title: String) {
    OLLAMA("Ollama"), OPENAI("OpenAI compatible"), ANTHROPIC("Anthropic"), GEMINI("Google Gemini")
}

data class ChatMessage(val id: String, val role: String, val content: String)
data class ChatSession(val id: String, val title: String, val messages: List<ChatMessage>)

data class ChatState(
    val host: String = "http://192.168.1.2:11434",
    val provider: ApiProvider = ApiProvider.OLLAMA,
    val apiKey: String = "",
    val model: String = "llama3.2:latest",
    val models: List<String> = emptyList(),
    val systemPrompt: String = "",
    val webSearch: Boolean = false,
    val thinking: Boolean = true,
    val maxTokens: Int = 4096,
    val sessions: List<ChatSession> = emptyList(),
    val activeId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val loading: Boolean = false,
    val status: String = "Set your API connection in settings"
) {
    val activeTitle: String? get() = sessions.firstOrNull { it.id == activeId }?.title
}

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "conversations.json")
    private val preferences = context.getSharedPreferences("eai-mobile", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()

    private fun loadPrefs() = ChatState(
        host = preferences.getString("host", "http://192.168.1.2:11434") ?: "http://192.168.1.2:11434",
        provider = runCatching { ApiProvider.valueOf(preferences.getString("provider", "OLLAMA")!!) }.getOrDefault(ApiProvider.OLLAMA),
        apiKey = preferences.getString("api_key", "") ?: "",
        model = preferences.getString("model", "llama3.2:latest") ?: "llama3.2:latest",
        systemPrompt = preferences.getString("system_prompt", "") ?: "",
        webSearch = preferences.getBoolean("web_search", false),
        thinking = preferences.getBoolean("thinking", true),
        maxTokens = preferences.getInt("max_tokens", 4096)
    )

    private val _state = MutableStateFlow(loadPrefs())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    init { loadSaved() }

    suspend fun saveSettings(host: String, provider: ApiProvider, apiKey: String, model: String,
                             systemPrompt: String, webSearch: Boolean, thinking: Boolean, maxTokens: Int) {
        val normalizedHost = host.trim().trimEnd('/')
        preferences.edit().putString("host", normalizedHost).putString("provider", provider.name)
            .putString("api_key", apiKey.trim()).putString("model", model.trim())
            .putString("system_prompt", systemPrompt).putBoolean("web_search", webSearch)
            .putBoolean("thinking", thinking).putInt("max_tokens", maxTokens.coerceIn(128, 32768)).apply()
        _state.value = _state.value.copy(host = normalizedHost, provider = provider, apiKey = apiKey.trim(),
            model = model.trim().ifEmpty { "llama3.2:latest" }, systemPrompt = systemPrompt,
            webSearch = webSearch, thinking = thinking, maxTokens = maxTokens.coerceIn(128, 32768), status = "Settings saved")
    }

    suspend fun setModel(model: String) {
        preferences.edit().putString("model", model).apply()
        _state.value = _state.value.copy(model = model)
    }

    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        val config = _state.value
        try {
            val request = Request.Builder().url(modelsUrl(config)).apply { authHeaders(config) }.get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("API returned HTTP ${response.code}: ${response.body?.string()?.take(180).orEmpty()}")
                val body = JSONObject(response.body?.string().orEmpty())
                val models = when (config.provider) {
                    ApiProvider.OLLAMA -> body.optJSONArray("models").asStringList("name")
                    ApiProvider.GEMINI -> body.optJSONArray("models").asStringList("name").map { it.removePrefix("models/") }
                    else -> body.optJSONArray("data").asStringList("id")
                }
                _state.value = _state.value.copy(models = models,
                    status = if (models.isEmpty()) "Connected, but no models were returned" else "Connected · ${models.size} models")
            }
        } catch (e: Exception) { _state.value = _state.value.copy(status = e.message?.take(150) ?: "Could not connect") }
    }

    private fun JSONArray?.asStringList(field: String): List<String> = this?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString(field)?.takeIf(String::isNotBlank) }
    } ?: emptyList()

    private fun modelsUrl(s: ChatState): String = when (s.provider) {
        ApiProvider.OLLAMA -> "${s.host}/api/tags"
        ApiProvider.ANTHROPIC -> "${s.host}/models"
        ApiProvider.GEMINI -> "${s.host}/models?key=${java.net.URLEncoder.encode(s.apiKey, "UTF-8")}"
        ApiProvider.OPENAI -> "${s.host}/models"
    }

    private fun Request.Builder.authHeaders(s: ChatState): Request.Builder = when (s.provider) {
        ApiProvider.ANTHROPIC -> header("x-api-key", s.apiKey).header("anthropic-version", "2023-06-01")
        ApiProvider.GEMINI -> this
        ApiProvider.OPENAI -> if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}") else this
        ApiProvider.OLLAMA -> this
    }

    suspend fun newChat() { saveActive(); _state.value = _state.value.copy(activeId = null, messages = emptyList(), streamingText = "") }
    suspend fun selectChat(id: String) {
        saveActive()
        val session = _state.value.sessions.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(activeId = id, messages = session.messages, streamingText = "")
    }
    suspend fun deleteChat(id: String) {
        val next = _state.value.sessions.filterNot { it.id == id }
        val active = _state.value.activeId == id
        _state.value = _state.value.copy(sessions = next, activeId = if (active) null else _state.value.activeId,
            messages = if (active) emptyList() else _state.value.messages)
        persist(next)
    }
    suspend fun stop() { _state.value = _state.value.copy(loading = false, status = "Stopped") }

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        val before = _state.value
        val sid = before.activeId ?: UUID.randomUUID().toString()
        val history = before.messages + ChatMessage(UUID.randomUUID().toString(), "user", text)
        val title = text.replace('\n', ' ').take(44).let { if (text.length > 44) "$it…" else it }
        _state.value = before.copy(activeId = sid, messages = history, streamingText = "", loading = true, status = "Connecting…")
        saveActive(title)
        try {
            val config = _state.value
            val request = when (config.provider) {
                ApiProvider.OLLAMA -> ollamaRequest(config, history)
                ApiProvider.OPENAI -> openAiRequest(config, history)
                ApiProvider.ANTHROPIC -> anthropicRequest(config, history)
                ApiProvider.GEMINI -> geminiRequest(config, history)
            }
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("API returned HTTP ${response.code}: ${response.body?.string()?.take(240).orEmpty()}")
                val source = response.body?.source() ?: error("Empty response")
                when (config.provider) {
                    ApiProvider.OLLAMA -> readOllama(source)
                    ApiProvider.OPENAI -> readSse(source, "openai")
                    ApiProvider.ANTHROPIC -> readSse(source, "anthropic")
                    ApiProvider.GEMINI -> readSse(source, "gemini")
                }
            }
            val final = _state.value.streamingText
            _state.value = _state.value.copy(messages = _state.value.messages + ChatMessage(UUID.randomUUID().toString(), "assistant", final),
                streamingText = "", loading = false, status = "")
            saveActive(title)
        } catch (e: Exception) { _state.value = _state.value.copy(loading = false, status = e.message?.take(180) ?: "Request failed") }
    }

    private fun baseMessages(s: ChatState, history: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        if (s.systemPrompt.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", s.systemPrompt))
        history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        return arr
    }

    private fun ollamaRequest(s: ChatState, history: List<ChatMessage>): Request {
        val msgs = JSONArray()
        if (s.systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", s.systemPrompt))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val options = JSONObject().put("num_predict", s.maxTokens).put("think", s.thinking)
        val json = JSONObject().put("model", s.model).put("messages", msgs).put("stream", true).put("options", options)
        return Request.Builder().url("${s.host}/api/chat").post(json.toString().jsonBody()).build()
    }

    private fun openAiRequest(s: ChatState, history: List<ChatMessage>): Request {
        val json = JSONObject().put("model", s.model).put("messages", baseMessages(s, history))
            .put("stream", true).put("max_tokens", s.maxTokens)
        return Request.Builder().url("${s.host}/chat/completions").apply { authHeaders(s) }.post(json.toString().jsonBody()).build()
    }

    private fun anthropicRequest(s: ChatState, history: List<ChatMessage>): Request {
        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val json = JSONObject().put("model", s.model).put("max_tokens", s.maxTokens).put("stream", true)
            .put("messages", messages)
        if (s.systemPrompt.isNotBlank()) json.put("system", s.systemPrompt)
        return Request.Builder().url("${s.host}/messages").apply { authHeaders(s) }.post(json.toString().jsonBody()).build()
    }

    private fun geminiRequest(s: ChatState, history: List<ChatMessage>): Request {
        val contents = JSONArray()
        history.filter { it.role != "system" }.forEach {
            contents.put(JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", it.content))))
        }
        val url = "${s.host}/models/${s.model}:streamGenerateContent?alt=sse&key=${java.net.URLEncoder.encode(s.apiKey, "UTF-8")}"
        val json = JSONObject().put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", s.maxTokens).put("thinkingConfig", JSONObject().put("includeThoughts", s.thinking)))
        if (s.systemPrompt.isNotBlank()) json.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", s.systemPrompt))))
        return Request.Builder().url(url).post(json.toString().jsonBody()).build()
    }

    private fun String.jsonBody() = toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun append(text: String) {
        if (text.isNotEmpty()) _state.value = _state.value.copy(streamingText = _state.value.streamingText + text, status = "Generating…")
    }

    private fun readOllama(source: BufferedSource) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            val chunk = JSONObject(line)
            append(chunk.optJSONObject("message")?.optString("content").orEmpty())
            if (chunk.optBoolean("done")) break
        }
    }

    private fun readSse(source: BufferedSource, kind: String) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
            val part = when (kind) {
                "openai" -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                "anthropic" -> if (o.optString("type") == "content_block_delta") o.optJSONObject("delta")?.optString("text").orEmpty() else ""
                else -> o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
            }
            append(part)
            if (o.optString("type") == "message_stop") break
        }
    }

    private fun saveActive(title: String? = null) {
        val current = _state.value; val id = current.activeId ?: return
        val existing = current.sessions.toMutableList(); val previous = existing.indexOfFirst { it.id == id }
        val item = ChatSession(id, title ?: existing.getOrNull(previous)?.title ?: "New chat", current.messages)
        if (previous >= 0) existing[previous] = item else existing.add(0, item)
        _state.value = current.copy(sessions = existing); persist(existing)
    }

    private fun persist(sessions: List<ChatSession>) {
        try {
            val arr = JSONArray()
            sessions.forEach { session ->
                val msgs = JSONArray()
                session.messages.forEach { msgs.put(JSONObject().put("id", it.id).put("role", it.role).put("content", it.content)) }
                arr.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", msgs))
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    private fun loadSaved() {
        try {
            val arr = JSONArray(file.readText())
            val sessions = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val messagesArray = o.optJSONArray("messages") ?: JSONArray()
                val messages = (0 until messagesArray.length()).mapNotNull { j ->
                    val m = messagesArray.optJSONObject(j) ?: return@mapNotNull null
                    ChatMessage(m.optString("id", UUID.randomUUID().toString()), m.optString("role"), m.optString("content"))
                }
                ChatSession(o.optString("id"), o.optString("title", "New chat"), messages)
            }
            _state.value = _state.value.copy(sessions = sessions)
        } catch (_: Exception) { }
    }
}
