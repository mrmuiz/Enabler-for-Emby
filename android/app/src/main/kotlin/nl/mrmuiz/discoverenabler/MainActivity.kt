package nl.mrmuiz.discoverenabler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var group: RadioGroup
    private lateinit var downloadBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var installBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var result: File? = null
    private var busy = false

    private val labels = mapOf(
        "arm64" to R.string.dev_arm64, "armv7" to R.string.dev_armv7, "firetv" to R.string.dev_firetv,
        "x64" to R.string.dev_x64, "x86" to R.string.dev_x86
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { setText(R.string.intro) })
        root.addView(TextView(this).apply {
            setText(R.string.choose_device)
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        group = RadioGroup(this)
        val detected = detectDevice()
        for (b in EmbyDownloads.builds) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = b.id
                setText(labels.getValue(b.id))
                isChecked = b.id == detected
            })
        }
        root.addView(group)
        downloadBtn = Button(this).apply {
            setText(R.string.btn_download)
            setOnClickListener { startDownload() }
        }
        pickBtn = Button(this).apply {
            setText(R.string.btn_pick)
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK
                )
            }
        }
        installBtn = Button(this).apply {
            setText(R.string.btn_install)
            visibility = View.GONE
            setOnClickListener { install() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        status = TextView(this)
        logView = TextView(this).apply { textSize = 12f }
        root.addView(downloadBtn)
        root.addView(pickBtn)
        root.addView(progress)
        root.addView(status)
        root.addView(installBtn)
        root.addView(logView)
        root.addView(TextView(this).apply {
            setText(R.string.note_uninstall)
            textSize = 12f
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun detectDevice(): String {
        if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return "firetv"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return "arm64"
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "armv7"
            "x86_64" -> "x64"
            "x86" -> "x86"
            else -> "arm64"
        }
    }

    private fun selectedBuild(): EmbyBuild {
        val tag = group.findViewById<View>(group.checkedRadioButtonId)?.tag as? String
        return EmbyDownloads.find(tag ?: "arm64")!!
    }

    private fun workDir() = File(cacheDir, "apk").also { it.mkdirs() }

    private fun setBusy(b: Boolean) {
        busy = b
        downloadBtn.isEnabled = !b
        pickBtn.isEnabled = !b
        group.isEnabled = !b
        if (b) {
            installBtn.visibility = View.GONE
            logView.text = ""
        }
        progress.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun startDownload() {
        if (busy) return
        val build = selectedBuild()
        setBusy(true)
        progress.isIndeterminate = false
        worker.execute {
            try {
                val src = File(workDir(), "emby-download.apk")
                EmbyDownloads.download(build, src) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            progress.progress = pct
                            status.text = getString(R.string.st_downloading, build.id, pct)
                        } else {
                            status.text = getString(R.string.st_downloading_unknown, build.id, done / 1_000_000)
                        }
                    }
                }
                patch(src, build.outputName)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (busy) return
            setBusy(true)
            status.setText(R.string.st_copying)
            progress.isIndeterminate = true
            worker.execute {
                val src = File(workDir(), "emby-picked.apk")
                try {
                    contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
                } catch (e: Exception) {
                    fail(e)
                    return@execute
                }
                patch(src, "Emby-Discover.apk")
            }
        } else if (requestCode == REQ_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) install()
        }
    }

    private fun patch(src: File, outName: String) {
        runOnUiThread {
            progress.isIndeterminate = true
            status.setText(R.string.st_patching)
        }
        try {
            val loader = assets.open("discover-web-loader.js").use { it.readBytes() }
            val key = SigningKey.loadOrCreate(File(filesDir, "signing-key.p12"))
            val out = File(workDir(), outName)
            EmbyApkPatcher.patch(src, out, key, loader) { m -> runOnUiThread { logView.append(m + "\n") } }
            src.delete()
            File(out.path + ".idsig").delete()
            result = out
            runOnUiThread {
                setBusy(false)
                status.text = getString(R.string.st_done, out.name)
                installBtn.visibility = View.VISIBLE
                install()
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        runOnUiThread {
            setBusy(false)
            val msg = if (e is NotEmbyApkException) getString(R.string.err_not_emby) else (e.message ?: e.toString())
            status.text = getString(R.string.st_error, msg)
        }
    }

    private fun install() {
        val apk = result ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            status.setText(R.string.st_allow_install)
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                REQ_INSTALL_PERMISSION
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private companion object {
        const val REQ_PICK = 1
        const val REQ_INSTALL_PERMISSION = 2
    }
}
