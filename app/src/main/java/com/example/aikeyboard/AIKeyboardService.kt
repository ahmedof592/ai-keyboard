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
import android.view.MotionEvent
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

    private enum class VoiceMode { NONE, REFINE_ONLY, TRANSLATE_TR, TRANSLATE_EN }

    private var currentVoiceMode = VoiceMode.NONE
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    private lateinit var tvStatus: TextView
    private lateinit var btnMicRefine: Button
    private lateinit var btnMicTr: Button
    private lateinit var btnMicEn: Button
    private lateinit var keysContainer: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deleteHandler = Handler(Looper.getMainLooper())

    private var currentLayout = "AR" // "AR", "TR", "EN", "SYM"
    private var lastAlphaLayout = "AR"
    private var isShifted = false
    private var workingModel: String? = null

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "")?.trim() ?: ""
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnMicRefine = view.findViewById(R.id.btnMicRefine)
        btnMicTr = view.findViewById(R.id.btnMicTr)
        btnMicEn = view.findViewById(R.id.btnMicEn)
        keysContainer = view.findViewById(R.id.keyboardKeysLayout)

        workingModel = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
            .getString("cached_model", "models/gemini-2.0-flash")

        // 1. زر مود الصياغة الصوتية المباشرة (نفس اللغة)
        btnMicRefine.setOnClickListener {
            handleVoiceModeClick(VoiceMode.REFINE_ONLY, btnMicRefine, "صياغة وتصحيح ✨")
        }

        // 2. زر مود الترجمة الصوتية إلى التركية
        btnMicTr.setOnClickListener {
            handleVoiceModeClick(VoiceMode.TRANSLATE_TR, btnMicTr, "ترجمة ➔ 🇹🇷 تركي")
        }

        // 3. زر مود الترجمة الصوتية إلى الإنكليزية
        btnMicEn.setOnClickListener {
            handleVoiceModeClick(VoiceMode.TRANSLATE_EN, btnMicEn, "ترجمة ➔ 🇬🇧 EN")
        }

        // 4. زر مود تصحيح النص المكتوب يدوياً بالحروف
        view.findViewById<Button>(R.id.btnRewriteText).setOnClickListener {
            if (isRecording) stopRecordingAndExecute()
            val currentText = getCurrentFieldText()
            if (currentText.isBlank()) {
                tvStatus.text = "⚠️ اكتب جملة أولاً لتصحيحها بالحروف!"
                return@setOnClickListener
            }
            tvStatus.text = "⏳ [مود تصحيح المكتوب] جاري تحسين الصياغة..."
            val prompt = "أعد صياغة النص التالي ليكون بأسلوب رسمي، فصيح، متقن، وخالٍ من الأخطاء الإملائية مع الحفاظ على نفس لغة النص تماماً بدون ترجمة. أرجع النص المصاغ فقط بدون أي إضافات:\n$currentText"
            callGeminiText(prompt)
        }

        // 5. زر تبديل الكيبورد
        view.findViewById<Button>(R.id.btnSwitchKeyboard).setOnClickListener {
            if (isRecording) stopRecordingAndExecute()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        renderKeyboardLayout()
        return view
    }

    private fun handleVoiceModeClick(mode: VoiceMode, button: Button, modeTitle: String) {
        if (isRecording) {
            val wasSameMode = (currentVoiceMode == mode)
            stopRecordingAndExecute()
            if (wasSameMode) return
        }

        currentVoiceMode = mode
        resetVoiceButtonTexts()
        button.text = "⏹️ إنهاء"

        tvStatus.text = "🔴 [مود $modeTitle] تحدث الآن.. اضغط الزر مجدداً للإنهاء"
        startAudioCapture()
    }

    private fun resetVoiceButtonTexts() {
        btnMicRefine.text = "🎤✨ صياغة"
        btnMicTr.text = "🎤🇹🇷 تركي"
        btnMicEn.text = "🎤🇬🇧 EN"
    }

    private fun startAudioCapture() {
        try {
            audioFile = File(cacheDir, "voice_record.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
        } catch (e: Exception) {
            isRecording = false
            resetVoiceButtonTexts()
            tvStatus.text = "❌ تأكد من منح إذن المايكروفون للتطبيق"
        }
    }

    private fun stopRecordingAndExecute() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) { }

        isRecording = false
        val executedMode = currentVoiceMode
        currentVoiceMode = VoiceMode.NONE
        resetVoiceButtonTexts()

        val bytes = audioFile?.readBytes() ?: run {
            tvStatus.text = "⚠️ لم يتم التقاط صوت كافٍ"
            return
        }
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)

        when (executedMode) {
            VoiceMode.REFINE_ONLY -> {
                tvStatus.text = "⏳ [مود الصياغة] جاري تحويل صوتك لنص رسمي بنفس اللغة..."
                val prompt = "استمع لهذا التسجيل الصوتي واكتب محتواه بدقة، وصغه بأسلوب رسمي، فصيح، وجميل مع الحفاظ التام على نفس لغة المتحدث الأصلية دون ترجمتها إلى لغة أخرى. أرجع النص النهائي المصاغ فقط."
                callGeminiAudio(prompt, base64Audio)
            }
            VoiceMode.TRANSLATE_TR -> {
                tvStatus.text = "⏳ [مود الترجمة التركية] جاري تحويل صوتك وترجمته إلى Türkçe..."
                val prompt = "استمع للتسجيل الصوتي بأي لغة كانت، وترجم المعنى إلى اللغة التركية (Türkçe) بأسلوب رسمي واحترافي وسليم تماماً. أرجع النص المترجم فقط."
                callGeminiAudio(prompt, base64Audio)
            }
            VoiceMode.TRANSLATE_EN -> {
                tvStatus.text = "⏳ [مود الترجمة الإنكليزية] جاري تحويل صوتك وترجمته إلى English..."
                val prompt = "استمع للتسجيل الصوتي بأي لغة كانت، وترجم المعنى إلى اللغة الإنكليزية (English) بأسلوب رسمي واحترافي. أرجع النص المترجم فقط."
                callGeminiAudio(prompt, base64Audio)
            }
            VoiceMode.NONE -> {}
        }
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
                listOf("ئ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "DEL"),
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

                    if (key == "DEL") {
                        val deleteRunnable = object : Runnable {
                            override fun run() {
                                currentInputConnection?.deleteSurroundingText(1, 0)
                                deleteHandler.postDelayed(this, 35)
                            }
                        }

                        setOnTouchListener { _, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    currentInputConnection?.deleteSurroundingText(1, 0)
                                    deleteHandler.postDelayed(deleteRunnable, 350)
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    deleteHandler.removeCallbacks(deleteRunnable)
                                    true
                                }
                                else -> false
                            }
                        }
                    } else {
                        setOnClickListener { handleKeyPress(key) }
                    }
                }
                rowLayout.addView(btn)
            }
            keysContainer.addView(rowLayout)
        }
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "SPACE" -> ic.commitText(" ", 1)
            "↵" -> ic.commitText("\n", 1)
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

    private fun getCurrentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(2500, 0) ?: ""
        val after = ic.getTextAfterCursor(2500, 0) ?: ""
        return "$before$after"
    }

    private fun replaceAllText(newText: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2500, 0) ?: ""
        val after = ic.getTextAfterCursor(2500, 0) ?: ""
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(newText.trim(), 1)
    }

    private fun callGeminiText(prompt: String) {
        val part = JSONObject().put("text", prompt)
        sendGeminiRequest(JSONArray().put(part))
    }

    private fun callGeminiAudio(prompt: String, base64Audio: String) {
        val textPart = JSONObject().put("text", prompt)
        val inlineData = JSONObject()
            .put("mime_type", "audio/mp4")
            .put("data", base64Audio)
        val audioPart = JSONObject().put("inline_data", inlineData)

        sendGeminiRequest(JSONArray().put(textPart).put(audioPart))
    }

    private fun sendGeminiRequest(parts: JSONArray) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            tvStatus.text = "⚠️ افتح تطبيق AI Keyboard وألصق مفتاح API أولاً!"
            return
        }

        Thread {
            try {
                if (workingModel != null) {
                    val (ok, _) = executeGenerateContent(workingModel!!, parts, apiKey)
                    if (ok) return@Thread
                }

                mainHandler.post { tvStatus.text = "⏳ جاري اختيار النموذج الأسرع لحسابك..." }

                val modelsUrl = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                val getConn = modelsUrl.openConnection() as HttpURLConnection
                getConn.requestMethod = "GET"
                getConn.setRequestProperty("x-goog-api-key", apiKey)
                getConn.connectTimeout = 10000
                getConn.readTimeout = 10000

                if (getConn.responseCode !in 200..299) {
                    val err = getConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val msg = try { JSONObject(err).getJSONObject("error").getString("message") } catch (e: Exception) { "خطأ في المفتاح: ${getConn.responseCode}" }
                    mainHandler.post { tvStatus.text = "❌ $msg" }
                    return@Thread
                }

                val resp = getConn.inputStream.bufferedReader().use { it.readText() }
                val modelsArr = JSONObject(resp).optJSONArray("models")

                val candidates = mutableListOf<String>()
                if (modelsArr != null) {
                    for (i in 0 until modelsArr.length()) {
                        val m = modelsArr.getJSONObject(i)
                        val mName = m.getString("name")
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        var canGenerate = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.getString(j) == "generateContent") {
                                    canGenerate = true
                                    break
                                }
                            }
                        }
                        if (canGenerate) {
                            if (mName.contains("flash", ignoreCase = true)) candidates.add(0, mName)
                            else candidates.add(mName)
                        }
                    }
                }

                var success = false
                var lastErr = ""
                for (modelPath in candidates) {
                    val (ok, err) = executeGenerateContent(modelPath, parts, apiKey)
                    if (ok) {
                        workingModel = modelPath
                        getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
                            .edit().putString("cached_model", modelPath).apply()
                        success = true
                        break
                    } else {
                        lastErr = err
                    }
                }

                if (!success) {
                    mainHandler.post { tvStatus.text = "❌ $lastErr" }
                }

            } catch (e: Exception) {
                mainHandler.post { tvStatus.text = "❌ خطأ في الاتصال بالإنترنت" }
            }
        }.start()
    }

    private fun executeGenerateContent(modelPath: String, parts: JSONArray, apiKey: String): Pair<Boolean, String> {
        return try {
            val cleanPath = if (modelPath.startsWith("models/")) modelPath else "models/$modelPath"
            val url = URL("https://generativelanguage.googleapis.com/v1beta/$cleanPath:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 12000
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
                Pair(true, "")
            } else {
                val errorDetail = try {
                    JSONObject(responseStr).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "كود ${conn.responseCode}"
                }
                Pair(false, errorDetail)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "خطأ في الشبكة")
        }
    }
}
