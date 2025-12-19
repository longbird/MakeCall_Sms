package com.example.autocall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat

/**
 * 통화 종료 원인(DisconnectCause)을 감지하는 리스너
 *
 * Android 9+ (API 28): PhoneStateListener.onCallDisconnectCauseChanged()
 * Android 12+ (API 31): TelephonyCallback.CallDisconnectCauseListener
 *
 * 참고: READ_PRECISE_PHONE_STATE 권한이 필요하며, 이는 시스템 앱만 사용 가능할 수 있음
 */
object CallDisconnectListener {
    private const val TAG = "CallDisconnectListener"

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var isRegistered = false

    // 마지막 DisconnectCause 저장
    var lastDisconnectCause: Int = -1
        private set
    var lastPreciseDisconnectCause: Int = -1
        private set

    // DisconnectCause 상수 (android.telephony.DisconnectCause)
    object DisconnectCause {
        const val NOT_DISCONNECTED = 0
        const val INCOMING_MISSED = 1
        const val NORMAL = 2
        const val LOCAL = 3
        const val BUSY = 4
        const val CONGESTION = 5
        const val MMI = 6
        const val INVALID_NUMBER = 7
        const val NUMBER_UNREACHABLE = 8
        const val SERVER_UNREACHABLE = 9
        const val INVALID_CREDENTIALS = 10
        const val OUT_OF_NETWORK = 11
        const val SERVER_ERROR = 12
        const val TIMED_OUT = 13
        const val LOST_SIGNAL = 14
        const val LIMIT_EXCEEDED = 15
        const val INCOMING_REJECTED = 16
        const val POWER_OFF = 17
        const val OUT_OF_SERVICE = 18
        const val ICC_ERROR = 19
        const val CALL_BARRED = 20
        const val FDN_BLOCKED = 21
        const val CS_RESTRICTED = 22
        const val CS_RESTRICTED_NORMAL = 23
        const val CS_RESTRICTED_EMERGENCY = 24
        const val UNOBTAINABLE_NUMBER = 25
        const val OUTGOING_FAILURE = 26
        const val OUTGOING_CANCELED = 27
        const val IMS_MERGED_SUCCESSFULLY = 28
        const val CDMA_ALREADY_ACTIVATED = 29
        const val NOT_VALID = 30
        const val ANSWERED_ELSEWHERE = 31
        const val CALL_PULLED = 32
        const val CALL_END_CAUSE_CALL_PULL = 33
        const val MAXIMUM_NUMBER_OF_CALLS_REACHED = 34
        const val DATA_DISABLED = 35
        const val DATA_LIMIT_REACHED = 36
        const val DIALED_ON_WRONG_SLOT = 37
        const val DIALED_CALL_FORWARDING_WHILE_ROAMING = 38
        const val IMEI_NOT_ACCEPTED = 39
        const val WIFI_LOST = 40
        const val IMS_ACCESS_BLOCKED = 41
        const val IMS_SIP_ALTERNATE_EMERGENCY_CALL = 42
        const val MEDIA_TIMEOUT = 43
        const val SIP_INVITE_TIMEOUT = 44
        const val EMERGENCY_TEMP_FAILURE = 45
        const val EMERGENCY_PERM_FAILURE = 46
        const val NORMAL_UNSPECIFIED = 47

        fun getName(cause: Int): String {
            return when (cause) {
                NOT_DISCONNECTED -> "NOT_DISCONNECTED"
                INCOMING_MISSED -> "INCOMING_MISSED"
                NORMAL -> "NORMAL (정상 종료)"
                LOCAL -> "LOCAL (내가 끊음)"
                BUSY -> "BUSY (통화중)"
                CONGESTION -> "CONGESTION (혼잡)"
                MMI -> "MMI"
                INVALID_NUMBER -> "INVALID_NUMBER (잘못된 번호)"
                NUMBER_UNREACHABLE -> "NUMBER_UNREACHABLE (연결 불가)"
                SERVER_UNREACHABLE -> "SERVER_UNREACHABLE"
                INVALID_CREDENTIALS -> "INVALID_CREDENTIALS"
                OUT_OF_NETWORK -> "OUT_OF_NETWORK"
                SERVER_ERROR -> "SERVER_ERROR"
                TIMED_OUT -> "TIMED_OUT (시간 초과)"
                LOST_SIGNAL -> "LOST_SIGNAL (신호 손실)"
                LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
                INCOMING_REJECTED -> "INCOMING_REJECTED (수신 거절)"
                POWER_OFF -> "POWER_OFF (전원 꺼짐)"
                OUT_OF_SERVICE -> "OUT_OF_SERVICE (서비스 불가)"
                ICC_ERROR -> "ICC_ERROR"
                CALL_BARRED -> "CALL_BARRED (통화 차단)"
                FDN_BLOCKED -> "FDN_BLOCKED"
                CS_RESTRICTED -> "CS_RESTRICTED"
                CS_RESTRICTED_NORMAL -> "CS_RESTRICTED_NORMAL"
                CS_RESTRICTED_EMERGENCY -> "CS_RESTRICTED_EMERGENCY"
                UNOBTAINABLE_NUMBER -> "UNOBTAINABLE_NUMBER (없는 번호)"
                OUTGOING_FAILURE -> "OUTGOING_FAILURE (발신 실패)"
                OUTGOING_CANCELED -> "OUTGOING_CANCELED (발신 취소)"
                ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE (다른 곳에서 응답)"
                CALL_PULLED -> "CALL_PULLED"
                NORMAL_UNSPECIFIED -> "NORMAL_UNSPECIFIED"
                else -> "UNKNOWN ($cause)"
            }
        }

        /**
         * 없는 번호/연결 불가 관련 DisconnectCause인지 확인
         */
        fun isInvalidNumber(cause: Int): Boolean {
            return cause == INVALID_NUMBER ||
                   cause == NUMBER_UNREACHABLE ||
                   cause == UNOBTAINABLE_NUMBER ||
                   cause == OUT_OF_SERVICE ||
                   cause == POWER_OFF
        }

        /**
         * 통화중 관련 DisconnectCause인지 확인
         */
        fun isBusy(cause: Int): Boolean {
            return cause == BUSY || cause == CONGESTION
        }
    }

    /**
     * DisconnectCause 리스너 등록
     */
    fun register(context: Context) {
        if (isRegistered) {
            Log.d(TAG, "이미 등록되어 있음")
            return
        }

        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.e(TAG, "TelephonyManager를 가져올 수 없음")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "DisconnectCause 리스너 등록 시도")
        Log.d(TAG, "Android API: ${Build.VERSION.SDK_INT}")
        Log.d(TAG, "========================================")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31)
                registerTelephonyCallback(context)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9-11 (API 28-30)
                registerPhoneStateListener(context)
            } else {
                Log.w(TAG, "Android ${Build.VERSION.SDK_INT}는 DisconnectCause 감지를 지원하지 않음")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "권한 오류: ${e.message}")
            Log.e(TAG, "READ_PRECISE_PHONE_STATE 권한이 필요합니다 (시스템 앱 전용)")
        } catch (e: Exception) {
            Log.e(TAG, "리스너 등록 실패: ${e.message}", e)
        }
    }

    /**
     * Android 12+ TelephonyCallback 등록
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback(context: Context) {
        try {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallDisconnectCauseListener {
                override fun onCallDisconnectCauseChanged(disconnectCause: Int, preciseDisconnectCause: Int) {
                    handleDisconnectCause(disconnectCause, preciseDisconnectCause)
                }
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                telephonyManager?.registerTelephonyCallback(
                    context.mainExecutor,
                    telephonyCallback!!
                )
                isRegistered = true
                Log.d(TAG, "TelephonyCallback 등록 성공 (Android 12+)")
            } else {
                Log.w(TAG, "READ_PHONE_STATE 권한 없음")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "TelephonyCallback 등록 실패 (권한 부족): ${e.message}")
            // READ_PRECISE_PHONE_STATE 권한이 필요할 수 있음
        }
    }

    /**
     * Android 9-11 PhoneStateListener 등록
     */
    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener(context: Context) {
        try {
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallDisconnectCauseChanged(disconnectCause: Int, preciseDisconnectCause: Int) {
                    handleDisconnectCause(disconnectCause, preciseDisconnectCause)
                }
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                telephonyManager?.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES
                )
                isRegistered = true
                Log.d(TAG, "PhoneStateListener 등록 성공 (Android 9-11)")
            } else {
                Log.w(TAG, "READ_PHONE_STATE 권한 없음")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "PhoneStateListener 등록 실패 (권한 부족): ${e.message}")
        }
    }

    /**
     * DisconnectCause 처리
     */
    private fun handleDisconnectCause(disconnectCause: Int, preciseDisconnectCause: Int) {
        lastDisconnectCause = disconnectCause
        lastPreciseDisconnectCause = preciseDisconnectCause

        val causeName = DisconnectCause.getName(disconnectCause)

        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║          DisconnectCause 감지됨!                           ║")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════╣")
        Log.d(TAG, "║ disconnectCause: $disconnectCause")
        Log.d(TAG, "║ 이름: $causeName")
        Log.d(TAG, "║ preciseDisconnectCause: $preciseDisconnectCause")
        Log.d(TAG, "║")
        Log.d(TAG, "║ 없는 번호 여부: ${DisconnectCause.isInvalidNumber(disconnectCause)}")
        Log.d(TAG, "║ 통화중 여부: ${DisconnectCause.isBusy(disconnectCause)}")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

        // 콜백 호출 (필요시 추가)
        onDisconnectCauseListener?.invoke(disconnectCause, preciseDisconnectCause)
    }

    /**
     * 리스너 해제
     */
    @Suppress("DEPRECATION")
    fun unregister() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager?.unregisterTelephonyCallback(telephonyCallback!!)
                telephonyCallback = null
            } else if (phoneStateListener != null) {
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                phoneStateListener = null
            }
            isRegistered = false
            Log.d(TAG, "DisconnectCause 리스너 해제됨")
        } catch (e: Exception) {
            Log.e(TAG, "리스너 해제 실패: ${e.message}", e)
        }
    }

    /**
     * 마지막 DisconnectCause 초기화
     */
    fun resetLastCause() {
        lastDisconnectCause = -1
        lastPreciseDisconnectCause = -1
    }

    /**
     * DisconnectCause 콜백 리스너
     */
    var onDisconnectCauseListener: ((disconnectCause: Int, preciseDisconnectCause: Int) -> Unit)? = null
}
