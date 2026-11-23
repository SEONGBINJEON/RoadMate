package com.roadmate.app.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.roadmate.app.core.PrivacyFilter

/**
 * AI 자유 대화 (Google Gemini API).
 *
 * Pleos Connect의 LLM SDK가 2026-10-19 에뮬레이터 v2.1.0부터 제공 종료되어, 외부 AI API로 대체했다.
 * API 키는 소스에 넣지 않고 local.properties(GEMINI_API_KEY)에서 읽는다. 키가 없으면 [isEnabled]=false이고
 * 앱은 기본 대본(DialogueScript)으로 동작한다. 응답이 늦거나 실패해도 null을 돌려주고 대본으로 대신한다.
 */
class GeminiChat(
    private val apiKey: String,
    private val model: String,
    private val log: (String) -> Unit
) {
    val isEnabled: Boolean get() = apiKey.isNotBlank()

    /** 지금 대화 주제의 주고받은 말 (role = "user" | "model") */
    private val history = mutableListOf<Pair<String, String>>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var activeConnection: HttpURLConnection? = null

    /** Invalidate callbacks when a driving phase, permission, or Activity changes. */
    fun cancelPending() {
        generation++
        activeConnection?.disconnect()
        activeConnection = null
    }

    /** 로드메이트가 먼저 말을 걸면 새 대화를 시작한다. */
    fun startTopic(openerText: String) {
        cancelPending()
        history.clear()
        history += "user" to "(운전 중. 로드메이트가 먼저 말을 걸었다)"
        history += "model" to openerText
    }

    /**
     * 운전자의 대답에 이어서 할 말을 만든다. 결과는 메인 스레드로 돌려준다(실패하면 null).
     * @param situation 운전 상황 요약 (연속 운전 시간, 시간대, 졸음 위험 등)
     * @param lastTurn true면 질문 없이 대화를 마무리하게 한다.
     */
    fun reply(answer: String, situation: String, lastTurn: Boolean, fallback: String, onResult: (String?) -> Unit) {
        if (!isEnabled) { onResult(null); return }
        // 비식별화: 전화번호·이메일·차량번호·긴 숫자열을 가리고 길이를 제한한 뒤 보낸다.
        cancelPending()
        val token = generation
        val maskedAnswer = PrivacyFilter.mask(answer)
        val messages = history.toList() + ("user" to maskedAnswer)
        Thread {
            val text = try {
                // 짧은 대화라 '생각(thinking)'은 최소로. 이 설정을 모르는 모델이면 설정 없이 한 번 더 시도한다.
                call(buildRequest(situation, lastTurn, minimalThinking = useThinkingConfig, messages = messages), token)
                    ?: if (thinkingRejected) { useThinkingConfig = false; thinkingRejected = false; call(buildRequest(situation, lastTurn, false, messages), token) } else null
            } catch (e: Exception) {
                if (token == generation) log("  · AI 연결 실패 → 기본 안내")
                null
            }
            val cleaned = text?.let(::clean)?.takeIf { it.isNotBlank() }
            main.post {
                if (token != generation) return@post
                history += "user" to maskedAnswer
                // 실패해서 대본으로 대답한 경우에도 그 말을 기록해 대화 순서(사용자↔모델)를 맞춘다.
                history += "model" to (cleaned ?: fallback)
                onResult(cleaned)
            }
        }.start()
    }

    @Volatile private var useThinkingConfig = true
    @Volatile private var thinkingRejected = false

    private fun buildRequest(situation: String, lastTurn: Boolean, minimalThinking: Boolean, messages: List<Pair<String, String>>): JSONObject {
        val system = buildString {
            append(SYSTEM_PROMPT)
            append("\n\n[지금 운전 상황] ").append(situation)
            if (lastTurn) append("\n[이번 차례] 질문하지 말고 한 문장으로 따뜻하게 대화를 마무리해. 졸리면 '쉬고 싶어'라고 말하라고 덧붙여.")
            else append("\n[이번 차례] 운전자의 말에 짧게 반응하고, 대답하기 쉬운 질문 하나로 끝내.")
        }
        val contents = JSONArray()
        messages.takeLast(12).forEach { (role, text) ->
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.8)
                put("maxOutputTokens", 1024)
                if (minimalThinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            })
    }

    private fun call(body: JSONObject, token: Long): String? {
        if (token != generation) return null
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        activeConnection = conn
        try {
            if (token != generation) return null
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 400 && response.contains("thinking", ignoreCase = true)) {
                    thinkingRejected = true // 생각 설정을 지원하지 않는 모델 → 설정 없이 다시 시도
                    return null
                }
                log("  · AI 응답 오류 $code → 기본 안내")
                return null
            }
            val candidate = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0) ?: return null
            if (candidate.optString("finishReason") == "MAX_TOKENS") {
                log("  · AI 대답이 중간에 끊겨서 사용하지 않음")
                return null // 반쪽짜리 문장을 읽지 않도록
            }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until parts.length())
                .map { parts.getJSONObject(it) }
                .filter { !it.optBoolean("thought", false) }
                .joinToString("") { it.optString("text") }
        } finally {
            conn.disconnect()
            if (activeConnection === conn) activeConnection = null
        }
    }

    /** TTS로 읽기 좋게: 이모지·마크다운 기호 제거, 너무 길면 두 문장까지만 */
    private fun clean(text: String): String {
        val plain = text.replace(Regex("[*#_`~>\\[\\]]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val sentences = Regex("[^.!?。]+[.!?。]?").findAll(plain).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        return sentences.take(2).joinToString(" ")
    }

    companion object {
        private const val SYSTEM_PROMPT = """너는 차량용 AI 동승자 '로드메이트'야. 졸음운전을 막으려고 운전자와 가볍게 대화해.
규칙:
- 한국어 존댓말, 1~2문장, 50자 안쪽으로 짧게. 소리로 읽히니 이모지·기호·목록 금지.
- 운전자가 한 말을 구체적으로 짚어서 반응해서 '듣고 있다'는 느낌을 줘.
- 대답하기 쉬운 열린 질문을 해. 퀴즈·게임·숫자 맞히기는 하지 마.
- 화면을 보거나 손을 쓰게 하는 요청은 절대 하지 마.
- 운전자가 피곤하다고 하면 바로 가까운 쉼터에서 쉬자고 권해.
- 의학적 진단, 위험한 운전 조언은 하지 마."""
    }
}
