package com.roadmate.app

import ai.pleos.playground.vehicle.Vehicle
import android.car.drivingstate.CarUxRestrictions
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.roadmate.app.ai.GeminiChat
import com.roadmate.app.car.CabinController
import com.roadmate.app.car.RestNavigator
import com.roadmate.app.car.VehicleMonitor
import com.roadmate.app.car.VoiceIO
import com.roadmate.app.car.UxRestrictionMonitor
import com.roadmate.app.core.DrowsinessRiskEngine
import com.roadmate.app.core.DrowsyMap
import com.roadmate.app.core.ParkingGate
import com.roadmate.app.core.ResponseGate
import com.roadmate.app.core.RoadMateBrain
import com.roadmate.app.core.RoadMateBrain.Action
import com.roadmate.app.ui.DrowsyMapView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 로드메이트 메인 화면.
 *
 * 차량 신호(VehicleMonitor) → 두뇌(RoadMateBrain)가 1초마다 판단 → 행동(Action) 실행
 *   - Speak: 음성으로 말하고 대답 듣기 (VoiceIO)
 *   - NavigateToRest: 졸음쉼터 길 안내 (RestNavigator) + 각성 모드 (CabinController)
 *   - StartNap / StartWake: 낮잠·기상 모드 (CabinController)
 *
 * 아래쪽 '시연 패널'은 에뮬레이터에서 바로 바꾸기 어려운 상황(장시간 운전, 졸음 경고 등)을
 * 버튼으로 재현하기 위한 것이다. 같은 상황은 Pleos Virtual Console로도 만들 수 있다.
 */
class MainActivity : Activity() {

    companion object {
        /** AI 대답을 기다리는 최대 시간. 넘으면 기본 대본으로 대답한다. */
        private const val AI_WAIT_MS = 4_000L
    }

    private lateinit var vehicle: Vehicle
    private lateinit var monitor: VehicleMonitor
    private lateinit var voice: VoiceIO
    private lateinit var cabin: CabinController
    private lateinit var navigator: RestNavigator
    private lateinit var ai: GeminiChat
    private lateinit var uxMonitor: UxRestrictionMonitor
    private var setupRestricted = true
    private var pendingAiConsent = false
    private var aiDialog: AlertDialog? = null
    private lateinit var setupRow: LinearLayout
    private lateinit var uxView: TextView

    private val brain = RoadMateBrain(RoadMateBrain.Config.DEMO)
    private val main = Handler(Looper.getMainLooper())
    private val parking = ParkingGate()
    private val responses = ResponseGate()
    private var aiPending = false
    private var destroyed = false
    private var resumed = false
    private var aiConsent = false
    private var napDialog: AlertDialog? = null
    private lateinit var napControls: LinearLayout
    private lateinit var confirmRestButton: Button
    private lateinit var sourceView: TextView

    // 상태 (화면 표시용)
    private var sdkTripMinutes = 0
    private var demoExtraMinutes = 0
    private var cruiseOn = false
    private var gearName = "-"
    private var isPark = true
    private var dawOn = false

    // 졸음 지도 (시연용 예시 데이터)
    private val drowsyMap = DrowsyMap.sample()
    private var carKm = 20
    private var inHotspot = false

    private lateinit var statusCard: LinearLayout
    private lateinit var phaseView: TextView
    private lateinit var scoreView: TextView
    private lateinit var riskBar: View
    private var riskScore = 0
    private var riskColor = Color.parseColor("#4CC38A")
    private lateinit var detailView: TextView
    private lateinit var speechView: TextView
    private lateinit var speechLabel: TextView
    private lateinit var mapCard: LinearLayout
    private lateinit var mapView: DrowsyMapView
    private lateinit var mapInfoView: TextView
    private lateinit var demoPanel: LinearLayout
    private lateinit var logView: TextView
    private lateinit var gearButton: Button
    private lateinit var dawButton: Button

    private val loop = object : Runnable {
        override fun run() {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            // 말하거나 대답을 듣는 중에는 새 판단을 미룬다 (대화 도중에 새 질문이 끼어들지 않도록)
            brain.tick(hour, allowConversation = !voice.isBusy && !aiPending).forEach(::perform)
            render()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestRuntimePermissions()

        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        monitor = VehicleMonitor(vehicle, ::log)
        cabin = CabinController(vehicle, ::log) { parking.parkedConfirmed }
        navigator = RestNavigator(this, ::log)
        voice = VoiceIO(this, ::log)
        ai = GeminiChat(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL, ::log)
        voice.listener = object : VoiceIO.Listener {
            override fun onAnswer(text: String) { cancelDialogue(); brain.onAnswer(text).forEach(::perform) }
            override fun onNoAnswer() { brain.onNoAnswer() }
        }

        // 차량 서비스 연결이 끝날 시간을 조금 준 뒤 구독 시작
        main.postDelayed({
            monitor.start(vehicleCallback)
            cabin.prepare() // 이 차가 지원하는 공조·조명·창문 영역과 범위 확인
            // Keep the driver's chosen audio volume.
        }, 1_000L)
        main.postDelayed(loop, 1_500L)
        log("로드메이트 시작 — 시연 모드(말 걸기 간격 15초, 낮잠 30초)")
        log(if (ai.isEnabled) "🤖 AI 대화 켜짐 (${BuildConfig.GEMINI_MODEL})" else "💬 AI 키 없음 — 기본 대본으로 대화")
        aiConsent = getPreferences(MODE_PRIVATE).getBoolean("ai_consent", false)
        updateAiLabel()
        pendingAiConsent = ai.isEnabled && !getPreferences(MODE_PRIVATE).contains("ai_consent")
        uxMonitor = UxRestrictionMonitor(this) { restrictions -> runOnUiThread { applyUxRestrictions(restrictions) } }
        uxMonitor.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (brain.phase == RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION && parking.parkedConfirmed) {
            showNapConfirmation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (brain.phase == RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION && parking.parkedConfirmed) {
            showNapConfirmation()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private val vehicleCallback = object : VehicleMonitor.Callback {
        override fun onTripMinutes(minutes: Int) = runOnUiThread {
            sdkTripMinutes = minutes
            brain.onTripMinutes(sdkTripMinutes + demoExtraMinutes)
        }
        override fun onCruise(active: Boolean) = runOnUiThread {
            if (active != cruiseOn) log(if (active) "크루즈 컨트롤 켜짐" else "크루즈 컨트롤 꺼짐")
            cruiseOn = active
            brain.onCruise(active)
        }
        override fun onGear(isPark: Boolean, gearName: String) = runOnUiThread {
            parking.updateActual(isPark)
            this@MainActivity.gearName = gearName
            syncGear()
        }
        override fun onGearUnavailable() = runOnUiThread {
            parking.updateActual(null)
            gearName = "신호 미확인"
            syncGear()
        }
        override fun onDriverInput(source: String) = runOnUiThread { brain.onDriverInput() }
        override fun onDawWarning(on: Boolean) = runOnUiThread { applyDaw(on) }
    }

    /** 두뇌가 내린 행동을 실제로 실행 */
    private fun perform(action: Action) {
        if (destroyed) return
        when (action) {
            is Action.Speak -> {
                cancelDialogue()
                if (action.listenAfter) ai.startTopic(action.text) // 먼저 말 걸기 = 새 대화 시작
                say(action.text, action.listenAfter)
            }
            is Action.Reply -> {
                if (!ai.isEnabled || !aiConsent) {
                    say(action.fallback, action.listenAfter)
                } else {
                    val token = responses.begin()
                    aiPending = true
                    val tooSlow = Runnable {
                        if (!destroyed && responses.complete(token)) {
                            aiPending = false
                            ai.cancelPending()
                            log("AI 응답 지연 → 기본 안내")
                            say(action.fallback, action.listenAfter)
                        }
                    }
                    main.postDelayed(tooSlow, AI_WAIT_MS)
                    ai.reply(action.answer, situation(), lastTurn = !action.listenAfter, fallback = action.fallback) { text ->
                        if (destroyed || !responses.complete(token)) return@reply
                        aiPending = false
                        main.removeCallbacks(tooSlow)
                        say(text ?: action.fallback, action.listenAfter)
                    }
                }
            }
            Action.NavigateToRest -> {
                cabin.refreshMode()
                navigator.guideToNearest()
            }
            Action.RequestNapConfirmation -> {
                cancelDialogue()
                voice.cancel()
                if (parking.parkedConfirmed) {
                    // Navigation may own the foreground. Return only after the real SDK confirms P.
                    // Reuse this Activity so the pending confirmation and vehicle state survive.
                    if (resumed) showNapConfirmation() else {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        ))
                    }
                }
            }
            Action.StartNap -> {
                cancelDialogue()
                if (parking.parkedConfirmed) cabin.napMode()
            }
            Action.StopCabin -> {
                cancelDialogue()
                voice.cancel()
                napDialog?.dismiss()
                napDialog = null
                cabin.cancelPending()
                speechView.text = "휴식 준비를 중단했어요. 시트와 창문을 직접 확인해 주세요."
            }
            Action.StartWake -> {
                cancelDialogue()
                if (parking.parkedConfirmed) cabin.wakeMode()
                navigator.cancelGuide()
            }
        }
    }

    private fun say(text: String, listenAfter: Boolean) {
        if (destroyed) return
        speechView.text = text
        voice.speak(text, listenAfter)
    }

    /** AI에게 알려 줄 지금 운전 상황 */
    private fun situation(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return buildString {
            append("연속 운전 ${brain.continuousMinutes}분, 현재 ${hour}시, 졸음 위험 점수 ${riskScore}/100")
            if (dawOn) append(", 차량 졸음 경고 켜짐")
            if (inHotspot) drowsyMap.hotspotAt(carKm)?.let { append(", 다른 운전자들이 자주 졸았던 '${it.name}' 구간 주행 중") }
            append(", 경부고속도로 서울에서 대전 방향")
        }
    }

    private fun syncGear() {
        val wasPark = isPark
        isPark = parking.effectiveParked
        brain.onGear(isPark, parking.parkedConfirmed)
        if (!parking.parkedConfirmed && ::cabin.isInitialized) {
            cabin.cancelPending()
            if (brain.phase == RoadMateBrain.Phase.NAPPING || brain.phase == RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION) {
                brain.cancelRest().forEach(::perform)
            }
        }
        if (wasPark != isPark && ::ai.isInitialized) cancelDialogue()
        if (::gearButton.isInitialized) gearButton.text = if (isPark) "모의 기어 P → D" else "모의 기어 D → P"
        render()
        if (pendingAiConsent && ::ai.isInitialized) showAiConsent()
    }

    private fun applyUxRestrictions(restrictions: CarUxRestrictions?) {
        if (destroyed) return
        setupRestricted = restrictions == null || (restrictions.isRequiresDistractionOptimization &&
            (restrictions.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) != 0)
        setupRow.visibility = if (setupRestricted) View.GONE else View.VISIBLE
        uxView.text = when {
            restrictions == null -> "차량의 주행 안전 상태 확인 중 · 설정 잠금"
            setupRestricted -> "주행 안전을 위해 시연·지도·AI 설정·로그를 잠갔어요."
            else -> "설정 사용 가능"
        }
        if (setupRestricted) {
            demoPanel.visibility = View.GONE
            mapCard.visibility = View.GONE
            logView.visibility = View.GONE
            if (aiDialog?.isShowing == true) {
                pendingAiConsent = true
                aiDialog?.dismiss()
                aiDialog = null
            }
        } else if (pendingAiConsent) showAiConsent()
    }

    private fun cancelDialogue() {
        responses.cancel()
        aiPending = false
        if (::ai.isInitialized) ai.cancelPending()
    }

    private fun showNapConfirmation() {
        if (!parking.parkedConfirmed || brain.phase != RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION || napDialog?.isShowing == true) return
        speechView.text = "실제 기어 P를 확인했어요. 주변을 확인한 뒤 휴식을 시작해 주세요."
        napDialog = AlertDialog.Builder(this)
            .setTitle("휴식을 시작할까요?")
            .setMessage("안전한 곳에 정차했나요? 공조·조명·운전석 등받이를 조절합니다. 30초 시연 후 창문을 조금 열고 등받이를 앞으로 움직입니다. 언제든 중단할 수 있어요.")
            .setPositiveButton("휴식 시작") { _, _ ->
                napDialog = null
                brain.confirmNap().forEach(::perform)
                render()
            }
            .setNegativeButton("나중에") { _, _ -> cancelRest() }
            .setOnCancelListener { cancelRest() }
            .show()
    }

    private fun cancelRest() {
        brain.cancelRest().forEach(::perform)
        render()
    }

    private fun showAiConsent() {
        if (setupRestricted || !parking.parkedConfirmed) { pendingAiConsent = true; return }
        if (aiDialog?.isShowing == true) return
        pendingAiConsent = false
        aiDialog = AlertDialog.Builder(this)
            .setTitle("AI 대화를 사용할까요?")
            .setMessage("최근 대화 텍스트와 운전 시간·시각·점수를 Google Gemini에 보냅니다. 원음은 보내지 않고 일부 개인정보만 가립니다. 거부해도 기본 안내와 휴식 기능을 사용할 수 있어요.")
            .setPositiveButton("동의하고 사용") { _, _ -> setAiConsent(true) }
            .setNegativeButton("기본 안내 사용") { _, _ -> setAiConsent(false) }
            .show()
    }

    private fun setAiConsent(enabled: Boolean) {
        aiDialog = null
        pendingAiConsent = false
        cancelDialogue()
        aiConsent = enabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("ai_consent", enabled).apply()
        updateAiLabel()
    }

    private fun updateAiLabel() {
        speechLabel.text = if (ai.isEnabled && aiConsent) "로드메이트 · AI 대화" else "로드메이트 · 기본 안내"
    }

    private fun applyDaw(on: Boolean) {
        if (on != dawOn) log(if (on) "⚠️ 순정 졸음 경고 켜짐" else "순정 졸음 경고 꺼짐")
        dawOn = on
        brain.onDawWarning(on)
        if (::dawButton.isInitialized) dawButton.text = if (on) "졸음 경고 끄기" else "졸음 경고 켜기"
    }

    // ---------------- 졸음 지도 ----------------

    /** 시연: 차를 노선 위에서 앞으로 이동시키고, 졸음 다발 구간 진입 여부를 두뇌에 알린다. */
    private fun moveCar(km: Int) {
        carKm = km.coerceIn(0, drowsyMap.routeLengthKm)
        if (::mapView.isInitialized) mapView.carKm = carKm
        val spot = drowsyMap.hotspotAt(carKm)
        val nowInside = spot != null
        if (nowInside != inHotspot) {
            log(if (spot != null) "🗺 졸음 다발 구간 진입: ${spot.name} (졸음 신호 ${spot.reports}회)" else "🗺 졸음 다발 구간을 벗어났어요")
        }
        inHotspot = nowInside
        brain.onHotspot(nowInside)
        renderMapInfo()
    }

    private fun renderMapInfo() {
        if (!::mapInfoView.isInitialized) return
        val here = drowsyMap.hotspotAt(carKm)
        val next = drowsyMap.nextHotspot(carKm)
        mapInfoView.text = when {
            here != null -> "⚠️ 지금 '${here.name}' 졸음 다발 구간이에요 (졸음 신호 ${here.reports}회)"
            next != null -> "다음 졸음 다발 구간: ${next.first.name}까지 ${next.second}km"
            else -> "남은 구간에 졸음 다발 구간이 없어요"
        } + "\n자주 조는 구간 순위: " + drowsyMap.ranking().joinToString("  ") { "${it.name} ${it.reports}회" }
    }

    // ---------------- 화면 ----------------

    private fun render() {
        if (::confirmRestButton.isInitialized) confirmRestButton.visibility = if (brain.phase == RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION) View.VISIBLE else View.GONE
        val phaseText = when (brain.phase) {
            RoadMateBrain.Phase.DRIVING -> if (isPark) "🅿️ 주차 중" else "🚗 주행 중 — 지켜보는 중"
            RoadMateBrain.Phase.TALKING -> "💬 대화 중 — 먼저 말을 걸었어요"
            RoadMateBrain.Phase.GUIDING_TO_REST -> "🧭 졸음쉼터로 안내 중"
            RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION -> "🅿️ 정차 확인 · 휴식 준비"
            RoadMateBrain.Phase.NAPPING -> "🌙 파워냅 중 — 편히 쉬세요"
        }
        phaseView.text = phaseText
        if (::napControls.isInitialized) napControls.visibility = if (brain.phase == RoadMateBrain.Phase.NAPPING || brain.phase == RoadMateBrain.Phase.AWAITING_NAP_CONFIRMATION) View.VISIBLE else View.GONE
        if (::sourceView.isInitialized) sourceView.text = "공모전 시연 · 낮잠 30초 · 지도는 예시 데이터\n실제 기어: ${gearName} | 화면 입력: ${if (parking.demoParked != null) "모의 ${if (isPark) "P" else "D"}" else "차량 신호"} | 실내 휴식 제어: ${if (parking.parkedConfirmed) "주차 확인" else "차단"}"
        val r = brain.lastResult
        if (r != null && !isPark) {
            scoreView.text = "휴식 권고 ${r.score}점 · ${levelText(r.level)}"
            riskScore = r.score
            riskColor = levelColor(r.level)
        } else {
            scoreView.text = if (brain.phase == RoadMateBrain.Phase.NAPPING) "휴식 중" else "휴식 권고 —"
            riskScore = 0
            riskColor = Color.parseColor("#4CC38A")
        }
        scoreView.setTextColor(riskColor)
        riskBar.invalidate()
        (statusCard.background as? GradientDrawable)?.setStroke(4, riskColor)
        detailView.text = "연속 운전 ${brain.continuousMinutes}분 · 크루즈 ${if (cruiseOn) "ON" else "OFF"} · 기어 ${if (isPark) "P" else "D"} · 졸음 경고 ${if (dawOn) "ON" else "OFF"}" +
            (if (inHotspot) " · 졸음 다발 구간" else "") +
            (r?.reasons?.takeIf { it.isNotEmpty() && !isPark }?.joinToString(", ", prefix = "\n근거: ") ?: "")
    }

    private fun levelText(l: DrowsinessRiskEngine.Level) = when (l) {
        DrowsinessRiskEngine.Level.NORMAL -> "평상시"
        DrowsinessRiskEngine.Level.CAUTION -> "주의"
        DrowsinessRiskEngine.Level.DANGER -> "위험"
    }

    private fun levelColor(l: DrowsinessRiskEngine.Level) = Color.parseColor(
        when (l) {
            DrowsinessRiskEngine.Level.NORMAL -> "#4CC38A"
            DrowsinessRiskEngine.Level.CAUTION -> "#F5A623"
            DrowsinessRiskEngine.Level.DANGER -> "#FF5A5F"
        }
    )

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 28, 40, 28)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1A2129"))
            cornerRadius = 28f
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 20, 0, 0) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 40)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(text("로드메이트 RoadMate", 34f, "#7FB2FF", bold = true))
        sourceView = text("", 18f, "#B8C4CC").also { root.addView(it) }
        uxView = text("차량의 주행 안전 상태 확인 중 · 설정 잠금", 20f, "#F5A623").also { root.addView(it) }

        // ① 상태 카드: 운전 중에도 한눈에 보이도록 크게
        statusCard = card().also { root.addView(it) }
        phaseView = text("", 40f, "#FFFFFF", bold = true).also { statusCard.addView(it) }
        scoreView = text("", 34f, "#4CC38A", bold = true).also { statusCard.addView(it) }
        riskBar = object : View(this) {
            private val bg = Paint().apply { color = Color.parseColor("#2A333D") }
            private val fg = Paint()
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 28)
            override fun onDraw(canvas: Canvas) {
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 14f, 14f, bg)
                fg.color = riskColor
                canvas.drawRoundRect(0f, 0f, width * riskScore / 100f, height.toFloat(), 14f, 14f, fg)
            }
        }.also { statusCard.addView(it) }
        detailView = text("", 22f, "#B8C4CC").also { statusCard.addView(it) }

        // ② 로드메이트가 마지막으로 한 말
        val speechCard = card().also { root.addView(it) }
        speechLabel = text("💬 로드메이트", 22f, "#7A8A99", bold = true).also { speechCard.addView(it) }
        speechView = text("운전하는 동안 옆에서 지켜볼게요. 졸리면 언제든 '쉬고 싶어'라고 말씀하세요.", 30f, "#FFFFFF").also { speechCard.addView(it) }

        confirmRestButton = button("휴식 시작 확인", "#1E9E6A") { showNapConfirmation() }
        napControls = row(confirmRestButton, button("휴식 중단", "#9B3C44") { cancelRest() }).apply { visibility = View.GONE }
        root.addView(napControls)

        // ③ 졸음 지도 (확장 기능)
        mapCard = card().apply { visibility = View.GONE }.also { root.addView(it) }
        mapCard.addView(text("🗺 졸음 지도 — ${drowsyMap.routeName}", 24f, "#FFB3B5", bold = true))
        mapView = DrowsyMapView(this, drowsyMap).apply { carKm = this@MainActivity.carKm }.also { mapCard.addView(it) }
        mapInfoView = text("", 24f, "#B8C4CC").also { mapCard.addView(it) }
        renderMapInfo()

        // ④ 시연 패널 (에뮬레이터에서 상황 재현용)
        setupRow = row(
            button("🗺 졸음 지도", "#7A3E48") { mapCard.visibility = if (mapCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
            button("시연 패널 접기/펴기", "#2A333D") { demoPanel.visibility = if (demoPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
            button("로그 보기/숨기기", "#2A333D") { logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        ).apply { visibility = View.GONE }.also { root.addView(it) }
        demoPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }.also { root.addView(it) }
        demoPanel.addView(text("시연 패널 · 모의 기어는 차량 제어를 허용하지 않습니다", 22f, "#7A8A99", bold = true).apply { setPadding(0, 24, 0, 0) })
        demoPanel.addView(row(
            button("운전 +30분", "#2E6BE6") {
                demoExtraMinutes += 30
                brain.onTripMinutes(sdkTripMinutes + demoExtraMinutes)
                moveCar(carKm + 25) // 시연: 30분에 약 25km 이동
                log("시연: 운전 시간 +30분 (연속 ${brain.continuousMinutes}분, ${carKm}km 지점)")
            },
            button("졸음 경고 켜기", "#D9822B") { applyDaw(!dawOn) }.also { dawButton = it },
            button("모의 기어 P → D", "#5B6B7A") { parking.simulate(!isPark); syncGear() }.also { gearButton = it }
        ))
        demoPanel.addView(row(
            button("대답: \"괜찮아\"", "#1E9E6A") { voice.simulateAnswer("응 괜찮아") },
            button("대답: \"쉬고 싶어\"", "#1E9E6A") { voice.simulateAnswer("좀 졸려 쉬고 싶어") },
            button("졸음 구간으로 이동", "#7A3E48") {
                val next = drowsyMap.nextHotspot(carKm)?.first ?: drowsyMap.hotspots.first()
                moveCar(next.startKm + 1)
                mapCard.visibility = View.VISIBLE
            }
        ))
        demoPanel.addView(row(
            // Gleo(음성비서) 호출이 끼어드는 상황: 로드메이트는 즉시 멈췄다가 끝나면 이어서 말한다
            button("실제 기어로 복귀", "#4B5563") { parking.useVehicleSignal(); syncGear() },
            button("AI 대화 설정", "#4B5563") { showAiConsent() },
            button("음성 끼어들기 시험", "#4B5563") { voice.simulateInterruption() }
        ))

        logView = text("", 18f, "#8FA0AE").apply { setPadding(0, 24, 0, 0); visibility = View.GONE }
        root.addView(logView)
        render()
        return ScrollView(this).apply { addView(root) }
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 6, 0, 6)
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it) }
    }

    // 차량 화면은 터치 영역이 커야 한다 (Pleos UI 가이드)
    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 24f
        isAllCaps = false
        minHeight = 160
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 16, 8, 0)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    /** SDK 콜백은 다른 스레드에서 올 수 있어 화면 갱신은 UI 스레드에서 한다. 최근 40줄만 유지. */
    private fun log(message: String) {
        runOnUiThread {
            if (!::logView.isInitialized) return@runOnUiThread
            val lines = ("[${timeFormat.format(Date())}] $message\n" + logView.text).lines()
            logView.text = lines.take(40).joinToString("\n")
        }
    }

    private fun requestRuntimePermissions() {
        val needed = listOf("android.permission.RECORD_AUDIO", "pleos.car.permission.FUSED_LOCATION")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onDestroy() {
        destroyed = true
        cancelDialogue()
        if (::uxMonitor.isInitialized) uxMonitor.release()
        aiDialog?.dismiss()
        napDialog?.dismiss()
        cabin.cancelPending()
        main.removeCallbacksAndMessages(null)
        monitor.stop()
        voice.release()
        navigator.release()
        vehicle.release()
        super.onDestroy()
    }
}
