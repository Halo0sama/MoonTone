package com.halo.moontone.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.halo.moontone.BuildConfig
import com.halo.moontone.MoonToneApp
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.AppPreferences
import com.halo.moontone.ui.theme.MoonToneTheme
import com.halo.moontone.update.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: MoonToneController
    /**
     * Queries GitHub for the latest release and pops a dialog when it is
     * newer than the installed version. Best-effort by design: network
     * failures, rate limits and "no releases yet" all silently no-op.
     */
    var checkingUpdate by mutableStateOf(false)
        private set

    fun checkForAppUpdate(reportResult: Boolean = false) {
        if (checkingUpdate) return
        checkingUpdate = true
        lifecycleScope.launch {
            try {
                val release = UpdateChecker.fetchLatest()
                if (isFinishing || isDestroyed) return@launch
                if (release == null || !UpdateChecker.isNewer(release.tagName, BuildConfig.VERSION_NAME)) {
                    if (reportResult) AlertDialog.Builder(this@MainActivity)
                        .setTitle("检查更新")
                        .setMessage(if (release == null) "暂时无法获取更新信息，请稍后重试。" else "当前已是最新版本。")
                        .setPositiveButton("知道了", null).show()
                    return@launch
                }
                val notes = release.notes.ifBlank { release.title }.take(1200)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 ${release.tagName}")
                    .setMessage(notes + "\n\n当前版本：" + BuildConfig.VERSION_NAME + "\n更新安装会保留配对与设置。")
                    .setPositiveButton("前往下载") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) }
                    .setNegativeButton("以后再说", null).show()
            } finally { checkingUpdate = false }
        }
    }

    var permissionRevision by mutableIntStateOf(0)
        private set

    var localNetworkGranted by mutableStateOf(Build.VERSION.SDK_INT < 37)
        private set

    private fun hasLocalNetworkPermission() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        localNetworkGranted = hasLocalNetworkPermission()
        permissionRevision++
    }

    fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= 37) permLauncher.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    override fun onResume() {
        super.onResume()
        localNetworkGranted = hasLocalNetworkPermission()
        permissionRevision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Non-blocking update check against GitHub Releases; silently no-ops
        // on any failure or when already on the latest version.
        if (AppPreferences.checkUpdatesOnLaunch(this)) checkForAppUpdate()

        // Edge-to-edge (ExpressAssistant style): transparent system bars in theme,
        // content can go under the bottom gesture bar while status bar icons stay visible.
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.isStatusBarContrastEnforced = false
        }

        controller = (application as MoonToneApp).controller
        localNetworkGranted = hasLocalNetworkPermission()
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 37 && !localNetworkGranted) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) permLauncher.launch(permissions.toTypedArray())
        setContent {
            MoonToneTheme {
                MoonToneUI(controller, this, localNetworkGranted)
            }
        }
    }


}
