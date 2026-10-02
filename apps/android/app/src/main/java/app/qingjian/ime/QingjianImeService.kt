package app.qingjian.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * 青简的 Android 输入法壳（第一版：全拼打字 + 候选条）。
 *
 * 分工和桌面壳一致：这里只负责「把按键喂给引擎、把候选画出来、把选中的文本上屏」，
 * 拼音怎么切、候选怎么排全在 Rust 引擎里（`apps/android/native`）。
 */
class QingjianImeService : InputMethodService() {

    /** Rust 侧会话句柄；0 表示建会话失败。 */
    private var handle = 0L

    /** 已经敲进缓冲区、还没上屏的按键。 */
    private var keys = ""

    /** 英文直输模式：不进引擎，敲什么上什么。 */
    private var english = false

    /** 密码类输入框：按键直接上屏，完全不经过引擎（不学习、不记录、不出候选）。 */
    private var privateField = false

    private var candidateRow: LinearLayout? = null
    private var preeditView: TextView? = null
    private var statusLabel: TextView? = null

    /** 当前候选个数，空格键据此决定是选首选还是打空格。 */
    private var candidateCount = 0

    override fun onCreate() {
        super.onCreate()
        handle = try {
            // 引擎用 mmap 打开词库，必须是真实文件：先把 asset 复制到 filesDir
            val dict = File(filesDir, "dict.qj")
            if (!dict.exists() || dict.length() == 0L) {
                assets.open("dict.qj").use { input ->
                    dict.outputStream().use { output -> input.copyTo(output) }
                }
            }
            NativeEngine.nativeCreate(dict.absolutePath)
        } catch (e: Throwable) {
            // 词库没打进包、ABI 不匹配、建会话失败都落这里：显示提示而不是闪退
            0L
        }
    }

    override fun onDestroy() {
        if (handle != 0L) {
            NativeEngine.nativeDestroy(handle)
            handle = 0L
        }
        super.onDestroy()
    }

    /** 每次落到新的输入框都会先走这里：认出密码框就切私密模式。 */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        privateField = isPrivateField(info)
        if (handle != 0L) {
            NativeEngine.nativeSetPrivate(handle, privateField)
        }
        if (privateField) {
            // 进密码框：缓冲区和候选一起清掉，一个字都不留
            keys = ""
            candidateCount = 0
            candidateRow?.removeAllViews()
            preeditView?.text = ""
            statusLabel?.text = "私密输入：不学习、不记录、不出候选"
        } else {
            statusLabel?.text = if (english) "英文直输" else "拼音"
        }
    }

    /**
     * 密码类输入框判定。注意 `TYPE_TEXT_VARIATION_PASSWORD` 与
     * `TYPE_NUMBER_VARIATION_PASSWORD` 的值相同（都是 0x10），靠 class 位区分。
     */
    private fun isPrivateField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val klass = type and InputType.TYPE_MASK_CLASS
        if (klass != InputType.TYPE_CLASS_TEXT && klass != InputType.TYPE_CLASS_NUMBER) {
            return false
        }
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (handle == 0L) {
            statusLabel?.text = getString(R.string.engine_failed)
        } else {
            refresh()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        keys = ""
        candidateCount = 0
        candidateRow?.removeAllViews()
        preeditView?.text = ""
        if (handle != 0L) {
            NativeEngine.nativeClear(handle)
            // 离开输入框时复位私密态，别留给下一个框
            if (privateField) {
                privateField = false
                NativeEngine.nativeSetPrivate(handle, false)
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 候选条：横向滚动，候选多了也不挤
        val strip = HorizontalScrollView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candidateRow = row
        strip.addView(row)
        root.addView(
            strip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 拼音缓冲区 + 状态（中文/英文、报错都显示在这行）
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val preedit = TextView(this).apply {
            textSize = 16f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val status = TextView(this).apply {
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        preeditView = preedit
        statusLabel = status
        statusRow.addView(preedit, LinearLayout.LayoutParams(0, WRAP, 1f))
        statusRow.addView(status, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(statusRow)

        addKeyRow(root, "qwertyuiop".map { letter(it) })
        addKeyRow(root, "asdfghjkl".map { letter(it) })
        addKeyRow(root, "zxcvbnm".map { letter(it) } + listOf("⌫" to { backspace() }))

        val toggle = Button(this).apply {
            text = "中"
            textSize = 14f
        }
        addKeyRow(
            root,
            listOf(
                "中" to { toggleEnglish(toggle) },
                "空格" to { space() },
                "回车" to { enter() },
            ),
        )

        status.text = if (handle == 0L) getString(R.string.engine_failed) else "拼音"
        return root
    }

    private fun letter(c: Char): Pair<String, () -> Unit> = c.toString() to { pressLetter(c.toString()) }

    /** 一行等宽的键。 */
    private fun addKeyRow(root: LinearLayout, cells: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in cells) {
            row.addView(
                Button(this).apply {
                    text = label
                    textSize = 16f
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        }
        root.addView(row)
    }

    private fun toggleEnglish(button: Button) {
        english = !english
        button.text = if (english) "英" else "中"
        statusLabel?.text = if (english) "英文直输" else "拼音"
    }

    private fun pressLetter(text: String) {
        // 私密输入框与英文模式下按键都直接上屏，不经过引擎
        if (english || privateField) {
            currentInputConnection?.commitText(text, 1)
            return
        }
        keys += text
        refresh()
    }

    private fun backspace() {
        if (keys.isNotEmpty()) {
            keys = keys.dropLast(1)
            refresh()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun space() {
        if (keys.isNotEmpty() && candidateCount > 0) {
            commitAt(0)
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun refresh() {
        if (handle == 0L) return
        render(NativeEngine.nativeSetInput(handle, keys))
    }

    private fun commitAt(index: Int) {
        if (handle == 0L) return
        val result = NativeEngine.nativeCommit(handle, index)
        val obj = parse(result) ?: return
        if (obj.has("error")) {
            statusLabel?.text = obj.optString("error")
            return
        }
        val text = obj.optString("text")
        if (text.isNotEmpty()) {
            currentInputConnection?.commitText(text, 1)
        }
        keys = ""
        render(result)
    }

    /** 把引擎回的 JSON 画成候选条与缓冲区。 */
    private fun render(json: String) {
        val row = candidateRow ?: return
        row.removeAllViews()
        val obj = parse(json)
        if (obj == null) {
            candidateCount = 0
            return
        }
        if (obj.has("error")) {
            candidateCount = 0
            preeditView?.text = ""
            statusLabel?.text = obj.optString("error")
            return
        }
        preeditView?.text = obj.optString("preedit")
        val items = obj.optJSONArray("candidates")
        candidateCount = items?.length() ?: 0
        if (items == null) return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val label = item.optString("text")
            if (label.isEmpty()) continue
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 20f
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener { commitAt(i) }
                },
            )
        }
    }

    private fun parse(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (e: Exception) {
        null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
