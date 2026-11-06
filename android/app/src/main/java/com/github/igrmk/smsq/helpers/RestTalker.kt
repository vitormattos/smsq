package com.github.igrmk.smsq.helpers

import com.github.igrmk.smsq.Constants
import com.github.igrmk.smsq.entities.*
import com.google.crypto.tink.HybridEncrypt
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.modules.SerializersModule
import java.net.HttpURLConnection
import java.net.URL

// Android and Java's built-in HTTP client replace the discontinued Fuel
// dependency. The encrypted request/response wire format is unchanged.
class RestTalker(val logger: Logger, private val baseUrl: String, private val hybridEncrypt: HybridEncrypt?) {
    val tag = this::class.simpleName!!
    val json = Json(JsonConfiguration.Stable, context = SerializersModule {
        polymorphic(BasicResponse::class) {
            SmsResponse::class with SmsResponse.serializer()
        }
    })

    private fun <T> request(url: String, serializer: SerializationStrategy<T>, payload: T): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encryptedEnvelope = body(serializer, payload).toByteArray(Charsets.UTF_8)
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = Constants.SOCKET_TIMEOUT_MS
            connection.readTimeout = Constants.SOCKET_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(encryptedEnvelope.size)
            connection.outputStream.use { it.write(encryptedEnvelope) }
            val status = connection.responseCode
            if (status !in 200..299) {
                logger.lerr(tag, "HTTP request failed with status $status")
                null
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (ex: Exception) {
            logger.lerr(tag, "HTTP request failed: " + ex.javaClass.simpleName)
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun <U, T : BasicResponse<U>> readReply(
        serializer: DeserializationStrategy<T>, response: String?
    ): Pair<U?, Boolean> {
        if (response == null) {
            return Pair(null, false)
        }
        return try {
            val parsed = json.parse(serializer, response)
            if (parsed.error != null || parsed.result == null) {
                logger.lerr(tag, "Backend returned an invalid or unsuccessful response")
                Pair(null, true)
            } else {
                Pair(parsed.result, true)
            }
        } catch (ex: Exception) {
            logger.lerr(tag, "Cannot decode backend response: " + ex.javaClass.simpleName)
            Pair(null, false)
        }
    }

    fun postSms(data: SmsRequest): Pair<DeliveryResult?, Boolean> =
        readReply(
            SmsResponse.serializer(),
            request(postSmsUrl(baseUrl), SmsRequest.serializer(), data)
        )

    fun pollReplies(key: String): List<PendingReply>? {
        val payload = ReplyPollRequest().apply { this.key = key }
        val response = request(
            baseUrl.trimEnd('/') + "/v1/replies/poll",
            ReplyPollRequest.serializer(), payload
        )
        return readReply(ReplyPollResponse.serializer(), response).first
    }

    fun ackReply(key: String, id: Long, success: Boolean): Boolean {
        val payload = ReplyAckRequest().apply {
            this.key = key
            this.id = id
            this.success = success
        }
        val response = request(
            baseUrl.trimEnd('/') + "/v1/replies/ack",
            ReplyAckRequest.serializer(), payload
        )
        val (ack, gotReply) = readReply(ReplyAckResponse.serializer(), response)
        return gotReply && ack == "ok"
    }

    private fun <T> body(serializer: SerializationStrategy<T>, data: T): String {
        val payloadJson = json.stringify(serializer, data)
        val request = Request().apply { this.payload = encrypt(payloadJson) }
        return json.stringify(Request.serializer(), request)
    }

    private fun encrypt(str: String): String {
        val encrypted = hybridEncrypt!!.encrypt(str.toByteArray(), null)
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }
}
