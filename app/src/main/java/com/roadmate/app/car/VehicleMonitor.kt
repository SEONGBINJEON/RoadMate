package com.roadmate.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener

/**
 * 1단계 "예측"에 필요한 차량 신호를 Vehicle SDK에서 구독한다.
 *
 * - Odometer: 이번 주행의 운전 시간(분)
 * - DrivingMode: 크루즈 컨트롤 작동 여부 (단조로운 주행 판단)
 * - Gear: 기어 P(주차) 여부 (낮잠 모드 시작/종료)
 * - TurnSignal, Brake: 운전자 조작 (깨어 있다는 신호)
 * - Safety: 순정 졸음 경고등(DRIVER_ATTENTION)
 */
class VehicleMonitor(
    private val vehicle: Vehicle,
    private val log: (String) -> Unit
) {
    interface Callback {
        fun onTripMinutes(minutes: Int)
        fun onCruise(active: Boolean)
        fun onGear(isPark: Boolean, gearName: String)
        fun onGearUnavailable()
        fun onDriverInput(source: String)
        fun onDawWarning(on: Boolean)
    }

    private var callback: Callback? = null

    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) {
            value?.let { callback?.onTripMinutes(it) }
        }
        override fun onFailed(e: Exception) = log("주행 시간 구독 실패: ${e.message}")
    }

    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) {
            callback?.onCruise(state == CruiseControl.ACTIVATED)
        }
        override fun onFailed(e: Exception) = log("크루즈 구독 실패: ${e.message}")
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            if (gear == null) callback?.onGearUnavailable()
            else callback?.onGear(gear == VehicleGear.GEAR_PARK, gear.name)
        }
        override fun onFailed(e: Exception) { callback?.onGearUnavailable(); log("기어 구독 실패: ${e.message}") }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            if (state != null && state != TurnSignalValue.OFF) callback?.onDriverInput("방향지시등")
        }
        override fun onFailed(e: Exception) = log("방향지시등 구독 실패: ${e.message}")
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            if (status == true) callback?.onDriverInput("브레이크")
        }
        override fun onFailed(e: Exception) = log("브레이크 구독 실패: ${e.message}")
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            val daw = telltales?.telltales?.firstOrNull { it.type == TelltaleType.DRIVER_ATTENTION }
            callback?.onDawWarning(daw != null && daw.state != TelltaleState.OFF)
        }
        override fun onFailed(e: Exception) =
            log("순정 졸음 경고등 구독 불가(${e.message}) → 다른 신호로 판단합니다")
    }

    /** 차량 신호 구독 시작. 지원되지 않는 신호가 있어도 나머지는 계속 동작한다. */
    @Suppress("DEPRECATION")
    fun start(callback: Callback) {
        this.callback = callback
        safely("주행 시간") { vehicle.getOdometer().registerTripCurrentDrivingTime(drivingTimeListener) }
        safely("크루즈") { vehicle.getDrivingMode().registerCruiseControlState(cruiseListener) }
        safely("기어") { vehicle.getGear().registerGearSelection(gearListener) }
        safely("방향지시등") { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) }
        safely("브레이크") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        // 경고등 구독은 시스템 권한(PRIVATE_CAR_SAFETY)이 필요해 일반 앱에서는 막힐 수 있다.
        safely("순정 졸음 경고등") { vehicle.getSafety().registerTelltales(telltaleListener) }

        // 현재 값 한 번 읽기
        safely("현재 주행 시간") {
            vehicle.getOdometer().getTripCurrentDrivingTime(
                onComplete = { m -> m?.let { callback.onTripMinutes(it) } },
                onFailed = { e -> log("현재 주행 시간 조회 실패: ${e.message}") }
            )
        }
        safely("현재 기어") {
            vehicle.getGear().getCurrentGearSelectionState(
                onComplete = { g -> if (g == null) callback.onGearUnavailable() else callback.onGear(g == VehicleGear.GEAR_PARK, g.name) },
                onFailed = { e -> callback.onGearUnavailable(); log("현재 기어 조회 실패: ${e.message}") }
            )
        }
        log("차량 신호 구독 시작")
    }

    @Suppress("DEPRECATION")
    fun stop() {
        safely("구독 해제") {
            vehicle.getOdometer().unregisterTripCurrentDrivingTime(drivingTimeListener)
            vehicle.getDrivingMode().unregisterCruiseControlState(cruiseListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getSafety().unregisterTelltales(telltaleListener)
        }
        callback = null
    }

    private fun safely(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("$name 사용 불가: ${e.message}") }
    }
}
