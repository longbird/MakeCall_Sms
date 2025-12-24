package com.example.autocall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * 기본 전화 앱이 되기 위해 필요한 최소한의 Dialer Activity.
 *
 * 이 Activity는 DIAL 인텐트를 받아서 처리합니다.
 * 우리 앱의 주 목적은 자동 전화 걸기이므로,
 * 사용자가 직접 다이얼 요청을 하면 MainActivity로 전달합니다.
 */
class DialerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "DialerActivity 시작됨")
        Log.d(TAG, "Intent action: ${intent?.action}")
        Log.d(TAG, "Intent data: ${intent?.data}")

        // DIAL 인텐트 처리
        handleDialIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDialIntent(it) }
    }

    private fun handleDialIntent(intent: Intent?) {
        val phoneNumber = intent?.data?.schemeSpecificPart

        Log.d(TAG, "다이얼 요청 전화번호: $phoneNumber")

        // MainActivity로 전환하면서 전화번호 전달
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!phoneNumber.isNullOrEmpty()) {
                putExtra("phone_number", phoneNumber)
            }
        }

        startActivity(mainIntent)
        finish()
    }
}
