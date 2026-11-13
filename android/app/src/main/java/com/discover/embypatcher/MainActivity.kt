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
    private var embyState = EmbyApp.State(EmbyApp.Kind.NOT_INSTALLED, null)
    private lateinit var workProgress: ProgressBar
    private var workRunning = false
    @Volatile private var installHandedOff = false
    private val workExec = Executors.newSingleThreadExecutor()
    // The update check must not block starting a download.
    private val updateExec = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("emby", MODE_PRIVATE) }
    private val isTv by lazy {
        packageManager.hasSystemFeature("android.software.leanback") ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun buttonBackground(primary: Boolean): StateListDrawable {
        val normal = if (primary) Color.rgb(82, 181, 75) else Color.rgb(39, 63, 38)
        val focused = if (primary) Color.rgb(133, 220, 95) else Color.rgb(67, 111, 58)
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (isTv) 16 else 12).toFloat()
            setStroke(dp(if (isTv) 3 else 1), if (color == focused) Color.WHITE else Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focused))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focused))
            addState(intArrayOf(), shape(normal))
        }
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(15), dp(18), dp(15))
        background = GradientDrawable().apply { setColor(Color.rgb(25, 39, 25)); cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.rgb(39, 63, 38)) }
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 18, 10)
        window.navigationBarColor = Color.rgb(10, 18, 10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val edge = if (isTv) 48 else 22
            setPadding(dp(edge), dp(if (isTv) 28 else 20), dp(edge), dp(if (isTv) 26 else 18))
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
        brand.addView(logo, LinearLayout.LayoutParams(-2, dp(if (isTv) 44 else 36)))
        brand.addView(label("Patcher", if (isTv) 28f else 24f, Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0) })
        root.addView(brand)
        root.addView(label(getString(R.string.tagline), 13f, Color.rgb(174, 193, 169)).apply { setPadding(0, dp(5), 0, 0) })

        val statusCard = panel()
        statusCard.addView(label(getString(R.string.status_header), 11f, Color.rgb(174, 193, 169), true))
        statusView = label(getString(R.string.status_checking_device), 17f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(3)) }
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
        statusCard.addView(label(getString(R.string.status_hint), 12f, Color.rgb(174, 193, 169)))
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        val actions = panel()
        actions.addView(label(getString(R.string.actions_header), 11f, Color.rgb(174, 193, 169), true))
        fun makeButton(text: String, primary: Boolean) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = if (isTv) 19f else 14f
            setTextColor(if (primary) Color.rgb(10, 28, 9) else Color.WHITE)
            background = buttonBackground(primary)
            minHeight = dp(if (isTv) 68 else 50)
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
            textSize = if (isTv) 17f else 14f
            setTextColor(Color.WHITE)
            isChecked = adminMenu()
            isFocusable = true
            isFocusableInTouchMode = isTv
            minHeight = dp(if (isTv) 60 else 48)
            setPadding(dp(14), dp(6), dp(10), dp(6))
            background = buttonBackground(false)
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                prefs.edit().putBoolean("admin_menu", on).apply()
                log("Server menu in the app: " + (if (on) "on" else "off") + " (applies from the next build).")
                updateExec.execute { checkForEmbyUpdate() }
            }
        }
        val menuHint = label(getString(R.string.switch_server_menu_hint), 12f, Color.rgb(174, 193, 169)).apply { setPadding(dp(4), dp(4), 0, 0) }
        // Give the buttons enough height on TV and fix the D-pad order.
        removeButton.nextFocusDownId = buildButton.id
        buildButton.nextFocusDownId = forceButton.id
        forceButton.nextFocusUpId = buildButton.id
        forceButton.nextFocusDownId = menuSwitch.id
        menuSwitch.nextFocusUpId = forceButton.id
        val actionButtonHeight = dp(if (isTv) 68 else 50)
        actions.addView(removeButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(10) })
        actions.addView(buildButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(10) })
        actions.addView(forceButton, LinearLayout.LayoutParams(-1, actionButtonHeight).apply { topMargin = dp(8) })
        actions.addView(menuSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        actions.addView(menuHint)
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })

        val logCard = panel()
        logCard.addView(label(getString(R.string.log_header), 11f, Color.rgb(174, 193, 169), true))
        logView = label("", if (isTv) 15f else 13f, Color.rgb(240, 245, 238)).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1.0f); includeFontPadding = true; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(logView) }
        logCard.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(9) })
        root.addView(logCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(11); weight = 1f })
        setContentView(root)
        // D-pad users land on the primary action immediately; focus is clearly visible.
        buildButton.post { buildButton.requestFocus() }
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

    private fun adminMenu() = prefs.getBoolean("admin_menu", true)
    private fun menuFlag(on: Boolean = adminMenu()) = if (on) "1" else "0"
    /** Marker of the last installed build: sha#patcherVersion#beta#menu=1 (older builds: menu always on). */
    private fun builtMenuFlag(marker: String?) = marker?.substringAfter("#menu=", "1")?.take(1) ?: "1"

    private fun uninstallEmby() {
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

    private fun checkForEmbyUpdate() {
        val state = EmbyApp.state(this)
        embyState = state
        val v = state.versionName ?: "?"
        // First what is on the device; the online check only makes sense for a patched Emby.
        val message = when (state.kind) {
            EmbyApp.Kind.NOT_INSTALLED -> getString(R.string.state_not_installed)
            EmbyApp.Kind.FOREIGN -> getString(R.string.state_foreign, v)
            EmbyApp.Kind.PATCHED -> {
                if (!workRunning) runOnUiThread { statusView.text = getString(R.string.state_patched_checking, v) }
                val latest = Updater.latestSha(Updater.apkName())
                val installed = prefs.getString("sha", null)
                when {
                    latest == null -> getString(R.string.state_patched_check_failed, v)
                    installed == null -> getString(R.string.state_patched_unknown, v)
                    latest != installed.substringBefore("#") -> getString(R.string.state_update_available, v)
                    builtMenuFlag(installed) != menuFlag() -> getString(R.string.state_menu_changed)
                    else -> getString(R.string.state_up_to_date, v)
                }
            }
        }
        runOnUiThread {
            if (!workRunning) {
                statusView.text = message
                buildButton.text = buildLabel()
            }
            removeButton.visibility = if (state.kind == EmbyApp.Kind.FOREIGN) View.VISIBLE else View.GONE
            if (state.kind == EmbyApp.Kind.FOREIGN && !workRunning) removeButton.requestFocus()
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
        val state = EmbyApp.state(this)
        embyState = state
        if (state.kind == EmbyApp.Kind.FOREIGN) {
            log("An Emby app with a different signature is installed. Android cannot update it to the patched version.")
            log("Uninstall the existing Emby app first (you will need to sign in again; your server and library are not changed).")
            removeButton.visibility = View.VISIBLE
            removeButton.requestFocus()
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
        val sha = Updater.latestSha(name); val marker = sha?.let { "$it#$myVersion#beta#menu=${menuFlag(menuOn)}" }
        val patchedInstalled = EmbyApp.state(this).kind == EmbyApp.Kind.PATCHED
        if (marker == null) log("Could not check the version, downloading anyway.")
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

    private fun log(msg: String) { runOnUiThread { logView.append(msg + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } } }
}
