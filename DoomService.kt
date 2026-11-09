package com.kainoa.doomstopper

// DO NOT DELETE YOUR PACKAGE LINE AT THE TOP!

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class DoomService : AccessibilityService() {

    enum class ScrollMode { STRICT, COUNTED, SAFE }

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences

    private var invisibleShield: LinearLayout? = null
    
    // UI Elements for Cleanser
    private var doomOverlay: LinearLayout? = null
    private var doomOverlayParams: WindowManager.LayoutParams? = null
    private var isDoomOverlayShowing = false
    private var activeFocusRequest: AudioFocusRequest? = null
    
    private var currentDelayMs = 0L
    private var totalTimeSavedMs = 0L
    private val DELAY_INCREMENT_MS = 50L
    
    private var isCleansing = false
    private var currentScrollMode = ScrollMode.SAFE
    
    // Advanced Scroll Detection Counters
    private var homeScrollCount = 0
    private var lastScrollRegisterTime = 0L
    
    // Prevents the background poller from racing against our own faked taps
    private var isSimulatingTouch = false

    private var screenWidth = 0
    private var screenHeight = 0

    // Supported Doom-Scroll Apps
    private val YT_PACKAGE = "com.google.android.youtube"
    private val TT_PACKAGE = "com.zhiliaoapp.musically"
    private val TT_PACKAGE_ALT = "com.ss.android.ugc.trill"
    private val IG_PACKAGE = "com.instagram.android"

    private fun isTargetApp(pkg: String) = pkg == YT_PACKAGE || pkg == TT_PACKAGE || pkg == TT_PACKAGE_ALT || pkg == IG_PACKAGE

    // Unified Handler to prevent overlapping threads
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private val checkAppRunnable = object : Runnable {
        override fun run() {
            if (!isCleansing && !isSimulatingTouch) {
                val activePackage = rootInActiveWindow?.packageName?.toString() ?: ""
                if (isTargetApp(activePackage)) {
                    checkAppContentState(activePackage)
                }
            }
            if (isPolling) {
                mainHandler.postDelayed(this, 300)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("DoomStopper", "DOOM SERVICE ONLINE")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("DoomStats", Context.MODE_PRIVATE)

        currentDelayMs = prefs.getLong("currentDelayMs", 0L)
        totalTimeSavedMs = prefs.getLong("totalTimeSavedMs", 0L)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        try {
            if (event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event?.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                Log.d("DoomStopper", "🚨 KILL SWITCH ACTIVATED 🚨")
                isCleansing = false
                isSimulatingTouch = false
                removeInvisibleShield()
                removeDoomOverlay()
                currentScrollMode = ScrollMode.SAFE
                homeScrollCount = 0
                stopPolling()
            }
            return super.onKeyEvent(event)
        } catch (e: Exception) {}
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName == "com.kainoa.doomstopper" || packageName == "com.android.systemui") {
            return
        }

        // 🔥 THE SCROLL DETECTION ENGINE 🔥
        if (currentScrollMode == ScrollMode.COUNTED && !isCleansing && !isSimulatingTouch) {
            val eventType = event?.eventType
            val className = event?.className?.toString() ?: ""
            
            // 1. Check for official scroll events
            val isOfficialScroll = (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
            
            // 2. Check for "Visual Bursts" (If IG suppresses scrolls, its RecyclerView still rapidly updates its contents when dragged)
            val isListUpdating = (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && 
                                 (className.contains("RecyclerView") || className.contains("ListView") || className.contains("ScrollView")))
            
            if (isOfficialScroll || isListUpdating) {
                handleScrollDetected()
            }
        }

        val activePackage = rootInActiveWindow?.packageName?.toString() ?: ""

        if (isTargetApp(activePackage)) {
            startPolling()
            
            if (isCleansing) {
                if (!isDoomOverlayShowing && doomOverlay != null && doomOverlayParams != null) {
                    try {
                        windowManager.addView(doomOverlay, doomOverlayParams)
                        isDoomOverlayShowing = true
                        
                        requestMute()
                        mainHandler.postDelayed({ 
                            if (isCleansing && isDoomOverlayShowing) requestMute() 
                        }, 500)
                    } catch (e: Exception) {}
                }
            } else if (!isSimulatingTouch) {
                checkAppContentState(activePackage)
            }
            
        } else if (activePackage.isNotEmpty()) {
            stopPolling()
            homeScrollCount = 0
            
            if (isCleansing) {
                if (isDoomOverlayShowing && doomOverlay != null) {
                    try {
                        windowManager.removeView(doomOverlay)
                        isDoomOverlayShowing = false
                        abandonMute()
                    } catch (e: Exception) {}
                }
            }
            
            if (currentScrollMode != ScrollMode.SAFE) {
                Log.d("DoomStopper", "🚪 LEFT APP (Went to $activePackage). Lowering Shield.")
                currentScrollMode = ScrollMode.SAFE
                removeInvisibleShield()
            }
        }
    }

    private fun handleScrollDetected() {
        val now = System.currentTimeMillis()
        // 1-second cooldown so a normal swipe doesn't accidentally count as multiple scrolls
        if (now - lastScrollRegisterTime > 2500) { 
            lastScrollRegisterTime = now
            homeScrollCount++
            Log.d("DoomStopper", "📱 Home Scroll Detected! $homeScrollCount/5")
            
            if (homeScrollCount >= 5) {
                homeScrollCount = 0
                // Trigger screen, but DO NOT auto-scroll when finished
                triggerCleanser(isSwipingUp = true, shouldAutoScroll = false) 
            }
        }
    }

    private fun startPolling() {
        if (!isPolling) {
            isPolling = true
            mainHandler.post(checkAppRunnable)
        }
    }

    private fun stopPolling() {
        isPolling = false
        mainHandler.removeCallbacks(checkAppRunnable)
    }

    private fun checkAppContentState(activePackage: String) {
        val newMode = getScrollMode(activePackage)
        
        if (newMode != currentScrollMode) {
            Log.d("DoomStopper", "Mode Changed: $currentScrollMode -> $newMode")
            currentScrollMode = newMode
            
            if (newMode == ScrollMode.STRICT) {
                showSafeZoneShield() // Full Screen Block
            } else {
                removeInvisibleShield() // Counted/Safe gets no invisible shield
            }
        }
    }

    // THE BRAINS OF THE OPERATION
    private fun getScrollMode(packageName: String): ScrollMode {
        val rootNode = rootInActiveWindow ?: return ScrollMode.SAFE
        var mode = ScrollMode.SAFE

        if (packageName == YT_PACKAGE) {
            val isComments = hasVisibleNode(rootNode, "com.google.android.youtube:id/app_engagement_panel") ||
                             hasVisibleNode(rootNode, "com.google.android.youtube:id/bottom_sheet_layout") ||
                             hasVisibleText(rootNode, "Comments")
            if (!isComments && hasVisibleNode(rootNode, "com.google.android.youtube:id/reel_recycler")) {
                mode = ScrollMode.STRICT
            }
            
        } else if (packageName == TT_PACKAGE || packageName == TT_PACKAGE_ALT) {
            val isComments = hasVisibleNode(rootNode, "com.zhiliaoapp.musically:id/el0") || 
                             hasVisibleText(rootNode, "Add comment...") ||
                             hasVisibleText(rootNode, "CAMERA")
            if (!isComments && hasVisibleNode(rootNode, "com.zhiliaoapp.musically:id/player_view")) {
                mode = ScrollMode.STRICT
            }
            
        } else if (packageName == IG_PACKAGE) {
            val isComments = hasVisibleNode(rootNode, "com.instagram.android:id/layout_comment_thread_edittext_multiline") ||
                             hasVisibleText(rootNode, "Join the conversation…") || 
                             hasVisibleText(rootNode, "Add a comment...")
            
            if (!isComments) {
                // Check if they are in Reels
                if (hasVisibleNode(rootNode, "com.instagram.android:id/clips_viewer_pager") ||
                    hasVisibleNode(rootNode, "com.instagram.android:id/clips_video_container")) {
                    mode = ScrollMode.STRICT
                    
                } else {
                    // Check if they are on the Home Feed (Works instantly on cold boot)
                    val isHomeFeed = isNodeSelected(rootNode, "com.instagram.android:id/feed_tab") ||
                                     hasVisibleNode(rootNode, "com.instagram.android:id/main_feed_action_bar") ||
                                     hasVisibleNode(rootNode, "com.instagram.android:id/row_feed_button_like") ||
                                     hasVisibleNode(rootNode, "android:id/list")

                    if (isHomeFeed) {
                        mode = ScrollMode.COUNTED
                    }
                }
            }
        }
        
        rootNode.recycle()
        return mode
    }

    private fun hasVisibleNode(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) return false
        var found = false
        for (node in nodes) {
            if (!found && node.isVisibleToUser) found = true
            node.recycle() 
        }
        return found
    }

    private fun hasVisibleText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isEmpty()) return false
        var found = false
        for (node in nodes) {
            if (!found && node.isVisibleToUser) found = true
            node.recycle()
        }
        return found
    }

    private fun isNodeSelected(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) return false
        var selected = false
        for (node in nodes) {
            if (!selected && node.isSelected) selected = true
            node.recycle()
        }
        return selected
    }

    private fun showSafeZoneShield() {
        try {
            if (invisibleShield != null) return

            invisibleShield = LinearLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            var startY = 0f
            var startX = 0f

            invisibleShield?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.rawX
                        val endY = event.rawY
                        val deltaY = endY - startY
                        val deltaX = endX - startX

                        if (abs(deltaY) > 100 && abs(deltaY) > abs(deltaX)) {
                            removeInvisibleShield()
                            val isSwipingUp = deltaY < 0
                            triggerCleanser(isSwipingUp, shouldAutoScroll = true)
                        } else {
                            removeInvisibleShield()
                            isSimulatingTouch = true 
                            mainHandler.postDelayed({
                                simulateGesture(startX, startY, startX, startY, 50)
                                mainHandler.postDelayed({
                                    isSimulatingTouch = false
                                    val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                                    if (isTargetApp(currentPkg)) checkAppContentState(currentPkg)
                                }, 500)
                            }, 50)
                        }
                    }
                }
                true 
            }

            val safeHeight = (screenHeight * 0.90).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                safeHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager.addView(invisibleShield, params)
        } catch (e: Exception) {}
    }

    // 🔥 FIX: Bulletproof WindowManager View Removal
    private fun removeInvisibleShield() {
        invisibleShield?.let { shield ->
            try {
                windowManager.removeView(shield)
            } catch (e: Exception) {
                // Ignored - The view hasn't fully attached to the screen yet
            }
        }
        invisibleShield = null
    }

    // 🔥 FIX: Bulletproof WindowManager View Removal
    private fun removeDoomOverlay() {
        doomOverlay?.let { overlay ->
            try {
                if (isDoomOverlayShowing) {
                    windowManager.removeView(overlay)
                }
            } catch (e: Exception) {
                // Ignored
            }
        }
        doomOverlay = null
        isDoomOverlayShowing = false
        abandonMute()
    }

    private fun requestMute() {
        if (activeFocusRequest == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION") 
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {}
    }

    private fun abandonMute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(activeFocusRequest!!)
        } else {
            @Suppress("DEPRECATION") 
            audioManager.abandonAudioFocus(null)
        }

        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {}
    }

    private fun triggerCleanser(isSwipingUp: Boolean, shouldAutoScroll: Boolean) {
        if (isCleansing) return
        isCleansing = true
        requestMute()

        val isMacroFriction = currentDelayMs >= 1000

        doomOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212")) 
            setPadding(64, 64, 64, 64)
        }

        val timeText = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        
        val statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#555555")) 
            gravity = Gravity.CENTER
            text = ""
            setPadding(0, 32, 0, 0)
        }

        if (isMacroFriction) {
            doomOverlay?.addView(TextView(this).apply {
                textSize = 20f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                text = "ALL-TIME RECLAIMED"
            })
            doomOverlay?.addView(timeText)
            
            doomOverlay?.addView(statusText)
        }

        doomOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(doomOverlay, doomOverlayParams)
        isDoomOverlayShowing = true

        var timeElapsed = 0L
        val totalTimeAtStart = totalTimeSavedMs
        var isReady = false
        var isTickRunning = true

        var gStartY = 0f
        doomOverlay?.setOnTouchListener { _, event ->
            if (isReady) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> gStartY = event.rawY
                    MotionEvent.ACTION_UP -> {
                        val deltaY = event.rawY - gStartY
                        if (abs(deltaY) > 50) {
                            val nextIsSwipingUp = deltaY < 0
                            isTickRunning = false
                            finishCleanser(nextIsSwipingUp, shouldAutoScroll, timeElapsed)
                        }
                    }
                }
            }
            true
        }

        val tickRunnable = object : Runnable {
            override fun run() {
                if (!isTickRunning || !isCleansing) return

                timeElapsed += 50

                if (isMacroFriction) {
                    val dynamicTotal = totalTimeAtStart + timeElapsed
                    timeText.text = formatTime(dynamicTotal)
                    
                    if (!isReady) {
                        val percent = (timeElapsed.toFloat() / currentDelayMs.toFloat())
                    }
                }

                if (timeElapsed >= currentDelayMs && !isReady) {
                    isReady = true 
                    
                    if (isMacroFriction) {
                        statusText.text = "Ready. Swipe up to continue."
                        statusText.setTextColor(Color.parseColor("#4CAF50"))
                    } else {
                        isTickRunning = false
                        finishCleanser(isSwipingUp, shouldAutoScroll, timeElapsed)
                        return
                    }
                }
                mainHandler.postDelayed(this, 50)
            }
        }
        mainHandler.postDelayed(tickRunnable, 50)
    }

    private fun finishCleanser(isSwipingUp: Boolean, shouldAutoScroll: Boolean, timeElapsed: Long) {
        totalTimeSavedMs += timeElapsed
        currentDelayMs += DELAY_INCREMENT_MS
        prefs.edit().putLong("currentDelayMs", currentDelayMs).putLong("totalTimeSavedMs", totalTimeSavedMs).apply()

        removeDoomOverlay()
        isCleansing = false
        
        if (shouldAutoScroll) {
            isSimulatingTouch = true 
            mainHandler.postDelayed({
                val midX = screenWidth / 2f
                val startY = screenHeight * 0.7f
                val endY = screenHeight * 0.3f

                if (isSwipingUp) simulateGesture(midX, startY, midX, endY, 300) 
                else simulateGesture(midX, endY, midX, startY, 300) 
                
                currentScrollMode = ScrollMode.SAFE 
                mainHandler.postDelayed({
                    isSimulatingTouch = false
                    val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    if (isTargetApp(currentPkg)) checkAppContentState(currentPkg)
                }, 500)
            }, 50)
        } else {
            currentScrollMode = ScrollMode.SAFE 
            mainHandler.postDelayed({
                val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                if (isTargetApp(currentPkg)) checkAppContentState(currentPkg)
            }, 100)
        }
    }

    private fun simulateGesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60
        val remainingSeconds = totalSeconds % 60
        return if (hours > 0) "${hours}h ${remainingMinutes}m ${remainingSeconds}s" else if (totalMinutes > 0) "${totalMinutes}m ${remainingSeconds}s" else "${totalSeconds}s"
    }

    override fun onInterrupt() { 
        stopPolling()
        abandonMute()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        abandonMute()
        mainHandler.removeCallbacksAndMessages(null)
    }
}