package com.loopwave.wallpaper

import android.animation.ObjectAnimator
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.loopwave.wallpaper.databinding.ActivityMainBinding

/**
 * Lets the user pick a video, preview it, and set it as a live wallpaper
 * via the system's live-wallpaper picker. Exposes playback speed and
 * crop-mode controls, both applied live to the preview and persisted for
 * [VideoWallpaperService] to read. Live wallpapers apply to both home and
 * lock screen simultaneously — Android does not support independent
 * live wallpapers per surface.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var selectedVideoUri: Uri? = null

    // Current preview MediaPlayer, captured so we can re-apply speed when the
    // slider moves without needing to re-prepare the whole VideoView.
    private var previewPlayer: MediaPlayer? = null
    private var currentSpeed: Float = VideoWallpaperService.Companion.DEFAULT_SPEED
    private var currentScalingMode: Int = VideoWallpaperService.Companion.DEFAULT_SCALING_MODE

    // Debounce handler — delays writing speed to prefs until 500ms after the
    // user lifts their finger, so rapid slider adjustments don't each trigger
    // a wallpaper service re-prepare.
    private val speedDebounceHandler = Handler(Looper.getMainLooper())
    private val speedDebounceRunnable = Runnable {
        prefs.edit().putFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, currentSpeed).apply()
    }

    // Android's Photo Picker — opens directly into a Google Photos-style grid
    // regardless of any OS-remembered "last used app" state, unlike
    // ACTION_OPEN_DOCUMENT which can default to the plain Files app for a
    // package the system has no picker history for yet. Its returned URIs
    // support takePersistableUriPermission the same as document URIs, which
    // matters here since the wallpaper service needs to reopen this file
    // indefinitely, including after reboots.
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        currentSpeed = prefs.getFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, VideoWallpaperService.Companion.DEFAULT_SPEED)
        currentScalingMode = prefs.getInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, VideoWallpaperService.Companion.DEFAULT_SCALING_MODE)

        setupSpeedControl()
        setupCropControl()
        restoreSelection()
        startRecDotPulse()

        binding.pickButton.setOnClickListener {
            pickVideoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        binding.setWallpaperButton.setOnClickListener {
            launchLiveWallpaperPicker()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.galleryDriftItem.setOnClickListener { onBundledClipSelected(R.raw.loop_drift) }
        binding.galleryPulseItem.setOnClickListener { onBundledClipSelected(R.raw.loop_pulse) }
        binding.galleryTwinItem.setOnClickListener { onBundledClipSelected(R.raw.loop_twin) }
    }

    /**
     * The one motion signature in this screen: a slow breathing pulse on the
     * "preview · looping" indicator dot, echoing the video looping below it.
     * Deliberately subtle and singular — restraint matters more than a
     * showy animation here.
     */
    private fun startRecDotPulse() {
        ObjectAnimator.ofFloat(binding.recDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 1400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    // --- Speed control -------------------------------------------------

    /**
     * SeekBar progress (0..35) maps to speed 0.25x..2.0x in 0.05x steps,
     * with progress 15 landing exactly on 1.0x (normal speed) so the default
     * thumb position reads naturally as "no change."
     */
    private fun progressToSpeed(progress: Int): Float = 0.25f + (progress * 0.05f)
    private fun speedToProgress(speed: Float): Int = (((speed - 0.25f) / 0.05f) + 0.5f).toInt()

    private fun setupSpeedControl() {
        binding.speedSeekBar.progress = speedToProgress(currentSpeed).coerceIn(0, 35)
        updateSpeedLabel(currentSpeed)

        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                updateSpeedLabel(speed)
                if (fromUser) {
                    currentSpeed = speed
                    applySpeedToPreview(speed)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Cancel any pending debounced write when the user starts
                // dragging again before the delay has elapsed.
                speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Write to prefs 500ms after finger lifts — giving the user
                // time to make a second adjustment before the wallpaper
                // service does an expensive re-prepare.
                speedDebounceHandler.postDelayed(speedDebounceRunnable, 500L)
            }
        })
    }

    private fun updateSpeedLabel(speed: Float) {
        binding.speedValueText.text = String.format("%.2fx", speed)
    }

    private fun applySpeedToPreview(speed: Float) {
        val player = previewPlayer ?: return
        try {
            if (!player.isPlaying) player.start()
            player.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: IllegalStateException) {
            // Player not in a state that accepts speed changes right now (e.g.
            // mid-teardown) — safe to ignore, the next prepare will apply it.
        }
    }

    // --- Crop / scaling mode control ------------------------------------

    private fun setupCropControl() {
        val initialCheckedId = if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
            binding.cropFitButton.id
        } else {
            binding.cropFillButton.id
        }
        binding.cropToggleGroup.check(initialCheckedId)

        binding.cropToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScalingMode = if (checkedId == binding.cropFitButton.id) {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
            } else {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
            prefs.edit().putInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, currentScalingMode).apply()
            // Crop mode for VideoView itself is controlled by view scaleType,
            // which VideoView doesn't expose directly the way MediaPlayer does
            // for a raw Surface — the preview already fills its card via
            // layout_gravity=center, so this setting's visual effect is most
            // apparent once actually set as a wallpaper. We still persist it
            // here so the wallpaper service picks it up immediately.
        }
    }

    // --- Video selection -------------------------------------------------

    private fun restoreSelection() {
        val saved = prefs.getString(VideoWallpaperService.Companion.PREF_VIDEO_URI, null)
        if (saved.isNullOrBlank()) return

        val uri = try {
            Uri.parse(saved)
        } catch (e: Exception) {
            // Stored value was somehow malformed — treat as no selection
            // rather than crashing on launch.
            null
        } ?: return

        // Bundled gallery clips (android.resource:// URIs) are always
        // readable — they're baked into the APK, not a document the user
        // granted access to — so only user-picked files need the persisted
        // permission check.
        val stillGranted = isBundledResourceUri(uri) || contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            selectedVideoUri = uri
            showPreview(uri)
        }
    }

    private fun isBundledResourceUri(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE

    /**
     * Selects one of the built-in procedural loops (see res/raw) as an
     * alternative to picking a personal video. Skips takePersistableUriPermission
     * entirely — that call is only valid for document-provider URIs the user
     * granted access to, not for the app's own bundled resources.
     */
    private fun onBundledClipSelected(rawResId: Int) {
        val uri = Uri.parse("android.resource://$packageName/$rawResId")
        selectedVideoUri = uri
        prefs.edit().putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString()).apply()
        showPreview(uri)
    }

    private fun onVideoPicked(uri: Uri) {
        // Persist permission so the wallpaper service can still read this file
        // after the picker activity closes, and across device reboots.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Couldn't get permanent access to that file", Toast.LENGTH_LONG).show()
            return
        }

        selectedVideoUri = uri
        prefs.edit().putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString()).apply()
        showPreview(uri)
    }

    private fun showPreview(uri: Uri) {
        binding.noVideoText.visibility = View.GONE
        binding.previewVideo.visibility = View.VISIBLE
        binding.previewVideo.setVideoURI(uri)
        binding.previewVideo.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            previewPlayer = mp
            binding.previewVideo.start()
            applySpeedToPreview(currentSpeed)
        }

        binding.setWallpaperButton.isEnabled = true
    }

    private fun launchLiveWallpaperPicker() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, getString(R.string.select_video_first), Toast.LENGTH_SHORT).show()
            return
        }

        if (!prefs.getBoolean(PREF_BATTERY_TIP_SHOWN, false)) {
            showBatteryTipThenLaunch()
            return
        }

        launchLiveWallpaperPickerIntent()
    }

    /**
     * Shown exactly once, the first time anyone taps "Set as Wallpaper" —
     * live wallpapers are a well-known battery complaint, and heading it off
     * here costs one dialog instead of a 1-star review later.
     */
    private fun showBatteryTipThenLaunch() {
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_tip_title)
            .setMessage(R.string.battery_tip_message)
            .setCancelable(false)
            .setPositiveButton(R.string.battery_tip_continue) { _, _ ->
                prefs.edit().putBoolean(PREF_BATTERY_TIP_SHOWN, true).apply()
                launchLiveWallpaperPickerIntent()
            }
            .show()
    }

    private fun launchLiveWallpaperPickerIntent() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)
        )

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.error_live_wallpaper_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        selectedVideoUri?.let {
            if (!binding.previewVideo.isPlaying) {
                binding.previewVideo.start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.previewVideo.isPlaying) {
            binding.previewVideo.pause()
        }
        previewPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
    }

    companion object {
        private const val PREF_BATTERY_TIP_SHOWN = "battery_tip_shown"
    }
}