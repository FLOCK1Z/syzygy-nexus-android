package com.syzygy.nexus

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiFailure(val status: Int, message: String) : IOException(message)

class NexusApi(private val origin: String = ORIGIN) {
    companion object {
        const val ORIGIN = "https://jhosepher-syzygy-neural-f63d2d2.hf.space"
    }

    @Volatile var token: String? = null
    @Volatile private var active: Call? = null
    val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.MINUTES)
            .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun cancel() {
        active?.cancel()
    }

    private fun request(path: String, data: JSONObject? = null): Request.Builder =
        Request.Builder().url(origin + path).apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (path.startsWith("/api/work/") || data?.optBoolean("work_mode") == true)
                header("X-Nexus-Work", "1")
            if (data != null) post(data.toString().toRequestBody(jsonType))
        }

    private fun checked(response: Response): Response {
        if (response.isSuccessful) return response
        val raw = response.peekBody(16384).string()
        val error = runCatching { JSONObject(raw).optString("erro") }.getOrDefault("")
        throw ApiFailure(
            response.code,
            error.ifBlank {
                if (response.code == 401) "Sua sessão expirou. Entre novamente."
                else "Não foi possível concluir (${response.code}). Tente mais tarde."
            },
        )
    }

    suspend fun json(path: String, data: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            client.newCall(request(path, data).build()).execute().use { response ->
                checked(response)
                JSONObject(response.body?.string() ?: throw IOException("Resposta vazia do Nexus."))
            }
        }

    suspend fun upload(name: String, mime: String, bytes: ByteArray): Doc =
        withContext(Dispatchers.IO) {
            require(bytes.size <= 10 * 1024 * 1024) { "O limite por documento é 10 MB." }
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
                    .build()
            client.newCall(request("/api/upload").post(body).build()).execute().use { response ->
                checked(response)
                val result = JSONObject(response.body!!.string())
                Doc(result.getString("doc_id"), result.getString("filename"))
            }
        }

    suspend fun chat(
        model: String,
        messages: List<Message>,
        docs: List<Doc>,
        onText: suspend (String) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val data =
                JSONObject()
                    .put("ia", model)
                    .put("mensagem", messages.last().text)
                    .put(
                        "conversation_history",
                        JSONArray(
                            messages
                                .dropLast(1)
                                .filter { it.status != "error" }
                                .map { JSONObject().put("role", it.role).put("content", it.text) }
                        ),
                    )
                    .put("doc_id", docs.joinToString(",") { it.id })
                    .put("work_mode", false)
                    .put("osint", false)
            val call = client.newCall(request("/api/chat", data).build())
            active = call
            try {
                call.execute().use { response ->
                    checked(response)
                    val reader =
                        response.body?.charStream() ?: throw IOException("Resposta sem conteúdo.")
                    val buffer = CharArray(512)
                    var total = 0
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 2_000_000)
                            throw IOException("Resposta acima do limite do aplicativo.")
                        onText(String(buffer, 0, count))
                    }
                }
            } finally {
                if (active === call) active = null
            }
        }

    suspend fun image(
        model: String,
        prompt: String,
        onProgress: suspend (String) -> Unit,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val call =
                client.newCall(
                    request(
                            "/api/images/generate",
                            JSONObject()
                                .put("model", model)
                                .put("prompt", prompt)
                                .put("size", "1024x1024")
                                .put("quality", "auto")
                                .put("context", JSONArray()),
                        )
                        .build()
                )
            active = call
            try {
                call.execute().use { response ->
                    checked(response)
                    val reader = response.body!!.charStream().buffered()
                    var bytes = 0
                    var result: List<String>? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        bytes += line.length
                        if (bytes > 36 * 1024 * 1024) throw IOException("Imagem acima do limite.")
                        if (!line.startsWith("data:")) continue
                        val event = JSONObject(line.removePrefix("data:"))
                        when (event.optString("type")) {
                            "error" ->
                                throw IOException(event.optString("error", "A geração falhou."))
                            "progress" -> onProgress("Criando sua imagem…")
                            "result" ->
                                result =
                                    event
                                        .getJSONArray("images")
                                        .objects()
                                        .map { it.optString("src", it.optString("url")) }
                                        .filter {
                                            it.startsWith("https://") ||
                                                it.startsWith("data:image/")
                                        }
                        }
                    }
                    result?.takeIf { it.isNotEmpty() }
                        ?: throw IOException(
                            "Conexão encerrada sem imagem confirmada. Confira o consumo antes de repetir."
                        )
                }
            } finally {
                if (active === call) active = null
            }
        }
}
