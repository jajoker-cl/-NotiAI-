package com.donotnotify.donotnotify.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object OemAutostart {
    private const val TAG = "OemAutostart"

    enum class Vendor { XIAOMI, HUAWEI, OPPO, ONEPLUS, VIVO, SAMSUNG, ASUS, LETV, MEIZU, NOKIA }

    fun currentVendor(): Vendor? {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfr.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Vendor.XIAOMI
            mfr.contains("huawei") || mfr.contains("honor") -> Vendor.HUAWEI
            mfr.contains("oppo") -> Vendor.OPPO
            mfr.contains("oneplus") -> Vendor.ONEPLUS
            mfr.contains("vivo") -> Vendor.VIVO
            mfr.contains("samsung") -> Vendor.SAMSUNG
            mfr.contains("asus") -> Vendor.ASUS
            mfr.contains("letv") -> Vendor.LETV
            mfr.contains("meizu") -> Vendor.MEIZU
            mfr.contains("nokia") -> Vendor.NOKIA
            else -> null
        }
    }

    fun applies(): Boolean = currentVendor() != null

    private fun candidateIntents(vendor: Vendor): List<Intent> {
        val components = when (vendor) {
            Vendor.XIAOMI -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
            )
            Vendor.HUAWEI -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            )
            Vendor.OPPO -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            Vendor.ONEPLUS -> listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            Vendor.VIVO -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
            )
            Vendor.SAMSUNG -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            Vendor.ASUS -> listOf(
                "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
                "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            )
            Vendor.LETV -> listOf(
                "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            )
            Vendor.MEIZU -> listOf(
                "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
            )
            Vendor.NOKIA -> listOf(
                "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
            )
        }
        return components.map { (pkg, cls) ->
            Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun tryLaunchAutostart(context: Context): Boolean {
        val vendor = currentVendor() ?: return false
        for (intent in candidateIntents(vendor)) {
            try {
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch ${intent.component}", e)
            }
        }
        return false
    }
}
