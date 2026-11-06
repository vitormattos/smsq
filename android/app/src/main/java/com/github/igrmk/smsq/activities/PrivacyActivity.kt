package com.github.igrmk.smsq.activities

import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import com.github.igrmk.smsq.R
import com.github.igrmk.smsq.helpers.linf
import kotlinx.android.synthetic.main.activity_privacy.*

class PrivacyActivity : AppCompatActivity() {
    private val tag = this::class.simpleName!!

    override fun onCreate(savedInstanceState: Bundle?) {
        linf(tag, "creating activity...")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        // Privacy text is local and HTML-escaped; WebView needs no script access.
        text.settings.javaScriptEnabled = false
        val policy = assets.open("PRIVACY.md").bufferedReader().use { it.readText() }
        val safe = TextUtils.htmlEncode(policy)
        val html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{padding:12px;font-family:sans-serif;}" +
            "pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:sans-serif;}</style>" +
            "</head><body><pre>" + safe + "</pre></body></html>"
        text.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
