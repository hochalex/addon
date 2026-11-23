package at.hochalex.assistent.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Duration

class ClaudeRefusalException(msg: String) : Exception(msg)

/**
 * Dünner Wrapper um das offizielle Anthropic-SDK.
 * Standardmodell: claude-opus-5-5 (in den Einstellungen änderbar).
 */
class ClaudeClient(apiKey: String, val model: String) {

    val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(120))
        .maxRetries(2)
        .build()

    /** Grundgerüst für jede Anfrage inkl. serverseitigem Refusal-Fallback. */
    fun params(maxTokens: Long, effort: OutputConfig.Effort, format: JsonOutputFormat? = null): MessageCreateParams.Builder {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .outputConfig(OutputConfig.builder().effort(effort).apply { if (format != null) format(format) }.build())
        if (supportsServerFallback(model)) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b
    }

    suspend fun create(params: MessageCreateParams): Message = withContext(Dispatchers.IO) {
        val msg = client.messages().create(params)
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw ClaudeRefusalException("Claude hat die Anfrage abgelehnt.")
        }
        msg
    }

    suspend fun text(system: String, user: String, maxTokens: Long = 8000, effort: OutputConfig.Effort = OutputConfig.Effort.MEDIUM): String {
        val msg = create(params(maxTokens, effort).system(system).addUserMessage(user).build())
        return textOf(msg)
    }

    /** Antwort als JSON, garantiert passend zum Schema (Structured Outputs). */
    suspend fun json(system: String, user: String, schema: Map<String, Any?>, maxTokens: Long = 4000, effort: OutputConfig.Effort = OutputConfig.Effort.LOW): JsonObject {
        val format = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().apply {
                schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
            }.build())
            .build()
        val msg = create(params(maxTokens, effort, format).system(system).addUserMessage(user).build())
        return Json.parseToJsonElement(textOf(msg)).jsonObject
    }

    suspend fun test(): String = text("Antworte mit genau einem Wort.", "Sag 'OK'.", 1000, OutputConfig.Effort.LOW)

    companion object {
        fun textOf(msg: Message): String =
            msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()

        fun jsonToString(v: JsonValue): String = jsonMapper().writeValueAsString(v)

        fun supportsServerFallback(model: String) =
            model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") || model == "claude-sonnet-5-5"
    }
}
