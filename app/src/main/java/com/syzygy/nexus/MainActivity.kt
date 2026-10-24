package com.syzygy.nexus

import android.Manifest
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: NexusModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NexusApp(model) }
    }

    override fun onStop() {
        model.stopVoice()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NexusApp(model: NexusModel) {
    val state = model.state
    var theme by rememberSaveable { mutableStateOf("system") }
    val dark = if (theme == "system") isSystemInDarkTheme() else theme == "dark"
    val palette =
        if (dark)
            darkColorScheme(
                primary = Color.White,
                onPrimary = Color.Black,
                background = Color(0xff171717),
                surface = Color(0xff171717),
                surfaceVariant = Color(0xff272727),
                onSurface = Color(0xfff4f4f4),
                onSurfaceVariant = Color(0xffa6a6a6),
                outline = Color(0xff3a3a3a),
            )
        else
            lightColorScheme(
                primary = Color(0xff171717),
                onPrimary = Color.White,
                background = Color.White,
                surface = Color.White,
                surfaceVariant = Color(0xfff4f4f4),
                outline = Color(0xffdddddd),
            )
    MaterialTheme(colorScheme = palette) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val list = rememberLazyListState()
        val clipboard = LocalClipboardManager.current
        var prompt by rememberSaveable { mutableStateOf("") }
        var panel by rememberSaveable { mutableStateOf("") }
        var search by rememberSaveable { mutableStateOf("") }
        var authMode by rememberSaveable { mutableStateOf("login") }
        var login by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var resetLink by remember { mutableStateOf("") }
        var workFrame by remember { mutableStateOf<String?>(null) }
        var frameBusy by remember { mutableStateOf(false) }
        var imageModel by rememberSaveable { mutableStateOf("") }
        var voiceModel by rememberSaveable { mutableStateOf("") }
        var deleteId by remember { mutableStateOf<String?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        val picker =
            androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) {
                model.upload(it)
            }
        val mic =
            androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { allowed ->
                if (allowed) model.startVoice(voiceModel)
                else model.notice("Permita o microfone para conversar por voz.")
            }
        fun requireUser(): Boolean {
            if (state.user != null) return true
            panel = "auth"
            return false
        }
        fun share(value: String) {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                    },
                    "Compartilhar",
                )
            )
        }
        LaunchedEffect(state.conversation.messages.lastOrNull()?.text) {
            if (state.busy && state.conversation.messages.isNotEmpty())
                list.animateScrollToItem(state.conversation.messages.lastIndex)
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !state.busy,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 330.dp).fillMaxHeight(),
                    drawerContainerColor = if (dark) Color(0xff101010) else Color(0xfff8f8f8),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Nexus", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawer.close() } }) {
                            Icon(Icons.Outlined.Close, "Fechar menu")
                        }
                    }
                    OutlinedTextField(
                        search,
                        { search = it },
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        placeholder = { Text("Buscar conversas") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    NativeRow(Icons.Outlined.Edit, "Nova conversa") {
                        model.newConversation()
                        prompt = ""
                        scope.launch { drawer.close() }
                    }
                    NativeRow(Icons.Outlined.Image, "Imagens") {
                        scope.launch { drawer.close() }
                        if (requireUser()) panel = "images"
                    }
                    Text(
                        "SUAS CONVERSAS",
                        Modifier.padding(20.dp),
                        fontSize = 11.sp,
                        color = palette.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(
                            state.history.filter { it.title.contains(search, true) },
                            key = { it.id },
                        ) { c ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (c.id == state.conversation.id) palette.surfaceVariant
                                        else Color.Transparent
                                    )
                                    .clickable(enabled = !state.busy) {
                                        model.select(c)
                                        prompt = ""
                                        scope.launch { drawer.close() }
                                    }
                                    .padding(start = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.title, Modifier.weight(1f), maxLines = 1, fontSize = 14.sp)
                                IconButton(
                                    onClick = {
                                        deleteId = c.id
                                        confirmDelete = true
                                    },
                                    enabled = !state.busy,
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        "Excluir ${c.title}",
                                        Modifier.size(17.dp),
                                        tint = palette.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (state.history.isEmpty())
                            item {
                                Text(
                                    "As conversas deste aparelho aparecerão aqui.",
                                    Modifier.padding(20.dp),
                                    fontSize = 13.sp,
                                    color = palette.onSurfaceVariant,
                                )
                            }
                    }
                    HorizontalDivider(color = palette.outline)
                    NativeRow(
                        Icons.Outlined.AccountCircle,
                        state.user?.login ?: "Entre na sua conta",
                        state.user?.tier?.uppercase() ?: "Continue com o Nexus",
                    ) {
                        scope.launch { drawer.close() }
                        panel = if (state.user == null) "auth" else "settings"
                    }
                }
            },
        ) {
            if (state.workMode && state.user != null) {
                WorkCommandWorkspace(
                    model = model,
                    dark = dark,
                    onChat = { model.workMode() },
                    onProfile = { panel = "settings" },
                    onModels = { panel = "models" },
                    onTools = { panel = "tools" },
                    onAttach = {
                        picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"))
                    },
                    onVoice = { panel = "voice" },
                )
            } else Scaffold(
                topBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .statusBarsPadding()
                            .height(58.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Outlined.Menu, "Abrir menu")
                        }
                        TextButton(onClick = { if (requireUser()) panel = "models" }) {
                            Text("Nexus", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(19.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.user == null) {
                            Button(
                                onClick = { panel = "auth" },
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                            ) {
                                Text("Entrar")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    model.newConversation()
                                    prompt = ""
                                },
                                enabled = !state.busy,
                            ) {
                                Icon(Icons.Outlined.Edit, "Nova conversa")
                            }
                            IconButton(onClick = { panel = "conversation" }) {
                                Icon(Icons.Outlined.MoreHoriz, "Opções")
                            }
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if (state.loading)
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    else if (state.conversation.messages.isEmpty())
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(15.dp),
                                border = BorderStroke(1.dp, palette.onSurface),
                                color = Color.Transparent,
                            ) {
                                Text(
                                    "N",
                                    Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                    fontSize = 28.sp,
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                if (state.conversation.temporary) "Conversa temporária"
                                else "Como posso ajudar?",
                                fontSize = 29.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (state.conversation.temporary)
                                    "Não será salva no histórico deste aparelho."
                                else "Pense, crie e explore com o Nexus.",
                                Modifier.padding(top = 12.dp),
                                color = palette.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(22.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                listOf(
                                        "Escrever algo" to "Ajude-me a escrever um texto ",
                                        "Resumir um documento" to
                                            "Resuma os pontos principais do documento ",
                                        "Explorar uma ideia" to "Quero explorar uma ideia: ",
                                    )
                                    .forEach { (label, value) ->
                                        OutlinedButton(
                                            onClick = { prompt = value },
                                            Modifier.padding(4.dp),
                                        ) {
                                            Text(label, fontSize = 12.sp)
                                        }
                                    }
                            }
                        }
                    else
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            state = list,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(26.dp),
                        ) {
                            items(state.conversation.messages, key = { it.id }) { message ->
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment =
                                        if (message.role == "user") Alignment.End
                                        else Alignment.Start,
                                ) {
                                    if (message.role == "user")
                                        Surface(
                                            shape = RoundedCornerShape(23.dp),
                                            color = palette.surfaceVariant,
                                            modifier = Modifier.widthIn(max = 310.dp),
                                        ) {
                                            Column(Modifier.padding(15.dp)) {
                                                SelectionContainerText(message.text)
                                                message.docs.forEach {
                                                    Text(
                                                        "▤ ${it.name}",
                                                        fontSize = 12.sp,
                                                        color = palette.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    else {
                                        NativeMarkdown(
                                            message.text.ifBlank {
                                                if (message.work != null) "Missão em andamento"
                                                else if (message.status == "sending") "●"
                                                else "Resposta não recebida."
                                            }
                                        )
                                        message.work?.let { mission ->
                                            NativeWorkCard(
                                                mission,
                                                state.approvalBusy,
                                                { id, allow ->
                                                    model.workDecision(message.id, id, allow)
                                                },
                                                {
                                                    scope.launch {
                                                        frameBusy = true
                                                        try {
                                                            workFrame =
                                                                model.workFrame(mission.sessionId)
                                                            if (workFrame == null)
                                                                model.notice(
                                                                    "Imagem do computador indisponível agora."
                                                                )
                                                        } catch (_: Exception) {
                                                            model.notice(
                                                                "A visualização está indisponível ou protegida por uma sessão privada."
                                                            )
                                                        } finally {
                                                            frameBusy = false
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        message.images.forEach { src ->
                                            AsyncImage(
                                                model = src,
                                                contentDescription = "Imagem criada no Nexus",
                                                modifier =
                                                    Modifier.fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(18.dp)),
                                            )
                                        }
                                        if (message.status != "sending")
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        clipboard.setText(
                                                            AnnotatedString(message.text)
                                                        )
                                                        model.notice("Resposta copiada.")
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.ContentCopy,
                                                        "Copiar",
                                                        Modifier.size(18.dp),
                                                        tint = palette.onSurfaceVariant,
                                                    )
                                                }
                                                IconButton(onClick = { share(message.text) }) {
                                                    Icon(
                                                        Icons.Outlined.Share,
                                                        "Compartilhar",
                                                        Modifier.size(18.dp),
                                                        tint = palette.onSurfaceVariant,
                                                    )
                                                }
                                                if (
                                                    message.id ==
                                                        state.conversation.messages.lastOrNull()?.id
                                                )
                                                    IconButton(
                                                        onClick = {
                                                            prompt =
                                                                state.conversation.messages
                                                                    .lastOrNull {
                                                                        it.role == "user"
                                                                    }
                                                                    ?.text ?: ""
                                                            model.notice(
                                                                "Pedido preparado. Envie para solicitar outra resposta."
                                                            )
                                                        },
                                                        enabled = !state.busy,
                                                    ) {
                                                        Icon(
                                                            Icons.Outlined.Refresh,
                                                            "Preparar outra resposta",
                                                            Modifier.size(18.dp),
                                                            tint = palette.onSurfaceVariant,
                                                        )
                                                    }
                                                if (message.status in listOf("error", "stopped"))
                                                    Text(
                                                        if (message.status == "error")
                                                            "Não concluída"
                                                        else "Interrompida",
                                                        fontSize = 12.sp,
                                                        color = palette.onSurfaceVariant,
                                                    )
                                            }
                                    }
                                }
                            }
                        }
                    if (state.notice.isNotBlank())
                        Surface(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .clickable { model.notice("") },
                            color = palette.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(state.notice, Modifier.padding(12.dp), fontSize = 12.sp)
                        }
                    if (state.docs.isNotEmpty())
                        Row(
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp)
                        ) {
                            state.docs.forEach { doc ->
                                InputChip(
                                    selected = false,
                                    onClick = { model.removeDoc(doc.id) },
                                    label = { Text(doc.name, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            "Remover ${doc.name}",
                                            Modifier.size(14.dp),
                                        )
                                    },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = palette.surfaceVariant,
                        border = BorderStroke(1.dp, palette.outline),
                    ) {
                        Column {
                            TextField(
                                prompt,
                                { prompt = it },
                                Modifier.fillMaxWidth(),
                                placeholder = { Text("Pergunte alguma coisa") },
                                maxLines = 6,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                enabled = !state.loading,
                            )
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 5.dp, end = 9.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { panel = "tools" }, enabled = !state.busy) {
                                    Icon(Icons.Outlined.Add, "Adicionar arquivos ou ferramentas")
                                }
                                Text(
                                    if (state.uploading) "Enviando documento…"
                                    else
                                        (if (state.workMode) "Work · " else "") +
                                            state.conversation.model.ifBlank { "Nexus" },
                                    Modifier.weight(1f),
                                    fontSize = 11.sp,
                                    color = palette.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                FilledIconButton(
                                    onClick = {
                                        when {
                                            state.busy -> model.stop()
                                            prompt.isNotBlank() ->
                                                if (requireUser()) {
                                                    model.send(prompt)
                                                    prompt = ""
                                                }
                                            else -> if (requireUser()) panel = "voice"
                                        }
                                    },
                                    enabled = !state.uploading && !state.loading,
                                    modifier = Modifier.size(37.dp),
                                ) {
                                    Icon(
                                        if (state.busy) Icons.Outlined.Stop
                                        else if (prompt.isNotBlank()) Icons.Outlined.ArrowUpward
                                        else Icons.Outlined.GraphicEq,
                                        if (state.busy) "Interromper"
                                        else if (prompt.isNotBlank()) "Enviar" else "Voz",
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "O Nexus pode cometer erros. Confira informações importantes.",
                        Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                        fontSize = 10.sp,
                        color = palette.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        if (frameBusy)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Consultando computador…") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        if (workFrame != null)
            AlertDialog(
                onDismissRequest = { workFrame = null },
                title = { Text("Computador do Work") },
                text = {
                    AsyncImage(
                        model = workFrame,
                        contentDescription = "Captura real do computador da missão",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.4f),
                    )
                },
                confirmButton = { TextButton(onClick = { workFrame = null }) { Text("Fechar") } },
            )
        if (confirmDelete)
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Excluir conversa?") },
                text = { Text("A cópia deste aparelho será apagada.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.remove(deleteId)
                            confirmDelete = false
                        }
                    ) {
                        Text("Excluir")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
                },
            )
        if (panel.isNotBlank())
            ModalBottomSheet(
                onDismissRequest = {
                    panel = ""
                    password = ""
                    confirm = ""
                    resetLink = ""
                    if (state.voiceActive) model.stopVoice()
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        when (panel) {
                            "auth" ->
                                when (authMode) {
                                    "register" -> "Crie sua conta"
                                    "recover" -> "Recuperar acesso"
                                    "reset" -> "Nova senha"
                                    else -> "Bem-vindo ao Nexus"
                                }
                            "models" -> "Escolha seu modelo"
                            "settings" -> "Configurações"
                            "usage" -> "Seu uso"
                            "images" -> "Criar uma imagem"
                            "voice" -> "Voz ao vivo"
                            "tools" -> "Adicionar à conversa"
                            else -> "Conversa"
                        },
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    when (panel) {
                        "auth" -> {
                            Text(
                                if (authMode == "recover")
                                    "Enviaremos um link válido por até 15 minutos, se houver uma conta local elegível."
                                else "Use sua conta Nexus para continuar.",
                                fontSize = 14.sp,
                                color = palette.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            if (authMode != "reset")
                                OutlinedTextField(
                                    login,
                                    { login = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("E-mail ou login") },
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Email),
                                )
                            else
                                OutlinedTextField(
                                    resetLink,
                                    { resetLink = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("Link recebido por e-mail") },
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            if (authMode != "recover") {
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    password,
                                    { password = it },
                                    Modifier.fillMaxWidth(),
                                    label = {
                                        Text(if (authMode == "reset") "Nova senha" else "Senha")
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Password),
                                )
                            }
                            if (authMode in listOf("register", "reset")) {
                                Text(
                                    "15 a 128 caracteres, com maiúscula, minúscula, número e símbolo.",
                                    Modifier.padding(vertical = 10.dp),
                                    fontSize = 12.sp,
                                    color = palette.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    confirm,
                                    { confirm = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("Confirmar senha") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            }
                            if (state.notice.isNotBlank())
                                Text(
                                    state.notice,
                                    Modifier.padding(vertical = 12.dp),
                                    fontSize = 13.sp,
                                )
                            Button(
                                onClick = {
                                    when (authMode) {
                                        "recover" -> model.recover(login)
                                        "reset" ->
                                            model.reset(resetLink, password, confirm) {
                                                authMode = "login"
                                                password = ""
                                                confirm = ""
                                                resetLink = ""
                                            }
                                        else ->
                                            model.auth(login, password, authMode == "register") {
                                                password = ""
                                                confirm = ""
                                                panel = ""
                                            }
                                    }
                                },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            ) {
                                Text(
                                    if (state.busy) "Aguarde…"
                                    else
                                        when (authMode) {
                                            "register" -> "Criar conta"
                                            "recover" -> "Enviar recuperação"
                                            "reset" -> "Aplicar nova senha"
                                            else -> "Entrar"
                                        }
                                )
                            }
                            if (authMode == "login") {
                                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank())
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val option =
                                                        GetSignInWithGoogleOption.Builder(
                                                                BuildConfig.GOOGLE_WEB_CLIENT_ID
                                                            )
                                                            .build()
                                                    val result =
                                                        CredentialManager.create(context)
                                                            .getCredential(
                                                                MutableContextWrapper(context),
                                                                GetCredentialRequest.Builder()
                                                                    .addCredentialOption(option)
                                                                    .build(),
                                                            )
                                                    val credential =
                                                        result.credential as? CustomCredential
                                                            ?: error(
                                                                "Credencial do Google inválida."
                                                            )
                                                    if (
                                                        credential.type !=
                                                            GoogleIdTokenCredential
                                                                .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                                    )
                                                        error(
                                                            "Credencial do Google não reconhecida."
                                                        )
                                                    model.googleToken(
                                                        GoogleIdTokenCredential.createFrom(
                                                                credential.data
                                                            )
                                                            .idToken
                                                    ) {
                                                        panel = ""
                                                    }
                                                } catch (
                                                    _:
                                                        GetCredentialCancellationException) {} catch (
                                                    _: NoCredentialException) {
                                                    model.notice(
                                                        "Nenhuma conta do Google disponível. Verifique sua conta no Android e tente novamente."
                                                    )
                                                } catch (_: Exception) {
                                                    model.notice(
                                                        "O Google não confirmou o acesso. Tente novamente."
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.busy,
                                    ) {
                                        Text("Continuar com Google")
                                    }
                                TextButton(
                                    onClick = {
                                        authMode = "recover"
                                        model.notice("")
                                    }
                                ) {
                                    Text("Esqueci minha senha")
                                }
                                TextButton(
                                    onClick = {
                                        authMode = "register"
                                        model.notice("")
                                    }
                                ) {
                                    Text("Criar uma conta")
                                }
                            } else {
                                if (authMode == "recover")
                                    TextButton(
                                        onClick = {
                                            authMode = "reset"
                                            model.notice("")
                                        }
                                    ) {
                                        Text("Já recebi o link")
                                    }
                                TextButton(
                                    onClick = {
                                        authMode = "login"
                                        password = ""
                                        confirm = ""
                                        model.notice("")
                                    }
                                ) {
                                    Text("Voltar para entrar")
                                }
                            }
                        }
                        "models" -> {
                            val names =
                                state.catalog.optJSONObject("ias")?.namesList() ?: emptyList()
                            names.forEach { name ->
                                NativeRow(
                                    Icons.Outlined.AutoAwesome,
                                    name,
                                    if (name == state.conversation.model) "Selecionado" else "",
                                ) {
                                    model.model(name)
                                    panel = ""
                                }
                            }
                            if (names.isEmpty()) Text("Nenhum modelo disponível para esta conta.")
                            NativeRow(Icons.Outlined.Refresh, "Atualizar modelos") {
                                scope.launch { model.refresh() }
                            }
                        }
                        "settings" -> {
                            Text(state.user?.login ?: "", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Plano ${state.user?.tier?.uppercase()}",
                                fontSize = 13.sp,
                                color = palette.onSurfaceVariant,
                            )
                            NativeRow(Icons.Outlined.Tune, "Uso e limites") {
                                panel = "usage"
                                scope.launch { model.refresh() }
                            }
                            NativeRow(
                                Icons.Outlined.Contrast,
                                "Aparência",
                                when (theme) {
                                    "dark" -> "Escura"
                                    "light" -> "Clara"
                                    else -> "Igual ao sistema"
                                },
                            ) {
                                theme =
                                    when (theme) {
                                        "system" -> "dark"
                                        "dark" -> "light"
                                        else -> "system"
                                    }
                            }
                            NativeRow(
                                Icons.Outlined.Security,
                                "Histórico neste aparelho",
                                "Protegido com a chave de segurança do Android",
                            ) {
                                model.notice(
                                    "As conversas são locais e separadas por conta. Não são sincronizadas com o site."
                                )
                            }
                            NativeRow(Icons.Outlined.Delete, "Apagar histórico local") {
                                deleteId = null
                                confirmDelete = true
                            }
                            if (BuildConfig.PRIVACY_URL.startsWith("https://"))
                                NativeRow(Icons.Outlined.PrivacyTip, "Privacidade") {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            BuildConfig.PRIVACY_URL.toUri(),
                                        )
                                    )
                                }
                            if (BuildConfig.DELETE_ACCOUNT_URL.startsWith("https://"))
                                NativeRow(
                                    Icons.Outlined.PersonRemove,
                                    "Solicitar exclusão da conta",
                                ) {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            BuildConfig.DELETE_ACCOUNT_URL.toUri(),
                                        )
                                    )
                                }
                            NativeRow(Icons.Outlined.Logout, "Sair da conta") {
                                model.logout()
                                scope.launch {
                                    runCatching {
                                        CredentialManager.create(context)
                                            .clearCredentialState(ClearCredentialStateRequest())
                                    }
                                }
                                panel = ""
                            }
                            Text(
                                "Nexus · versão de testes",
                                fontSize = 11.sp,
                                color = palette.onSurfaceVariant,
                                modifier = Modifier.padding(top = 20.dp),
                            )
                        }
                        "usage" -> {
                            NativeQuota(state.quota)
                            NativeRow(Icons.Outlined.Refresh, "Atualizar agora") {
                                scope.launch { model.refresh() }
                            }
                        }
                        "tools" -> {
                            NativeRow(
                                Icons.Outlined.Computer,
                                if (state.workMode) "Voltar para Chat" else "Usar Work",
                                "Agente em computador separado",
                            ) {
                                if (requireUser()) {
                                    model.workMode()
                                    panel = ""
                                }
                            }
                            if (state.workMode) {
                                NativeRow(
                                    Icons.Outlined.Security,
                                    "Autorização de ações",
                                    if (state.workAutonomy == "step") "Passo a passo"
                                    else "Autônomo",
                                ) {
                                    model.workAutonomy()
                                }
                                NativeRow(
                                    Icons.Outlined.DesktopWindows,
                                    "Aparência do computador",
                                    if (state.desktopStyle == "ubuntu") "Linux" else "Windows",
                                ) {
                                    model.desktopStyle()
                                }
                            }
                            NativeRow(
                                Icons.Outlined.AttachFile,
                                "Adicionar arquivos",
                                "PDF, DOCX e TXT · até 10 MB por arquivo",
                            ) {
                                if (requireUser()) {
                                    panel = ""
                                    picker.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "text/plain",
                                        )
                                    )
                                }
                            }
                            NativeRow(Icons.Outlined.Image, "Criar uma imagem") {
                                if (requireUser()) panel = "images"
                            }
                            NativeRow(Icons.Outlined.GraphicEq, "Conversar por voz") {
                                if (requireUser()) panel = "voice"
                            }
                        }
                        "images" -> {
                            Text(
                                "Descreva o que deseja criar. A geração usa seu plano Nexus.",
                                fontSize = 14.sp,
                                color = palette.onSurfaceVariant,
                            )
                            val names =
                                state.catalog.optJSONObject("image_ias")?.namesList() ?: emptyList()
                            names.forEach { name ->
                                NativeRow(
                                    Icons.Outlined.Image,
                                    name,
                                    if (imageModel == name) "Selecionado" else "",
                                ) {
                                    imageModel = name
                                }
                            }
                            if (names.isEmpty())
                                Text(
                                    "Nenhum modelo de imagem disponível.",
                                    Modifier.padding(vertical = 16.dp),
                                )
                            OutlinedTextField(
                                prompt,
                                { prompt = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Descrição da imagem") },
                                minLines = 3,
                            )
                            Button(
                                onClick = {
                                    model.send(prompt, imageModel)
                                    prompt = ""
                                    panel = ""
                                },
                                enabled = !state.busy && prompt.isNotBlank() && imageModel in names,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            ) {
                                Text("Criar imagem")
                            }
                        }
                        "voice" -> {
                            if (!state.voiceActive) {
                                val voices = state.catalog.optJSONObject("voice_ias")
                                val names =
                                    voices?.namesList()?.filter {
                                        voices.getJSONObject(it).optString("transport") ==
                                            "websocket"
                                    } ?: emptyList()
                                Text(
                                    "O microfone só será usado enquanto esta conversa estiver aberta.",
                                    fontSize = 14.sp,
                                    color = palette.onSurfaceVariant,
                                )
                                names.forEach { name ->
                                    NativeRow(
                                        Icons.Outlined.GraphicEq,
                                        name,
                                        if (name == voiceModel) "Selecionado" else "",
                                    ) {
                                        voiceModel = name
                                    }
                                }
                                if (names.isEmpty())
                                    Text(
                                        "Nenhum modelo compatível com voz nativa está disponível agora.",
                                        Modifier.padding(vertical = 16.dp),
                                    )
                                Button(
                                    onClick = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                                    enabled = voiceModel in names,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Iniciar conversa")
                                }
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().height(190.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xff8b73cf),
                                        modifier = Modifier.size(110.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.GraphicEq,
                                                null,
                                                Modifier.size(48.dp),
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    state.voiceStatus,
                                    Modifier.align(Alignment.CenterHorizontally),
                                    fontSize = 18.sp,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    IconButton(onClick = { model.muteVoice() }) {
                                        Icon(Icons.Outlined.MicOff, "Ligar ou desligar microfone")
                                    }
                                    IconButton(onClick = { model.interruptVoice() }) {
                                        Icon(Icons.Outlined.StopCircle, "Interromper resposta")
                                    }
                                    IconButton(onClick = { model.stopVoice() }) {
                                        Icon(Icons.Outlined.Close, "Encerrar conversa")
                                    }
                                }
                                state.voiceTranscript.takeLast(6).forEach { (role, text) ->
                                    Text(
                                        (if (role == "user") "Você: " else "Nexus: ") + text,
                                        Modifier.padding(vertical = 7.dp),
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                        "conversation" -> {
                            NativeRow(Icons.Outlined.Edit, "Nova conversa") {
                                model.newConversation()
                                prompt = ""
                                panel = ""
                            }
                            NativeRow(Icons.Outlined.Security, "Nova conversa temporária") {
                                model.newConversation(true)
                                prompt = ""
                                panel = ""
                            }
                            NativeRow(Icons.Outlined.Share, "Compartilhar como texto") {
                                share(
                                    state.conversation.messages.joinToString("\n\n") {
                                        (if (it.role == "user") "Você: " else "Nexus: ") + it.text
                                    }
                                )
                            }
                            NativeRow(Icons.Outlined.Delete, "Excluir esta conversa") {
                                deleteId = state.conversation.id
                                confirmDelete = true
                                panel = ""
                            }
                        }
                    }
                }
            }
    }
}

@Composable
fun NativeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            if (detail.isNotBlank())
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SelectionContainerText(value: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(value, fontSize = 16.sp, lineHeight = 25.sp)
    }
}

@Composable
fun NativeMarkdown(value: String) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val context = LocalContext.current
    val markdown = remember(context) { Markwon.create(context) }
    AndroidView(
        factory = {
            TextView(it).apply {
                textSize = 16f
                setTextIsSelectable(true)
                setLineSpacing(5f, 1f)
            }
        },
        update = {
            it.setTextColor(color)
            markdown.setMarkdown(it, value)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NativeQuota(q: JSONObject?) {
    if (q == null) {
        Text("Consumo indisponível. Toque em atualizar para consultar.")
        return
    }
    listOf("chat" to "Chat", "work" to "Work").forEach { (key, title) ->
        val group = q.optJSONObject(key)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(17.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                listOf("short" to "Janela atual", "week" to "Semanal").forEach { (name, label) ->
                    val row = group?.optJSONObject(name)
                    if (row != null) {
                        Text(
                            "$label · ${row.optDouble("used")} / ${row.optDouble("limit")} unidades",
                            Modifier.padding(top = 15.dp, bottom = 8.dp),
                            fontSize = 13.sp,
                        )
                        LinearProgressIndicator(
                            progress = {
                                ((row.optDouble("percent", 0.0) / 100).toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${row.optDouble("remaining")} disponíveis",
                            Modifier.padding(top = 6.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Text(
        "Créditos Work: ${q.optJSONObject("credits")?.optDouble("balance",0.0)?:0.0}",
        Modifier.padding(vertical = 12.dp),
    )
    Text(
        q.optString("policy_note"),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun NativeWorkCard(
    mission: WorkMission,
    approvalBusy: String,
    decide: (String, Boolean) -> Unit,
    frame: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                workStateLabel(mission.state),
                fontWeight = FontWeight.SemiBold,
            )
            mission.steps.takeLast(12).forEach { step ->
                Text(step.text, Modifier.padding(top = 11.dp), fontSize = 13.sp)
                if (step.status == "waiting" && step.actionId.isNotBlank() && mission.active)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = { decide(step.actionId, true) },
                            enabled = approvalBusy.isBlank(),
                        ) {
                            Text("Autorizar")
                        }
                        OutlinedButton(
                            onClick = { decide(step.actionId, false) },
                            enabled = approvalBusy.isBlank(),
                        ) {
                            Text("Recusar")
                        }
                    }
            }
            if (mission.sessionId.isNotBlank())
                OutlinedButton(onClick = frame, modifier = Modifier.padding(top = 10.dp)) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Ver computador")
                }
        }
    }
}
