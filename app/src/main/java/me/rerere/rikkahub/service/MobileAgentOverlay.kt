package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.mobileagent.PhoneBackendState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small, non-focusable window owned by the user's already enabled accessibility service.
 * It observes controller state only; neither its timer nor its controls read the target UI.
 */
class MobileAgentOverlay(
    private val service: AccessibilityService,
    private val onPause: (PhoneSessionToken) -> Unit,
    private val onResume: (PhoneSessionToken) -> Unit,
    private val onStop: (PhoneSessionToken) -> Unit,
    private val onOpenChat: (PhoneSessionToken) -> Unit,
    private val onWindowChanged: (Int?) -> Unit = {},
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private var session = PhoneSessionState()
    private var backend = PhoneBackendState()
    private var resumeAvailable = true
    private var model: MobileAgentOverlayModel? = null
    private var content: LinearLayout? = null
    private var header: TextView? = null
    private var body: LinearLayout? = null
    private var phase: TextView? = null
    private var target: TextView? = null
    private var elapsed: TextView? = null
    private var counts: TextView? = null
    private var events: TextView? = null
    private var toggle: Button? = null
    private var stop: Button? = null
    private var chat: Button? = null
    private var expanded = false
    private var attached = false
    private var registeredWindowId: Int? = null
    private var lastSessionId: String? = null
    private var labelPackage: String? = null
    private var label = ""
    @Volatile private var closed = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = dp(12)
        y = dp(88)
        title = "Mobile Agent control"
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                backend = backend.copy(locked = true)
                model = null
                removeWindow()
            }
        }
    }

    private val clock = object : Runnable {
        override fun run() {
            if (closed || !attached) return
            refresh()
            if (attached) main.postDelayed(this, 1_000)
        }
    }

    init {
        ContextCompat.registerReceiver(service, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun render(session: PhoneSessionState, backend: PhoneBackendState, canResume: Boolean = true) {
        onMain {
            if (closed) return@onMain
            this.session = session
            this.backend = backend
            resumeAvailable = canResume
            refresh()
        }
    }

    private fun refresh() {
        val effectiveBackend = if (deviceLocked()) backend.copy(locked = true) else backend
        val next = MobileAgentOverlayPresenter.present(session, effectiveBackend, System.currentTimeMillis(), resumeAvailable)
        model = next
        if (next == null) {
            removeWindow()
            return
        }
        if (lastSessionId != next.token.sessionId) {
            lastSessionId = next.token.sessionId
            expanded = false
        }
        if (content == null) createWindow()
        updateViews(next)
        updateGeometry()
        if (!attached) {
            try {
                windows.addView(content, params)
                attached = true
                publishWindowId()
                content?.post { publishWindowId() }
                main.removeCallbacks(clock)
                main.postDelayed(clock, 1_000)
            } catch (_: RuntimeException) {
                // The service may be detaching. The existing notification remains the STOP entry.
                model = null
                removeWindow()
            }
        }
    }

    private fun createWindow() {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(8).toFloat()
            clipToOutline = true
        }
        content = root
        root.viewTreeObserver.addOnGlobalLayoutListener { publishWindowId() }
        header = text(14, Color.WHITE).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener {
                if (model != null) {
                    expanded = !expanded
                    model?.let(::updateViews)
                    updateGeometry()
                }
            }
            installDrag(this)
        }.also { root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))) }

        body = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }.also { panel -> root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
        val details = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        phase = text(15, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) }.also(details::addView)
        target = text(12).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.also(details::addView)
        elapsed = text(12).also(details::addView)
        counts = text(12).also(details::addView)
        details.addView(text(12).apply { text = "最近活动"; setPadding(0, dp(8), 0, dp(3)) })
        events = text(11).also(details::addView)
        body?.addView(ScrollView(service).apply { addView(details); isFillViewport = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = button("暂停", Color.rgb(34, 98, 98)).also { actions.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        stop = button("STOP", Color.rgb(170, 47, 54)).also {
            actions.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        }
        body?.addView(actions)
        chat = button("回到聊天", Color.rgb(52, 63, 80)).also {
            body?.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(5) })
        }
    }

    private fun updateViews(next: MobileAgentOverlayModel) {
        val time = MobileAgentOverlayPresenter.duration(next.elapsedMillis)
        header?.text = if (expanded) "手机控制 · ${next.statusLabel}  ▴" else "● ${next.statusLabel}  $time  ▾"
        header?.contentDescription = "手机控制，${next.phaseLabel}，${if (expanded) "点击折叠" else "点击展开"}，可拖动移动"
        body?.visibility = if (expanded) View.VISIBLE else View.GONE
        content?.background = rounded(Color.rgb(25, 34, 47), if (expanded) 18 else 24)
        phase?.text = next.phaseLabel
        target?.text = "目标：${targetLabel(next.targetPackage)}"
        elapsed?.text = "耗时 $time / ${MobileAgentOverlayPresenter.duration(next.durationLimitMillis)}"
        counts?.text = "动作 ${next.actionsUsed}/${next.actionLimit} · 观察 ${next.observationsUsed}/${next.observationLimit}"
        events?.text = next.recentEvents.joinToString("\n").ifEmpty { "暂无已记录动作" }
        val action = if (next.canPause) MobileAgentOverlayAction.PAUSE else MobileAgentOverlayAction.RESUME
        toggle?.text = if (next.canPause) "暂停" else "继续"
        toggle?.isEnabled = next.canPause || next.canResume
        toggle?.alpha = if (next.canPause || next.canResume) 1f else .45f
        bind(toggle, action, next.token)
        bind(stop, MobileAgentOverlayAction.STOP, next.token)
        bind(chat, MobileAgentOverlayAction.OPEN_CHAT, next.token)
    }

    private fun bind(view: View?, action: MobileAgentOverlayAction, token: PhoneSessionToken) {
        view?.setOnClickListener {
            if (closed || deviceLocked()) {
                model = null
                removeWindow()
                return@setOnClickListener
            }
            if (!MobileAgentOverlayPresenter.allows(action, token, model)) return@setOnClickListener
            when (action) {
                MobileAgentOverlayAction.PAUSE -> onPause(token)
                MobileAgentOverlayAction.RESUME -> onResume(token)
                MobileAgentOverlayAction.STOP -> onStop(token)
                MobileAgentOverlayAction.OPEN_CHAT -> onOpenChat(token)
            }
        }
    }

    private fun updateGeometry() {
        val metrics = service.resources.displayMetrics
        val margin = dp(8)
        val width = if (expanded) minOf(dp(280), metrics.widthPixels - margin * 2) else minOf(dp(168), metrics.widthPixels - margin * 2)
        val height = if (expanded) minOf(dp(330), (metrics.heightPixels * .55f).roundToInt()) else dp(48)
        val previous = listOf(params.width, params.height, params.x, params.y)
        params.width = width.coerceAtLeast(1)
        params.height = height.coerceAtLeast(1)
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width - margin).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height - margin).coerceAtLeast(0))
        if (attached && previous != listOf(params.width, params.height, params.x, params.y)) updateWindowLayout()
    }

    private fun installDrag(view: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    downX = event.rawX; downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.x = initialX + dx.roundToInt()
                        params.y = initialY + dy.roundToInt()
                        updateGeometry()
                        updateWindowLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) touched.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun updateWindowLayout() {
        val root = content ?: return
        if (!attached) return
        try { windows.updateViewLayout(root, params) } catch (_: RuntimeException) { model = null; removeWindow() }
    }

    private fun publishWindowId() {
        val root = content ?: return
        if (closed || !attached || !root.isAttachedToWindow) return
        // The View's direct window-ID accessor is hidden; create only our own node via the SDK.
        val ownNode = root.createAccessibilityNodeInfo()
        val id = try {
            ownNode.windowId.takeIf { it >= 0 }
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                ownNode.recycle()
            }
        } ?: return
        if (registeredWindowId != id) {
            registeredWindowId = id
            onWindowChanged(id)
        }
    }

    private fun removeWindow() {
        main.removeCallbacks(clock)
        val root = content
        if (attached && root != null) {
            attached = false
            try { windows.removeViewImmediate(root) } catch (_: RuntimeException) { /* Already detached by Android. */ }
        }
        if (registeredWindowId != null) {
            registeredWindowId = null
            onWindowChanged(null)
        }
    }

    private fun targetLabel(packageName: String): String {
        if (labelPackage != packageName) {
            labelPackage = packageName
            label = try {
                service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0))
                    .toString().replace(Regex("[\\r\\n\\t]"), " ").take(50)
            } catch (_: Exception) { packageName }
        }
        return label
    }

    private fun deviceLocked() = keyguard.isKeyguardLocked || keyguard.isDeviceLocked || !power.isInteractive

    private fun text(size: Int, color: Int = Color.rgb(203, 213, 225)) = TextView(service).apply {
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(label: String, color: Int) = Button(service).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(color, 10)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    override fun close() {
        if (closed) return
        closed = true
        onMain {
            model = null
            removeWindow()
            try { service.unregisterReceiver(screenOff) } catch (_: IllegalArgumentException) { /* Already unregistered. */ }
            content = null
        }
    }
}
