package at.hochalex.assistent

import at.hochalex.assistent.ai.ClaudeClient
import at.hochalex.assistent.ai.Prompts
import at.hochalex.assistent.voice.VoiceAgent
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.OutputConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft, dass die an die Claude API gesendeten Anfragen die erwartete Form haben (ohne Netz). */
class RequestShapeTest {
    private val claude = ClaudeClient("test-key", "claude-opus-5-5")

    private fun body(p: com.anthropic.models.messages.MessageCreateParams) =
        Json.parseToJsonElement(jsonMapper().writeValueAsString(p._body())).jsonObject

    @Test
    fun structuredOutputRequest() {
        val format = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().apply {
                Prompts.ANALYZE_SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
            }.build()).build()
        val p = claude.params(4000, OutputConfig.Effort.LOW, format).system("s").addUserMessage("u").build()
        val b = body(p)
        println(b)
        assertEquals("claude-opus-5-5", b["model"]!!.jsonPrimitive.content)
        assertEquals("default", b["fallbacks"]!!.jsonPrimitive.content)
        val oc = b["output_config"]!!.jsonObject
        assertEquals("low", oc["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", oc["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", oc["format"]!!.jsonObject["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(p._headers().values("anthropic-beta").contains("server-side-fallback-2026-07-01"))
    }

    @Test
    fun toolsSerialize() {
        val p = claude.params(16000, OutputConfig.Effort.LOW).system("s").addUserMessage("u")
            .apply { VoiceAgent.TOOLS.forEach { addTool(it) } }.build()
        val tools = body(p)["tools"]!!.jsonArray
        assertEquals(VoiceAgent.TOOLS.size, tools.size)
        val send = tools.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "send_draft" }
        println(send)
        assertEquals("object", send["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("draft_id", send["input_schema"]!!.jsonObject["required"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun noFallbackForOtherModels() {
        val p = ClaudeClient("k", "claude-haiku-5-5").params(100, OutputConfig.Effort.LOW).addUserMessage("u").build()
        assertTrue(body(p)["fallbacks"] == null)
    }
}
