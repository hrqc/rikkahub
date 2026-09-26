package me.rerere.rikkahub.data.mobileagent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

internal class AndroidPassiveDeviceProbe(private val context: Context) : PassiveDeviceProbe {
    override suspend fun capture(): PassiveDeviceSnapshot = PassiveDeviceSnapshot(
        deviceManufacturer = Build.MANUFACTURER.orEmpty(),
        deviceModel = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        capabilities = listOf(
            DeviceCapability(
                "accessibility", "无障碍手机控制", CapabilityStatus.NOT_IMPLEMENTED,
                "本版本尚未接入无障碍服务，不能读取页面、点击或输入；不会自动打开系统设置。",
            ),
            DeviceCapability(
                "screenshot", "屏幕截图", CapabilityStatus.NOT_IMPLEMENTED,
                "截图后端尚未接入。系统版本或 Root 检查成功不代表已经能够截图。",
            ),
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
                "foreground_control", "前台接管与冲突保护", CapabilityStatus.NOT_IMPLEMENTED,
                "手机接管流程尚未接入。聊天的后台生成服务不等于能在后台操作其他应用。",
            ),
            apkInstallCapability(),
        ),
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
