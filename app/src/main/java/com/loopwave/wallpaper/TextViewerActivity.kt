package com.loopwave.wallpaper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.loopwave.wallpaper.databinding.ActivityTextViewerBinding

/**
 * Generic scrollable title+body screen, reused for both Privacy Policy and
 * Open Source Licenses so those two static-text screens don't need separate
 * near-identical Activities.
 */
class TextViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.screenTitle.text = intent.getStringExtra(EXTRA_TITLE)
        binding.bodyText.text = intent.getStringExtra(EXTRA_BODY)

        binding.backButton.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
    }
}
