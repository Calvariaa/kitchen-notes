package io.github.Calvariaa.kitchennotes.ai

import android.util.Base64
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一张待发送的图片 */
data class ImagePayload(val bytes: ByteArray, val mime: String)

/**
 * AI 调用失败的分类。
 *
 * 每个子类带一个资源 id，由 UI 用当前语言渲染 —— 错误对象可能在语言切换前创建，
 * 直接存中文句子会让英文界面里冒出一段中文。
 * 只有 [detail] 保留原文（接口返回体、异常消息，属于数据不是界面）。
 */
sealed class AiError(
    /** 展示用的资源 id */
    val resId: Int,
    /** 技术细节，可为空 */
    val detail: String? = null,
) : Exception(detail) {

    class MissingKey : AiError(R.string.ai_err_missing_key)

    class Http(val code: Int, val body: String) :
        AiError(R.string.ai_err_http, body.take(300))

    class Network(cause: String?) : AiError(R.string.ai_err_network, cause)

    class Parse(detail: String?) : AiError(R.string.ai_err_parse, detail)

    class Empty : AiError(R.string.ai_err_empty)
}

/**
 * DeepSeek 兼容 OpenAI Chat Completions 的客户端。
 *
 * 只依赖 okhttp + kotlinx.serialization，不引 OpenAI SDK：
 * SDK 会把 baseUrl 处理、重试、超时都包死，而这个 App 的用户会自己填
 * 「api 链接和 key」（可能填第三方中转），手写请求反而更可控。
 *
 * 关于视觉：DeepSeek 目前 deepseek-flash 支持图片输入，走标准 OpenAI 的
 * `content: [{type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}]`。
 * 图片只能出现在 user 消息里，放进 system/assistant 会被 400 拒绝。
 */
class DeepSeekClient(private val settings: AppSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val baseUrl = settings.normalizedBaseUrl

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ------------------------------------------------------------ 主入口

    /**
     * 生成一条菜谱 JSON（原始字符串）。
     */
    suspend fun generateRecipe(
        rawText: String,
        userTitleHint: String,
        platformHint: String,
        images: List<ImagePayload>,
    ): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) throw AiError.MissingKey()

        val system = RecipePrompt.buildSystemPrompt(settings.useBuiltinPrompt, settings.promptAddon)
        val userText = RecipePrompt.buildUserMessage(
            rawText = rawText,
            imageCount = images.size,
            platformHint = platformHint,
            userTitleHint = userTitleHint,
        )
        chat(system = system, userText = userText, images = images)
    }

    /** 让模型基于已有菜谱改写 */
    suspend fun refineRecipe(currentJson: String, instruction: String): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) throw AiError.MissingKey()
        val system = RecipePrompt.buildSystemPrompt(settings.useBuiltinPrompt, settings.promptAddon)
        chat(
            system = system,
            userText = RecipePrompt.buildRefineMessage(currentJson, instruction),
            images = emptyList(),
        )
    }

    /** 连通性自检：发一句最短的话，返回模型回复片段。 */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) throw AiError.MissingKey()
        chat(
            system = "你是一个连通性自检助手，只回复两个字：在线。",
            userText = "ping",
            images = emptyList(),
            maxTokens = 32,
        )
    }

    // ------------------------------------------------------------ HTTP

    private fun chat(
        system: String,
        userText: String,
        images: List<ImagePayload>,
        maxTokens: Int? = null,
    ): String {
        val payload = buildJsonObject {
            put("model", settings.model.ifBlank { "deepseek-flash" })
            put("stream", false)
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (!settings.thinkingEnabled) {
                // 菜谱生成不需要 CoT：关掉能省 60% 以上输出 token，且不会污染 JSON。
                putJsonObject("thinking") { put("type", "disabled") }
            } else {
                putJsonObject("thinking") { put("type", "enabled") }
                put("reasoning_effort", settings.reasoningEffort)
            }
            put("temperature", settings.temperature)

            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", userText)
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", userText)
                            })
                            images.take(settings.maxImagesPerRequest.coerceIn(1, 12)).forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        val b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP)
                                        put("url", "data:${img.mime};base64,$b64")
                                        put("detail", "high")
                                    }
                                })
                            }
                        })
                    }
                })
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AiError.Network(e.message ?: e.javaClass.simpleName)
        }

        response.use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiError.Http(resp.code, bodyText)
            }
            return extractContent(bodyText)
        }
    }

    private fun extractContent(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw AiError.Parse("not JSON: ${body.take(200)}") }
        val choices = root["choices"]?.jsonArray
            ?: throw AiError.Parse("no choices in response")
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiError.Empty()
        val content = message["content"]
        val text = when (content) {
            is JsonPrimitive -> content.content
            null -> ""
            else -> content.toString()
        }
        if (text.isBlank()) {
            // 有的中转会把内容塞在 reasoning_content 里
            val reasoning = (message["reasoning_content"] as? JsonPrimitive)?.content.orEmpty()
            if (reasoning.isBlank()) throw AiError.Empty()
            return reasoning
        }
        return text
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 常见推荐值，设置页做成一排可点的芯片 */
        val MODEL_PRESETS = listOf("deepseek-flash", "deepseek-v4-pro")
        val BASE_URL_PRESETS = listOf(
            "https://api.deepseek.com",
            "https://api.deepseek.com/v1",
        )
    }
}

// ---------------------------------------------------------------- JSON 修补

/**
 * 模型的 JSON 输出不总是干净的：会带 ```json 围栏、会在前后加一句「好的，以下是……」、
 * 会在最后多一个逗号。这里做「就地修补」而不是简单正则，修补失败才报错。
 */
object RecipeJsonRepair {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    fun extractObject(raw: String): String {
        var text = raw.trim()

        // 1) 去掉 ```json ... ``` 围栏（可能有多段，取最长的一段）
        if (text.contains("```")) {
            val fenced = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
                .findAll(text)
                .map { it.groupValues[1] }
                .maxByOrNull { it.length }
            if (!fenced.isNullOrBlank()) text = fenced.trim()
        }

        // 2) 掐头去尾，取第一个 { 到与之配对的 }（跳过字符串里的括号）
        val start = text.indexOf('{')
        if (start < 0) throw AiError.Parse("no '{' in output")
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) {
            // 输出被截断：补够右括号
            text = text.substring(start) + "}".repeat(depth.coerceAtLeast(1))
        } else {
            text = text.substring(start, end + 1)
        }

        // 3) 去掉尾随逗号
        text = Regex(",\\s*([}\\]])").replace(text) { it.groupValues[1] }
        return text
    }

    fun parse(raw: String): JsonObject {
        val cleaned = extractObject(raw)
        val element = runCatching { json.parseToJsonElement(cleaned) }
            .getOrElse { throw AiError.Parse(it.message ?: "malformed JSON") }
        return element as? JsonObject ?: throw AiError.Parse("top level is not an object")
    }

    /** 宽松取字符串：可能是字符串、数字，或者 [a, b] 数组被写成一段文字 */
    fun JsonElement?.str(): String = when (this) {
        null -> ""
        is JsonPrimitive -> content
        is JsonArray -> jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("、")
        else -> toString()
    }

    fun JsonElement?.intOr(default: Int?): Int? {
        val p = this as? JsonPrimitive ?: return default
        p.intOrNull?.let { return it }
        p.doubleOrNull?.let { return it.toInt() }
        return Regex("-?\\d+").find(p.content)?.value?.toIntOrNull() ?: default
    }

    fun JsonElement?.stringList(): List<String> = when (this) {
        null -> emptyList()
        is JsonArray -> jsonArray.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.content.takeIf { s -> s.isNotBlank() }
                is JsonObject -> it["name"].str().ifBlank { null }
                else -> null
            }
        }

        is JsonPrimitive -> content.split('\n', '；', ';')
            .map { it.trim().trimStart('-', '*', '·').trim() }
            .filter { it.isNotBlank() }

        else -> emptyList()
    }
}
