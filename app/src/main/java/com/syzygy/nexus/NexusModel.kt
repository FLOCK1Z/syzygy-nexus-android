package com.syzygy.nexus

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject

/** UI state is native and independent of the web application's DOM. */
data class NexusState(
    val user: NexusUser? = null,
    val catalog: JSONObject = JSONObject(),
    val quota: JSONObject? = null,
    val conversation: Conversation = Conversation(),
    val history: List<Conversation> = emptyList(),
    val docs: List<Doc> = emptyList(),
    val busy: Boolean = false,
    val uploading: Boolean = false,
    val loading: Boolean = true,
    val notice: String = "",
    val voiceStatus: String = "",
    val voiceTranscript: List<Pair<String, String>> = emptyList(),
    val voiceActive: Boolean = false,
    val workMode: Boolean = false,
    val workAutonomy: String = "step",
    val desktopStyle: String = "ubuntu",
    val approvalBusy: String = "",
)

class NexusModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(NexusState())
        private set

    val api = NexusApi()
    private val store = SecureStore(app)
    private var voice: NativeVoice? = null
    private var sessionEpoch = 0
    private var voiceEpoch = 0
    private var stopped = false

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.session() }
                if (saved != null) {
                    api.token = saved.getString("token")
                    val result = api.json("/api/session")
                    val user = result.getJSONObject("session")
                    loadUser(NexusUser(user.getString("login"), user.optString("tier", "free")))
                    refresh()
                }
            } catch (e: Exception) {
                if (e is ApiFailure && e.status == 401) {
                    withContext(Dispatchers.IO) { store.session(null) }
                    api.token = null
                }
                notice(e.message ?: "Não foi possível conectar ao Nexus.")
            } finally {
                state = state.copy(loading = false)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(60000)
                if (state.user != null) refresh()
            }
        }
    }

    fun notice(text: String) {
        state = state.copy(notice = text)
    }

    private suspend fun loadUser(user: NexusUser) {
        val rows = withContext(Dispatchers.IO) { store.list(user.login) }
        state =
            state.copy(
                user = user,
                history = rows,
                conversation = Conversation(),
                docs = emptyList(),
            )
    }

    private suspend fun persist(c: Conversation, owner: String) {
        withContext(Dispatchers.IO) { store.save(owner, c) }
        if (state.user?.login == owner) {
            val rows = withContext(Dispatchers.IO) { store.list(owner) }
            if (state.user?.login == owner) state = state.copy(history = rows)
        }
    }

    suspend fun refresh() {
        val owner = state.user?.login ?: return
        try {
            val result = api.json("/api/init")
            if (state.user?.login != owner) return
            val selected =
                state.conversation.model.ifBlank {
                    result.optJSONObject("ias")?.namesList()?.firstOrNull() ?: ""
                }
            state =
                state.copy(
                    catalog = result,
                    quota = result.optJSONObject("quota"),
                    conversation = state.conversation.copy(model = selected),
                )
        } catch (e: Exception) {
            if (state.user?.login != owner) return
            if (e is ApiFailure && e.status == 401) logout()
            notice(e.message ?: "Conexão indisponível.")
        }
    }

    fun auth(login: String, password: String, register: Boolean, onSuccess: () -> Unit) {
        if (state.busy) return
        if (register) {
            passwordProblem(password)?.let {
                notice(it)
                return
            }
        }
        state = state.copy(busy = true, notice = "")
        viewModelScope.launch {
            try {
                val result =
                    api.json(
                        if (register) "/api/register" else "/api/login",
                        JSONObject().put("login", login.trim()).put("senha", password),
                    )
                accept(result)
                onSuccess()
            } catch (e: Exception) {
                notice(e.message ?: "Não foi possível entrar.")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    suspend fun accept(result: JSONObject) {
        val info = result.getJSONObject("usuario")
        val user = NexusUser(info.getString("login"), info.optString("tier", "free"))
        withContext(Dispatchers.IO) {
            store.list(user.login)
            store.session(JSONObject().put("token", result.getString("token")))
        }
        api.token = result.getString("token")
        sessionEpoch++
        loadUser(user)
        refresh()
    }

    fun googleToken(token: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            state = state.copy(busy = true)
            try {
                accept(api.json("/api/auth/google", JSONObject().put("token", token)))
                onSuccess()
            } catch (e: Exception) {
                notice(e.message ?: "O Google não confirmou o acesso.")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun recover(email: String) {
        viewModelScope.launch {
            try {
                val result =
                    api.json("/api/recover/request", JSONObject().put("email", email.trim()))
                notice(
                    result.optString(
                        "mensagem",
                        "Solicitação recebida. Confira sua caixa de entrada e spam.",
                    )
                )
            } catch (e: Exception) {
                notice(e.message ?: "Não foi possível solicitar a recuperação.")
            }
        }
    }

    fun reset(link: String, password: String, confirm: String, onSuccess: () -> Unit) {
        if (password != confirm) {
            notice("As senhas não coincidem.")
            return
        }
        passwordProblem(password)?.let {
            notice(it)
            return
        }
        val uri = link.trim().toUri()
        val origin = NexusApi.ORIGIN.toUri()
        if (uri.scheme != "https" || uri.host != origin.host || uri.path != "/account/security") {
            notice("Use o link de recuperação enviado pelo Nexus.")
            return
        }
        val token =
            ("https://local.invalid/?" + (uri.fragment ?: "")).toUri().getQueryParameter("reset")
                ?: run {
                    notice("Link inválido.")
                    return
                }
        viewModelScope.launch {
            try {
                api.json(
                    "/api/recover/reset",
                    JSONObject().put("token", token).put("senha", password),
                )
                notice("Senha atualizada. Entre novamente.")
                onSuccess()
            } catch (e: Exception) {
                notice(e.message ?: "Recuperação não confirmada.")
            }
        }
    }

    fun logout() {
        api.cancel()
        stopVoice()
        voiceEpoch++
        sessionEpoch++
        api.token = null
        state = NexusState(loading = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.session(null) }
            } catch (_: Exception) {
                notice(
                    "Não foi possível remover a sessão local. Feche o aplicativo e tente novamente."
                )
            }
        }
    }

    fun newConversation(temporary: Boolean = false) {
        if (state.busy) return
        state =
            state.copy(
                conversation =
                    Conversation(model = state.conversation.model, temporary = temporary),
                docs = emptyList(),
                notice = "",
            )
    }

    fun select(c: Conversation) {
        if (state.busy) return
        state = state.copy(conversation = c, docs = emptyList(), notice = "")
        val m = c.messages.lastOrNull()
        if (m?.work?.active == true) {
            state = state.copy(busy = true)
            viewModelScope.launch {
                try {
                    followWork(c, m.id, state.user!!.login, sessionEpoch)
                } catch (e: Exception) {
                    notice(e.message ?: "Não foi possível consultar a missão.")
                }
            }
        }
    }

    fun model(name: String) {
        if (!state.busy) state = state.copy(conversation = state.conversation.copy(model = name))
    }

    fun remove(id: String?) {
        val owner = state.user?.login ?: return
        if (state.busy) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.remove(owner, id) }
                val rows = withContext(Dispatchers.IO) { store.list(owner) }
                state = state.copy(history = rows)
                if (id == null || state.conversation.id == id) newConversation()
            } catch (e: Exception) {
                notice(e.message ?: "Não foi possível apagar o histórico.")
            }
        }
    }

    fun removeDoc(id: String) {
        state = state.copy(docs = state.docs.filterNot { it.id == id })
    }

    fun upload(uris: List<Uri>) {
        val owner = state.user?.login ?: return
        if (state.uploading || state.busy) return
        if (uris.size + state.docs.size > 8) {
            notice("Envie até oito documentos.")
            return
        }
        state = state.copy(uploading = true)
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    val doc =
                        withContext(Dispatchers.IO) {
                            val resolver = getApplication<Application>().contentResolver
                            var name = "documento"
                            resolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) name = it.getString(0) }
                            val mime = resolver.getType(uri) ?: "application/octet-stream"
                            val bytes =
                                resolver.openInputStream(uri)?.use { input ->
                                    val out = ByteArrayOutputStream()
                                    val buffer = ByteArray(16384)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (out.size() + count > 10 * 1024 * 1024)
                                            throw IllegalArgumentException("$name excede 10 MB.")
                                        out.write(buffer, 0, count)
                                    }
                                    out.toByteArray()
                                } ?: throw IllegalArgumentException("Arquivo indisponível.")
                            api.upload(name, mime, bytes)
                        }
                    if (state.user?.login != owner) return@launch
                    state = state.copy(docs = state.docs + doc)
                }
            } catch (e: Exception) {
                notice(e.message ?: "Não foi possível ler o documento.")
            } finally {
                state = state.copy(uploading = false)
            }
        }
    }

    fun stop() {
        val mission = state.conversation.messages.lastOrNull()?.work
        if (mission?.active == true) {
            viewModelScope.launch {
                try {
                    api.json("/api/work/session/stop", JSONObject().put("job_id", mission.jobId))
                    notice("Parada solicitada. Aguardando o ponto seguro da execução.")
                } catch (e: Exception) {
                    notice(e.message ?: "Parada não confirmada.")
                }
            }
            return
        }
        stopped = true
        api.cancel()
    }

    fun workMode() {
        if (!state.busy) state = state.copy(workMode = !state.workMode)
    }

    fun workAutonomy() {
        if (!state.busy)
            state = state.copy(workAutonomy = if (state.workAutonomy == "step") "auto" else "step")
    }

    fun desktopStyle() {
        if (!state.busy)
            state =
                state.copy(
                    desktopStyle = if (state.desktopStyle == "ubuntu") "windows10" else "ubuntu"
                )
    }

    fun send(prompt: String, imageModel: String? = null) {
        val user = state.user ?: return
        if (state.busy || state.uploading || prompt.isBlank()) return
        if (state.workMode && imageModel == null) {
            sendWork(prompt)
            return
        }
        val model = imageModel ?: state.conversation.model
        if (model.isBlank()) {
            notice("Escolha um modelo.")
            return
        }
        if (imageModel == null && state.catalog.optJSONObject("ias")?.has(model) != true) {
            notice("O modelo não está mais disponível. Escolha outro.")
            return
        }
        val snapshot = state.conversation
        val docs = state.docs
        val messages = snapshot.messages + Message(role = "user", text = prompt.trim(), docs = docs)
        val id = newId()
        val epoch = sessionEpoch
        var c =
            snapshot.copy(
                title = if (snapshot.messages.isEmpty()) prompt.trim().take(60) else snapshot.title,
                messages = messages + Message(id, "assistant", "", "sending"),
                updated = System.currentTimeMillis(),
            )
        state = state.copy(conversation = c, docs = emptyList(), busy = true, notice = "")
        stopped = false
        fun update(text: String, status: String = "sending", images: List<String> = emptyList()) {
            c =
                c.copy(
                    messages =
                        c.messages.map {
                            if (it.id == id) it.copy(text = text, status = status, images = images)
                            else it
                        },
                    updated = System.currentTimeMillis(),
                )
            if (epoch == sessionEpoch) state = state.copy(conversation = c)
        }
        viewModelScope.launch {
            var text = ""
            try {
                persist(c, user.login)
                if (imageModel == null) {
                    api.chat(model, messages, docs) { chunk ->
                        text += chunk
                        withContext(Dispatchers.Main) { if (epoch == sessionEpoch) update(text) }
                    }
                    if (text.isBlank())
                        throw IllegalStateException("A conexão terminou sem uma resposta.")
                    update(text, "complete")
                } else {
                    val images =
                        api.image(model, prompt) { message ->
                            withContext(Dispatchers.Main) {
                                if (epoch == sessionEpoch) notice(message)
                            }
                        }
                    update("Imagem criada no Nexus.", "complete", images)
                    notice("")
                }
            } catch (e: Exception) {
                update(text, if (stopped) "stopped" else "error")
                if (epoch == sessionEpoch)
                    notice(
                        if (stopped)
                            "Recebimento interrompido. O uso realizado pode ser contabilizado."
                        else e.message ?: "A resposta não foi concluída."
                    )
            } finally {
                try {
                    persist(c, user.login)
                } catch (_: Exception) {
                    if (epoch == sessionEpoch)
                        notice("Não foi possível salvar esta conversa no aparelho.")
                }
                if (epoch == sessionEpoch) {
                    state = state.copy(busy = false)
                    refresh()
                }
            }
        }
    }

    private fun sendWork(prompt: String) {
        val user = state.user ?: return
        val epoch = sessionEpoch
        val old = state.conversation
        val docs = state.docs
        if (old.model.isBlank()) {
            notice("Selecione um modelo.")
            return
        }
        val messages = old.messages + Message(role = "user", text = prompt.trim(), docs = docs)
        val mission = WorkMission("nxj_" + newId().replace("-", ""))
        val answer = Message(role = "assistant", text = "", status = "sending", work = mission)
        val next =
            old.copy(
                title = if (old.messages.isEmpty()) prompt.take(60) else old.title,
                messages = messages + answer,
                updated = System.currentTimeMillis(),
            )
        state = state.copy(conversation = next, docs = emptyList(), busy = true, notice = "")
        viewModelScope.launch {
            try {
                persist(next, user.login)
                try {
                    api.json(
                        "/api/chat",
                        workStartPayload(
                            mission.jobId,
                            old.model,
                            messages,
                            docs,
                            state.workAutonomy,
                            state.desktopStyle,
                        ),
                    )
                } catch (e: ApiFailure) {
                    if (e.status in 400..499) throw e
                    notice("Início não confirmado. Consultando a missão sem reenviar a tarefa.")
                } catch (_: Exception) {
                    notice("Conexão interrompida. Consultando a missão sem reenviar a tarefa.")
                }
                followWork(next, answer.id, user.login, epoch)
            } catch (e: Exception) {
                if (epoch == sessionEpoch) {
                    val c =
                        state.conversation.copy(
                            messages =
                                state.conversation.messages.map {
                                    if (it.id == answer.id)
                                        it.copy(
                                            status = "error",
                                            work = it.work?.copy(state = "unconfirmed"),
                                        )
                                    else it
                                }
                        )
                    state = state.copy(conversation = c, busy = false)
                    notice(e.message ?: "Não foi possível confirmar a missão.")
                    runCatching { persist(c, user.login) }
                }
            }
        }
    }

    private suspend fun followWork(
        initial: Conversation,
        messageId: String,
        owner: String,
        epoch: Int,
    ) {
        var c = initial
        try {
            while (epoch == sessionEpoch && state.conversation.id == initial.id) {
                var message = c.messages.first { it.id == messageId }
                val before = message.work ?: break
                val result =
                    try {
                        api.json(
                            "/api/work/session/events",
                            JSONObject().put("job_id", before.jobId).put("after", before.cursor),
                        )
                    } catch (e: ApiFailure) {
                        if (e.status in listOf(401, 403, 404)) throw e
                        if (e.status == 409) {
                            c =
                                c.copy(
                                    messages =
                                        c.messages.map {
                                            if (it.id == messageId)
                                                it.copy(work = WorkMission(before.jobId, "running"))
                                            else it
                                        }
                                )
                            continue
                        }
                        notice("Consulta temporariamente indisponível. Nenhuma ação foi repetida.")
                        delay(2000)
                        continue
                    } catch (_: Exception) {
                        if (epoch != sessionEpoch) break
                        notice("Reconectando à missão. Nenhuma ação foi repetida.")
                        delay(2000)
                        continue
                    }
                if (epoch != sessionEpoch || state.conversation.id != initial.id) break
                val mission = before.apply(result)
                message =
                    message.copy(
                        text = mission.displayText(),
                        work = mission,
                        status =
                            if (mission.active) "sending"
                            else if (mission.state in listOf("error", "cancelled", "interrupted"))
                                "error"
                            else "complete",
                    )
                c =
                    c.copy(
                        messages = c.messages.map { if (it.id == messageId) message else it },
                        updated = System.currentTimeMillis(),
                    )
                state = state.copy(conversation = c)
                persist(c, owner)
                if (!mission.active) break
                delay(100)
            }
        } finally {
            if (epoch == sessionEpoch) {
                state = state.copy(busy = false)
                refresh()
            }
        }
    }

    fun workDecision(messageId: String, actionId: String, allow: Boolean) {
        val m = state.conversation.messages.firstOrNull { it.id == messageId } ?: return
        val mission = m.work ?: return
        if (
            state.approvalBusy.isNotBlank() ||
                mission.sessionId.isBlank() ||
                mission.steps.none { it.actionId == actionId && it.status == "waiting" }
        )
            return
        val owner = state.user?.login ?: return
        state = state.copy(approvalBusy = actionId)
        viewModelScope.launch {
            try {
                api.json(
                    "/api/work/control",
                    JSONObject()
                        .put("control_protocol", "nexus-approval-v1")
                        .put("session_id", mission.sessionId)
                        .put("action_id", actionId)
                        .put("decision", if (allow) "allow" else "deny"),
                )
                if (state.user?.login == owner)
                    notice(
                        if (allow) "Autorização recebida. Acompanhe a execução."
                        else "Recusa recebida. Acompanhe o estado da missão."
                    )
            } catch (e: Exception) {
                if (state.user?.login == owner)
                    notice(
                        e.message ?: "Decisão não confirmada. Consulte o estado antes de repetir."
                    )
            } finally {
                state = state.copy(approvalBusy = "")
            }
        }
    }

    suspend fun workFrame(sessionId: String): String? {
        val result = api.json("/api/work/browser/frame", JSONObject().put("session_id", sessionId))
        return result.optString("screenshot").takeIf {
            it.startsWith("data:image/jpeg;base64,") && it.length < 4_000_000
        }
    }

    fun startVoice(model: String) {
        if (state.user == null || state.voiceActive) return
        val config = state.catalog.optJSONObject("voice_ias")?.optJSONObject(model) ?: return
        if (config.optString("transport") != "websocket") {
            notice("Este modelo ainda precisa da integração WebRTC no Android.")
            return
        }
        val epoch = ++voiceEpoch
        state =
            state.copy(
                voiceActive = true,
                voiceStatus = "Conectando…",
                voiceTranscript = emptyList(),
            )
        voice =
            NativeVoice(
                api,
                viewModelScope,
                { status -> if (voiceEpoch == epoch) state = state.copy(voiceStatus = status) },
                { role, text ->
                    if (voiceEpoch == epoch && text.isNotBlank())
                        state =
                            state.copy(
                                voiceTranscript =
                                    (state.voiceTranscript + (role to text)).takeLast(50)
                            )
                },
            )
        viewModelScope.launch { voice?.start(model, config.optString("voice")) }
    }

    fun stopVoice() {
        voice?.stop()
        voice = null
        state = state.copy(voiceActive = false)
    }

    fun muteVoice() {
        voice?.toggleMute()
    }

    fun interruptVoice() {
        voice?.interrupt()
    }

    override fun onCleared() {
        api.cancel()
        stopVoice()
        super.onCleared()
    }
}
