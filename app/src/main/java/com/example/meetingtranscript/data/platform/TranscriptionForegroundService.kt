package com.example.meetingtranscript.data.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.example.meetingtranscript.MainActivity
import com.example.meetingtranscript.R
import com.example.meetingtranscript.domain.TranscriptSegment
import com.example.meetingtranscript.domain.TranscriptSession
import com.example.meetingtranscript.domain.TranscriptDestination
import com.example.meetingtranscript.domain.SocketState
import java.time.Instant
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Owns the visible foreground recording lifecycle and restarts bounded recognizer requests. */
class TranscriptionForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var repository: SharedPreferencesTranscriptRepository
    private lateinit var audioInput: SystemAudioCaptureService
    private var sessionId: String? = null
    private var listening = false
    private var speechStartedAt: Instant? = null
    private var serverUrl: String? = null
    private var meetingRoomId: String? = null
    private var roomDisplayName: String? = null
    private var userDisplayName: String? = null
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private lateinit var outbox: TranscriptOutbox
    private var remoteTarget: TranscriptDestination? = null
    private val remote by lazy { AccountServices.get(applicationContext).discussion }

    override fun onCreate() {
        super.onCreate()
        repository = SharedPreferencesTranscriptRepository(this)
        outbox = TranscriptOutbox(this)
        audioInput = SystemAudioCaptureService(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = when (intent?.action) {
        ACTION_STOP -> { stopTranscription(); START_NOT_STICKY }
        else -> {
            if (!listening) {
                serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
                meetingRoomId = intent?.getStringExtra("meeting_room_id")
                roomDisplayName = intent?.getStringExtra("room_display_name")
                userDisplayName = intent?.getStringExtra("user_display_name")
                val remoteServer = intent?.getStringExtra("remote_server_id")
                val remoteUser = intent?.getStringExtra("remote_user_id")
                remoteTarget = if (remoteServer != null && remoteUser != null && meetingRoomId != null && serverUrl != null)
                    TranscriptDestination(remoteServer, remoteUser, meetingRoomId!!, serverUrl!!) else null
                val target = remoteTarget
                if (target != null && (remote.state.value.target != target || remote.state.value.blocked || remote.state.value.connection !is SocketState.Joined)) {
                    publish(EVENT_ERROR, message = "請先等候討論室連線成功，再開始收音。")
                    stopSelf()
                } else if (meetingRoomId != null) startTranscription() else stopSelf()
            }
            START_NOT_STICKY
        }
    }

    private fun startTranscription() {
        if (listening) return
        startVisibleService()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            publish(EVENT_ERROR, message = "此裝置沒有可用的系統語音辨識服務。")
            stopTranscription()
            return
        }
        sessionId = UUID.randomUUID().toString()
        repository.create(TranscriptSession(id = sessionId!!, deviceId = installationId(), startedAt = Instant.now(), meetingRoomId = meetingRoomId, roomDisplayName = roomDisplayName, userDisplayName = userDisplayName, serverUrl = serverUrl))
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        listening = true
        remoteTarget?.let {
            if (runCatching { remote.recordingStarted(it, audioInput.currentInputName(), ::stopTranscription) }.isFailure) {
                publish(EVENT_ERROR, message = "討論室狀態已改變，請更新狀態後再開始。")
                stopTranscription()
                return
            }
        }
        publish(EVENT_STARTED, device = audioInput.currentInputName())
        beginRecognition()
    }

    private fun beginRecognition() {
        if (!listening) return
        speechStartedAt = Instant.now()
        runCatching { recognizer.startListening(recognizerIntent()) }
            .onFailure { publish(EVENT_ERROR, message = "無法啟動語音辨識。") ; restartRecognition() }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Android 13+ recognizers may keep one listening session open and deliver final
        // segments. This avoids reopening the recognizer (and its system cue) after every pause.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SEGMENT_SILENCE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                arrayListOf("zh-TW", "en-US"),
            )
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!listening) return
            partialResults?.bestText()?.let {
                if (remoteTarget != null) remote.partial(it)
                publish(EVENT_PARTIAL, text = it)
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            persistFinalResult(results)
            // This callback is used by recognizers that do not support segmented sessions.
            restartRecognition()
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            persistFinalResult(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            // A supported segmented session normally ends only when the service ends it.
            // Restart once here rather than after every recognised segment.
            restartRecognition()
        }

        override fun onError(error: Int) {
            if (!listening) return
            // Silence/no-match is an expected segment boundary. Other errors are surfaced without transcript content.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                publish(EVENT_ERROR, message = "語音辨識暫時中斷，正在重新連線。")
            }
            restartRecognition()
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun persistFinalResult(results: android.os.Bundle?) {
        val text = results?.bestText()?.trim().orEmpty()
        if (!listening || text.isEmpty() || sessionId == null) return
        val alternatives = results?.stringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f }
        val endedAt = Instant.now()
        val segment = TranscriptSegment(
            text = text,
            alternatives = alternatives.ifEmpty { listOf(text) },
            language = "zh-TW",
            startedAt = speechStartedAt ?: endedAt,
            endedAt = endedAt,
            confidence = confidence,
        )
        repository.appendSegment(sessionId!!, segment)
        val target = remoteTarget
        if (target != null) {
            runCatching { remote.capture(target, segment) }.onFailure {
                remote.reportMessage("待送資料保存失敗，原文仍保留於本機逐字稿。")
                publish(EVENT_ERROR, message = "待送資料保存失敗，原文仍保留於本機逐字稿。")
            }
        } else uploadFinalSegment(sessionId!!, segment)
        publish(EVENT_FINAL, text = text)
        speechStartedAt = endedAt
    }

    private fun restartRecognition() {
        if (listening) mainHandler.postDelayed(::beginRecognition, RESTART_DELAY_MS)
    }

    private fun uploadFinalSegment(transcriptId: String, segment: TranscriptSegment) {
        val baseUrl = serverUrl?.trim()?.removeSuffix("/") ?: return
                val payload = JSONObject().apply {
                    put("clientTranscriptId", transcriptId); put("locale", "zh-TW")
                    put("meetingRoomId", meetingRoomId); put("roomDisplayName", roomDisplayName); put("serverUrl", baseUrl)
                    put("userDisplayName", userDisplayName)
                    put("startedAt", segment.startedAt.toString()); put("endedAt", segment.endedAt.toString())
                    put("segments", JSONArray().put(JSONObject().apply {
                        put("segmentId", segment.id); put("text", segment.text); put("isFinal", true)
                    }))
                }.toString()
        runCatching { outbox.enqueue(segment.id, baseUrl, payload) }.onFailure {
            publish(EVENT_ERROR, message = "待上傳資料儲存失敗，逐字稿仍保留於本機。")
        }
        uploadExecutor.execute {
            if (outbox.flush() > 0) publish(EVENT_ERROR, message = "部分逐字稿等待上傳，請檢查網路與伺服器。")
        }
    }

    private fun stopTranscription() {
        if (!listening) { stopSelf(); return }
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { recognizer.cancel(); recognizer.destroy() }
        sessionId?.let { repository.finish(it, Instant.now()) }
        if (remoteTarget != null) remote.recordingStopped()
        publish(EVENT_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startVisibleService() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun publish(type: String, text: String? = null, device: String? = null, message: String? = null) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).apply {
            putExtra(EXTRA_TYPE, type); putExtra(EXTRA_TEXT, text); putExtra(EXTRA_DEVICE, device); putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun installationId(): String {
        val preferences = getSharedPreferences("app", MODE_PRIVATE)
        return preferences.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("installation_id", it).apply()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { if (listening) stopTranscription(); uploadExecutor.shutdown(); super.onDestroy() }

    private fun Bundle.bestText() = stringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    private fun android.os.Bundle.stringArrayList(key: String) = getStringArrayList(key)

    companion object {
        const val ACTION_START = "com.example.meetingtranscript.START"
        const val ACTION_STOP = "com.example.meetingtranscript.STOP"
        const val ACTION_EVENT = "com.example.meetingtranscript.TRANSCRIPTION_EVENT"
        const val EXTRA_TYPE = "type"; const val EXTRA_TEXT = "text"; const val EXTRA_DEVICE = "device"; const val EXTRA_MESSAGE = "message"
        const val EXTRA_SERVER_URL = "server_url"
        const val EVENT_STARTED = "started"; const val EVENT_PARTIAL = "partial"; const val EVENT_FINAL = "final"; const val EVENT_STOPPED = "stopped"; const val EVENT_ERROR = "error"
        private const val CHANNEL_ID = "active_transcription"; private const val NOTIFICATION_ID = 1001
        private const val RESTART_DELAY_MS = 700L; private const val SEGMENT_SILENCE_MS = 1_500L
    }
}
