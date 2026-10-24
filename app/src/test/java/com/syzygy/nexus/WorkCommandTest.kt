package com.syzygy.nexus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkCommandTest {
    private fun events(vararg chunks: Pair<Int, String>, state: String = "running") =
        JSONObject().put("state", state).put("last_seq", chunks.maxOf { it.first })
            .put("events", JSONArray(chunks.map { (seq, data) -> JSONObject().put("seq", seq).put("data", data) }))

    @Test fun unorderedBatchDoesNotLoseEarlierChunks() {
        val m = WorkMission("nxj_test").apply(events(3 to "C", 1 to "A", 2 to "B", 2 to "B"))
        assertEquals("ABC", m.raw)
        assertEquals(3, m.cursor)
        assertEquals(m, m.apply(events(1 to "A", 3 to "C")))
    }

    @Test fun splitApprovalTagSurvivesSaveAndRestore() {
        val first = WorkMission("nxj_test").apply(events(1 to "<work_step>{\"action_id\":\"a\","))
        assertEquals("", first.displayText())
        val restored = WorkMission.from(first.json())
        val full = restored.apply(events(2 to "\"session_id\":\"s\",\"status\":\"waiting\",\"text\":\"Autorizar\"}</work_step>Resposta"))
        assertEquals("s", full.sessionId)
        assertEquals("waiting", full.steps.single().status)
        assertEquals("Resposta", full.displayText())
    }

    @Test fun serverTerminalStateWaitsForLastEvent() {
        val response = events(1 to "Parcial", state = "completed").put("last_seq", 2)
        val partial = WorkMission("nxj_test").apply(response)
        assertTrue(partial.active)
        val done = partial.apply(events(2 to " final", state = "completed"))
        assertFalse(done.active)
        assertEquals("Parcial final", done.displayText())
    }

    @Test fun unknownStateCannotClaimSuccess() {
        assertEquals("Estado não reconhecido", workStateLabel("future-state"))
        assertEquals("Missão não concluída", workStateLabel("failed"))
    }

    @Test fun unsupportedFeaturesRemainOff() {
        assertEquals(WorkCapabilities(), WorkUiState.from(NexusState()).capabilities)
        val caps = WorkCapabilities()
        assertFalse(caps.workVoiceCommands || caps.continuousDesktopStream || caps.remoteMouseKeyboard ||
            caps.remoteTerminal || caps.artifactsDownload || caps.artifactVersions || caps.parallelAgents ||
            caps.pauseResume || caps.livePreview)
    }

    @Test fun noMissionNeverLooksLikeStopRequested() {
        assertFalse(WorkUiState.from(NexusState()).stopRequested)
    }

    @Test fun disconnectedMissionKeepsLastRealSteps() {
        val m = WorkMission("nxj_test", state = "running", steps = listOf(WorkStep("a", "Recebido", "running")))
        val message = Message(role = "assistant", text = "", work = m)
        val ui = WorkUiState.from(NexusState(conversation = Conversation(messages = listOf(message)), workConnected = false))
        assertEquals("Conexão interrompida", ui.title)
        assertEquals(m.steps, ui.mission!!.steps)
    }
}
