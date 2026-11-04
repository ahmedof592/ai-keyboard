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

    // المود الرئيسي: إما تحسين الصياغة بنفس اللغة، أو ترجمة للغة محددة
    private enum class MainMode { REFINE, TRANSLATE }

    // درجات الصياغة الاحترافية
    private enum class ToneStyle(val label: String, val promptDesc: String) {
        FORMAL("👔 رسمي", "بأسلوب رسمي، احترافي، رصين، ومتقن جداً"),
        SEMI_FORMAL("🤝 نصف رسمي", "بأسلوب نصف رسمي، عملي، طبيعي، ومحترم"),
        POLITE("🌸 ألطف", "بأسلوب لطيف جداً،ودود، لبق، ودافئ"),
        CONCISE("🎯 مختصر", "بأسلوب مختصر، مباشر، واضح، وخالٍ من الحشو")
    }

    // لغات الهدف في مود الترجمة
    private enum class TargetLang(val label: String, val langName: String) {
        AR("➔ 🇸🇦 عربي", "العربية الفصحى السليمة"),
        TR("➔ 🇹🇷 تركي", "التركية (Türkçe)"),
        EN("➔ 🇬🇧 EN", "الإنكليزية (English)")
    }

    private var currentMainMode = MainMode.REFINE
    private var currentTone = ToneStyle.FORMAL
    private var currentTargetLang = TargetLang.TR

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    private lateinit var tvStatus: TextView
    private lateinit var btnModeRefine: Button
    private lateinit var btnModeTranslate: Button
    private lateinit var btnMicAction: Button
    private lateinit var btnApplyText: Button
    private lateinit var btnSwitchKeyboard: Button
    private lateinit var subOptionsContainer: LinearLayout
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
        btnModeRefine = view.findViewById(R.id.btnModeRefine)
        btnModeTranslate = view.findViewById(R.id.btnModeTranslate)
        btnMicAction = view.findViewById(R.id.btnMicAction)
        btnApplyText = view.findViewById(R.id.btnApplyText)
        btnSwitchKeyboard = view.findViewById(R.id.btnSwitchKeyboard)
        subOptionsContainer = view.findViewById(R.id.subOptionsContainer)
        keysContainer = view.findViewById(R.id.keyboardKeysLayout)

        workingModel = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)
            .getString("cached_model", null)

        // التبديل إلى مود التحسين (نفس اللغة)
        btnModeRefine.setOnClickListener {
            if (isRecording) cancelRecording()
            currentMainMode = MainMode.REFINE
            updateTopControlsUI()
        }

        // التبديل إلى مود الترجمة
        btnModeTranslate.setOnClickListener {
            if (isRecording) cancelRecording()
            currentMainMode = MainMode.TRANSLATE
            updateTopControlsUI()
        }

        // زر المايكروفون الذكي (يبدأ التسجيل أو ينهيه وينفذ المود المختار)
        btnMicAction.setOnClickListener {
            if (!isRecording) {
                startAudioCapture()
            } else {
                stopRecordingAndProcess()
            }
        }

        // زر التنفيذ على النص المكتوب حالياً
        btnApplyText.setOnClickListener {
            if (isRecording) {
                stopRecordingAndProcess()
                return@setOnClickListener
            }
            val currentText = getCurrentFieldText()
            if (currentText.isBlank()) {
                tvStatus.text = "⚠️ اكتب نصاً أولاً أو اضغط (🎤 تحدث)!"
                return@setOnClickListener
            }
            executeTextAction(currentText)
        }

        // زر تغيير لوحة المفاتيح
        btnSwitchKeyboard.setOnClickListener {
            if (isRecording) cancelRecording()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        updateTopControlsUI()
        renderKeyboardLayout()
        return view
    }

    private fun styleButton(btn: Button, bgColor: String, txtColor: String, strokeColor: String? = null) {
        val gd = GradientDrawable().apply {
            cornerRadius = dpToPx(6).toFloat()
            setColor(Color.parseColor(bgColor))
            if (strokeColor != null) {
                setStroke(dpToPx(1), Color.parseColor(strokeColor))
            }
        }
        btn.background = gd
        btn.setTextColor(Color.parseColor(txtColor))
    }

    private fun updateTopControlsUI() {
        // تلوين أزرار المود الرئيسي لتكون واضحة تماماً
        if (currentMainMode == MainMode.REFINE) {
            styleButton(btnModeRefine, "#A6E3A1", "#11111B", "#FFFFFF") // أخضر ساطع للمود الفعال
            styleButton(btnModeTranslate, "#24253A", "#9399B2") // خافت لغير الفعال
        } else {
            styleButton(btnModeRefine, "#24253A", "#9399B2")
            styleButton(btnModeTranslate, "#89B4FA", "#11111B", "#FFFFFF") // أزرق ساطع لمود الترجمة
        }

        if (isRecording) {
            styleButton(btnMicAction, "#F38BA8", "#11111B", "#FFFFFF")
            btnMicAction.text = "⏹️ إنهاء"
        } else {
            styleButton(btnMicAction, "#F38BA8", "#11111B")
            btnMicAction.text = "🎤 تحدث"
        }

        styleButton(btnApplyText, "#CBA6F7", "#11111B")
        styleButton(btnSwitchKeyboard, "#313244", "#FFFFFF")

        // تحديث شريط الحالة العلوي
        if (!isRecording) {
            if (currentMainMode == MainMode.REFINE) {
                tvStatus.text = "🟢 المود: تحسين الصياغة (نفس اللغة) | النمط: ${currentTone.label}"
            } else {
                tvStatus.text = "🔵 المود: ترجمة فورية (${currentTargetLang.label}) | النمط: ${currentTone.label}"
            }
        }

        // بناء شريط الخيارات الفرعية حسب المود الفعال
        subOptionsContainer.removeAllViews()

        if (currentMainMode == MainMode.REFINE) {
            // عرض أزرار اختيار النمط (رسمي / نصف رسمي / ألطف / مختصر)
            for (tone in ToneStyle.values()) {
                val isSelected = (currentTone == tone)
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
                    }
                    text = tone.label
                    textSize = 10f
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    styleButton(
                        this,
                        if (isSelected) "#45475A" else "#1E1E2E",
                        if (isSelected) "#A6E3A1" else "#A6ADC8",
                        if (isSelected) "#A6E3A1" else null
                    )
                    setOnClickListener {
                        currentTone = tone
                        updateTopControlsUI()
                    }
                }
                subOptionsContainer.addView(btn)
            }
        } else {
            // في مود الترجمة: عرض اللغات الثلاث + زر تبديل النمط
            for (lang in TargetLang.values()) {
                val isSelected = (currentTargetLang == lang)
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
                    }
                    text = lang.label
                    textSize = 10.5f
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    styleButton(
                        this,
                        if (isSelected) "#313244" else "#1E1E2E",
                        if (isSelected) "#89B4FA" else "#A6ADC8",
                        if (isSelected) "#89B4FA" else null
                    )
                    setOnClickListener {
                        currentTargetLang = lang
                        updateTopControlsUI()
                    }
                }
                subOptionsContainer.addView(btn)
            }

            // زر تبديل النمط داخل مود الترجمة
            val toneCycleBtn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f).apply {
                    setMargins(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
                }
                text = currentTone.label
                textSize = 10f
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                styleButton(this, "#24253A", "#F9E2AF", "#F9E2AF")
                setOnClickListener {
                    val tones = ToneStyle.values()
                    currentTone = tones[(currentTone.ordinal + 1) % tones.size]
                    updateTopControlsUI()
                }
            }
            subOptionsContainer.addView(toneCycleBtn)
        }
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
            updateTopControlsUI()
            if (currentMainMode == MainMode.REFINE) {
                tvStatus.text = "🔴 تحدث بأي لغة.. سأحسّن الصياغة بنفس لغتك (${currentTone.label})"
            } else {
                tvStatus.text = "🔴 تحدث بأي لغة.. سأترجم كلامك ${currentTargetLang.label} (${currentTone.label})"
            }
        } catch (e: Exception) {
            isRecording = false
            updateTopControlsUI()
            tvStatus.text = "❌ تأكد من تفعيل إذن المايكروفون للتطبيق"
        }
    }

    private fun cancelRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        updateTopControlsUI()
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        updateTopControlsUI()

        val bytes = audioFile?.readBytes()
        if (bytes == null || bytes.isEmpty()) {
            tvStatus.text = "⚠️ لم يتم التقاط صوت، حاول مجدداً"
            return
        }
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)

        if (currentMainMode == MainMode.REFINE) {
            tvStatus.text = "⏳ جاري تحسين صياغة كلامك بنفس لغته (${currentTone.label})..."
            val prompt = """
                استمع إلى هذا التسجيل الصوتي وتعرف على لغته تلقائياً.
                المطلوب: إعادة صياغة الكلام المنطوق وتصحيحه ليكون ${currentTone.promptDesc}، مع الحفاظ الصارم على نفس لغة المتحدث الأصلية (إذا تحدث بالتركية أخرج النص بالتركية، وإذا تحدث بالعربية أخرجه بالعربية، وإذا تحدث بالإنكليزية أخرجه بالإنكليزية).
                أرجع النص النهائي المصاغ فقط بدون أي مقدمات أو علامات تنصيص أو شرح.
            """.trimIndent()
            callGeminiAudio(prompt, base64Audio)
        } else {
            tvStatus.text = "⏳ جاري ترجمة صوتك ${currentTargetLang.label} (${currentTone.label})..."
            val prompt = """
                استمع إلى هذا التسجيل الصوتي وتعرف على لغته تلقائياً مهما كانت.
                المطلوب: ترجمة معنى الكلام وصياغته إلى اللغة ${currentTargetLang.langName} ${currentTone.promptDesc}.
                أرجع النص المترجم النهائي فقط بدون أي مقدمات أو علامات تنصيص أو شرح.
            """.trimIndent()
            callGeminiAudio(prompt, base64Audio)
        }
    }

    private fun executeTextAction(inputText: String) {
        if (currentMainMode == MainMode.REFINE) {
            tvStatus.text = "⏳ جاري تحسين النص المكتوب بنفس لغته (${currentTone.label})..."
            val prompt = """
                تعرف على لغة النص التالي تلقائياً، وأعد صياغته وتصحيحه ليكون ${currentTone.promptDesc}، مع الحفاظ التام على نفس لغة النص الأصلية دون ترجمته.
                أرجع النص المعدل فقط بدون أي شرح أو إضافات:
                $inputText
            """.trimIndent()
            callGeminiText(prompt)
        } else {
            tvStatus.text = "⏳ جاري ترجمة النص ${currentTargetLang.label} (${currentTone.label})..."
            val prompt = """
                تعرف على لغة النص التالي تلقائياً، وترجمه إلى اللغة ${currentTargetLang.langName} ${currentTone.promptDesc}.
                أرجع النص المترجم فقط بدون أي شرح أو إضافات:
                $inputText
            """.trimIndent()
            callGeminiText(prompt)
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
            "AR" -> {
                if (!isShifted) {
                    // الحروف العربية الكاملة بما فيها (د) و (ذ) و (لا)
                    listOf(
                        listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"),
                        listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط", "ذ"),
                        listOf("⇧", "ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "DEL"),
                        listOf("123", "🌐 TR", "-", "SPACE", "،", ".", "↵")
                    )
                } else {
                    // الطبقة الثانية للعربية: الهمزات (أ إ آ) والتشكيل والرموز السريعة
                    listOf(
                        listOf("أ", "إ", "آ", "لأ", "لإ", "لآ", "َ", "ً", "ُ", "ٌ", "ِ", "ٍ"),
                        listOf("ّ", "ْ", "ـ", "«", "»", "/", ":", "؛", "(", ")", "؟", "!"),
                        listOf("⬆", "@", "#", "%", "&", "*", "+", "=", "_", "\"", "'", "DEL"),
                        listOf("123", "🌐 TR", "-", "SPACE", "،", ".", "↵")
                    )
                }
            }
            "TR" -> {
                val trLocale = Locale("tr", "TR")
                val r1 = listOf("q", "w", "e", "r", "t", "y", "u", "ı", "o", "p", "ğ", "ü")
                val r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ş", "i")
                val r3 = listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "ö", "ç", "DEL")
                val r4 = listOf("123", "🌐 EN", "-", "SPACE", ",", ".", "↵")

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
                val r4 = listOf("123", "🌐 AR", "-", "SPACE", ",", ".", "↵")

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
            else -> listOf( // "SYM" شامل لكل الكاركترات والداش والرموز
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("-", "_", "/", ":", ";", "(", ")", "$", "&", "@"),
                listOf("+", "=", "*", "\"", "'", "!", "?", "؟", "%", "#", "DEL"),
                listOf("ABC", "🌐 AR", "-", "SPACE", "،", ".", "↵")
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
                    "SPACE" -> 3.4f
                    "DEL", "↵" -> 1.4f
                    "123", "ABC", "🌐 AR", "🌐 TR", "🌐 EN" -> 1.3f
                    "⇧", "⬆" -> 1.15f
                    else -> 1.0f
                }

                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                        setMargins(dpToPx(1), dpToPx(2), dpToPx(1), dpToPx(2))
                    }
                    text = when (key) {
                        "SPACE" -> when (currentLayout) {
                            "AR" -> "مسافة"
                            "TR" -> "Boşluk"
                            else -> "Space"
                        }
                        "DEL" -> "⌫"
                        else -> key
                    }
                    textSize = when (key) {
                        "SPACE", "123", "ABC", "🌐 AR", "🌐 TR", "🌐 EN" -> 10.5f
                        "DEL", "↵", "⇧", "⬆" -> 13f
                        else -> if (row.size >= 12) 13.5f else 15f
                    }
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false

                    val bg = GradientDrawable().apply {
                        cornerRadius = dpToPx(5).toFloat()
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
                            "⇧", "⬆" -> Color.parseColor("#F9E2AF")
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
                                    deleteHandler.postDelayed(deleteRunnable, 320)
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
                isShifted = false
                currentLayout = "SYM"
                renderKeyboardLayout()
            }
            "ABC" -> {
                isShifted = false
                currentLayout = lastAlphaLayout
                renderKeyboardLayout()
            }
            "🌐 TR" -> {
                isShifted = false
                currentLayout = "TR"
                lastAlphaLayout = "TR"
                renderKeyboardLayout()
            }
            "🌐 EN" -> {
                isShifted = false
                currentLayout = "EN"
                lastAlphaLayout = "EN"
                renderKeyboardLayout()
            }
            "🌐 AR" -> {
                isShifted = false
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
        val before = ic.getTextBeforeCursor(3000, 0) ?: ""
        val after = ic.getTextAfterCursor(3000, 0) ?: ""
        return "$before$after"
    }

    private fun replaceAllText(newText: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(3000, 0) ?: ""
        val after = ic.getTextAfterCursor(3000, 0) ?: ""
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
            tvStatus.text = "⚠️ افتح تطبيق AI Keyboard واحفظ مفتاح API أولاً!"
            return
        }

        Thread {
            try {
                // 1. تجربة النموذج المحفوظ إن وجد
                if (workingModel != null) {
                    val (ok, _) = executeGenerateContent(workingModel!!, parts, apiKey)
                    if (ok) return@Thread
                    workingModel = null
                }

                // 2. جلب النماذج الفعالة لحساب المستخدم من Google مباشرة
                val modelsUrl = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                val getConn = modelsUrl.openConnection() as HttpURLConnection
                getConn.requestMethod = "GET"
                getConn.setRequestProperty("x-goog-api-key", apiKey)
                getConn.connectTimeout = 10000
                getConn.readTimeout = 10000

                val candidates = mutableListOf<String>()
                if (getConn.responseCode in 200..299) {
                    val resp = getConn.inputStream.bufferedReader().use { it.readText() }
                    val modelsArr = JSONObject(resp).optJSONArray("models")
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
                                if (mName.contains("2.0-flash", ignoreCase = true)) {
                                    candidates.add(0, mName)
                                } else if (mName.contains("flash", ignoreCase = true)) {
                                    candidates.add(mName)
                                } else {
                                    candidates.add(mName)
                                }
                            }
                        }
                    }
                } else {
                    candidates.add("models/gemini-2.0-flash")
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
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.doOutput = true

            val content = JSONObject().put("parts", parts)
            val payload = JSONObject().put("contents", JSONArray().put(content))

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseStr = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

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
                    updateTopControlsUI()
                    tvStatus.text = "✅ تم التنفيذ بنجاح! (${currentTone.label})"
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
