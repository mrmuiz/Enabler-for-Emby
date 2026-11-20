package com.discover.embypatcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var buildButton: Button
    private lateinit var forceButton: Button
    private lateinit var removeButton: Button
    private lateinit var menuSwitch: Switch
    private lateinit var root: LinearLayout
    private lateinit var lastLogView: TextView
    private lateinit var bannerView: TextView
    private lateinit var tvSwitch: Switch
    private lateinit var tabMain: Button
    private lateinit var tabMore: Button
    private lateinit var pageMain: View
    private lateinit var pageMore: View
    private var showingMore = false
    /** After a tap on an action, the latest log line is also shown on the first tab. */
    private var echoLog = false
    private var embyState = EmbyApp.State(EmbyApp.Kind.NOT_INSTALLED, null)
    private lateinit var workProgress: ProgressBar
    private var workRunning = false
    @Volatile private var installHandedOff = false
    private val workExec = Executors.newSingleThreadExecutor()
    // The update check must not block starting a download.
    private val updateExec = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("emby", MODE_PRIVATE) }
    /**
     * Not every TV or TV stick reports itself as a TV: some boxes have no leanback feature and call
     * themselves a phone. Having no touchscreen is the most reliable extra hint. The switch in the
     * settings overrules this, for the sets that still fall through.
     */
    private val tvDetected by lazy {
        packageManager.hasSystemFeature("android.software.leanback") ||
            packageManager.hasSystemFeature("android.software.leanback_only") ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
    private val isTv by lazy { prefs.getBoolean("tv_mode", tvDetected) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    /** On a TV you sit metres away, so everything is larger. */
    private fun sp(v: Float) = if (isTv) v * 1.5f else v
    /** TVs often wash out dark colours, so the panels and the secondary text get more contrast. */
    private val textDim by lazy { if (isTv) Color.rgb(206, 222, 202) else Color.rgb(174, 193, 169) }
    private val panelFill by lazy { if (isTv) Color.rgb(28, 45, 27) else Color.rgb(25, 39, 25) }
    private val panelEdge by lazy { if (isTv) Color.rgb(74, 112, 70) else Color.rgb(39, 63, 38) }
    private fun buttonBackground(primary: Boolean): StateListDrawable {
        val normal = if (primary) Color.rgb(82, 181, 75) else Color.rgb(39, 63, 38)
        val focused = if (primary) Color.rgb(133, 220, 95) else Color.rgb(67, 111, 58)
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (isTv) 16 else 12).toFloat()
            setStroke(dp(if (isTv) 4 else 1), if (color == focused) Color.WHITE else Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focused))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focused))
            addState(intArrayOf(), shape(normal))
        }
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = if (isTv) 22 else 18
        setPadding(dp(pad), dp(if (isTv) 18 else 15), dp(pad), dp(if (isTv) 18 else 15))
        background = GradientDrawable().apply { setColor(panelFill); cornerRadius = dp(17).toFloat(); setStroke(dp(if (isTv) 2 else 1), panelEdge) }
    }
    private fun tabBackground(selected: Boolean): StateListDrawable {
        val normal = if (selected) Color.rgb(39, 63, 38) else Color.TRANSPARENT
        val focused = Color.rgb(67, 111, 58)
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (isTv) 14 else 11).toFloat()
            setStroke(dp(if (isTv) 4 else 1), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focused, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focused, Color.WHITE))
            addState(intArrayOf(), shape(normal, if (selected) Color.rgb(82, 181, 75) else Color.rgb(39, 63, 38)))
        }
    }
    /** Two tabs: the Emby status and actions, and the activity log with the settings. */
    private fun showTab(more: Boolean) {
        showingMore = more
        pageMain.visibility = if (more) View.GONE else View.VISIBLE
        pageMore.visibility = if (more) View.VISIBLE else View.GONE
        tabMain.background = tabBackground(!more)
        tabMore.background = tabBackground(more)
        tabMain.setTextColor(if (!more) Color.WHITE else Color.rgb(174, 193, 169))
        tabMore.setTextColor(if (more) Color.WHITE else Color.rgb(174, 193, 169))
        tabMain.setTypeface(null, if (!more) Typeface.BOLD else Typeface.NORMAL)
        tabMore.setTypeface(null, if (more) Typeface.BOLD else Typeface.NORMAL)
        if (more) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 18, 10)
        window.navigationBarColor = Color.rgb(10, 18, 10)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // A TV cuts off the edges of the picture (overscan); 5% of the screen is the usual safe margin.
            val sideEdge = if (isTv) (resources.displayMetrics.widthPixels * 0.05f).toInt() else dp(22)
            val topEdge = if (isTv) (resources.displayMetrics.heightPixels * 0.05f).toInt() else dp(20)
            setPadding(sideEdge, topEdge, sideEdge, topEdge)
            setBackgroundColor(Color.rgb(10, 18, 10))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.discover_wordmark)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            contentDescription = "Discover"
        }
        brand.addView(logo, LinearLayout.LayoutParams(-2, dp(if (isTv) 56 else 36)))
        brand.addView(label("Patcher", sp(24f), Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0) })
        root.addView(brand)
        root.addView(label(getString(R.string.tagline), sp(13f), textDim).apply { setPadding(0, dp(5), 0, 0) })

        fun makeTab(text: String) = Button(this).apply {
            id = View.generateViewId()
            this.text = text
            isAllCaps = false
            textSize = sp(14f)
            minHeight = dp(if (isTv) 62 else 42)
            minimumHeight = dp(if (isTv) 62 else 42)
            isFocusable = true
            isFocusableInTouchMode = isTv
            stateListAnimator = null
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        tabMain = makeTab(getString(R.string.tab_main)).apply { setOnClickListener { showTab(false) } }
        tabMore = makeTab(getString(R.string.tab_more)).apply { setOnClickListener { showTab(true) } }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabMain, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabMore, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val mainColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val moreColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Notice about a new version, above the status; hidden until there is one.
        bannerView = label("", sp(14f), Color.rgb(10, 28, 9), true).apply {
            id = View.generateViewId()
            visibility = View.GONE
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(Color.rgb(133, 220, 95)); cornerRadius = dp(14).toFloat() }
            isFocusable = false
            setOnClickListener { openPatcherReleases() }
        }
        mainColumn.addView(bannerView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val statusCard = panel()
        statusCard.addView(label(getString(R.string.status_header), sp(11f), textDim, true))
        statusView = label(getString(R.string.status_checking_device), sp(17f), Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(3)) }
        statusCard.addView(statusView)
        workProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= 21) {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(82, 181, 75))
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(82, 181, 75))
            }
        }
        statusCard.addView(workProgress, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(8) })
        statusCard.addView(label(getString(R.string.status_hint), sp(12f), textDim))
        // The latest line of the activity log (the full log is on the second tab).
        lastLogView = label("", sp(12f), Color.rgb(214, 226, 210)).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        statusCard.addView(lastLogView)
        mainColumn.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val actions = panel()
        actions.addView(label(getString(R.string.actions_header), sp(11f), textDim, true))
        fun makeButton(text: String, primary: Boolean) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = sp(14f)
            setTextColor(if (primary) Color.rgb(10, 28, 9) else Color.WHITE)
            background = buttonBackground(primary)
            minHeight = dp(if (isTv) 76 else 50)
            minWidth = dp(if (isTv) 320 else 0)
            isFocusable = true
            // On phones the first tap should click right away; focus-in-touch-mode
            // made the first tap only give focus.
            isFocusableInTouchMode = isTv
            isClickable = true
            stateListAnimator = null
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        buildButton = makeButton(getString(R.string.btn_install), true).apply {
            id = View.generateViewId()
            setOnClickListener { start(false) }
        }
        forceButton = makeButton(getString(R.string.btn_force), false).apply {
            id = View.generateViewId()
            setOnClickListener { start(true) }
        }
        removeButton = makeButton(getString(R.string.btn_remove), false).apply {
            id = View.generateViewId()
            visibility = View.GONE
            setOnClickListener { uninstallEmby() }
        }
        menuSwitch = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_server_menu)
            textSize = sp(14f)
            setTextColor(Color.WHITE)
            isChecked = adminMenu()
            isFocusable = true
            isFocusableInTouchMode = isTv
            minHeight = dp(if (isTv) 70 else 48)
            setPadding(dp(14), dp(6), dp(10), dp(6))
            background = buttonBackground(false)
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                prefs.edit().putBoolean("admin_menu", on).apply()
                log("Server menu in the app: " + (if (on) "on" else "off") + " (applies from the next build).")
                updateExec.execute { checkForEmbyUpdate() }
            }
        }
        val menuHint = label(getString(R.string.switch_server_menu_hint), sp(12f), textDim).apply { setPadding(dp(4), dp(4), 0, 0) }
        // Lets someone switch on the TV layout by hand when their set is not recognised as a TV.
        tvSwitch = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_tv_mode)
            textSize = sp(14f)
            setTextColor(Color.WHITE)
            isChecked = isTv
            isFocusable = true
            isFocusableInTouchMode = isTv
            minHeight = dp(if (isTv) 70 else 48)
            setPadding(dp(14), dp(6), dp(10), dp(6))
            background = buttonBackground(false)
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                prefs.edit().putBoolean("tv_mode", on).apply()
                log("TV mode: " + (if (on) "on" else "off") + " — restarting the screen.")
                recreate()
            }
        }
        val tvHint = label(getString(R.string.switch_tv_mode_hint), sp(12f), textDim).apply { setPadding(dp(4), dp(4), 0, 0) }
        // Give the buttons enough height on TV and fix the D-pad order.
        tabMain.nextFocusRightId = tabMore.id
        tabMore.nextFocusLeftId = tabMain.id
        tabMain.nextFocusDownId = buildButton.id
        tabMore.nextFocusDownId = menuSwitch.id
        removeButton.nextFocusUpId = tabMain.id
        removeButton.nextFocusDownId = buildButton.id
        buildButton.nextFocusUpId = tabMain.id
        buildButton.nextFocusDownId = forceButton.id
        forceButton.nextFocusUpId = buildButton.id
        forceButton.nextFocusDownId = forceButton.id
        menuSwitch.nextFocusUpId = tabMore.id
        menuSwitch.nextFocusDownId = tvSwitch.id
        tvSwitch.nextFocusUpId = menuSwitch.id
        val actionButtonHeight = dp(if (isTv) 76 else 50)
        actions.addView(removeButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(10) })
        actions.addView(buildButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(10) })
        actions.addView(forceButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(8) })
        mainColumn.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })

        val settings = panel()
        settings.addView(label(getString(R.string.settings_header), sp(11f), textDim, true))
        settings.addView(menuSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        settings.addView(menuHint)
        settings.addView(tvSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        settings.addView(tvHint)
        moreColumn.addView(settings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val logCard = panel()
        logCard.addView(label(getString(R.string.log_header), sp(11f), textDim, true))
        logView = label("", if (isTv) 17f else 13f, Color.rgb(240, 245, 238)).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(!isTv); if (isTv) { isFocusable = false; isFocusableInTouchMode = false }; setLineSpacing(dp(4).toFloat(), 1.0f); includeFontPadding = true; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        // On a TV the log itself takes the focus, so the remote can scroll through it.
        scroll = ScrollView(this).apply {
            id = View.generateViewId()
            isFillViewport = true
            if (isTv) {
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                background = buttonBackground(false)
                nextFocusUpId = tvSwitch.id
            }
            addView(logView)
        }
        if (isTv) tvSwitch.nextFocusDownId = scroll.id
        logCard.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(9) })
        moreColumn.addView(logCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(11); weight = 1f })
        // The first tab scrolls when the screen is small (phone in landscape).
        pageMain = ScrollView(this).apply { isFillViewport = true; addView(mainColumn, ViewGroup.LayoutParams(-1, -2)) }
        pageMore = moreColumn
        root.addView(pageMain, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pageMore, LinearLayout.LayoutParams(-1, 0, 1f))
        showTab(false)
        setContentView(root)
        // D-pad users land on the primary action immediately; focus is clearly visible.
        buildButton.post { ensureFocus() }
        if (isTv) log("TV mode: remote control (D-pad) navigation enabled.")

        Installer.sink = { status, english ->
            log(english)
            runOnUiThread {
                statusView.text = status
                workProgress.visibility = View.GONE
                workProgress.isIndeterminate = false
            }
        }
        Installer.installedListener = { runOnUiThread { showInstalledPopup() } }
        prefs.getString("last_error", null)?.let { log("Previous install error: $it"); prefs.edit().remove("last_error").apply() }
        log("${PatchSource.collect(this).size} file(s) in the patch set.")
        log("Extra files folder: ${PatchSource.externalPatchDir(this)}")
        scheduleDailyUpdateCheck()
        updateExec.execute { checkForPatcherUpdate() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2402)
        }
    }

    override fun onResume() {
        super.onResume()
        // Show the real state again after returning from "Uninstall" or the Android installer.
        updateExec.execute { checkForEmbyUpdate() }
    }

    override fun onDestroy() {
        if (Installer.installedListener != null) Installer.installedListener = null
        super.onDestroy()
    }

    /** D-pad: always keep the focus on a visible control (a TV shows no cursor, so lost focus = dead remote). */
    private fun ensureFocus() {
        val f = currentFocus
        val ok = f != null && f.isShown && (f === bannerView || f === removeButton || f === buildButton || f === forceButton || f === menuSwitch || f === tvSwitch || f === scroll || f === tabMain || f === tabMore)
        if (ok) return
        val target = when {
            showingMore -> menuSwitch
            removeButton.visibility == View.VISIBLE -> removeButton
            buildButton.isEnabled -> buildButton
            else -> tabMore
        }
        target.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) root.post { ensureFocus() }
    }

    private fun adminMenu() = prefs.getBoolean("admin_menu", true)
    private fun menuFlag(on: Boolean = adminMenu()) = if (on) "1" else "0"
    /** Marker of the last installed build: sha#patcherVersion#beta#menu=1 (older builds: menu always on). */
    private fun builtMenuFlag(marker: String?) = marker?.substringAfter("#menu=", "1")?.take(1) ?: "1"

    private fun uninstallEmby() {
        echoLog = true
        log("Android will ask to uninstall Emby. After that you can install the Discover version.")
        try { startActivity(EmbyApp.uninstallIntent()) }
        catch (e: Exception) {
            try { startActivity(EmbyApp.appDetailsIntent()); log("Choose 'Uninstall' there.") }
            catch (_: Exception) { log("Uninstall Emby via Settings > Apps, or via adb: adb uninstall ${EmbyApp.PACKAGE}") }
        }
    }

    private fun showInstalledPopup() {
        if (isFinishing || isDestroyed) return
        val launch = EmbyApp.launchIntent(this)
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.popup_installed_title))
            .setMessage(EmbyApp.state(this).versionName?.let { getString(R.string.popup_installed_text_version, it) } ?: getString(R.string.popup_installed_text))
            .setNegativeButton(getString(R.string.popup_close), null)
        EmbyApp.icon(this)?.let { b.setIcon(it) }
        if (launch != null) b.setPositiveButton(getString(R.string.popup_open_emby)) { _, _ -> try { startActivity(launch) } catch (_: Exception) { } }
        b.show().getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        updateExec.execute { checkForEmbyUpdate() }
    }

    /** Opens the download page of Discover Patcher; on a TV there is often no browser, so say so in the log. */
    private fun openPatcherReleases() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PATCHER_RELEASES))) }
        catch (_: Exception) { log("No browser on this device. Download it on a phone or computer: ${Updater.PATCHER_RELEASES}") }
    }

    /** Is there a newer Discover Patcher on GitHub? Shown as a banner above the status. */
    private fun checkForPatcherUpdate() {
        val latest = Updater.latestPatcher() ?: return
        val mine = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (_: Exception) { return }
        if (latest.versionCode <= mine) { newPatcherVersion = null; runOnUiThread { showBanner() }; return }
        newPatcherVersion = latest.versionName.ifEmpty { latest.versionCode.toString() }
        log("New Discover Patcher available: ${newPatcherVersion} (you have ${packageManager.getPackageInfo(packageName, 0).versionName}).")
        runOnUiThread { showBanner() }
    }

    /** One line above the status: a new Patcher comes first, otherwise a new Emby. */
    private fun showBanner() {
        val patcher = newPatcherVersion
        val text = when {
            patcher != null -> getString(R.string.banner_patcher_update, patcher) + " — " + getString(R.string.banner_patcher_update_action)
            embyUpdateReady -> getString(R.string.banner_emby_update)
            else -> null
        }
        bannerView.text = text ?: ""
        bannerView.visibility = if (text == null) View.GONE else View.VISIBLE
        bannerView.isClickable = patcher != null
        bannerView.isFocusable = patcher != null
        bannerView.isFocusableInTouchMode = patcher != null && isTv
        if (text != null && patcher != null) {
            bannerView.nextFocusDownId = if (removeButton.visibility == View.VISIBLE) removeButton.id else buildButton.id
            tabMain.nextFocusDownId = bannerView.id
        } else {
            tabMain.nextFocusDownId = buildButton.id
        }
    }

    private fun scheduleDailyUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<EmbyUpdateWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-emby-update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        log("Daily check for new Emby versions enabled.")
    }

    private fun showProgress(value: Int, message: String) {
        runOnUiThread {
            workProgress.isIndeterminate = false
            workProgress.progress = value.coerceIn(0, 100)
            statusView.text = message
        }
    }

    @Volatile private var lastStatusLog: String? = null
    @Volatile private var lastCheckError: String? = null
    /** Version name of a newer Discover Patcher on GitHub, or null. */
    @Volatile private var newPatcherVersion: String? = null
    @Volatile private var embyUpdateReady = false

    private fun checkForEmbyUpdate() {
        val state = EmbyApp.state(this)
        embyState = state
        val v = state.versionName ?: "?"
        embyUpdateReady = false
        // First what is on the device; the online check only makes sense for a patched Emby.
        val message = when (state.kind) {
            EmbyApp.Kind.NOT_INSTALLED -> getString(R.string.state_not_installed)
            EmbyApp.Kind.FOREIGN -> getString(R.string.state_foreign, v)
            EmbyApp.Kind.PATCHED -> {
                if (!workRunning) runOnUiThread { statusView.text = getString(R.string.state_patched_checking, v) }
                val installed = prefs.getString("sha", null)
                if (installed == null) getString(R.string.state_patched_unknown, v)
                else {
                    val check = Updater.check(Updater.apkName(), installed)
                    check.newMarker?.let { prefs.edit().putString("sha", it).apply() }
                    if (check.current == null) {
                        val why = Updater.lastError
                        if (why != null && why != lastCheckError) { lastCheckError = why; log("Update check failed: $why") }
                    } else lastCheckError = null
                    when {
                        check.latest == null -> getString(R.string.state_patched_check_failed, v)
                        // Online id known, but the installed build (older patcher version) cannot be compared with it.
                        check.current == null -> getString(R.string.state_patched_unknown, v)
                        check.current == false -> { embyUpdateReady = true; getString(R.string.state_update_available, v) }
                        builtMenuFlag(installed) != menuFlag() -> getString(R.string.state_menu_changed)
                        else -> getString(R.string.state_up_to_date, v)
                    }
                }
            }
        }
        runOnUiThread {
            if (!workRunning) {
                statusView.text = message
                buildButton.text = buildLabel()
            }
            removeButton.visibility = if (state.kind == EmbyApp.Kind.FOREIGN) View.VISIBLE else View.GONE
            showBanner()
            if (state.kind == EmbyApp.Kind.FOREIGN && !workRunning && !showingMore) removeButton.post { removeButton.requestFocus() }
            else root.post { ensureFocus() }
        }
        if (message != lastStatusLog) {
            lastStatusLog = message
            log("Status: " + statusLogText(state, message))
        }
    }

    /** The activity log stays in English (easier for support); the status line follows the phone language. */
    private fun statusLogText(state: EmbyApp.State, localized: String): String {
        val v = state.versionName ?: "?"
        return when (state.kind) {
            EmbyApp.Kind.NOT_INSTALLED -> "Emby is not installed yet"
            EmbyApp.Kind.FOREIGN -> "Emby $v installed with a different signature (official app or another patch)"
            EmbyApp.Kind.PATCHED -> "patched Emby $v installed — $localized"
        }
    }

    private fun buildLabel() = when (embyState.kind) {
        EmbyApp.Kind.PATCHED -> getString(R.string.btn_update)
        else -> getString(R.string.btn_install)
    }

    private fun start(force: Boolean) {
        if (workRunning) return
        echoLog = true
        val state = EmbyApp.state(this)
        embyState = state
        if (state.kind == EmbyApp.Kind.FOREIGN) {
            log("An Emby app with a different signature is installed. Android cannot update it to the patched version.")
            log("Uninstall the existing Emby app first (you will need to sign in again; your server and library are not changed).")
            removeButton.visibility = View.VISIBLE
            showTab(false)
            removeButton.post { removeButton.requestFocus() }
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))); log("Allow 'Install unknown apps' and try again.") }
            catch (e: ActivityNotFoundException) { log("Grant permission via adb: adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow") }
            return
        }
        workRunning = true
        installHandedOff = false
        buildButton.isEnabled = false; forceButton.isEnabled = false
        buildButton.text = getString(R.string.btn_working)
        forceButton.text = getString(R.string.btn_wait)
        buildButton.alpha = 0.72f; forceButton.alpha = 0.55f
        workProgress.progress = 0
        workProgress.isIndeterminate = false
        workProgress.visibility = View.VISIBLE
        statusView.text = getString(R.string.step_preparing)
        log("Task started. Progress is shown below.")
        workExec.execute {
            try { work(force) }
            catch (e: Throwable) { log("ERROR: ${e.message ?: e.javaClass.simpleName}") }
            finally {
                runOnUiThread {
                    workRunning = false
                    buildButton.isEnabled = true; forceButton.isEnabled = true
                    buildButton.text = buildLabel()
                    forceButton.text = getString(R.string.btn_force)
                    ensureFocus()
                    buildButton.alpha = 1f; forceButton.alpha = 1f
                    if (!installHandedOff) {
                        workProgress.visibility = View.GONE
                        workProgress.progress = 0
                        statusView.text = getString(R.string.step_done_see_log)
                    }
                }
            }
        }
    }

    private fun work(force: Boolean) {
        val name = Updater.apkName()
        log("Download channel: latest official beta; variant: $name")
        val myVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val menuOn = adminMenu()
        val sha = Updater.latestId(name); val marker = sha?.let { "$it#$myVersion#beta#menu=${menuFlag(menuOn)}" }
        val patchedInstalled = EmbyApp.state(this).kind == EmbyApp.Kind.PATCHED
        if (marker == null) log("Could not check the version (${Updater.lastError ?: "unknown reason"}), downloading anyway.")
        else if (!force && patchedInstalled && marker == prefs.getString("sha", null)) { log("You already have the latest version. Use 'Rebuild (force)' to reinstall anyway."); return }
        log("Server menu in the app: " + if (menuOn) "on" else "off")
        val orig = File(cacheDir, "orig.apk"); val patched = File(cacheDir, "patched.apk"); val signed = File(cacheDir, "signed.apk")
        listOf(orig, patched, signed).forEach { it.delete() }
        showProgress(0, getString(R.string.step_downloading, 0))
        log("Downloading…")
        Updater.download(Updater.downloadUrl(name), orig) { percent -> showProgress(percent, getString(R.string.step_downloading, percent)) }
        log("Download complete: ${orig.length() / 1024 / 1024} MB")
        runOnUiThread { workProgress.isIndeterminate = true; statusView.text = getString(R.string.step_verifying) }
        log("Verifying download: reading APK structure and version…")
        val info = packageManager.getPackageArchiveInfo(orig.path, 0) ?: error("Downloaded file is not a valid APK")
        log("Emby-versie: ${info.versionName}"); logDiagnostics(orig)
        runOnUiThread { workProgress.isIndeterminate = true; statusView.text = getString(R.string.step_patching) }
        log("Patching…")
        val files = LinkedHashMap(PatchSource.collect(this))
        val options = "window.__discoverAdminMenu = ${menuOn};\n"
        files[OPTIONS_FILE] = { options.byteInputStream(Charsets.UTF_8) }
        val snippet = "<script src=\"modules/discover-options.js\"></script>\n" + PatchSource.snippet(this)
        Patcher.patch(orig, patched, files, snippet) { log(it) }
        runOnUiThread { workProgress.isIndeterminate = true; statusView.text = getString(R.string.step_signing) }
        log("Patching complete. Signing APK…")
        Signer.sign(this, patched, signed, info.applicationInfo?.minSdkVersion ?: 21)
        runOnUiThread { workProgress.isIndeterminate = true; statusView.text = getString(R.string.step_preparing_install) }
        log("Signing complete. Preparing installation…")
        val bad = Patcher.misaligned(signed)
        if (bad.isEmpty()) log("Alignment after signing: OK") else { log("Alignment after signing FAILED (${bad.size}):"); bad.take(6).forEach { log("  $it") } }
        orig.delete(); patched.delete(); if (marker != null) prefs.edit().putString("pending_sha", marker).apply()
        runOnUiThread { workProgress.isIndeterminate = true; installHandedOff = true; statusView.text = getString(R.string.step_installing) }
        log("Sending install session to Android. The Android install confirmation may appear…")
        Installer.install(this, signed)
    }

    private fun logDiagnostics(apk: File) {
        log("Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"); log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val abis = try { java.util.zip.ZipFile(apk).use { zf -> zf.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSortedSet() } } catch (e: Exception) { emptySet<String>() }
        log("ABIs in the Emby APK: ${abis.joinToString().ifEmpty { "none" }}")
        val freeMb = cacheDir.usableSpace / 1024 / 1024; val needMb = apk.length() * 3 / 1024 / 1024
        log("Free storage: $freeMb MB (needed, rough estimate: $needMb MB)")
        if (freeMb < needMb) log("WARNING: low storage; installation may fail with 'Failed to extract native libs'.")
    }

    companion object { private const val OPTIONS_FILE = "assets/www/modules/discover-options.js" }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            if (echoLog) { lastLogView.text = msg.trim(); lastLogView.visibility = View.VISIBLE }
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
