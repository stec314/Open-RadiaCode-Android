package com.radiacode.ble.pro

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiacode.ble.R
import com.radiacode.ble.RadiacodeBleClient
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.device.ProSettings
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Base class for the instrument screens. Builds a consistent pro-dark layout in code
 * (toolbar, scrolling column, cards, rows) so screens stay small and free of XML.
 */
abstract class ProActivity : AppCompatActivity() {

    protected val ui = Handler(Looper.getMainLooper())
    protected val worker = Executors.newSingleThreadExecutor()
    protected val density: Float by lazy { resources.displayMetrics.density }

    protected val cBg by lazy { color(R.color.pro_background) }
    protected val cSurface by lazy { color(R.color.pro_surface) }
    protected val cBorder by lazy { color(R.color.pro_border) }
    protected val cCyan by lazy { color(R.color.pro_cyan) }
    protected val cMagenta by lazy { color(R.color.pro_magenta) }
    protected val cGreen by lazy { color(R.color.pro_green) }
    protected val cRed by lazy { color(R.color.pro_red) }
    protected val cYellow by lazy { color(R.color.pro_yellow) }
    protected val cAmber by lazy { color(R.color.pro_amber) }
    protected val cText by lazy { color(R.color.pro_text_primary) }
    protected val cText2 by lazy { color(R.color.pro_text_secondary) }
    protected val cMuted by lazy { color(R.color.pro_text_muted) }

    protected lateinit var column: LinearLayout
    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var scroll: ScrollView

    protected fun color(id: Int) = ContextCompat.getColor(this, id)
    protected fun dp(v: Float): Int = (v * density).toInt()
    protected fun dp(v: Int): Int = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProSettings.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val receivers = ArrayList<android.content.BroadcastReceiver>()

    override fun onDestroy() {
        super.onDestroy()
        receivers.forEach { runCatching { unregisterReceiver(it) } }
        receivers.clear()
        worker.shutdownNow()
    }

    /** Listen to the app's own broadcasts for the lifetime of the screen. */
    protected fun listen(vararg actions: String, onIntent: (android.content.Intent) -> Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent != null) onIntent(intent)
            }
        }
        val filter = android.content.IntentFilter().apply { actions.forEach { addAction(it) } }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receivers.add(r)
    }

    /** Share a file from internal storage through the app FileProvider. */
    protected fun share(file: java.io.File, mime: String, subject: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(send, subject))
        } catch (t: Throwable) {
            toast("Share failed: ${t.message}")
        }
    }

    /** Writes text to an exports folder in internal storage and returns the file. */
    protected fun exportFile(name: String, content: String): java.io.File {
        val dir = java.io.File(filesDir, "exports").apply { mkdirs() }
        return java.io.File(dir, name).apply { writeText(content) }
    }

    /** Sets up toolbar + scrolling column. Returns the column to add content to. */
    protected fun page(title: String, subtitle: String? = null, scrollable: Boolean = true): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        toolbar = MaterialToolbar(this).apply {
            this.title = title
            this.subtitle = subtitle
            setTitleTextColor(cText)
            setSubtitleTextColor(cMuted)
            setBackgroundColor(cBg)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationIcon?.setTint(cText)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        if (scrollable) {
            scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(column)
            }
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            scroll = ScrollView(this)
            root.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        return column
    }

    protected fun cardBackground(stroke: Int = cBorder, fill: Int = cSurface, radius: Float = 16f) =
        GradientDrawable().apply {
            cornerRadius = radius * density
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    /** A titled card. */
    protected fun card(parent: LinearLayout, title: String? = null, hint: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        if (title != null) c.addView(text(title.uppercase(), 11f, cMuted, bold = true).apply { letterSpacing = 0.1f })
        if (hint != null) c.addView(text(hint, 12f, cMuted).apply { setPadding(0, dp(4), 0, dp(2)) })
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        return c
    }

    protected fun text(
        s: CharSequence,
        sizeSp: Float = 14f,
        color: Int = cText,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }

    protected fun spacer(parent: LinearLayout, h: Int = 8) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    protected fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(cBorder) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    /** Label on the left, arbitrary view on the right. */
    protected fun row(parent: LinearLayout, label: String, desc: String? = null, right: View? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(label, 15f, cText))
        if (desc != null) left.addView(text(desc, 12f, cMuted))
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) r.addView(right)
        parent.addView(r)
        return r
    }

    protected fun valueRow(parent: LinearLayout, label: String, value: String, valueColor: Int = cText): TextView {
        val v = text(value, 15f, valueColor, mono = true).apply { gravity = Gravity.END }
        row(parent, label, null, v)
        return v
    }

    protected fun switchRow(parent: LinearLayout, label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): SwitchMaterial {
        val sw = SwitchMaterial(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cCyan, cMuted)
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row(parent, label, desc, sw)
        return sw
    }

    protected fun sliderRow(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): SeekBar {
        val valueView = text(format(value), 14f, cCyan, mono = true)
        row(parent, label, null, valueView)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(cCyan)
            thumbTintList = ColorStateList.valueOf(cCyan)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueView.text = format(p + min)
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(sb)
        return sb
    }

    protected fun numberField(initial: String, decimal: Boolean = true, widthDp: Int = 110): EditText = EditText(this).apply {
        setText(initial)
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        setTextColor(cText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.END
        background = cardBackground(cBorder, color(R.color.pro_surface_elevated), 8f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        isSingleLine = true
    }

    protected fun textField(initial: String, hint: String): EditText = EditText(this).apply {
        setText(initial)
        this.hint = hint
        setHintTextColor(cMuted)
        setTextColor(cText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = cardBackground(cBorder, color(R.color.pro_surface_elevated), 8f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isSingleLine = true
    }

    /** Segmented choice. Returns a setter for the selected index. */
    protected fun segmented(parent: LinearLayout, options: List<String>, selected: Int, onSelect: (Int) -> Unit): (Int) -> Unit {
        val holder = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        holder.addView(bar)
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, tv ->
                val on = i == sel
                tv.setTextColor(if (on) cBg else cText2)
                tv.background = cardBackground(if (on) cCyan else cBorder, if (on) cCyan else Color.TRANSPARENT, 14f)
            }
        }
        options.forEachIndexed { i, o ->
            val tv = text(o, 13f, cText2, bold = true).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { paint(i); onSelect(i) }
            }
            views.add(tv)
            bar.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        paint(selected)
        parent.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })
        return { paint(it) }
    }

    enum class Btn { PRIMARY, SECONDARY, DANGER }

    protected fun button(label: String, style: Btn = Btn.SECONDARY, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            cornerRadius = dp(20)
            val (fg, stroke, fill) = when (style) {
                Btn.PRIMARY -> Triple(cCyan, cCyan, Color.parseColor("#1A2A30"))
                Btn.DANGER -> Triple(cRed, cRed, Color.parseColor("#2A1A1C"))
                Btn.SECONDARY -> Triple(cText2, Color.parseColor("#3A3A3E"), cSurface)
            }
            setTextColor(fg)
            strokeColor = ColorStateList.valueOf(stroke)
            strokeWidth = dp(1)
            backgroundTintList = ColorStateList.valueOf(fill)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(42)
            setOnClickListener { onClick() }
        }

    /** Buttons laid out in equal-width columns, wrapping to new lines every [perRow]. */
    protected fun buttons(parent: LinearLayout, perRow: Int, vararg b: MaterialButton) {
        var line: LinearLayout? = null
        b.forEachIndexed { i, btn ->
            if (i % perRow == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            line!!.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i % perRow != perRow - 1) dp(8) else 0
            })
        }
        // pad last row so buttons keep equal width
        val rem = b.size % perRow
        if (rem != 0) repeat(perRow - rem) { line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    /** A view of fixed height (dp) added to [parent], for custom drawn content. */
    protected fun addFixed(parent: LinearLayout, v: View, heightDp: Int) {
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { topMargin = dp(8) })
    }

    protected fun frame(): FrameLayout = FrameLayout(this)

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun confirm(title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Runs a device operation off the main thread with a timeout and delivers the result on the
     * main thread. [onResult] receives null and [onError] the message when there is no device or
     * the operation fails.
     */
    internal fun <T> device(
        timeoutSec: Long = 15,
        op: (RadiacodeBleClient) -> CompletableFuture<T>,
        onError: (String) -> Unit = { toast(it) },
        onResult: (T) -> Unit,
    ) {
        val client = DeviceGateway.client(this)
        if (client == null) {
            onError(if (DeviceGateway.isServiceRunning) "No connected device" else "Connection service not running")
            return
        }
        worker.execute {
            try {
                val r = op(client).get(timeoutSec, TimeUnit.SECONDS)
                ui.post { if (!isFinishing) onResult(r) }
            } catch (t: Throwable) {
                val cause = (t.cause ?: t)
                ui.post { if (!isFinishing) onError(cause.message ?: cause.javaClass.simpleName) }
            }
        }
    }
}
