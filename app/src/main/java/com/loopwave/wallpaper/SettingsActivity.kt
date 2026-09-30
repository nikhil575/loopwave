package com.loopwave.wallpaper

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.loopwave.wallpaper.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = getString(R.string.app_version_label) + " " + versionName()
        binding.backButton.setOnClickListener { finish() }

        binding.privacyPolicyRow.setOnClickListener {
            openTextViewer(getString(R.string.privacy_policy), getString(R.string.privacy_policy_content))
        }
        binding.licensesRow.setOnClickListener {
            openTextViewer(getString(R.string.licenses_title), getString(R.string.licenses_content))
        }
        binding.feedbackRow.setOnClickListener { sendFeedback() }
        binding.rateRow.setOnClickListener { openPlayStoreListing() }
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (e: Exception) {
            "—"
        }
    }

    private fun openTextViewer(title: String, body: String) {
        val intent = Intent(this, TextViewerActivity::class.java)
        intent.putExtra(TextViewerActivity.EXTRA_TITLE, title)
        intent.putExtra(TextViewerActivity.EXTRA_BODY, body)
        startActivity(intent)
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.feedback_email)))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback_subject))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tries the Play Store app first (nicer in-app review flow), falls back
     * to the web listing if Play Store isn't installed — matters most on
     * emulators/dev devices without the Play Store app during testing.
     */
    private fun openPlayStoreListing() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.no_play_store), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
