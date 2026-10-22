package com.syzygy.nexus

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NexusApiTest {
    private fun server(): MockWebServer = MockWebServer().apply { start() }

    @Test
    fun loginUsesExistingContract() = runBlocking {
        val s = server()
        try {
            s.enqueue(
                MockResponse().setBody("{\"token\":\"session\",\"usuario\":{\"login\":\"user\"}}")
            )
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            api.json("/api/login", JSONObject().put("login", "user").put("senha", "hidden"))
            val request = s.takeRequest()
            assertEquals("/api/login", request.path)
            assertEquals("hidden", JSONObject(request.body.readUtf8()).getString("senha"))
            assertNull(request.getHeader("Authorization"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun chatStreamsUtf8AndAttachesOwnedDocuments() = runBlocking {
        val s = server()
        try {
            s.enqueue(MockResponse().setChunkedBody("Olá 🌎!", 2))
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            api.token = "private"
            var text = ""
            api.chat(
                "Nome público",
                listOf(
                    Message(role = "assistant", text = "Antes"),
                    Message(role = "user", text = "Pergunta"),
                ),
                listOf(Doc("document-id", "file.pdf")),
            ) {
                text += it
            }
            assertEquals("Olá 🌎!", text)
            val request = s.takeRequest()
            assertEquals("Bearer private", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("document-id", body.getString("doc_id"))
            assertEquals(
                "assistant",
                body.getJSONArray("conversation_history").getJSONObject(0).getString("role"),
            )
            assertFalse(body.getBoolean("work_mode"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun unauthorizedDoesNotRetryOrHideServerError() = runBlocking {
        val s = server()
        try {
            s.enqueue(MockResponse().setResponseCode(401).setBody("{\"erro\":\"Sessão expirada\"}"))
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            try {
                api.json("/api/init")
                fail("401 was accepted")
            } catch (e: ApiFailure) {
                assertEquals(401, e.status)
                assertEquals("Sessão expirada", e.message)
            }
            assertEquals(1, s.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun htmlErrorRetainsHttpStatus() = runBlocking {
        val s = server()
        try {
            s.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setChunkedBody("<html>Gateway unavailable</html>", 3)
            )
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            try {
                api.json("/api/init")
                fail("503 was accepted")
            } catch (e: ApiFailure) {
                assertEquals(503, e.status)
            }
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun multipartSetsItsOwnBoundary() = runBlocking {
        val s = server()
        try {
            s.enqueue(MockResponse().setBody("{\"doc_id\":\"a\",\"filename\":\"a.pdf\"}"))
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            assertEquals("a", api.upload("a.pdf", "application/pdf", byteArrayOf(1, 2)).id)
            val request = s.takeRequest()
            assertTrue(
                request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary=")
            )
            assertTrue(request.body.readUtf8().contains("a.pdf"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun imageResultRequiresConfirmedImages() = runBlocking {
        val s = server()
        try {
            s.enqueue(
                MockResponse()
                    .setChunkedBody(
                        "data: {\"type\":\"progress\"}\n\ndata: {\"type\":\"result\",\"images\":[{\"src\":\"data:image/png;base64,YQ==\"}]}\n\n",
                        7,
                    )
            )
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            assertEquals(listOf("data:image/png;base64,YQ=="), api.image("Imagem", "Teste") {})
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun imageProgressWithoutResultIsFailure() = runBlocking {
        val s = server()
        try {
            s.enqueue(MockResponse().setBody("data: {\"type\":\"progress\"}\n\n"))
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            try {
                api.image("Imagem", "Teste") {}
                fail("Incomplete generation was accepted")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("sem imagem confirmada"))
            }
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun passwordMinimumClientChecks() {
        assertNotNull(passwordProblem("short"))
        assertNotNull(passwordProblem("aaaaaaaaaaaaaaaa"))
        assertNull(passwordProblem("Nexus-Example2026!"))
        assertNotNull(passwordProblem("A".repeat(129)))
    }

    @Test
    fun interruptedConversationRestoresAsStopped() {
        val c =
            Conversation(
                messages = listOf(Message(role = "assistant", text = "Parcial", status = "sending"))
            )
        val restored = conversationFrom(c.json())
        assertEquals("stopped", restored.messages.single().status)
        assertEquals("Parcial", restored.messages.single().text)
    }

    @Test
    fun workEventsAreDeduplicatedAndApprovalStaysExplicit() {
        val raw =
            "<work_step>{\"action_id\":\"a1\",\"session_id\":\"session\",\"status\":\"waiting\",\"text\":\"Confirmar ação\"}</work_step>Resultado"
        val response =
            JSONObject()
                .put("state", "running")
                .put("last_seq", 1)
                .put(
                    "events",
                    org.json.JSONArray().put(JSONObject().put("seq", 1).put("data", raw)),
                )
        val once = WorkMission("nxj_test").apply(response)
        val twice = once.apply(response)
        assertEquals(once, twice)
        assertEquals("Resultado", twice.displayText())
        assertEquals("waiting", twice.steps.single().status)
        assertEquals("session", twice.sessionId)
    }

    @Test
    fun incompleteWorkTagsAreNotShownAsJson() {
        val m = WorkMission("nxj_test", raw = "Texto público<work_step>{\"status\":")
        assertEquals("Texto público", m.displayText())
    }

    @Test
    fun workStartUsesPresavedIdentityAndSeparatedExecution() {
        val body =
            workStartPayload(
                "nxj_saved",
                "Modelo",
                listOf(Message(role = "user", text = "Execute minha tarefa")),
                emptyList(),
            )
        assertEquals("nxj_saved", body.getString("work_job_id"))
        assertEquals("separated", body.getString("execution_profile"))
        assertEquals("step", body.getString("autonomy_mode"))
        assertTrue(body.getBoolean("detached_work"))
        assertEquals("nexus-approval-v1", body.getString("control_protocol"))
    }

    @Test
    fun workQueriesCarryRequiredHeader() = runBlocking {
        val s = server()
        try {
            s.enqueue(MockResponse().setBody("{\"state\":\"running\"}"))
            val api = NexusApi(s.url("/").toString().removeSuffix("/"))
            api.token = "session"
            api.json(
                "/api/work/session/events",
                JSONObject().put("job_id", "nxj_saved").put("after", 0),
            )
            val request = s.takeRequest()
            assertEquals("1", request.getHeader("X-Nexus-Work"))
            assertEquals("nxj_saved", JSONObject(request.body.readUtf8()).getString("job_id"))
        } finally {
            s.shutdown()
        }
    }
}
