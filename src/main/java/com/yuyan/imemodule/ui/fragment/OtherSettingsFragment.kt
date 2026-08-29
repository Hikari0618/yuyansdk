package com.yuyan.imemodule.ui.fragment

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.activity.LauncherActivity
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.inputmethod.util.RimeDeployUtils
import com.yuyan.inputmethod.util.RimeSyncScheduler
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon

private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val componentName = ComponentName(Launcher.instance.context.packageName, LauncherActivity::class.java.name)
    Launcher.instance.context.packageManager.setComponentEnabledSetting(componentName, if(value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
}

class OtherSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().other){

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 10-: 请求 WRITE_EXTERNAL_STORAGE
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    doSync()
                } else {
                    Toast.makeText(requireContext(), "需要存储权限才能同步", Toast.LENGTH_SHORT).show()
                }
            }

        // Android 11+: 请求 MANAGE_EXTERNAL_STORAGE
        manageStorageLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    doSync()
                } else {
                    Toast.makeText(requireContext(), "需要所有文件访问权限才能同步", Toast.LENGTH_SHORT).show()
                }
            }

        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".zip")) {
                            ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                            return@withContext
                        }
                        try {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(400L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(R.string.export_user_data) {
            lifecycleScope.launch {
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        }
        screen.addPreference(R.string.import_user_data) {
            AlertDialog.Builder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.import_user_data)
                .setMessage(R.string.confirm_import_user_data)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importLauncher.launch("application/zip")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        // Rime 重新部署（方案更新后重新编译）
        screen.addPreference("⚙️ 重新部署", "方案更新后点击，重新编译方案文件") {
            AlertDialog.Builder(ctx)
                .setTitle("重新部署")
                .setMessage("将重新编译方案文件，部署期间输入法暂时不可用。")
                .setPositiveButton("开始部署") { _, _ ->
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            RimeDeployUtils.deploy()
                        }
                        Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        // Rime 原生同步
        screen.addPreference("🔄 同步用户数据", "上次: ${RimeSyncScheduler.getLastSyncTimeStr(ctx)}") {
            checkPermissionAndSync()
        }
        // 自动同步间隔
        val currentInterval = RimeSyncScheduler.getInterval(ctx)
        val intervalLabel = if (currentInterval > 0) "当前: ${currentInterval} 分钟" else "当前: 关闭"
        screen.addPreference("⏱️ 自动同步间隔", "$intervalLabel | 上次: ${RimeSyncScheduler.getLastSyncTimeStr(ctx)}") {
            val options = arrayOf("关闭", "5 分钟", "15 分钟", "30 分钟", "60 分钟")
            val values = intArrayOf(0, 5, 15, 30, 60)
            AlertDialog.Builder(ctx)
                .setTitle("自动同步间隔")
                .setItems(options) { _, which ->
                    if (values[which] > 0) {
                        // 开启自动同步前先检查权限
                        checkPermissionAndSync()
                    }
                    RimeSyncScheduler.setInterval(ctx, values[which])
                    Toast.makeText(ctx, "已设为 ${options[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun checkPermissionAndSync() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 需要 MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                doSync()
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("需要存储权限")
                    .setMessage("Rime 同步需要写入 /sdcard/rime/sync/ 目录，请授予「所有文件访问」权限。")
                    .setPositiveButton("去授权") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            // Android 10-: 需要 WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                doSync()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun doSync() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RimeSyncScheduler.doSyncNow()
            }
            Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show()
        }
    }
}
