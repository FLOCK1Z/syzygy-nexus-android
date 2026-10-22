package com.syzygy.nexus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Atomic encrypted private app file. No passwords, provider keys or plaintext JWTs. */
class SecureStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "nexus-native-v1.bin"))
    private val alias = "nexus-native-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size in 30..(48 * 1024 * 1024)) { "Histórico local inválido." }
        val buffer = ByteBuffer.wrap(bytes)
        val n = buffer.get().toInt()
        require(n == 12)
        val iv = ByteArray(n)
        buffer.get(iv)
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
        return JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
    }

    private fun write(data: JSONObject) {
        val plain = data.toString().toByteArray(Charsets.UTF_8)
        require(plain.size < 40 * 1024 * 1024) {
            "Histórico local cheio. Exclua conversas antigas."
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain)
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    @Synchronized fun session(): JSONObject? = read().optJSONObject("session")

    @Synchronized
    fun session(value: JSONObject?) {
        val data = read()
        if (value == null) data.remove("session") else data.put("session", value)
        write(data)
    }

    @Synchronized
    fun list(owner: String): List<Conversation> =
        read()
            .optJSONObject("history")
            ?.optJSONArray(owner)
            ?.objects()
            ?.map(::conversationFrom)
            ?.sortedByDescending { it.updated } ?: emptyList()

    @Synchronized
    fun save(owner: String, c: Conversation) {
        if (c.temporary) return
        val data = read()
        val history = data.optJSONObject("history") ?: JSONObject()
        val rows =
            (history.optJSONArray(owner)?.objects()?.map(::conversationFrom) ?: emptyList())
                .filterNot { it.id == c.id } + c
        history.put(owner, JSONArray(rows.sortedByDescending { it.updated }.map { it.json() }))
        data.put("history", history)
        write(data)
    }

    @Synchronized
    fun remove(owner: String, id: String?) {
        val data = read()
        val history = data.optJSONObject("history") ?: return
        if (id == null) history.remove(owner)
        else
            history.put(
                owner,
                JSONArray(
                    (history.optJSONArray(owner)?.objects() ?: emptyList()).filterNot {
                        it.optString("id") == id
                    }
                ),
            )
        data.put("history", history)
        write(data)
    }
}
