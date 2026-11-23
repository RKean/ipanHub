package com.cartube.ytauto

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button

class InstallGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val body = TextView(this).apply {
            text = buildGuideText()
            textSize = 14f
        }
        column.addView(body)

        val adbCmd = TextView(this).apply {
            text = ADB_COMMAND
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 16)
        }
        column.addView(adbCmd)

        val copyBtn = Button(this).apply {
            text = getString(R.string.install_guide_copy_adb)
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_COMMAND))
                Toast.makeText(this@InstallGuideActivity, R.string.install_guide_copied, Toast.LENGTH_SHORT).show()
            }
        }
        column.addView(copyBtn)

        scroll.addView(column)
        setContentView(scroll)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.install_guide_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildGuideText(): String {
        val installer = AaInstallTrust.installingPackageName(this) ?: "(unknown)"
        val trusted = AaInstallTrust.isTrustedForTemplateVideo(this)
        return buildString {
            append(getString(R.string.install_guide_intro))
            append("\n\n")
            append("Current installer: ")
            append(installer)
            append("\nTemplate video mode: ")
            append(if (trusted) "YES" else "NO (media/audio only)")
            append("\n\n")
            append(getString(R.string.install_guide_windows_steps))
            append("\n\n")
            append(getString(R.string.install_guide_shizuku_note))
            append("\n\n")
            append(getString(R.string.install_guide_after_install))
        }
    }

    companion object {
        const val ADB_COMMAND =
            "adb install -r -g -i com.android.vending cartube-debug.apk"
    }
}