package com.saihgupr.androidrelay

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat

/**
 * 后台保活引导的纯逻辑层(做法参考 Gotify:检测"忽略电池优化" + 引导用户逐项完成设置)。
 *
 * 三个部分:
 * - [RomFlavor]:按厂商识别 ROM,携带该 ROM 的"自启动/后台活动"与"多任务加锁"指引
 *   文案,以及自启动设置页的候选深链入口(国产 ROM 各版本入口经常变动,按序尝试、
 *   全部失效回退应用信息页);
 * - [KeepAliveCheck] + [KeepAliveStep]:四项保活自检的聚合与引导顺序。
 *   四项为:电池优化白名单、自启动/后台活动、多任务加锁、常驻通知。
 *   前后两端(电池/通知)可程序化实时检测;中间两项系统没有可查询的 API,
 *   由用户勾选确认(见 [KeepAliveChecks]);
 * - [KeepAliveChecks] / [KeepAliveIntents]:依赖 Context 的检测读写与页面跳转。
 *
 * 除 [KeepAliveChecks] / [KeepAliveIntents] 外不触碰运行时 Android 环境
 * (R 资源 ID 为编译期常量),核心识别与聚合逻辑可在 JVM 单测覆盖。
 */
enum class RomFlavor(
    /** 展示给用户的 ROM 名称,用于"当前系统:%s"行。 */
    val label: String,
    /** 是否存在"自启动/后台活动"类白名单(原生系统无,该项自动视为完成)。 */
    val needsAutostart: Boolean,
    /** 多任务界面是否支持"锁定/保护"卡片(多数原生系统不支持,该行隐藏)。 */
    val needsLockHint: Boolean,
    /** 自启动/后台活动的操作指引(卡内说明与引导弹窗共用同一文案)。 */
    @StringRes val autostartStepsRes: Int,
    /** 多任务加锁的操作指引。 */
    @StringRes val lockStepsRes: Int,
    /**
     * 自启动/后台活动设置页的候选入口,格式 "包名/Activity 全名",按序尝试;
     * 部分页面(如 MIUI 省电策略)依赖 `package_name`/`package_label` extra 定位到本应用。
     */
    val autostartEntries: List<String>,
) {
    XIAOMI(
        "小米 MIUI / 澎湃OS", true, true,
        R.string.ka_autostart_desc_miui, R.string.ka_lock_desc_miui,
        listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 省电策略页(需 package_name extra),自启动入口被裁剪时兜底
            "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        ),
    ),
    HUAWEI(
        "华为 EMUI / HarmonyOS", true, true,
        R.string.ka_autostart_desc_huawei, R.string.ka_lock_desc_huawei,
        listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    ),
    // 荣耀独立成体系后仍复用华为 systemManager 的入口,仅文案与名称不同
    HONOR(
        "荣耀 MagicOS", true, true,
        R.string.ka_autostart_desc_huawei, R.string.ka_lock_desc_huawei,
        listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
    ),
    OPPO(
        "OPPO / 一加 / realme (ColorOS)", true, true,
        R.string.ka_autostart_desc_oppo, R.string.ka_lock_desc_oppo,
        listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
    ),
    VIVO(
        "vivo / iQOO (OriginOS / Funtouch)", true, true,
        R.string.ka_autostart_desc_vivo, R.string.ka_lock_desc_vivo,
        listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
    ),
    SAMSUNG(
        "三星 One UI", true, true,
        R.string.ka_autostart_desc_samsung, R.string.ka_lock_desc_samsung,
        listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
    ),
    MEIZU(
        "魅族 Flyme", true, true,
        R.string.ka_autostart_desc_meizu, R.string.ka_lock_desc_meizu,
        listOf(
            "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
            "com.meizu.safe/com.meizu.safe.permission.PermissionMainActivity",
        ),
    ),
    STOCK(
        "原生 Android", false, false,
        R.string.ka_autostart_desc_stock, R.string.ka_lock_desc_stock,
        emptyList(),
    ),

    ;

    companion object {

        /**
         * 按厂商/品牌识别 ROM(忽略大小写,子串匹配以覆盖 Redmi/POCO/iQOO 等子品牌)。
         * 识别顺序上荣耀先于华为(新机型两者都可能出现);未识别的厂商一律按原生
         * 处理,只保留"电池优化 + 常驻通知"两项可检测的引导。
         */
        fun detect(manufacturer: String?, brand: String? = null): RomFlavor {
            val m = (manufacturer ?: "").lowercase()
            val b = (brand ?: "").lowercase()
            fun hit(vararg keys: String): Boolean = keys.any { it in m || it in b }
            return when {
                hit("xiaomi", "redmi", "poco") -> XIAOMI
                hit("honor") -> HONOR
                hit("huawei") -> HUAWEI
                hit("oppo", "realme", "oneplus") -> OPPO
                hit("vivo", "iqoo") -> VIVO
                hit("samsung") -> SAMSUNG
                hit("meizu") -> MEIZU
                else -> STOCK
            }
        }
    }
}

/** 引导弹窗(配对成功后)的步骤顺序:先系统级权限,最后常驻通知的"别关闭"提醒。 */
enum class KeepAliveStep {
    BATTERY,
    AUTOSTART,
    LOCK_RECENTS,
    NOTIFICATION,
}

/**
 * 四项保活自检的聚合结果;两项可实时检测(电池/通知),两项读用户确认(自启动/加锁)。
 * [pending] 即尚未完成的步骤列表,配对后的引导弹窗按它逐项弹出。
 */
data class KeepAliveCheck(
    val batteryOk: Boolean,
    val notificationOk: Boolean,
    val autostartOk: Boolean,
    val recentsOk: Boolean,
) {

    val allDone: Boolean
        get() = batteryOk && notificationOk && autostartOk && recentsOk

    /** 未完成项,按引导顺序(电池 → 自启动 → 加锁 → 通知)排列。 */
    fun pending(): List<KeepAliveStep> = buildList {
        if (!batteryOk) add(KeepAliveStep.BATTERY)
        if (!autostartOk) add(KeepAliveStep.AUTOSTART)
        if (!recentsOk) add(KeepAliveStep.LOCK_RECENTS)
        if (!notificationOk) add(KeepAliveStep.NOTIFICATION)
    }

    companion object {

        /**
         * 聚合四项状态:原生 ROM 的自启动/加锁无需设置,无论用户是否勾选都视为完成,
         * 避免"永不满足的自检"一直显示橙色。
         */
        fun evaluate(
            batteryOk: Boolean,
            notificationOk: Boolean,
            autostartConfirmed: Boolean,
            recentsConfirmed: Boolean,
            flavor: RomFlavor,
        ): KeepAliveCheck = KeepAliveCheck(
            batteryOk = batteryOk,
            notificationOk = notificationOk,
            autostartOk = !flavor.needsAutostart || autostartConfirmed,
            recentsOk = !flavor.needsLockHint || recentsConfirmed,
        )
    }
}

/**
 * 保活自检的检测与用户确认读写。
 *
 * 电池/通知是系统实时状态,每次进入主界面重新读取;自启动/加锁没有公开 API 可查,
 * 采用"用户勾选确认"并持久化到 "lan_config"(与既有引导状态同文件,保持设置存储单一来源)。
 */
object KeepAliveChecks {

    private const val KEY_AUTOSTART_CONFIRMED = "ka_autostart_confirmed"
    private const val KEY_RECENTS_CONFIRMED = "ka_recents_confirmed"
    private const val KEY_ONBOARDING_DISMISSED = "ka_onboarding_dismissed"
    // 0.3.0 的旧"国产 ROM 提示"持久化键;已选择过"不再提示"的用户不再被打扰
    private const val KEY_ROM_HINT_DISMISSED_LEGACY = "rom_hint_dismissed"

    /** 电池优化白名单:用户在系统弹窗中点"允许"后为 true。 */
    fun batteryOk(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    /**
     * 常驻通知是否仍在服务:应用级通知被禁(含 Android 13+ 通知权限被拒)或前台服务
     * 通知渠道被用户改为"关闭"时为 false。渠道尚不存在(前台服务从未启动)视为通过,
     * 服务启动时会以 IMPORTANCE_LOW 重建渠道。
     */
    fun notificationOk(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(RelayForegroundService.CHANNEL_ID) ?: return true
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** 用户勾选的"自启动/后台活动已完成"(系统无 API 可查,只能自证)。 */
    fun autostartConfirmed(context: Context): Boolean =
        LanSettings.prefs(context).getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    fun setAutostartConfirmed(context: Context, value: Boolean) {
        LanSettings.prefs(context).edit().putBoolean(KEY_AUTOSTART_CONFIRMED, value).apply()
    }

    /** 用户勾选的"多任务已加锁"。 */
    fun recentsConfirmed(context: Context): Boolean =
        LanSettings.prefs(context).getBoolean(KEY_RECENTS_CONFIRMED, false)

    fun setRecentsConfirmed(context: Context, value: Boolean) {
        LanSettings.prefs(context).edit().putBoolean(KEY_RECENTS_CONFIRMED, value).apply()
    }

    /** 引导弹窗是否被用户永久关闭("不再提示");兼容 0.3.0 旧键,老用户不被再次打扰。 */
    fun onboardingDismissed(context: Context): Boolean {
        val prefs = LanSettings.prefs(context)
        return prefs.getBoolean(KEY_ONBOARDING_DISMISSED, false) ||
            prefs.getBoolean(KEY_ROM_HINT_DISMISSED_LEGACY, false)
    }

    fun setOnboardingDismissed(context: Context) {
        LanSettings.prefs(context).edit().putBoolean(KEY_ONBOARDING_DISMISSED, true).apply()
    }
}

/**
 * 保活相关的系统页面跳转:所有入口全部 try/catch —— 国产 ROM 常裁剪深链,
 * 按候选顺序尝试,最终回退应用信息页;返回 false 时由调用方 Toast 手动路径。
 */
object KeepAliveIntents {

    /**
     * 打开自启动/后台活动设置:按 [RomFlavor.autostartEntries] 依次尝试深链
     * (附带 package_name/package_label,MIUI 省电策略页靠它定位本应用),
     * 全部失效则回退应用信息页。
     *
     * @return 是否成功打开了任意系统页面(几乎只有连应用信息页都被裁剪时才为 false)
     */
    fun openAutostart(activity: Activity, flavor: RomFlavor): Boolean {
        for (entry in flavor.autostartEntries) {
            val parts = entry.split('/', limit = 2)
            if (parts.size != 2) continue
            try {
                activity.startActivity(
                    Intent()
                        .setClassName(parts[0], parts[1])
                        .putExtra("package_name", activity.packageName)
                        .putExtra("package_label", activity.getString(R.string.app_name))
                )
                return true
            } catch (_: Exception) {
                // 该入口在此 ROM 版本上不存在,继续尝试下一个
            }
        }
        return openAppDetails(activity)
    }

    /** 本应用的通知设置页(自查/恢复常驻通知的入口);API 26 以下退回应用信息页。 */
    fun openAppNotificationSettings(activity: Activity): Boolean {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
            return true
        } catch (_: Exception) {
            return openAppDetails(activity)
        }
    }

    /**
     * 电池优化白名单的系统确认弹窗(与 Gotify 一致的做法,只弹系统对话框、
     * 不静默改设置);ROM 裁剪该弹窗时退回电池优化列表页,再失败返回 false。
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity): Boolean {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            )
            return true
        } catch (_: Exception) {
            return try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 应用信息页(所有 ROM 的通用兜底:电池/通知/自启动都能从这里进)。 */
    fun openAppDetails(activity: Activity): Boolean {
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
