package com.syzygy.nexus

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

/** PCM voice transport owned by the activity. No microphone use in background. */
class NativeVoice(
    private val api: NexusApi,
    private val scope: CoroutineScope,
    private val onState: (String) -> Unit,
    private val onTranscript: (String, String) -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile
    var muted = false
        private set

    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var lease: String? = null
    private val running = AtomicBoolean(false)
    private val audio = LinkedBlockingQueue<ByteArray>(64)

    private fun state(text: String) {
        scope.launch(Dispatchers.Main) { onState(text) }
    }

    suspend fun start(model: String, voice: String) {
        if (!running.compareAndSet(false, true)) return
        state("Conectando…")
        try {
            val prepared =
                api.json("/api/voice/prepare", JSONObject().put("model", model).put("voice", voice))
            lease = prepared.getString("session_id")
            if (!running.get()) {
                stopRemote()
                return
            }
            socket =
                api.client.newWebSocket(
                    Request.Builder()
                        .url(NexusApi.ORIGIN.replace("https://", "wss://") + "/api/voice/stream")
                        .header("Origin", NexusApi.ORIGIN)
                        .build(),
                    object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            if (running.get())
                                ws.send(JSONObject().put("session_id", lease).toString())
                            else ws.close(1000, "Encerrado")
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            if (!running.get()) return
                            try {
                                require(text.length <= 200000)
                                val event = JSONObject(text)
                                when (event.optString("type")) {
                                    "nexus.ready" -> {
                                        val rate = event.getInt("input_sample_rate")
                                        require(rate == 16000 || rate == 24000)
                                        require(event.getInt("output_sample_rate") == 24000)
                                        startAudio(rate)
                                        state("Ouvindo")
                                    }
                                    "response.audio.delta" -> {
                                        val pcm =
                                            Base64.decode(event.getString("delta"), Base64.NO_WRAP)
                                        if (pcm.size % 2 != 0 || !audio.offer(pcm))
                                            throw IOException(
                                                "A reprodução de áudio ficou atrasada."
                                            )
                                        state("Falando")
                                    }
                                    "input_audio_buffer.speech_started" -> {
                                        audio.clear()
                                        state(if (muted) "Microfone desligado" else "Ouvindo")
                                    }
                                    "input_audio_buffer.speech_stopped",
                                    "response.created" -> state("Pensando…")
                                    "response.audio_transcript.done",
                                    "response.output_audio_transcript.done" ->
                                        scope.launch(Dispatchers.Main) {
                                            onTranscript("assistant", event.optString("transcript"))
                                        }
                                    "conversation.item.input_audio_transcription.completed" ->
                                        scope.launch(Dispatchers.Main) {
                                            onTranscript("user", event.optString("transcript"))
                                        }
                                    "response.done" ->
                                        state(if (muted) "Microfone desligado" else "Ouvindo")
                                    "nexus.notice" ->
                                        state(
                                            event.optJSONObject("error")?.optString("message")
                                                ?: "Fale novamente."
                                        )
                                    "error" -> {
                                        state(
                                            event.optJSONObject("error")?.optString("message")
                                                ?: "A conexão de voz falhou."
                                        )
                                        stop()
                                    }
                                }
                            } catch (_: Exception) {
                                state(
                                    "Não foi possível manter o áudio. Confira o consumo antes de repetir."
                                )
                                stop()
                            }
                        }

                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                            if (running.get()) {
                                state("Conexão de voz interrompida.")
                                stop()
                            }
                        }

                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            if (running.get()) {
                                state("Conversa encerrada.")
                                stop()
                            }
                        }
                    },
                )
            scope.launch {
                delay(25000)
                if (running.get() && recorder == null) {
                    state("O servidor não confirmou a sessão de voz.")
                    stop()
                }
            }
        } catch (e: Exception) {
            state(e.message ?: "Não foi possível iniciar a voz.")
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(rate: Int) {
        if (!running.get() || recorder != null) return
        val r =
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(
                        AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ),
                        rate / 5,
                    )
                )
                .build()
        recorder = r
        require(r.state == AudioRecord.STATE_INITIALIZED)
        val p =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(
                        AudioTrack.getMinBufferSize(
                            24000,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ),
                        9600,
                    )
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        recorder = r
        player = p
        if (AcousticEchoCanceler.isAvailable())
            echo = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable())
            noise = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        r.startRecording()
        p.play()
        scope.launch(Dispatchers.IO) {
            val bytes = ByteArray(rate / 25)
            try {
                while (running.get()) {
                    val count = r.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) break
                    if (!muted) {
                        val ws = socket ?: break
                        if (ws.queueSize() > 128000) throw IOException()
                        ws.send(
                            JSONObject()
                                .put("type", "input_audio_buffer.append")
                                .put(
                                    "audio",
                                    Base64.encodeToString(bytes, 0, count, Base64.NO_WRAP),
                                )
                                .toString()
                        )
                    }
                }
            } catch (_: Exception) {
                if (running.get()) {
                    state("Captura de áudio interrompida.")
                    stop()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (running.get()) {
                    val bytes = audio.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    var offset = 0
                    while (running.get() && offset < bytes.size) {
                        val count =
                            p.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (count <= 0) break
                        offset += count
                    }
                }
            } catch (_: Exception) {
                if (running.get()) {
                    state("Reprodução de áudio interrompida.")
                    stop()
                }
            }
        }
    }

    fun toggleMute() {
        muted = !muted
        state(if (muted) "Microfone desligado" else "Ouvindo")
    }

    fun interrupt() {
        audio.clear()
        socket?.send("{\"type\":\"response.cancel\"}")
        state(if (muted) "Microfone desligado" else "Ouvindo")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        socket?.close(1000, "Encerrado pelo usuário")
        socket = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.release() }
        player = null
        echo?.release()
        echo = null
        noise?.release()
        noise = null
        audio.clear()
        muted = false
        scope.launch(Dispatchers.IO) { stopRemote() }
    }

    private suspend fun stopRemote() {
        val id = lease ?: return
        lease = null
        try {
            val result = api.json("/api/voice/stop", JSONObject().put("session_id", id))
            if (!result.optBoolean("ended"))
                state("Áudio local encerrado. Encerramento remoto não confirmado.")
        } catch (_: Exception) {
            state(
                "Áudio local encerrado. Confira o consumo; o encerramento remoto não foi confirmado."
            )
        }
    }
}
