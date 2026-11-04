package com.example.aikeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AIKeyboardService : InputMethodService() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: Button
    private lateinit var keysContainer: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentLayout = "AR" // "AR", "TR", "EN", "SYM"
    private var lastAlphaLayout = "AR"
    private var isShifted = false

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "")?.trim() ?: ""
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnMic = view.findViewById(R.id.btnMic)
        keysContainer = view.findViewById(R.id.keyboardKeysLayout)

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

        renderKeyboardLayout()

        return view
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun renderKeyboardLayout() {
        keysContainer.removeAllViews()

        val rows: List<List<String>> = when (currentLayout) {
            "AR" -> listOf(
                listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
                listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
                listOf("DEL", "ئ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ"),
                listOf("123", "🌐 TR", "SPACE", "،", "↵")
            )
            "TR" -> {
                val trLocale = Locale("tr", "TR")
                val r1 = listOf("q", "w", "e", "r", "t", "y", "u", "ı", "o", "p", "ğ", "ü")
                val r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ş", "i")
                val r3 = listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "ö", "ç", "DEL")
                val r4 = listOf("123", "🌐 EN", "SPACE", ".", "↵")

                if (isShifted) {
                    listOf(
                        r1.map { it.uppercase(trLocale) },
                        r2.map { it.uppercase(trLocale) },
                        listOf("⬆") + r3.subList(1, r3.size - 1).map { it.uppercase(trLocale) } + listOf("DEL"),
                        r4
                    )
                } else {
                    listOf(r1, r2, r3, r4)
                }
            }
            "EN" -> {
                val r1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
                val r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
                val r3 = listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "DEL")
                val r4 = listOf("123", "🌐 AR", "SPACE", ".", "↵")

                if (isShifted) {
                    listOf(
                        r1.map { it.uppercase() },
                        r2.map { it.uppercase() },
                        listOf("⬆") + r3.subList(1, r3.size - 1).map { it.uppercase() } + listOf("DEL"),
                        r4
                    )
                } else {
                    listOf(r1, r2, r3, r4)
                }
            }
            else -> listOf( // "SYM"
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
                listOf("*", "\"", "'", ":", ";", "!", "?", "DEL"),
                listOf("ABC", "🌐 AR", "SPACE", ".", "↵")
            )
        }

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(38)
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (key in row) {
                val weight = when (key) {
                    "SPACE" -> 4.0f
                    "DEL", "↵" -> 1.5f
                    "123", "ABC", "🌐 AR", "🌐 TR", "🌐 EN" -> 1.4f
                    "⇧", "⬆" -> 1.2f
                    else -> 1.0f
                }

                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                        setMargins(dpToPx(1), dpToPx(2), dpToPx(1), dpToPx(2))
                    }
                    text = when (key) {
                        "SPACE" -> "مسافة"
                        "DEL" -> "⌫"
                        else -> key
                    }
                    textSize = when (key) {
                        "SPACE", "123", "ABC", "🌐 AR", "🌐 TR", "🌐 EN" -> 10.5f
                        "DEL", "↵", "⇧", "⬆" -> 13f
                        else -> if (currentLayout == "TR") 13.5f else 15f
                    }
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false

                    val bg = GradientDrawable().apply {
                        cornerRadius = dpToPx(6).toFloat()
                        setColor(
                            when (key) {
                                "SPACE" -> Color.parseColor("#313244")
                                "DEL", "↵" -> Color.parseColor("#45475A")
                                "123", "ABC", "🌐 AR", "🌐 TR", "🌐 EN", "⇧", "⬆" -> Color.parseColor("#36384C")
                                else -> Color.parseColor("#24253A")
                            }
                        )
                    }
                    background = bg
                    setTextColor(
                        when (key) {
                            "↵" -> Color.parseColor("#A6E3A1")
                            "DEL" -> Color.parseColor("#F38BA8")
                            else -> Color.WHITE
                        }
                    )

                    setOnClickListener { handleKeyPress(key) }
                }
                rowLayout.addView(btn)
            }
            keysContainer.addView(rowLayout)
        }
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "DEL" -> {
                ic.deleteSurroundingText(1, 0)
            }
            "SPACE" -> {
                ic.commitText(" ", 1)
            }
            "↵" -> {
                ic.commitText("\n", 1)
            }
            "123" -> {
                currentLayout = "SYM"
                renderKeyboardLayout()
            }
            "ABC" -> {
                currentLayout = lastAlphaLayout
                renderKeyboardLayout()
            }
            "🌐 TR" -> {
                currentLayout = "TR"
                lastAlphaLayout = "TR"
                renderKeyboardLayout()
            }
            "🌐 EN" -> {
                currentLayout = "EN"
                lastAlphaLayout = "EN"
                renderKeyboardLayout()
            }
            "🌐 AR" -> {
                currentLayout = "AR"
                lastAlphaLayout = "AR"
                renderKeyboardLayout()
            }
            "⇧" -> {
                isShifted = true
                renderKeyboardLayout()
            }
            "⬆" -> {
                isShifted = false
                renderKeyboardLayout()
            }
            else -> {
                ic.commitText(key, 1)
                if (isShifted) {
                    isShifted = false
                    renderKeyboardLayout()
                }
            }
        }
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
            tvStatus.text = "🔴 تحدث الآن.. ثم اضغط (🇸🇦 / 🇹🇷 / 🇬🇧)"
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
                // إرسال الطلب مع المفتاح في الرابط وفي الترويسة معاً لضمان القبول الكامل
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
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
                    val errorDetail = try {
                        JSONObject(responseStr).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "كود ${conn.responseCode}"
                    }
                    mainHandler.post {
                        tvStatus.text = "❌ $errorDetail"
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
