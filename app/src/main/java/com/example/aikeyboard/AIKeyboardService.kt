package com.example.aikeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AIKeyboardService : InputMethodService() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: Button
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "")?.trim() ?: ""
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnMic = view.findViewById(R.id.btnMic)

        btnMic.setOnClickListener {
            if (!isRecording) startRecording() else stopRecordingAndSend("العربية")
        }

        view.findViewById<Button>(R.id.btnVoiceAr).setOnClickListener { handleLanguageAction("العربية") }
        view.findViewById<Button>(R.id.btnVoiceTr).setOnClickListener { handleLanguageAction("التركية (Türkçe)") }
        view.findViewById<Button>(R.id.btnVoiceEn).setOnClickListener { handleLanguageAction("الإنكليزية (English)") }

        view.findViewById<Button>(R.id.btnRewriteFormal).setOnClickListener {
            val currentText = getCurrentFieldText()
            if (currentText.isBlank()) {
                tvStatus.text = "⚠️ اكتب جملة أولاً لتصحيحها!"
                return@setOnClickListener
            }
            tvStatus.text = "⏳ جاري تحسين الصياغة..."
            val prompt = "أعد صياغة النص التالي ليكون بأسلوب رسمي، متقن، جميل، وخالٍ من الأخطاء الإملائية مع الحفاظ على نفس لغة النص. أرجع النص المعدل فقط بدون أي شرح أو علامات اقتباس:\n$currentText"
            callGeminiText(prompt)
        }

        view.findViewById<Button>(R.id.btnSwitchKeyboard).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        view.findViewById<Button>(R.id.btnClear).setOnClickListener {
            replaceAllText("")
            tvStatus.text = "تم مسح النص"
        }

        return view
    }

    private fun handleLanguageAction(targetLang: String) {
        if (isRecording) {
            stopRecordingAndSend(targetLang)
        } else {
            val currentText = getCurrentFieldText()
            if (currentText.isBlank()) {
                tvStatus.text = "⚠️ اضغط (🎤 تحدث) أولاً أو اكتب نصاً لترجمته!"
                return
            }
            tvStatus.text = "⏳ جاري الترجمة والصياغة إلى $targetLang..."
            val prompt = "ترجم النص التالي إلى اللغة $targetLang بأسلوب رسمي وسليم وطبيعي جداً، وأرجع النص المترجم فقط بدون أي إضافات:\n$currentText"
            callGeminiText(prompt)
        }
    }

    private fun startRecording() {
        try {
            audioFile = File(cacheDir, "voice_input.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            btnMic.text = "⏹️ يتحدث..."
            tvStatus.text = "🔴 تحدث بأي لغة.. ثم اضغط (عربي / تركي / EN)"
        } catch (e: Exception) {
            tvStatus.text = "❌ تأكد من تفعيل إذن المايكروفون للتطبيق"
        }
    }

    private fun stopRecordingAndSend(targetLang: String) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            btnMic.text = "🎤 تحدث"
            tvStatus.text = "⏳ جاري تحويل صوتك إلى $targetLang..."

            val bytes = audioFile?.readBytes() ?: return
            val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val prompt = "استمع لهذا التسجيل الصوتي بأي لغة كان، وقم بكتابته وترجمته إلى اللغة $targetLang بأسلوب رسمي، متقن، وجميل. أرجع النص النهائي فقط بدون أي مقدمات أو شرح."
            callGeminiAudio(prompt, base64Audio)
        } catch (e: Exception) {
            isRecording = false
            btnMic.text = "🎤 تحدث"
            tvStatus.text = "❌ حدث خطأ أثناء التسجيل"
        }
    }

    private fun getCurrentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(2000, 0) ?: ""
        val after = ic.getTextAfterCursor(2000, 0) ?: ""
        return "$before$after"
    }

    private fun replaceAllText(newText: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0) ?: ""
        val after = ic.getTextAfterCursor(2000, 0) ?: ""
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(newText.trim(), 1)
    }

    private fun callGeminiText(prompt: String) {
        val part = JSONObject().put("text", prompt)
        val parts = JSONArray().put(part)
        sendGeminiRequest(parts)
    }

    private fun callGeminiAudio(prompt: String, base64Audio: String) {
        val textPart = JSONObject().put("text", prompt)
        val inlineData = JSONObject()
            .put("mime_type", "audio/mp4")
            .put("data", base64Audio)
        val audioPart = JSONObject().put("inline_data", inlineData)

        val parts = JSONArray().put(textPart).put(audioPart)
        sendGeminiRequest(parts)
    }

    private fun sendGeminiRequest(parts: JSONArray) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            tvStatus.text = "⚠️ افتح تطبيق AI Keyboard وألصق مفتاح API أولاً!"
            return
        }
        Thread {
            try {
                // إرسال الطلب مع تمرير المفتاح داخل ترويسة x-goog-api-key لدعم مفاتيح AQ الجديدة
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.doOutput = true

                val content = JSONObject().put("parts", parts)
                val payload = JSONObject().put("contents", JSONArray().put(content))

                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseStr = stream.bufferedReader().use { it.readText() }

                if (conn.responseCode in 200..299) {
                    val jsonResponse = JSONObject(responseStr)
                    val resultText = jsonResponse
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    mainHandler.post {
                        replaceAllText(resultText)
                        tvStatus.text = "✅ تم التحديث بنجاح!"
                    }
                } else {
                    mainHandler.post {
                        tvStatus.text = "❌ خطأ من الخادم (${conn.responseCode})"
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = "❌ خطأ في الاتصال بالإنترنت"
                }
            }
        }.start()
    }
}
