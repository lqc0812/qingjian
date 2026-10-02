package app.qingjian.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 一个只有一屏说明的小入口：IME 装完不会自己出现在输入框里，
 * 得先去系统设置里启用。这个 Activity 就是把你送过去。
 */
class EnableActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.enable_hint)
            textSize = 16f
        })

        layout.addView(Button(this).apply {
            text = getString(R.string.enable_button)
            gravity = Gravity.CENTER
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        setContentView(layout)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
