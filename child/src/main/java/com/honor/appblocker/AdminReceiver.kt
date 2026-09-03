package com.honor.appblocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * 设备管理员/DeviceOwner接收器 - 核心管控类
 *
 * 主要职责：
 * 1. 接收管理员激活/停用事件
 * 2. 在激活后立即设置用户限制（禁止安装APP等）
 * 3. 防止管理员被非法停用
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"

        /** 获取本组件 */
        fun getComponent(context: Context): ComponentName =
            ComponentName(context, AdminReceiver::class.java)

        /** 检查是否拥有DeviceOwner权限 */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return try {
                dpm.isDeviceOwnerApp(context.packageName)
            } catch (e: Exception) {
                false
            }
        }

        /** 检查是否是ProfileOwner (工作资料模式) */
        fun isProfileOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return try {
                dpm.isProfileOwnerApp(context.packageName)
            } catch (e: Exception) {
                false
            }
        }

        /** 检查是否拥有普通设备管理员权限 */
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponent(context))
        }

        /**
         * 启用全部管控策略
         * 需要DeviceOwner权限才能完全生效
         */
        fun enforceAllPolicies(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)

            // 仅当拥有管理员权限时才设置
            if (!isAdminActive(context)) {
                Log.w(TAG, "enforceAllPolicies: 管理员权限未激活，跳过策略设置")
                return
            }

            val prefs = PrefsManager(context)
            try {
                // ===== 核心策略1：禁止安装任何APP和游戏 =====
                if (prefs.blockInstallApps) {
                    // 禁止从应用市场/ADB/浏览器等所有渠道安装应用（覆盖USB/ADB安装）
                    safeAddRestriction(dpm, component, UserManager.DISALLOW_INSTALL_APPS)
                    // 禁止从"未知来源"安装（旧版Android 8.0以下辅助策略）
                    safeAddRestriction(dpm, component, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    Log.i(TAG, "已启用: 禁止安装APP/游戏策略")
                }

                // ===== 核心策略2：保护管控应用不被卸载 =====
                // 只阻止卸载荣耀管控自己，不影响其他APP的卸载
                // 全局 DISALLOW_UNINSTALL_APPS 会导致家长也无法卸载孩子手机上的APP
                val myPkg = context.packageName
                if (isDeviceOwner(context)) {
                    runCatching {
                        dpm.setUninstallBlocked(component, myPkg, true)
                        Log.i(TAG, "已启用: 保护管控应用不被卸载 (setUninstallBlocked)")
                    }.onFailure { Log.w(TAG, "setUninstallBlocked 失败: ${it.message}") }
                }

                // ===== 可选策略3：禁止恢复出厂设置 =====
                if (prefs.blockFactoryReset) {
                    safeAddRestriction(dpm, component, UserManager.DISALLOW_FACTORY_RESET)
                    Log.i(TAG, "已启用: 禁止恢复出厂设置")
                }

                // ===== 可选策略4：禁止添加用户/访客 =====
                safeAddRestriction(dpm, component, UserManager.DISALLOW_ADD_USER)
                safeAddRestriction(dpm, component, UserManager.DISALLOW_USER_SWITCH)

                // ===== 可选策略5：禁止Safe Mode（防止进入安全模式绕过管控） =====
                safeAddRestriction(dpm, component, UserManager.DISALLOW_SAFE_BOOT)

                // ===== 可选策略6：禁止修改系统时间（防止孩子改时间伪造密码修改记录） =====
                safeAddRestriction(dpm, component, UserManager.DISALLOW_CONFIG_DATE_TIME)

                // ===== 核心策略7：隐藏「开发者选项」菜单（仅DeviceOwner可设置） =====
                // 防止孩子关闭USB调试，切断家长电脑ADB救援通道（adb本身不受影响）
                if (isDeviceOwner(context)) {
                    safeSetGlobalSetting(dpm, component, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
                    Log.i(TAG, "已启用: 隐藏开发者选项菜单（保护ADB救援通道）")
                }

                Log.i(TAG, "管控策略全部执行完毕")
                // 注意：不再无条件弹Toast（本方法在App每次启动时都会静默执行自愈）
            } catch (e: Exception) {
                Log.e(TAG, "设置管控策略异常: ${e.message}", e)
            }
        }

        /** 安全添加用户限制（非DeviceOwner可能抛异常）
         *  注：重复添加同一限制是安全的（幂等，无副作用），不需要先 hasUserRestriction 查询 */
        private fun safeAddRestriction(dpm: DevicePolicyManager, comp: ComponentName, key: String) {
            try {
                dpm.addUserRestriction(comp, key)
            } catch (e: SecurityException) {
                Log.w(TAG, "权限不足，无法设置限制: $key - ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "设置限制失败: $key - ${e.message}")
            }
        }

        /** 安全移除用户限制
         *  注：重复移除同一限制是安全的（幂等） */
        private fun safeClearRestriction(dpm: DevicePolicyManager, comp: ComponentName, key: String) {
            try {
                dpm.clearUserRestriction(comp, key)
            } catch (e: Exception) {
                Log.w(TAG, "移除限制失败: $key - ${e.message}")
            }
        }

        /** 安全写入全局设置（仅DeviceOwner有效，非DeviceOwner会抛SecurityException） */
        private fun safeSetGlobalSetting(dpm: DevicePolicyManager, comp: ComponentName, key: String, value: Int) {
            try {
                dpm.setGlobalSetting(comp, key, value.toString())
            } catch (e: Exception) {
                Log.w(TAG, "设置全局设置失败: $key - ${e.message}")
            }
        }

        /** 解除DeviceOwner (返回 false 表示需ADB命令解除) */
        fun clearDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)

            if (!isDeviceOwner(context)) {
                // 不是DeviceOwner，直接尝试清除active admin
                try {
                    dpm.removeActiveAdmin(component)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "removeActiveAdmin 失败", e)
                    return false
                }
            }

            // 是 DeviceOwner，必须先清除所有限制再清除DeviceOwner
            try {
                val keys = arrayOf(
                    UserManager.DISALLOW_INSTALL_APPS,
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                    UserManager.DISALLOW_UNINSTALL_APPS,
                    UserManager.DISALLOW_FACTORY_RESET,
                    UserManager.DISALLOW_ADD_USER,
                    UserManager.DISALLOW_USER_SWITCH,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_CONFIG_DATE_TIME
                )
                keys.forEach { safeClearRestriction(dpm, component, it) }

                // 恢复开发者选项菜单为系统默认开启状态
                safeSetGlobalSetting(dpm, component, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)

                // Android 8.0+ 支持清除DeviceOwner
                dpm.clearDeviceOwnerApp(context.packageName)
                // 清除后再移除 active admin
                if (isAdminActive(context)) {
                    dpm.removeActiveAdmin(component)
                }
                return true
            } catch (e: SecurityException) {
                Log.e(TAG, "clearDeviceOwner 安全异常（通常需ADB解除）", e)
                return false
            } catch (e: Exception) {
                Log.e(TAG, "clearDeviceOwner 异常", e)
                return false
            }
        }

        /** 切换单一禁止安装策略（用于运行时开关）
         *  同时联动 DISALLOW_UNINSTALL_APPS：禁止安装时也禁止卸载（防止卸了又装），
         *  允许安装时也允许卸载（方便家长管理APP）
         */
        fun setBlockInstallPolicy(context: Context, enable: Boolean) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            if (!isAdminActive(context)) return

            val keys = arrayOf(
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_UNINSTALL_APPS  // 联动：禁止安装时也禁止卸载
            )
            keys.forEach { key ->
                if (enable) safeAddRestriction(dpm, component, key)
                else safeClearRestriction(dpm, component, key)
            }
        }

        /** 切换禁止恢复出厂设置策略 */
        fun setBlockFactoryResetPolicy(context: Context, enable: Boolean) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            if (!isAdminActive(context)) return

            if (enable) safeAddRestriction(dpm, component, UserManager.DISALLOW_FACTORY_RESET)
            else safeClearRestriction(dpm, component, UserManager.DISALLOW_FACTORY_RESET)
        }

        /** 隐藏 APP（DeviceOwner setApplicationHidden，API 24+）— 效果等价于删除 */
        fun hideApp(context: Context, packageName: String): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            if (!isDeviceOwner(context)) return false
            return runCatching {
                dpm.setApplicationHidden(component, packageName, true)
                Log.i(TAG, "隐藏 APP: $packageName")
                true
            }.getOrDefault(false)
        }

        /** 显示被隐藏的 APP */
        fun showApp(context: Context, packageName: String): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            if (!isDeviceOwner(context)) return false
            return runCatching {
                dpm.setApplicationHidden(component, packageName, false)
                Log.i(TAG, "显示 APP: $packageName")
                true
            }.getOrDefault(false)
        }

        /** 查询 APP 是否被隐藏 */
        fun isAppHidden(context: Context, packageName: String): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            if (!isDeviceOwner(context)) return false
            return runCatching { dpm.isApplicationHidden(component, packageName) }.getOrDefault(false)
        }

        /** 立即锁屏（DeviceOwner / DeviceAdmin 都可用，API 8+） */
        fun lockNow(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!isAdminActive(context)) return false
            return runCatching {
                dpm.lockNow()
                Log.i(TAG, "立即锁屏")
                true
            }.getOrDefault(false)
        }

        /** 解除锁屏后唤醒屏幕 + 解锁 Keyguard（让孩子立刻看到桌面） */
        fun wakeUpScreen(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getComponent(context)
            val isDO = isDeviceOwner(context)

            // 1. DeviceOwner 专属：先禁用 Keyguard（绕过荣耀 ROM 锁屏）
            if (isDO) {
                runCatching {
                    dpm.setKeyguardDisabled(component, true)
                    Log.i(TAG, "DeviceOwner: setKeyguardDisabled(true)")
                }.onFailure { Log.e(TAG, "setKeyguardDisabled(true) 失败: ${it.message}") }
            }

            // 2. WakeLock 亮屏
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "HonorAppBlocker:wakeUnlock"
                )
                wl.acquire(5000L) // 亮 5 秒，自动释放
                Log.i(TAG, "WakeLock 屏幕已唤醒")
            }.onFailure { Log.e(TAG, "WakeLock 失败: ${it.message}") }

            // 3. 兜底：KeyguardManager（Android 14 上可能无效，但试一下）
            runCatching {
                val kgm = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                @Suppress("DEPRECATION")
                kgm.newKeyguardLock("HonorAppBlocker").disableKeyguard()
            }

            // 4. 3 秒后恢复 Keyguard（只解除这一次锁屏，不是永久关闭）
            if (isDO) {
                android.os.Handler(context.mainLooper).postDelayed({
                    runCatching {
                        dpm.setKeyguardDisabled(component, false)
                        Log.i(TAG, "DeviceOwner: setKeyguardDisabled(false) 恢复")
                    }.onFailure { Log.e(TAG, "setKeyguardDisabled(false) 失败: ${it.message}") }
                }, 3000L)
            }
        }
    }

    // ========== 生命周期回调 ==========

    /** 管理员激活时触发 */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "设备管理员已激活 (isDeviceOwner=${isDeviceOwner(context)})")
        // 激活后立即应用全部管控策略
        enforceAllPolicies(context)
    }

    /** DeviceOwner 状态下的 profile 就绪 */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning 完成，开始启用管控")
        enforceAllPolicies(context)
    }

    /** 管理员被要求停用（返回 true 可以阻止停用，但在DeviceOwner下无效） */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "管理员停用请求被拦截！")
        return "⚠️ 此为设备管控应用，停用会导致管控失效。确认停用吗？"
    }

    /** 管理员被停用后 */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "设备管理员已被停用！管控功能失效！")
        Toast.makeText(context, "系统组件已停用，部分功能可能不可用", Toast.LENGTH_LONG).show()
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) = Unit
    override fun onPasswordFailed(context: Context, intent: Intent, userHandle: android.os.UserHandle) = Unit
    override fun onPasswordSucceeded(context: Context, intent: Intent, userHandle: android.os.UserHandle) = Unit
}
