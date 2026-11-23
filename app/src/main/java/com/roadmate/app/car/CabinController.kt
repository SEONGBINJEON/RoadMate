package com.roadmate.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.os.Handler
import android.os.Looper

/**
 * 3·4단계에서 차 실내 환경을 바꾼다 (Vehicle SDK 제어 API).
 *
 * - 각성 모드: 졸음쉼터로 가는 동안 시원한 바람 + 통풍시트로 1차로 잠을 깨운다.
 * - 낮잠 모드: 조명을 어둡게, 잠자기 좋은 온도로, 시트 열선을 약하게, 등받이를 뒤로.
 * - 기상 모드: 시원한 바람, 통풍시트, 조명 밝게, 창문 살짝 열기, 등받이 원위치.
 *
 * 차종마다 제어할 수 있는 영역(운전석, 앞좌석 전체 등)과 값의 범위가 다르기 때문에,
 * prepare()에서 Capability API로 '이 차가 지원하는 영역·범위'를 먼저 확인하고 그 값으로 제어한다.
 */
class CabinController(
    private val vehicle: Vehicle,
    private val log: (String) -> Unit,
    private val parkedConfirmed: () -> Boolean
) {
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var parkedOperation = false
    private var backrestSupported = false
    private var windowSupported = false
    private var backrestMoving = false

    /** Cancel delayed climate retries and stop any seat movement immediately. */
    fun cancelPending() {
        generation++
        main.removeCallbacksAndMessages(null)
        if (backrestMoving) {
            call("등받이 정지") { vehicle.getSeat().moveSeatBackrestAngle(backrestArea, MoveControl.STOP, onFailed = fail("등받이")) }
            backrestMoving = false
        }
        parkedOperation = false
    }

    private fun begin(parkedOnly: Boolean): Boolean {
        cancelPending()
        if (parkedOnly && !parkedConfirmed()) { log("실제 기어 P가 확인되지 않아 실내 제어를 보류합니다"); return false }
        parkedOperation = parkedOnly
        return true
    }

    private fun active(token: Long): Boolean = token == generation && (!parkedOperation || parkedConfirmed())

    // Capability 확인 결과 (확인 전에는 운전석 기본값)
    private var powerArea: SeatArea = driverSeat
    private var acArea: SeatArea = driverSeat
    private var fanArea: SeatArea = driverSeat
    private var fanRange = 0..8
    private var tempArea: SeatArea = driverSeat
    private var tempRange = 16f..30f
    private var ambientArea: SeatArea = driverSeat
    private var ambientRange = 0..100
    private var backrestArea: SeatArea = driverSeat
    private var windowArea = WindowArea(WindowValue.ROW_1_LEFT)
    private var windowRange = 0..100
    private var seatTempArea: SeatArea = driverSeat
    private var seatTempRange = 0..3          // 음수 = 통풍(V), 양수 = 열선(H)
    private var seatVentArea: SeatArea = driverSeat
    private var seatVentRange = 0..3

    /** 차량이 지원하는 제어 영역·범위 확인. 차량 서비스 연결 후 한 번 호출한다. */
    fun prepare() {
        val hvac = vehicle.getHVAC()
        call("공조 전원 확인") {
            hvac.checkHvacPowerCapabilities(
                onComplete = { _, areas -> areas.firstOrNull()?.let { powerArea = it } },
                onFailed = fail("공조 전원 확인")
            )
        }
        call("에어컨 확인") {
            hvac.checkHvacAcCapabilities(
                onComplete = { _, areas -> areas.firstOrNull()?.let { acArea = it } },
                onFailed = fail("에어컨 확인")
            )
        }
        call("풍량 확인") {
            hvac.checkHvacFanSpeedCapabilities(
                onComplete = { _, map -> map.entries.firstOrNull()?.let { fanArea = it.key; fanRange = it.value.minValue..it.value.maxValue } },
                onFailed = fail("풍량 확인")
            )
        }
        call("온도 확인") {
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { _, map -> map.entries.firstOrNull()?.let { tempArea = it.key; tempRange = it.value.minValue..it.value.maxValue } },
                onFailed = fail("온도 확인")
            )
        }
        call("앰비언트 조명 확인") {
            vehicle.getLight().checkAmbientLightBrightnessCapabilities(
                onComplete = { _, map -> map.entries.firstOrNull()?.let { ambientArea = it.key; ambientRange = it.value.minValue..it.value.maxValue } },
                onFailed = fail("앰비언트 조명 확인")
            )
        }
        call("등받이 확인") {
            vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities(
                onComplete = { _, areas -> areas.firstOrNull { it == driverSeat }?.let { backrestArea = it; backrestSupported = true } },
                onFailed = fail("등받이 확인")
            )
        }
        call("시트 온도 확인") {
            vehicle.getSeat().checkSeatTemperatureCapabilities(
                onComplete = { _, map -> (map.entries.firstOrNull { it.key == driverSeat } ?: map.entries.firstOrNull())?.let { seatTempArea = it.key; seatTempRange = it.value.minValue..it.value.maxValue }
                    log("  · 시트 온도 지원: ${map.size}개 좌석, 범위 $seatTempRange") },
                onFailed = fail("시트 온도 확인")
            )
        }
        call("통풍시트 확인") {
            vehicle.getSeat().checkSeatVentilationCapabilities(
                onComplete = { _, map -> (map.entries.firstOrNull { it.key == driverSeat } ?: map.entries.firstOrNull())?.let { seatVentArea = it.key; seatVentRange = it.value.minValue..it.value.maxValue }
                    log("  · 통풍시트 지원: ${map.size}개 좌석, 범위 $seatVentRange") },
                onFailed = fail("통풍시트 확인")
            )
        }
        call("창문 확인") {
            vehicle.getWindow().checkWindowPositionCapabilities(
                onComplete = { _, map -> map.entries.firstOrNull { it.key == windowArea }?.let { windowRange = it.value.minValue..it.value.maxValue; windowSupported = true } },
                onFailed = fail("창문 확인")
            )
        }
    }

    /** 졸음쉼터로 가는 동안: 시원한 바람 + 통풍시트 */
    fun refreshMode() {
        if (!begin(parkedOnly = false)) return
        log("❄️ 각성 모드: 시원한 바람, 통풍시트")
        climate(temperature = 20f, fanRatio = 0.6f, ac = true)
        seatCooling(level = 2)
    }

    /** 정차 후 낮잠 모드 */
    fun napMode() {
        if (!begin(parkedOnly = true)) return
        log("🌙 낮잠 모드: 조명 어둡게, 24℃, 등받이 뒤로")
        climate(temperature = 24f, fanRatio = 0.25f, ac = false)
        call("시트 열선") { vehicle.getSeat().setHvacSeatTemperature(seatTempArea, 1.coerceIn(seatTempRange), onFailed = fail("시트 열선")) }
        call("실내등") { vehicle.getLight().setCabinLightSwitch(LightSwitch.OFF, onFailed = fail("실내등")) }
        ambient(ratio = 0.1f)
        moveBackrest(MoveControl.BACKWARD)
    }

    /** 낮잠이 끝나면 기상 모드 */
    fun wakeMode() {
        if (!begin(parkedOnly = true)) return
        log("☀️ 기상 모드: 시원한 바람, 조명 밝게, 창문 살짝 열기")
        climate(temperature = 19f, fanRatio = 0.75f, ac = true)
        seatCooling(level = 3)
        call("실내등") { vehicle.getLight().setCabinLightSwitch(LightSwitch.ON, onFailed = fail("실내등")) }
        ambient(ratio = 1f)
        val open = windowRange.first + ((windowRange.last - windowRange.first) * 0.2f).toInt()
        if (windowSupported && parkedConfirmed()) call("창문") { vehicle.getWindow().setWindowPosition(windowArea, open, onFailed = fail("창문")) }
        moveBackrest(MoveControl.FORWARD)
    }

    /**
     * 통풍시트 켜기. 차량에 따라 '시트 온도' 하나로 열선(+)·통풍(-)을 함께 쓰거나(가상 콘솔의 V3~H3),
     * 통풍 신호가 따로 있다. 지원 범위를 보고 맞는 쪽으로 보낸다.
     */
    private fun seatCooling(level: Int) {
        log("  · 통풍시트 ${level}단 요청 (시트 온도 범위 $seatTempRange)")
        if (seatTempRange.first < 0) {
            val v = (-level).coerceIn(seatTempRange)
            call("통풍시트") { vehicle.getSeat().setHvacSeatTemperature(seatTempArea, v, onComplete = { _, _ -> log("  · 통풍시트 V$level 설정 완료") }, onFailed = fail("통풍시트")) }
        } else {
            call("시트 열선 끄기") { vehicle.getSeat().setHvacSeatTemperature(seatTempArea, 0.coerceIn(seatTempRange), onFailed = fail("시트 열선")) }
            call("통풍시트") { vehicle.getSeat().setHvacSeatVentilation(seatVentArea, level.coerceIn(seatVentRange), onComplete = { _, _ -> log("  · 통풍시트(별도 신호) $level 설정 완료") }, onFailed = fail("통풍시트")) }
        }
    }

    private fun climate(temperature: Float, fanRatio: Float, ac: Boolean) {
        val hvac = vehicle.getHVAC()
        val temp = temperature.coerceIn(tempRange.start, tempRange.endInclusive)
        val fan = fanRange.first + ((fanRange.last - fanRange.first) * fanRatio).toInt()
        call("공조 전원") { hvac.setHvacPower(powerArea, true, onFailed = fail("공조 전원")) }
        // 전원이 켜진 직후에는 풍량 등이 '사용 불가'로 거절될 수 있어 잠깐 기다렸다가 설정한다.
        val token = generation
        main.postDelayed({
            if (!active(token)) return@postDelayed
            call("온도") { hvac.setHvacTemperature(tempArea, temp, TemperatureUnit.CELSIUS, onFailed = fail("온도")) }
            setFanWithRetry(fan, retriesLeft = 2, token = token)
            call("에어컨") { hvac.setHvacAc(acArea, ac, onFailed = fail("에어컨")) }
        }, 700L)
    }

    /** 공조 전원이 막 켜졌을 때는 풍량 설정이 거절될 수 있어, 실패하면 잠시 뒤 다시 시도한다. */
    private fun setFanWithRetry(fan: Int, retriesLeft: Int, token: Long) {
        if (!active(token)) return
        call("풍량") {
            vehicle.getHVAC().setHvacFanSpeed(fanArea, fan, onFailed = { e ->
                if (retriesLeft > 0 && active(token)) main.postDelayed({ setFanWithRetry(fan, retriesLeft - 1, token) }, 1_500L)
                else log("  · 풍량 실패: ${e.message}")
            })
        }
    }

    private fun ambient(ratio: Float) = call("앰비언트 조명") {
        val value = ambientRange.first + ((ambientRange.last - ambientRange.first) * ratio).toInt()
        vehicle.getLight().setAmbientLightBrightness(
            ambientArea, value,
            onComplete = { _, _ -> },
            onFailed = fail("앰비언트 조명")
        )
    }

    /** 등받이는 '움직이기 시작 → 3초 뒤 멈춤' 방식으로 제어한다. */
    private fun moveBackrest(direction: MoveControl) {
        if (!backrestSupported || !parkedConfirmed()) { log("운전석 등받이 지원/주차 확인 전에는 움직이지 않습니다"); return }
        backrestMoving = true
        call("등받이") { vehicle.getSeat().moveSeatBackrestAngle(backrestArea, direction, onFailed = fail("등받이")) }
        main.postDelayed({
            backrestMoving = false
            call("등받이 정지") { vehicle.getSeat().moveSeatBackrestAngle(backrestArea, MoveControl.STOP, onFailed = fail("등받이")) }
        }, 3_000L)
    }

    private fun fail(name: String): (Exception) -> Unit = { e -> log("  · $name 실패: ${e.message}") }

    private fun call(name: String, block: () -> Unit) {
        if (parkedOperation && !parkedConfirmed() && name != "등받이 정지") return
        try { block() } catch (e: Exception) { log("  · $name 사용 불가: ${e.message}") }
    }
}
