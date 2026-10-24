package com.syzygy.nexus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException

private val CommandBlue = Color(0xFF1976FF)
private val CommandViolet = Color(0xFF8256F5)

@Composable
fun NexusCommandTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF67B5FF), onPrimary = Color(0xFF071527),
        secondary = Color(0xFFC1ACFF), onSecondary = Color(0xFF20123E),
        background = Color(0xFF070B16), onBackground = Color(0xFFF3F7FF),
        surface = Color(0xFF0C1425), onSurface = Color(0xFFF3F7FF),
        surfaceVariant = Color(0xFF111C32), onSurfaceVariant = Color(0xFFADC0D9),
        outline = Color(0xFF5C7295), error = Color(0xFFFFA1B0), onError = Color(0xFF4B0A18),
    ) else lightColorScheme(
        primary = Color(0xFF0055C7), onPrimary = Color.White,
        secondary = Color(0xFF6032BA), onSecondary = Color.White,
        background = Color(0xFFF4F7FE), onBackground = Color(0xFF101D34),
        surface = Color.White, onSurface = Color(0xFF101D34),
        surfaceVariant = Color(0xFFE6EDFA), onSurfaceVariant = Color(0xFF415674),
        outline = Color(0xFF65748C), error = Color(0xFFB42240), onError = Color.White,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/** Uses the existing model and dialogs. Navigation never starts or re-sends a mission. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkCommandWorkspace(
    model: NexusModel, dark: Boolean, onChat: () -> Unit, onProfile: () -> Unit,
    onModels: () -> Unit, onTools: () -> Unit, onAttach: () -> Unit, onVoice: () -> Unit,
) {
    val state = model.state
    val ui = WorkUiState.from(state)
    var route by rememberSaveable(state.user?.login) { mutableStateOf("home") }
    var prompt by rememberSaveable(state.user?.login, state.conversation.id) { mutableStateOf("") }
    val voiceAvailable = state.catalog.optJSONObject("voice_ias")?.let { voices ->
        voices.namesList().any { voices.optJSONObject(it)?.optString("transport") == "websocket" }
    } == true
    BackHandler(route != "home") { route = "home" }
    NexusCommandTheme(dark) {
        Scaffold(
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (route == "home") onTools() else route = "home" }) {
                        Icon(if (route == "home") Icons.Outlined.Apps else Icons.Outlined.ArrowBack,
                            if (route == "home") "Ferramentas do Work" else "Voltar ao início")
                    }
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(when (route) {
                            "mission" -> "Execução"
                            "desktop" -> "Computador virtual"
                            "conversation" -> "Conversa com o Work"
                            "results" -> "Resultados"
                            "tasks" -> "Suas tarefas"
                            else -> "NEXUS WORK"
                        }, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(if (route == "home") "Seu espaço para construir" else state.conversation.title,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onProfile) { Icon(Icons.Outlined.AccountCircle, "Perfil e configurações") }
                }
            },
            bottomBar = {
                NavigationBar {
                    listOf("home" to "Início", "tasks" to "Tarefas", "conversation" to "Conversa", "results" to "Resultados").forEach { (key, label) ->
                        NavigationBarItem(selected = route == key, onClick = { route = key },
                            icon = { Icon(when (key) {
                                "home" -> Icons.Outlined.Home
                                "tasks" -> Icons.Outlined.Checklist
                                "conversation" -> Icons.Outlined.ChatBubbleOutline
                                else -> Icons.Outlined.FolderOpen
                            }, null) }, label = { Text(label) })
                    }
                }
            },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
                if (state.notice.isNotBlank()) {
                    CommandPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(state.notice, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.notice("") }) { Text("Dispensar") }
                    }
                }
                when (route) {
                    "home" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface,
                                    CommandBlue.copy(alpha = .18f), CommandViolet.copy(alpha = .12f))))
                                .padding(24.dp)) {
                                Column {
                                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Olá!\nO que vamos\nconstruir hoje?", fontSize = 30.sp,
                                        lineHeight = 37.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 16.dp))
                                    Text("Da ideia à execução, com você no controle.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        item { TaskComposer(state, prompt, { prompt = it }, onAttach, onModels,
                            onVoice, voiceAvailable, { model.removeDoc(it) }) {
                            model.send(prompt)
                            if (model.state.busy) { prompt = ""; route = "mission" }
                        } }
                        if (ui.mission != null) item {
                            CommandPanel(Modifier.fillMaxWidth()) {
                                Text(ui.title, fontWeight = FontWeight.SemiBold)
                                Text(state.conversation.title, style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { route = "mission" }) { Text("Acompanhar missão") }
                            }
                        }
                        items(listOf("Criar aplicação" to "Crie uma aplicação que ",
                            "Analisar dados" to "Analise os dados anexados e ",
                            "Automatizar tarefas" to "Automatize a tarefa de ",
                            "Pesquisar e organizar" to "Pesquise e organize informações sobre ")) { (label, text) ->
                            OutlinedButton(onClick = { prompt = text }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(18.dp)) {
                                Text(label, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null)
                            }
                        }
                        item {
                            TextButton(onClick = onChat, enabled = !state.busy) { Text("Abrir Chat") }
                            if (state.busy) Hint("Conclua a execução antes de alternar para o Chat nesta prévia.")
                        }
                    }
                    "mission" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { MissionHeader(ui, state.workLastUpdate) }
                        if (ui.mission == null) item { Hint("Envie um objetivo no Início ou selecione uma tarefa salva.") }
                        else {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { route = "desktop" }, modifier = Modifier.weight(1f)) { Text("Computador") }
                                    OutlinedButton(onClick = { route = "conversation" }, modifier = Modifier.weight(1f)) { Text("Conversa") }
                                }
                            }
                            if (ui.mission.steps.isEmpty()) item { Hint("As etapas aparecerão quando forem recebidas do executor.") }
                            itemsIndexed(ui.mission.steps, key = { index, step -> step.actionId.ifBlank { "step-$index" } }) { _, step ->
                                CommandPanel(Modifier.fillMaxWidth()) {
                                    Text(when (step.status) {
                                        "waiting" -> "Aguardando sua autorização"
                                        "done", "completed", "success" -> "Etapa concluída"
                                        "error", "failed" -> "Etapa com falha"
                                        "running" -> "Etapa em execução"
                                        else -> "Atualização da execução"
                                    }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    Text(step.text, Modifier.padding(top = 8.dp))
                                    if (step.status == "waiting" && step.actionId.isNotBlank() && ui.mission.active) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { model.workDecision(ui.message!!.id, step.actionId, true) },
                                                enabled = state.approvalBusy.isBlank()) { Text("Autorizar") }
                                            OutlinedButton(onClick = { model.workDecision(ui.message!!.id, step.actionId, false) },
                                                enabled = state.approvalBusy.isBlank()) { Text("Recusar") }
                                        }
                                    }
                                }
                            }
                            if (ui.mission.active) item {
                                Button(onClick = { model.stop() }, enabled = !ui.stopRequested && state.busy,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (ui.stopRequested) "Aguardando parada segura" else "Solicitar parada")
                                }
                                if (!state.busy) OutlinedButton(onClick = { model.select(state.conversation) }) { Text("Consultar missão novamente") }
                            }
                            item { TextButton(onClick = { route = "results" }) { Text("Ver resultados recebidos") } }
                        }
                    }
                    "desktop" -> DesktopScreen(model, ui, Modifier.weight(1f))
                    "conversation" -> {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Hint("A voz disponível é uma conversa geral. Instruções por voz para alterar uma missão ativa ainda não estão disponíveis.") }
                            items(state.conversation.messages, key = { it.id }) { message ->
                                if (message.text.isNotBlank()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                                        CommandPanel(Modifier.widthIn(max = 600.dp).fillMaxWidth(.94f)) {
                                            Text(if (message.role == "user") "Você" else "Nexus Work",
                                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                            NativeMarkdown(message.text)
                                            if (message.status == "error") Hint("Resposta não concluída")
                                        }
                                    }
                                }
                            }
                            if (state.conversation.messages.isEmpty()) item { Hint("Sua conversa com o Work aparecerá aqui.") }
                        }
                        if (state.busy) Hint("A missão continua em acompanhamento. O envio de novas instruções fica disponível ao terminar.")
                        TaskComposer(state, prompt, { prompt = it }, onAttach, onModels, onVoice, voiceAvailable, { model.removeDoc(it) }) {
                            model.send(prompt)
                            if (model.state.busy) { prompt = ""; route = "mission" }
                        }
                    }
                    "tasks" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Button(onClick = { model.newConversation(); route = "home" },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Nova tarefa") } }
                        val tasks = state.history.filter { c -> c.messages.any { it.work != null } }
                        if (tasks.isEmpty()) item { Hint("Nenhuma tarefa salva nesta conta e neste aparelho.") }
                        items(tasks, key = { it.id }) { task ->
                            CommandPanel(Modifier.fillMaxWidth()) {
                                Text(task.title, fontWeight = FontWeight.SemiBold)
                                Text(workStateLabel(task.messages.lastOrNull { it.work != null }?.work?.state.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { if (task.id != state.conversation.id || !state.busy) model.select(task); route = "mission" },
                                    enabled = !state.busy || task.id == state.conversation.id) { Text("Abrir tarefa") }
                            }
                        }
                    }
                    "results" -> ResultsScreen(ui, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TaskComposer(state: NexusState, prompt: String, change: (String) -> Unit,
    attach: () -> Unit, models: () -> Unit, voice: () -> Unit, voiceAvailable: Boolean,
    removeDoc: (String) -> Unit, send: () -> Unit) {
    CommandPanel(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (state.docs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            state.docs.forEach { doc -> InputChip(selected = false, onClick = { removeDoc(doc.id) },
                label = { Text(doc.name) }, trailingIcon = { Icon(Icons.Outlined.Close, "Remover ${doc.name}") },
                modifier = Modifier.padding(end = 8.dp)) }
        }
        OutlinedTextField(value = prompt, onValueChange = change,
            placeholder = { Text("Descreva sua tarefa…") }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = attach, enabled = !state.busy && !state.uploading) { Icon(Icons.Outlined.AttachFile, "Anexar documentos") }
            TextButton(onClick = models, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text(state.conversation.model.ifBlank { "Escolher modelo" }, maxLines = 2)
            }
            IconButton(onClick = voice, enabled = voiceAvailable) { Icon(Icons.Outlined.Mic, "Abrir conversa por voz") }
            FilledIconButton(onClick = send, enabled = !state.busy && !state.uploading && !state.loading &&
                prompt.isNotBlank() && state.conversation.model.isNotBlank()) { Icon(Icons.Outlined.ArrowForward, "Iniciar tarefa") }
        }
        if (state.uploading) Hint("Enviando documentos…")
    }
}

@Composable
private fun MissionHeader(ui: WorkUiState, updated: Long) {
    CommandPanel(Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PrecisionManufacturing, null, tint = MaterialTheme.colorScheme.primary)
        Text(ui.title, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 12.dp))
        Text(if (ui.mission?.active == true) "Acompanhamento dos eventos recebidos" else "Estado registrado da tarefa",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (updated > 0L) Text("Última consulta: " + java.text.DateFormat.getTimeInstance().format(java.util.Date(updated)),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DesktopScreen(model: NexusModel, ui: WorkUiState, modifier: Modifier) {
    val owner = model.state.user?.login
    val job = ui.mission?.jobId
    val session = ui.mission?.sessionId.orEmpty()
    var frame by remember(owner, job, session) { mutableStateOf<String?>(null) }
    var loading by remember(owner, job, session) { mutableStateOf(false) }
    var error by remember(owner, job, session) { mutableStateOf("") }
    var request by remember(owner, job, session) { mutableIntStateOf(0) }
    var zoom by remember(owner, job) { mutableFloatStateOf(1f) }
    var fullscreen by remember(owner, job) { mutableStateOf(false) }
    LaunchedEffect(owner, job, session, request) {
        if (session.isBlank() || request == 0) return@LaunchedEffect
        loading = true; error = ""; frame = null
        try {
            val result = model.workFrame(session)
            if (model.state.user?.login == owner && WorkUiState.from(model.state).mission?.jobId == job) {
                frame = result
                if (result == null) error = "O executor não retornou uma captura disponível."
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Não foi possível obter a captura. Tente atualizar." }
        finally { loading = false }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Captura sob demanda", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Hint("Atualize para solicitar uma imagem ao computador desta missão.")
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                frame != null -> AsyncImage(captureRequest(frame), "Captura do computador desta missão",
                    Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom), contentScale = ContentScale.Fit)
                else -> Hint(error.ifBlank { if (session.isBlank()) "Esta missão ainda não tem uma sessão de computador disponível." else "Nenhuma captura carregada." })
            }
        }
        Button(onClick = { request++ }, enabled = session.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "Consultando…" else "Atualizar captura")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { zoom = (zoom - .25f).coerceAtLeast(1f) }, enabled = frame != null) { Icon(Icons.Outlined.ZoomOut, "Diminuir zoom") }
            IconButton(onClick = { zoom = (zoom + .25f).coerceAtMost(3f) }, enabled = frame != null) { Icon(Icons.Outlined.ZoomIn, "Aumentar zoom") }
            IconButton(onClick = { fullscreen = true }, enabled = frame != null) { Icon(Icons.Outlined.Fullscreen, "Tela cheia") }
        }
        Hint("Controle de mouse, teclado, terminal remoto e arquivos ainda não estão disponíveis neste aplicativo.")
    }
    if (fullscreen && frame != null) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box {
                AsyncImage(captureRequest(frame), "Captura em tela cheia", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                FilledIconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Icon(Icons.Outlined.Close, "Fechar tela cheia") }
            }
        }
    }
}

@Composable
private fun captureRequest(data: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, data) {
        ImageRequest.Builder(context).data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED).build()
    }
}

@Composable
private fun ResultsScreen(ui: WorkUiState, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MissionHeader(ui, 0) }
        val text = ui.mission?.displayText().orEmpty()
        if (text.isNotBlank()) item {
            CommandPanel(Modifier.fillMaxWidth()) {
                Text(if (ui.mission?.active == true) "Resposta parcial recebida" else "Resposta recebida", fontWeight = FontWeight.SemiBold)
                NativeMarkdown(text)
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copiar resposta") }
            }
        } else item { Hint("Nenhum resultado recebido para esta missão.") }
        item { CommandPanel(Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Text("Arquivos e entregas", fontWeight = FontWeight.SemiBold)
            Hint("O contrato atual do aplicativo não fornece uma lista estruturada de artefatos. Downloads, prévia interativa e versões dependem dessa integração.")
        } }
    }
}

@Composable
private fun CommandPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}
