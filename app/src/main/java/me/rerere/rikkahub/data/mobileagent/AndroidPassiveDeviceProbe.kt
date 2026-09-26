package me.rerere.rikkahub.data.mobileagent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

internal class AndroidPassiveDeviceProbe(
    private val context: Context,
    private val phoneBackend: PhoneBackend,
) : PassiveDeviceProbe {
    override suspend fun capture(): PassiveDeviceSnapshot = PassiveDeviceSnapshot(
        deviceManufacturer = Build.MANUFACTURER.orEmpty(),
        deviceModel = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        capabilities = listOf(
            accessibilityCapability(),
            screenshotCapability(),
            DeviceCapability(
                "shizuku", "Shizuku", CapabilityStatus.NOT_IMPLEMENTED,
                "尚未接入 Shizuku，不会探测或申请其授权。",
            ),
            notificationCapability(),
            DeviceCapability(
                "workspace", "Workspace 命令环境", CapabilityStatus.UNKNOWN,
                "已有 Workspace 功能；环境按工作区独立配置，本页未检查其就绪状态。请在工作区页面确认。",
            ),
            DeviceCapability(
                "foreground_control", "前台接管与冲突保护",
                if (phoneBackend.state.value.connected) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
                "从聊天的手机控制面板选择目标应用并手动开始。切换到未授权应用或锁屏时会暂停；可随时通过面板或通知 STOP 停止。",
            ),
            apkInstallCapability(),
        ),
    )

    private fun accessibilityCapability(): DeviceCapability = DeviceCapability(
        "accessibility", "无障碍手机控制",
        if (phoneBackend.state.value.connected) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
        if (phoneBackend.state.value.connected) {
            "无障碍服务已实际连接。仅在你从聊天手动开始的任务中，读取和操作所选目标应用。"
        } else {
            "无障碍服务尚未连接。请在系统无障碍设置中自行开启本应用的服务；已开启但未连接时可关闭后重新开启。打开本页不会代你授权。"
        },
    )

    private fun screenshotCapability(): DeviceCapability = DeviceCapability(
        "screenshot", "屏幕截图",
        when {
            !phoneBackend.supportsScreenshot -> CapabilityStatus.UNAVAILABLE
            !phoneBackend.state.value.connected -> CapabilityStatus.PERMISSION_REQUIRED
            else -> CapabilityStatus.AVAILABLE
        },
        when {
            !phoneBackend.supportsScreenshot -> "当前系统不支持此截图后端，手机控制仍可使用页面结构。"
            !phoneBackend.state.value.connected -> "截图需要无障碍服务实际连接，并在每次任务中单独允许。"
            else -> "截图后端可用，默认关闭。只有你为本次任务勾选允许截图，模型才可请求必要画面。"
        },
    )

    private fun notificationCapability(): DeviceCapability = try {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        DeviceCapability(
            "notifications", "应用通知",
            if (enabled) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            if (enabled) "系统允许本应用发送通知；具体通知类别仍受系统设置控制。"
            else "系统尚未允许或已关闭本应用通知。本页只读取状态，不申请权限。",
        )
    } catch (_: Exception) {
        DeviceCapability(
            "notifications", "应用通知", CapabilityStatus.UNKNOWN,
            "未能读取系统通知权限状态。",
        )
    }

    @Suppress("DEPRECATION")
    private fun apkInstallCapability(): DeviceCapability {
        val permissionDetail = try {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            if ("android.permission.REQUEST_INSTALL_PACKAGES" !in info.requestedPermissions.orEmpty()) {
                "当前 APK 未声明请求安装未知应用的权限。"
            } else if (context.packageManager.canRequestPackageInstalls()) {
                "系统允许本应用请求安装未知应用，但安装仍可能需要用户确认。"
            } else {
                "系统尚未允许本应用请求安装未知应用。"
            }
        } catch (_: Exception) {
            "未能确认请求安装未知应用的系统权限。"
        }
        return DeviceCapability(
            "apk_install", "Agent APK 安装", CapabilityStatus.NOT_IMPLEMENTED,
            "Agent 安装与验证流程尚未接入。$permissionDetail",
        )
    }
}
