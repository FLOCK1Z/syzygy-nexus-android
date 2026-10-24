package com.syzygy.nexus

import org.json.JSONArray
import org.json.JSONObject

data class WorkStep(val actionId: String, val text: String, val status: String)

data class WorkMission(
    val jobId: String,
    val state: String = "starting",
    val cursor: Int = 0,
    val sessionId: String = "",
    val raw: String = "",
    val steps: List<WorkStep> = emptyList(),
) {
    fun json(): JSONObject =
        JSONObject()
            .put("job_id", jobId)
            .put("state", state)
            .put("cursor", cursor)
            .put("session_id", sessionId)
            .put("raw", raw)

    val active
        get() = state in listOf("starting", "running", "unconfirmed", "awaiting_approval", "stop_requested")

    fun displayText(): String {
        var text =
            raw.replace(Regex("<work_step>.*?</work_step>", RegexOption.DOT_MATCHES_ALL), "")
                .replace("<!-- syzygy_heartbeat -->", "")
                .replace("<work_done>", "")
        val incomplete = text.indexOf("<work_step>")
        if (incomplete >= 0) text = text.take(incomplete)
        return text.trim()
    }

    fun apply(response: JSONObject): WorkMission {
        var next = this
        response.optJSONArray("events")?.objects()?.sortedBy { it.optInt("seq") }?.forEach { event ->
            val seq = event.optInt("seq")
            // Preserve the existing cursor contract; order a batch before deduplicating it.
            if (seq > next.cursor) {
                val raw = next.raw + event.optString("data")
                require(raw.length <= 2_000_000) {
                    "A missão excedeu o histórico disponível no aplicativo."
                }
                next = next.copy(cursor = seq, raw = raw)
            }
        }
        val session =
            response.optString("session_id").takeIf { it.isNotBlank() && it != "null" }
                ?: next.sessionId
        val reported = response.optString("state", "running")
        val state =
            if (next.cursor >= response.optInt("last_seq", next.cursor)) reported else "running"
        val parsed = linkedMapOf<String, WorkStep>()
        var sid = session
        Regex("<work_step>(.*?)</work_step>", RegexOption.DOT_MATCHES_ALL)
            .findAll(next.raw)
            .forEachIndexed { index, match ->
                runCatching {
                    val item = JSONObject(match.groupValues[1])
                    item.optString("session_id").takeIf { it.isNotBlank() }?.let { sid = it }
                    val id = item.optString("action_id")
                    val text = item.optString("text").take(4000)
                    if (text.isNotBlank())
                        parsed[id.ifBlank { "step-$index" }] =
                            WorkStep(id, text, item.optString("status", "running"))
                }
            }
        return next.copy(state = state, sessionId = sid, steps = parsed.values.toList())
    }

    companion object {
        fun from(json: JSONObject): WorkMission =
            WorkMission(
                    json.getString("job_id"),
                    json.optString("state", "unconfirmed"),
                    json.optInt("cursor"),
                    json.optString("session_id"),
                    json.optString("raw"),
                )
                .apply(
                    JSONObject()
                        .put("last_seq", json.optInt("cursor"))
                        .put("state", json.optString("state", "unconfirmed"))
                )
    }
}

fun workStartPayload(
    jobId: String,
    model: String,
    messages: List<Message>,
    docs: List<Doc>,
    autonomy: String = "step",
    desktopStyle: String = "ubuntu",
): JSONObject =
    JSONObject()
        .put("ia", model)
        .put("mensagem", messages.last().text)
        .put(
            "conversation_history",
            JSONArray(
                messages.dropLast(1).map {
                    JSONObject().put("role", it.role).put("content", it.text)
                }
            ),
        )
        .put("doc_id", docs.joinToString(",") { it.id })
        .put("work_mode", true)
        .put("osint", false)
        .put("autonomy_mode", autonomy)
        .put("execution_profile", "separated")
        .put("desktop_style", desktopStyle)
        .put("control_protocol", "nexus-approval-v1")
        .put("detached_work", true)
        .put("work_job_id", jobId)
