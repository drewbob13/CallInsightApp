package com.example.callinsight

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PostCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NUMBER = "number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_call)

        val number = intent.getStringExtra(EXTRA_NUMBER)?.trim().orEmpty()

        val label = findViewById<TextView>(R.id.postCallNumber)
        val btn = findViewById<Button>(R.id.sendTextBtn)

        label.text = if (number.isBlank()) "Call ended" else "Call ended: $number"

        btn.setOnClickListener {
            if (number.isBlank()) return@setOnClickListener

            // Open default SMS app (usually Google Messages) to that number.
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
            }
            startActivity(smsIntent)
            finish()
        }
    }
}
