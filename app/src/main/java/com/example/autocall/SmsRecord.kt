package com.example.autocall

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SmsRecord(
    var id: Long = 0,
    var phoneNumber: String = "",
    var message: String = "",
    var timestamp: Long = 0
) {
    /**
     * 타임스탬프를 포맷된 날짜 문자열로 변환
     */
    fun getFormattedTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
