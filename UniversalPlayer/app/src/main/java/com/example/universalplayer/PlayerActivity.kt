package com.example.universalplayer

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.universalplayer.databinding.ActivityPlayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var uri: Uri
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null

    // состояние
    private var length = 0L
    private var resumeMs = 0L
    private var userSeeking = false
    private var locked = false
    private var controlsVisible = true
    private var ended = false
    private var forceSoftware = false

    // настройки текущего сеанса
    private var speed = 1.0f
    private var repeatOne = false
    private var aspectIndex = 0
    private var audioDelayMs = 0L
    private var spuDelayMs = 0L
    private var volumeBoost = 100
    private var sleepRunnable: Runnable? = null

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f, 4.0f)

    private val aspectNames = arrayOf(
        "По умолчанию (вписать)",
        "Подогнать под экран",
        "Растянуть на весь экран",
        "16:9",
        "4:3",
        "Оригинальный размер"
    )
    private val aspectTypes = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3,
        MediaPlayer.ScaleType.SURFACE_ORIGINAL
    )

    private val orientations = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    )
    private val orientationNames = arrayOf("Горизонтально", "Вертикально", "Авто-поворот")
    private var orientIndex = 0

    // выбор файла субтитров
    private val subPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null) loadSubtitle(u)
        }

    private val hideRunnable = Runnable { hideControls() }
    private val hideInfoRunnable = Runnable { b.infoText.visibility = View.GONE }
    private val hideUnlockRunnable = Runnable { b.btnUnlock.visibility = View.GONE }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        uri = data

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Utils.prefs(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupWindow()
        requestedOrientation = orientations[orientIndex]

        b.title.text = Utils.displayName(this, uri)
        Utils.addHistory(this, uri)
        resumeMs = prefs.getLong(posKey(), 0L)

        setupUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        val lib = LibVLC(this, arrayListOf("-vvv"))
        libVLC = lib
        val p = MediaPlayer(lib)
        p.setEventListener(MediaPlayer.EventListener { ev -> onPlayerEvent(ev) })
        player = p
    }

    override fun onStart() {
        super.onStart()
        val p = player ?: return
        p.attachViews(b.videoLayout, null, true, false)
        startPlayback(resumeMs)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        saveProgress()
        p.stop()
        p.detachViews()
        closePfd()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        libVLC?.release()
        player = null
        libVLC = null
        closePfd()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (locked) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlay(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(10_000); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---------------------------------------------------------------- окно

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.controls) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, b.root)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- UI

    private fun setupUi() {
        b.btnBack.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener { togglePlay(); scheduleHide() }
        b.btnRew.setOnClickListener { seekBy(-10_000); scheduleHide() }
        b.btnFwd.setOnClickListener { seekBy(10_000); scheduleHide() }
        b.btnSpeed.setOnClickListener { showSpeedDialog() }
        b.btnAudio.setOnClickListener { showAudioTracks() }
        b.btnSubs.setOnClickListener { showSubtitleTracks() }
        b.btnAspect.setOnClickListener { showAspectDialog() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnLock.setOnClickListener { setLocked(true) }
        b.btnUnlock.setOnClickListener { setLocked(false) }
        b.btnRotate.setOnClickListener {
            orientIndex = (orientIndex + 1) % orientations.size
            requestedOrientation = orientations[orientIndex]
            showInfo(orientationNames[orientIndex])
            scheduleHide()
        }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvTime.text = Utils.formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                userSeeking = false
                player?.setTime(s.progress.toLong())
                scheduleHide()
            }
        })

        b.touchLayer.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (gesture == Gesture.SEEK && action == MotionEvent.ACTION_UP) {
                    player?.setTime(gSeekTarget)
                }
                gesture = Gesture.NONE
            }
            true
        }
    }

    private fun showControls() {
        if (locked) return
        b.controls.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHide()
    }

    private fun hideControls() {
        b.controls.visibility = View.GONE
        controlsVisible = false
        hideSystemBars()
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player?.isPlaying == true && !userSeeking && controlsVisible) {
            handler.postDelayed(hideRunnable, 4000)
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        handler.removeCallbacks(hideUnlockRunnable)
        if (value) {
            hideControls()
            showUnlockTemporarily()
        } else {
            b.btnUnlock.visibility = View.GONE
            showControls()
        }
    }

    private fun showUnlockTemporarily() {
        b.btnUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 3000)
    }

    private fun showInfo(text: String) {
        b.infoText.text = text
        b.infoText.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 900)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun updatePlayIcon(playing: Boolean) {
        b.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateSpeedLabel() {
        b.btnSpeed.text = "${speed}x"
    }

    // ---------------------------------------------------------------- жесты

    private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME }

    private var gesture = Gesture.NONE
    private var gStartTime = 0L
    private var gSeekTarget = 0L
    private var gStartBrightness = 0.5f
    private var gStartVolume = 0
    private var gMaxVolume = 1

    private val detector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                gesture = Gesture.NONE
                gStartTime = (player?.time ?: 0L).coerceAtLeast(0L)
                gSeekTarget = gStartTime
                gStartBrightness = currentBrightness()
                gMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                gStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showUnlockTemporarily() else toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = b.root.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2f / 3f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (locked || e1 == null) return false
                val w = b.root.width.toFloat()
                val h = b.root.height.toFloat()
                if (w <= 0f || h <= 0f) return false

                if (gesture == Gesture.NONE) {
                    gesture = if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) Gesture.SEEK
                    else if (e1.x < w / 2f) Gesture.BRIGHTNESS
                    else Gesture.VOLUME
                }

                when (gesture) {
                    Gesture.SEEK -> {
                        if (length <= 0L) return true
                        val delta = ((e2.x - e1.x) / w * 120_000f).toLong()
                        gSeekTarget = (gStartTime + delta).coerceIn(0L, length)
                        val sign = if (delta >= 0) "+" else "−"
                        showInfo(
                            "${Utils.formatTime(gSeekTarget)}  ($sign${abs(delta) / 1000}с)"
                        )
                    }
                    Gesture.BRIGHTNESS -> {
                        val v = (gStartBrightness + (e1.y - e2.y) / h * 1.2f).coerceIn(0.01f, 1f)
                        setBrightness(v)
                        showInfo("☀ ${(v * 100).roundToInt()}%")
                    }
                    Gesture.VOLUME -> {
                        val v = (gStartVolume + (e1.y - e2.y) / h * gMaxVolume * 1.2f)
                            .roundToInt().coerceIn(0, gMaxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showInfo("🔊 ${v * 100 / gMaxVolume}%")
                    }
                    Gesture.NONE -> {}
                }
                return true
            }
        })
    }

    private fun currentBrightness(): Float {
        val cur = window.attributes.screenBrightness
        if (cur >= 0f) return cur
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    // ---------------------------------------------------------------- воспроизведение

    private fun posKey() = "pos_" + uri.toString().hashCode()

    private fun closePfd() {
        try {
            pfd?.close()
        } catch (e: Exception) {
            // ignore
        }
        pfd = null
    }

    private fun startPlayback(startMs: Long) {
        val p = player ?: return
        val lib = libVLC ?: return
        try {
            closePfd()
            val media: Media = if (uri.scheme == "content") {
                val d = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Не удалось открыть файл")
                pfd = d
                Media(lib, d.fileDescriptor)
            } else {
                Media(lib, uri)
            }
            val hw = prefs.getBoolean("hw", true) && !forceSoftware
            media.setHWDecoderEnabled(hw, false)
            media.addOption(":network-caching=1500")
            if (startMs > 0L) media.addOption(":start-time=${startMs / 1000}")
            p.setMedia(media)
            media.release()
            ended = false
            p.play()
        } catch (e: Exception) {
            toast("Не удалось открыть: ${e.message}")
            finish()
        }
    }

    private fun applyPlayerSettings() {
        val p = player ?: return
        p.setRate(speed)
        p.setVolume(volumeBoost)
        p.setAudioDelay(audioDelayMs * 1000L)
        p.setSpuDelay(spuDelayMs * 1000L)
        p.setVideoScale(aspectTypes[aspectIndex])
        updateSpeedLabel()
    }

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Opening -> b.loading.visibility = View.VISIBLE
            MediaPlayer.Event.Buffering ->
                b.loading.visibility = if (ev.buffering < 100f) View.VISIBLE else View.GONE
            MediaPlayer.Event.Playing -> {
                ended = false
                b.loading.visibility = View.GONE
                updatePlayIcon(true)
                applyPlayerSettings()
                scheduleHide()
            }
            MediaPlayer.Event.Paused -> {
                updatePlayIcon(false)
                handler.removeCallbacks(hideRunnable)
            }
            MediaPlayer.Event.Stopped -> updatePlayIcon(false)
            MediaPlayer.Event.LengthChanged -> setLength(ev.lengthChanged)
            MediaPlayer.Event.TimeChanged -> {
                val l = player?.length ?: 0L
                if (l != length) setLength(l)
                if (!userSeeking) updateProgress(ev.timeChanged)
            }
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> onPlayError()
        }
    }

    private fun setLength(l: Long) {
        length = l.coerceAtLeast(0L)
        b.seekBar.max = length.toInt()
        b.seekBar.isEnabled = length > 0L
        b.tvDuration.text = Utils.formatTime(length)
    }

    private fun updateProgress(t: Long) {
        val v = t.coerceAtLeast(0L)
        b.seekBar.progress = v.toInt()
        b.tvTime.text = Utils.formatTime(v)
    }

    private fun onEnded() {
        ended = true
        if (repeatOne) {
            handler.post {
                player?.stop()
                player?.play()
            }
        } else {
            updatePlayIcon(false)
            if (!locked) {
                b.controls.visibility = View.VISIBLE
                controlsVisible = true
                handler.removeCallbacks(hideRunnable)
            }
        }
    }

    private fun onPlayError() {
        val hwOn = prefs.getBoolean("hw", true)
        if (hwOn && !forceSoftware) {
            // пробуем ещё раз, но с программным декодированием
            forceSoftware = true
            toast("Переключаюсь на программное декодирование…")
            val t = (player?.time ?: 0L).coerceAtLeast(0L)
            handler.post {
                player?.stop()
                startPlayback(t)
            }
        } else {
            b.loading.visibility = View.GONE
            toast("Не удалось воспроизвести этот файл")
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (ended) p.stop()
            p.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val maxT = if (length > 0L) length else Long.MAX_VALUE
        val t = (p.time + deltaMs).coerceIn(0L, maxT)
        p.setTime(t)
        updateProgress(t)
        showInfo((if (deltaMs > 0) "▶▶ +" else "◀◀ −") + abs(deltaMs) / 1000 + "с")
    }

    private fun saveProgress() {
        val p = player ?: return
        val t = p.time
        val e = prefs.edit()
        if (length > 0L && t >= 3000L && t <= length - 5000L) {
            resumeMs = t
            e.putLong(posKey(), t)
        } else {
            resumeMs = 0L
            e.remove(posKey())
        }
        e.apply()
    }

    // ---------------------------------------------------------------- диалоги

    private fun present(builder: MaterialAlertDialogBuilder) {
        handler.removeCallbacks(hideRunnable)
        builder.setOnDismissListener {
            scheduleHide()
            hideSystemBars()
        }.show()
    }

    private fun showSpeedDialog() {
        val names = speeds.map { "${it}x" }.toTypedArray()
        val checked = speeds.indexOfFirst { it == speed }
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle("Скорость воспроизведения")
                .setSingleChoiceItems(names, checked) { d, which ->
                    speed = speeds[which]
                    player?.setRate(speed)
                    updateSpeedLabel()
                    d.dismiss()
                }
        )
    }

    private fun showTrackDialog(
        title: String,
        tracks: Array<MediaPlayer.TrackDescription>?,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        if (tracks == null || tracks.isEmpty()) {
            toast("Дорожек нет")
            return
        }
        val names = tracks.map { it.name ?: "Дорожка ${it.id}" }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == current }
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, checked) { d, which ->
                    onPick(tracks[which].id)
                    d.dismiss()
                }
        )
    }

    private fun showAudioTracks() {
        val p = player ?: return
        showTrackDialog("Звуковая дорожка", p.audioTracks, p.audioTrack) { id ->
            p.setAudioTrack(id)
        }
    }

    private fun showSubtitleTracks() {
        val p = player ?: return
        showTrackDialog("Субтитры", p.spuTracks, p.spuTrack) { id ->
            p.setSpuTrack(id)
        }
    }

    private fun showAspectDialog() {
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle("Соотношение сторон")
                .setSingleChoiceItems(aspectNames, aspectIndex) { d, which ->
                    aspectIndex = which
                    player?.setVideoScale(aspectTypes[which])
                    d.dismiss()
                }
        )
    }

    private fun showSliderDialog(
        title: String,
        min: Float,
        max: Float,
        step: Float,
        initial: Float,
        reset: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = format(initial)
        }
        val slider = Slider(this).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = initial.coerceIn(min, max)
            addOnChangeListener { _, v, _ ->
                label.text = format(v)
                onChange(v)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label)
            addView(slider)
        }
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", null)
                .setNeutralButton("Сброс") { _, _ -> onChange(reset) }
        )
    }

    private fun showSettings() {
        val hw = prefs.getBoolean("hw", true)
        val items = arrayOf(
            "Скорость: ${speed}x",
            "Звуковая дорожка",
            "Субтитры (выбор дорожки)",
            "Загрузить субтитры из файла…",
            "Соотношение сторон: ${aspectNames[aspectIndex]}",
            "Задержка звука: $audioDelayMs мс",
            "Задержка субтитров: $spuDelayMs мс",
            "Усиление громкости: $volumeBoost%",
            "Повтор: ${if (repeatOne) "одно видео" else "выкл."}",
            "Таймер сна",
            "Декодирование: ${if (hw) "аппаратное (HW)" else "программное (SW)"}"
        )
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle("Настройки")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showSpeedDialog()
                        1 -> showAudioTracks()
                        2 -> showSubtitleTracks()
                        3 -> subPicker.launch(arrayOf("*/*"))
                        4 -> showAspectDialog()
                        5 -> showSliderDialog(
                            "Задержка звука", -5000f, 5000f, 50f,
                            audioDelayMs.toFloat(), 0f,
                            { v -> "${v.toInt()} мс" }
                        ) { v ->
                            audioDelayMs = v.toLong()
                            player?.setAudioDelay(audioDelayMs * 1000L)
                        }
                        6 -> showSliderDialog(
                            "Задержка субтитров", -10000f, 10000f, 50f,
                            spuDelayMs.toFloat(), 0f,
                            { v -> "${v.toInt()} мс" }
                        ) { v ->
                            spuDelayMs = v.toLong()
                            player?.setSpuDelay(spuDelayMs * 1000L)
                        }
                        7 -> showSliderDialog(
                            "Усиление громкости", 100f, 200f, 5f,
                            volumeBoost.toFloat(), 100f,
                            { v -> "${v.toInt()}%" }
                        ) { v ->
                            volumeBoost = v.toInt()
                            player?.setVolume(volumeBoost)
                        }
                        8 -> {
                            repeatOne = !repeatOne
                            toast(if (repeatOne) "Повтор видео включён" else "Повтор выключен")
                        }
                        9 -> showSleepTimer()
                        10 -> toggleHardware()
                    }
                }
        )
    }

    private fun showSleepTimer() {
        val names = arrayOf("Выключен", "15 минут", "30 минут", "45 минут", "60 минут", "90 минут")
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        present(
            MaterialAlertDialogBuilder(this)
                .setTitle("Таймер сна")
                .setItems(names) { _, which ->
                    sleepRunnable?.let { handler.removeCallbacks(it) }
                    sleepRunnable = null
                    val m = minutes[which]
                    if (m > 0) {
                        val r = Runnable {
                            player?.pause()
                            toast("Таймер сна: пауза")
                        }
                        sleepRunnable = r
                        handler.postDelayed(r, m * 60_000L)
                        toast("Пауза через $m мин")
                    } else {
                        toast("Таймер выключен")
                    }
                }
        )
    }

    private fun toggleHardware() {
        val now = !prefs.getBoolean("hw", true)
        prefs.edit().putBoolean("hw", now).apply()
        forceSoftware = false
        toast(if (now) "Аппаратное декодирование" else "Программное декодирование")
        val p = player ?: return
        val t = p.time.coerceAtLeast(0L)
        p.stop()
        startPlayback(t)
    }

    private fun loadSubtitle(u: Uri) {
        Thread {
            try {
                val name = Utils.displayName(this, u)
                val ext = name.substringAfterLast('.', "srt")
                val f = File(cacheDir, "sub_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(u)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
                runOnUiThread {
                    val ok = player?.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
                    toast(if (ok == true) "Субтитры загружены" else "Не удалось подключить субтитры")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось прочитать файл субтитров") }
            }
        }.start()
    }
}
