package at.hochalex.assistent.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class HaEntity(val entityId: String, val name: String)

data class CalendarEvent(
    val calendar: String,
    val summary: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
    val uid: String = "",
)

data class TodoItem(
    val list: String,
    val summary: String,
    val uid: String,
    val done: Boolean,
    val due: String?,
    val description: String?,
)

class HaException(msg: String) : IOException(msg)

/** Home Assistant REST-API (Long-Lived-Token, HTTPS). */
class HaClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val zone: ZoneId = ZoneId.systemDefault()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun call(method: String, path: String, body: JsonElement? = null, query: Map<String, String> = emptyMap()): JsonElement? =
        withContext(Dispatchers.IO) {
            val url = "$base$path".toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "POST") "{}".toRequestBody(JSON_TYPE) else null)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw HaException("Home Assistant: HTTP ${resp.code} ${text.take(200)}")
                if (text.isBlank()) null else json.parseToJsonElement(text)
            }
        }

    suspend fun ping(): String {
        val r = call("GET", "/api/")?.jsonObject
        return r?.get("message")?.jsonPrimitive?.contentOrNull ?: "OK"
    }

    suspend fun calendars(): List<HaEntity> =
        call("GET", "/api/calendars")?.jsonArray?.map {
            val o = it.jsonObject
            HaEntity(o.str("entity_id"), o.str("name"))
        } ?: emptyList()

    suspend fun todoLists(): List<HaEntity> =
        call("GET", "/api/states")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it.str("entity_id").startsWith("todo.") }
            ?.map {
                HaEntity(it.str("entity_id"), it["attributes"]?.jsonObject?.str("friendly_name")?.trim().orEmpty().ifEmpty { it.str("entity_id") })
            } ?: emptyList()

    suspend fun events(calendar: HaEntity, from: ZonedDateTime, to: ZonedDateTime): List<CalendarEvent> {
        val arr = call(
            "GET", "/api/calendars/${calendar.entityId}",
            query = mapOf("start" to from.format(ISO), "end" to to.format(ISO))
        )?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val (start, allDay) = parseWhen(o["start"]) ?: return@mapNotNull null
            val (end, _) = parseWhen(o["end"]) ?: (start.plusHours(1) to allDay)
            CalendarEvent(
                calendar = calendar.name, summary = o.str("summary"), start = start, end = end, allDay = allDay,
                location = o.str("location"), description = o.str("description"), uid = o.str("uid"),
            )
        }
    }

    /** Termine aller Kalender (ohne Hilfskalender wie Workday-Sensor), chronologisch. */
    suspend fun allEvents(from: ZonedDateTime, to: ZonedDateTime): List<CalendarEvent> =
        calendars()
            .filterNot { it.entityId.contains("workday", true) || it.name.contains("workday", true) }
            .flatMap { runCatching { events(it, from, to) }.getOrDefault(emptyList()) }
            .sortedBy { it.start }

    suspend fun todoItems(list: HaEntity, includeDone: Boolean = false): List<TodoItem> {
        val body = buildJsonObject { put("entity_id", list.entityId) }
        val resp = call("POST", "/api/services/todo/get_items", body, mapOf("return_response" to ""))?.jsonObject ?: return emptyList()
        val sr = resp["service_response"]?.jsonObject ?: resp
        val items = sr[list.entityId]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.map { it.jsonObject }.map {
            TodoItem(
                list = list.name, summary = it.str("summary"), uid = it.str("uid"),
                done = it.str("status") == "completed",
                due = it.str("due").ifEmpty { null }, description = it.str("description").ifEmpty { null },
            )
        }.filter { includeDone || !it.done }
    }

    suspend fun addTodo(entityId: String, summary: String, dueDate: LocalDate? = null, dueDateTime: LocalDateTime? = null, description: String? = null) {
        call("POST", "/api/services/todo/add_item", buildJsonObject {
            put("entity_id", entityId)
            put("item", summary)
            if (dueDateTime != null) put("due_datetime", dueDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            else if (dueDate != null) put("due_date", dueDate.toString())
            if (!description.isNullOrBlank()) put("description", description)
        })
    }

    suspend fun completeTodo(entityId: String, item: String) {
        call("POST", "/api/services/todo/update_item", buildJsonObject {
            put("entity_id", entityId)
            put("item", item)
            put("status", "completed")
        })
    }

    suspend fun createEvent(entityId: String, summary: String, start: LocalDateTime, end: LocalDateTime?, allDay: Boolean, description: String? = null, location: String? = null) {
        call("POST", "/api/services/calendar/create_event", buildJsonObject {
            put("entity_id", entityId)
            put("summary", summary)
            if (allDay) {
                put("start_date", start.toLocalDate().toString())
                put("end_date", (end?.toLocalDate()?.takeIf { it.isAfter(start.toLocalDate()) } ?: start.toLocalDate().plusDays(1)).toString())
            } else {
                put("start_date_time", start.format(LOCAL))
                put("end_date_time", (end ?: start.plusHours(1)).format(LOCAL))
            }
            if (!description.isNullOrBlank()) put("description", description)
            if (!location.isNullOrBlank()) put("location", location)
        })
    }

    /** Freier Sprachbefehl an den HA-Gesprächsagenten (Smart Home). */
    suspend fun conversation(text: String): String {
        val r = call("POST", "/api/conversation/process", buildJsonObject {
            put("text", text)
            put("language", "de")
        })?.jsonObject
        return r?.get("response")?.jsonObject?.get("speech")?.jsonObject?.get("plain")?.jsonObject
            ?.str("speech").orEmpty().ifEmpty { "Erledigt." }
    }

    private fun parseWhen(el: JsonElement?): Pair<ZonedDateTime, Boolean>? {
        if (el == null) return null
        val raw = when (el) {
            is JsonObject -> el.str("dateTime").ifEmpty { el.str("date") }
            else -> el.jsonPrimitive.contentOrNull.orEmpty()
        }
        if (raw.isEmpty()) return null
        return if (raw.length == 10) {
            LocalDate.parse(raw).atStartOfDay(zone) to true
        } else {
            runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone) }.getOrNull()?.let { it to false }
                ?: runCatching { LocalDateTime.parse(raw).atZone(zone) to false }.getOrNull()
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
