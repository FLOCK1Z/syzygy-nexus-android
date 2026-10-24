package com.syzygy.nexus

/** Presentation only: unknown capabilities stay unavailable until a verified API exists. */
data class WorkCapabilities(
    val workVoiceCommands: Boolean = false,
    val continuousDesktopStream: Boolean = false,
    val remoteMouseKeyboard: Boolean = false,
    val remoteTerminal: Boolean = false,
    val artifactsDownload: Boolean = false,
    val artifactVersions: Boolean = false,
    val parallelAgents: Boolean = false,
    val pauseResume: Boolean = false,
    val livePreview: Boolean = false,
)

data class WorkUiState(
    val message: Message?,
    val mission: WorkMission?,
    val connected: Boolean,
    val observing: Boolean,
    val stopRequested: Boolean,
    val capabilities: WorkCapabilities = WorkCapabilities(),
) {
    val title: String
        get() = when {
            mission == null -> "Nenhuma missão selecionada"
            !connected -> "Conexão interrompida"
            mission.active && !observing -> "Acompanhamento interrompido"
            stopRequested && mission.active -> "Parada solicitada"
            mission.steps.any { it.status == "waiting" } && mission.active -> "Aguardando autorização"
            else -> workStateLabel(mission.state)
        }

    companion object {
        fun from(state: NexusState): WorkUiState {
            val message = state.conversation.messages.lastOrNull { it.work != null }
            return WorkUiState(message, message?.work, state.workConnected,
                state.busy, message?.work?.jobId == state.stopRequestedJob)
        }
    }
}

fun workStateLabel(state: String): String = when (state) {
    "starting" -> "Preparando missão"
    "running" -> "Em execução"
    "awaiting_approval" -> "Aguardando autorização"
    "stop_requested" -> "Parada solicitada"
    "done", "completed", "complete", "succeeded", "success" -> "Missão concluída"
    "cancelled", "canceled" -> "Missão cancelada"
    "error", "failed" -> "Missão não concluída"
    "interrupted" -> "Execução interrompida"
    "unconfirmed" -> "Estado ainda não confirmado"
    else -> "Estado não reconhecido"
}
