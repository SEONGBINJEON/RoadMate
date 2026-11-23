package com.roadmate.app.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 2단계 "대화"의 입·출력: 로드메이트가 말하고(TTS), 운전자의 대답을 듣는다(STT).
 *
 * 말한 뒤 대답을 기다려야 하면(listenAfter) 말이 끝나는 순간 마이크를 켜고,
 * 정해진 시간 안에 대답이 없으면 onNoAnswer()를 알린다. → 졸음 위험 점수가 올라간다.
 * 네트워크 없이 동작하도록 차량 내장 엔진(ON_DEVICE) 모드를 쓴다.
 *
 * 오디오 포커스 (운영사무국 안내 반영):
 * - 말하기 전에 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK으로 포커스를 요청하고, 말하기·대답 듣기가 끝나면 반납한다.
 * - LOSS: 즉시 중단하고 반납. LOSS_TRANSIENT(내비 안내, Gleo 호출 등): 즉시 중단 → 포커스가 돌아오면 다시 말한다.
 * - LOSS_TRANSIENT_CAN_DUCK: 시스템이 소리를 낮추도록 두고 계속 말한다.
 * - 포커스를 못 받으면(다른 음성 안내 중) 잠시 뒤 다시 시도한다.
 */
class VoiceIO(
    context: Context,
    private val log: (String) -> Unit,
    private val answerTimeoutMs: Long = 8_000L
) {
    interface Listener {
        fun onAnswer(text: String)
        fun onNoAnswer()
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)

    private var ttsReady = false
    private var sttReady = false
    private var listenAfterSpeech = false
    private var listening = false
    private var answered = false

    // ---- 오디오 포커스 ----
    private var hasFocus = false
    private var speaking = false
    private var pendingText: String? = null      // 포커스를 잃어서 못 다 한 말 (다시 받으면 이어서 말함)
    private var pendingListen = false
    private var speakCalledAt = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> main.post { onFocusChange(change) } }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(focusListener, main)
        .setWillPauseWhenDucked(false)
        .build()

    private val timeout = Runnable {
        if (listening && !answered) {
            safely { stt.stop() }
            finishListening(noAnswer = true)
        }
    }

    init {
        // 공식 문서 순서: 객체 생성 → initialize() → 리스너 등록. 실패해도 앱이 꺼지지 않도록 감싼다.
        safely {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    ttsReady = true
                    safely { tts.setLocale(Locale.KOREA) }
                    log("음성 출력(TTS) 준비 완료")
                }
                override fun onFailure(errorType: TtsError) = log("TTS 초기화 실패: $errorType")
            })
        }
        safely {
            tts.addEventListener(object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onStop() = Unit
                override fun onDone() {
                    main.post { onSpeechFinished() }
                }
                override fun onError(errorType: TtsError) {
                    log("음성 출력 오류: $errorType")
                    main.post { onSpeechFinished() }
                }
            })
        }
        safely {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    safely { stt.setLocale(Locale.KOREA) }
                    log("음성 인식(STT) 준비 완료")
                }
                override fun onFailure(errorType: SttError) = log("STT 초기화 실패: $errorType")
            })
        }
        safely {
            stt.addListener(object : ResultListener {
                override fun onReady() = Unit
                override fun onStartedRecognition() = Unit
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onUpdated(stt: String, completed: Boolean) {
                    val text = stt
                    if (completed && text.isNotBlank()) main.post {
                        if (listening && !answered) {
                            answered = true
                            log("🎙 운전자 대답 인식 완료")
                            finishListening(noAnswer = false)
                            listener?.onAnswer(text)
                        }
                    }
                }
                // 인식이 끝났거나 오류가 나도 바로 '대답 없음'으로 처리하지 않고,
                // 제한 시간(answerTimeoutMs)까지 기다린다. (시연 버튼으로도 대답할 수 있게)
                override fun onEndedRecognition() = Unit
                override fun onError(errorType: SttError) = log("음성 인식 오류: $errorType")
            })
        }
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 시연용: 안내 음성 볼륨을 최대치의 ratio(0~1)만큼으로 올린다. */
    fun boostVolume(ratio: Float = 0.9f) = safely {
        val stream = AudioManager.STREAM_MUSIC
        val target = (audio.getStreamMaxVolume(stream) * ratio).toInt()
        if (audio.getStreamVolume(stream) < target) {
            audio.setStreamVolume(stream, target, 0)
            log("🔈 음성 볼륨 ${(ratio * 100).toInt()}%로 조정")
        }
    }

    /** 말하기. listenAfter=true면 말이 끝난 뒤 대답을 듣는다. */
    fun speak(text: String, listenAfter: Boolean) {
        log("🔊 로드메이트: $text")
        main.removeCallbacks(retrySpeak)
        if (listening) { safely { stt.stop() }; listening = false; main.removeCallbacks(timeout) }
        listenAfterSpeech = listenAfter
        pendingText = text
        pendingListen = listenAfter
        if (!ttsReady) {
            log("(TTS 준비 전이라 화면에만 표시)")
            pendingText = null
            if (listenAfter) startListening()
            return
        }
        if (!requestFocus()) {
            log("🔇 다른 음성 안내 중이라 3초 뒤 다시 말할게요")
            main.postDelayed(retrySpeak, 3_000L)
            return
        }
        startSpeaking(text)
    }

    /** 말하는 중이거나, 대답을 듣는 중이거나, 끼어들기 때문에 말을 미뤄 둔 상태 */
    val isBusy: Boolean get() = speaking || listening || pendingText != null

    private val retrySpeak = Runnable { pendingText?.let { speak(it, pendingListen) } }

    private fun startSpeaking(text: String) {
        speaking = true
        speakCalledAt = System.currentTimeMillis()
        safely { tts.speak(text, true) }
    }

    private fun onSpeechFinished() {
        if (!speaking) return // 포커스를 잃어 중단한 경우
        speaking = false
        pendingText = null
        if (listenAfterSpeech) startListening() else releaseFocus()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result = try { audio.requestAudioFocus(focusRequest) } catch (e: Exception) { AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) log("  · 🔈 오디오 포커스 받음")
        return hasFocus
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        hasFocus = false
        safely { audio.abandonAudioFocusRequest(focusRequest) }
    }

    private fun onFocusChange(change: Int) {
        log("  · 오디오 포커스 변화: ${focusName(change)}")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                val text = pendingText
                if (text != null && !speaking) {
                    log("🔈 오디오 포커스 다시 받음 → 이어서 말할게요")
                    startSpeaking(text)
                } else if (!speaking && !listening) {
                    releaseFocus() // 이어서 할 말이 없으면 바로 돌려준다
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit // 시스템이 소리를 낮춘다. 계속 말한다.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // TTS 엔진이 말을 시작하면서 잠깐 포커스를 가져가는 경우는 무시한다 (말하자마자 0.8초 이내)
                if (speaking && System.currentTimeMillis() - speakCalledAt < 800L) return
                log("🔇 다른 음성(내비 안내·Gleo 등)이 끼어들어 잠시 멈춰요")
                interrupt(keepForLater = true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                log("🔇 오디오 포커스를 잃어 말하기를 멈춰요")
                interrupt(keepForLater = false)
                releaseFocus()
            }
        }
    }

    private fun focusName(change: Int) = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> change.toString()
    }

    /** 말하기·듣기를 즉시 멈춘다. keepForLater면 포커스가 돌아왔을 때 다시 말한다. */
    private fun interrupt(keepForLater: Boolean) {
        hasFocus = false
        if (speaking) {
            speaking = false
            safely { tts.stop() }
            if (!keepForLater) pendingText = null
        }
        if (listening) {
            // 대답을 듣던 중이면 '대답 없음'으로 치지 않고 그냥 멈춘다
            safely { stt.stop() }
            listening = false
            main.removeCallbacks(timeout)
        }
    }

    private var simulating = false

    /**
     * 시연용: 음성비서(Gleo) 호출이 끼어드는 상황을 흉내 낸다.
     * 차량에서 내비 안내와 음성비서 소리는 동시에 나올 수 있지만, 음성비서끼리는 한쪽이 멈춰야 한다.
     * 별도의 음성비서 오디오 포커스 요청으로 3초 동안 포커스를 가져갔다가 돌려준다.
     */
    fun simulateInterruption() {
        if (simulating) return
        simulating = true
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ }, main)
            .build()
        val result = try { audio.requestAudioFocus(other) } catch (e: Exception) { -1 }
        log("🎙 (시연) 음성비서 호출이 3초 동안 끼어들어요 (요청 결과 $result)")
        main.postDelayed({
            safely { audio.abandonAudioFocusRequest(other) }
            log("🎙 (시연) 음성비서 호출 끝")
            simulating = false
        }, 3_000L)
    }

    /** 시연용: 실제 마이크 대신 화면 버튼으로 대답을 넣는다. */
    fun simulateAnswer(text: String) {
        if (listening) { safely { stt.stop() } }
        answered = true
        finishListening(noAnswer = false)
        log("🎙 운전자(시연): $text")
        listener?.onAnswer(text)
    }

    private fun startListening() {
        listenAfterSpeech = false
        listening = true
        answered = false
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, answerTimeoutMs)
        if (sttReady) {
            log("👂 대답을 기다리는 중... (${answerTimeoutMs / 1000}초)")
            safely { stt.request() }
        } else {
            log("👂 (STT 준비 전) 화면의 대답 버튼으로 시연할 수 있어요")
        }
    }

    private fun finishListening(noAnswer: Boolean) {
        main.removeCallbacks(timeout)
        if (!listening) return
        listening = false
        releaseFocus()
        if (noAnswer) {
            log("… 대답 없음")
            listener?.onNoAnswer()
        }
    }

    fun cancel() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(retrySpeak)
        pendingText = null
        pendingListen = false
        listenAfterSpeech = false
        speaking = false
        listening = false
        answered = true
        safely { tts.stop() }
        safely { stt.stop() }
        releaseFocus()
    }

    fun release() {
        cancel()
        safely { tts.release() }
        safely { stt.release() }
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { log("음성 기능 오류: ${e.message}") }
    }
}
