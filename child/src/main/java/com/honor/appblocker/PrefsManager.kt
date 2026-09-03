package com.honor.appblocker

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * 配置偏好管理 - 持久化管控开关
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ===== 管控开关（默认全部开启） =====

    /** 禁止安装APP和游戏（DeviceOwner策略）
     *  每次关闭时自动记录时间戳，超过维护窗口(60分钟)会被自动重新打开，
     *  防止家长更新APP后忘记恢复开关导致全防护失效 */
    var blockInstallApps: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_INSTALL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_INSTALL, value).apply()
            if (!value) {
                prefs.edit().putLong(KEY_INSTALL_BLOCK_DISABLED_AT, System.currentTimeMillis()).apply()
            }
        }

    /** 判断禁装开关的"维护窗口"是否已过期（关闭超过60分钟，或旧数据无记录）。
     *  仅在 blockInstallApps == false 时调用才有意义 */
    fun isInstallBlockMaintenanceExpired(): Boolean {
        val disabledAt = prefs.getLong(KEY_INSTALL_BLOCK_DISABLED_AT, 0L)
        if (disabledAt == 0L) return true // 旧版本遗留的关闭状态，视为已过期
        return System.currentTimeMillis() - disabledAt > MAINTENANCE_WINDOW_MS
    }

    /** 是否已完成首次初始化（首次启动强制全开开关，之后尊重家长的手动设置） */
    fun isInitialConfigDone(): Boolean = prefs.getBoolean(KEY_INITIAL_CONFIG_DONE, false)

    /** 标记首次初始化完成 */
    fun markInitialConfigDone() {
        prefs.edit().putBoolean(KEY_INITIAL_CONFIG_DONE, true).apply()
    }

    /** 禁止微信游戏（无障碍服务策略） */
    var blockWechatGame: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WECHAT_GAME, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_WECHAT_GAME, value).apply()

    /** 禁止弹窗游戏/广告（无障碍服务策略） */
    var blockPopupGame: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUP_GAME, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_POPUP_GAME, value).apply()

    /** 禁止浏览器H5网页游戏（无障碍服务策略） */
    var blockBrowserGame: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_BROWSER_GAME, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_BROWSER_GAME, value).apply()

    /** 禁止短视频/社交App内的小游戏（QQ/抖音/快手/B站等，无障碍服务策略） */
    var blockShortVideoGame: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTVIDEO_GAME, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SHORTVIDEO_GAME, value).apply()

    /** 禁止恢复出厂设置（DeviceOwner策略） */
    var blockFactoryReset: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_FACTORY_RESET, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_FACTORY_RESET, value).apply()

    /** 拦截的游戏包名列表（可扩展） */
    var blockedGamePackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED_PACKAGES) ?: DEFAULT_BLOCKED_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, value).apply()

    // ===== 远程暂停拦截（家长通过 HTTP 指令临时放行小程序）=====

    /** 暂停截止时间戳（0 = 未暂停；>当前时间 = 暂停中） */
    var pauseUntil: Long
        get() = prefs.getLong(KEY_PAUSE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PAUSE_UNTIL, value).apply()

    /** 当前是否处于暂停拦截状态 */
    fun isPaused(): Boolean = pauseUntil > System.currentTimeMillis()

    /** 暂停剩余秒数（未暂停返回 0） */
    fun pauseRemainingSeconds(): Int {
        val remain = (pauseUntil - System.currentTimeMillis()) / 1000
        return if (remain > 0) remain.toInt() else 0
    }

    // ===== 远程锁屏（家长通过 HTTP 指令控制）=====

    /** 强制锁屏结束时间戳
     * - 0 且 forceLocked=true：永久锁定（只能家长手动 /unlock 解除）
     * - > 当前时间：强制锁进行中，到时自动解除
     * - <= 当前时间 且 forceLocked=true：已过期，应立即解除 forceLocked */
    var lockUntilAt: Long
        get() = prefs.getLong(KEY_LOCK_UNTIL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_UNTIL_AT, value).apply()

    /** 强制锁屏剩余秒数：
     * - forceLocked=false → 0（未在锁）
     * - forceLocked=true 且 lockUntilAt=0 → -1（永久锁，无到期）
     * - 其他：秒数（正数=进行中；负数或0=已过期应当解除）*/
    fun forceLockRemainingSeconds(): Int {
        if (!forceLocked) return 0
        if (lockUntilAt == 0L) return -1
        return ((lockUntilAt - System.currentTimeMillis()) / 1000).toInt()
    }

    /** 强制锁屏模式（true = 每 10 秒自动锁一次，开机也自动锁；到期或家长解除则为 false）*/
    var forceLocked: Boolean
        get() = prefs.getBoolean(KEY_FORCE_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_LOCKED, value).apply()

    // ===== 家长锁密码（默认 123456，家长可在解锁后修改）=====

    /** 家长解锁密码（≥6位，默认 123456；每次修改自动记录时间戳，用于发现孩子私自改密码） */
    var parentPassword: String
        get() = prefs.getString(KEY_PARENT_PASSWORD, DEFAULT_PARENT_PASSWORD) ?: DEFAULT_PARENT_PASSWORD
        set(value) = prefs.edit()
            .putString(KEY_PARENT_PASSWORD, value)
            .putLong(KEY_PARENT_PASSWORD_CHANGED_AT, System.currentTimeMillis())
            .apply()

    /** 家长密码最后一次修改时间（0 = 从未修改过，仍是默认密码） */
    val parentPasswordChangedAt: Long
        get() = prefs.getLong(KEY_PARENT_PASSWORD_CHANGED_AT, 0L)

    companion object {
        private const val KEY_BLOCK_INSTALL = "block_install_apps"
        private const val KEY_BLOCK_WECHAT_GAME = "block_wechat_game"
        private const val KEY_BLOCK_POPUP_GAME = "block_popup_game"
        private const val KEY_BLOCK_BROWSER_GAME = "block_browser_game"
        private const val KEY_BLOCK_SHORTVIDEO_GAME = "block_shortvideo_game"
        private const val KEY_BLOCK_FACTORY_RESET = "block_factory_reset"
        private const val KEY_BLOCKED_PACKAGES = "blocked_game_packages"
        private const val KEY_PARENT_PASSWORD = "parent_password"
        private const val KEY_PARENT_PASSWORD_CHANGED_AT = "parent_password_changed_at"
        private const val KEY_INSTALL_BLOCK_DISABLED_AT = "install_block_disabled_at"
        private const val KEY_INITIAL_CONFIG_DONE = "initial_config_done"
        private const val KEY_PAUSE_UNTIL = "pause_until"
        private const val KEY_FORCE_LOCKED = "force_locked"
        private const val KEY_LOCK_UNTIL_AT = "lock_until_at"

        /** 禁装开关允许关闭的维护窗口时长（60分钟，超时自动恢复） */
        private const val MAINTENANCE_WINDOW_MS = 60L * 60 * 1000

        /** 家长解锁默认密码（首次使用即生效） */
        const val DEFAULT_PARENT_PASSWORD = "123456"

        /** 常见游戏APP包名 - 兜底拦截（即使没有DeviceOwner也通过无障碍返回桌面） */
        val DEFAULT_BLOCKED_PACKAGES = setOf(
            // 腾讯游戏
            "com.tencent.tmgp.sgame",        // 王者荣耀
            "com.tencent.tmgp.pubgmhd",       // 和平精英平板版
            "com.tencent.tmgp.cod",           // 使命召唤手游
            "com.tencent.tmgp.qqfc",          // QQ飞车手游
            "com.tencent.tmgp.dnfm",          // 地下城与勇士手游
            "com.tencent.ig",                 // PUBG Mobile
            "com.tencent.minigame",           // 腾讯小游戏
            "com.tencent.cy",                 // 穿越火线手游
            // 网易游戏
            "com.netease.mc",                 // 我的世界网易版
            "com.netease.onmyoji",            // 阴阳师
            "com.netease.ldxy",               // 梦幻西游手游
            "com.netease.wsttt",              // 第五人格
            "com.netease.party.android",      // 蛋仔派对
            "com.netease.sky.android",        // 光遇
            // 米哈游
            "com.miHoYo.GenshinImpact",       // 原神
            "com.miHoYo.Honkai3rd",           // 崩坏3
            "com.miHoYo.HSR",                 // 崩坏星穹铁道
            "com.miHoYo.ys.mi",               // 原神小米版
            // 其他热门孩子爱玩的游戏
            "com.supercell.clashofclans",     // 部落冲突
            "com.supercell.clashroyale",      // 皇室战争
            "com.king.candycrushsaga",        // 糖果传奇
            "com.playrix.gardenscapes",       // 梦幻花园
            "com.playrix.homescapes",         // 梦幻家园
            "com.imangi.templerun2",          // 神庙逃亡2
            "com.roblox.client",              // Roblox
            "com.mojang.minecraftpe",         // 我的世界国际版
            "com.minitech.miniworld",         // 迷你世界
            "com.playcraft.miniworld",        // 迷你世界(其他渠道)
            "com.sofunny.Sausage",            // 香肠派对
            "com.kiloo.subwaysurf",           // 地铁跑酷
            "com.happyelements.AndroidAnimal",// 开心消消乐
            "com.popcap.pvz2cthdoub",         // 植物大战僵尸2(抖音)
            "com.popcap.pvz2cthww",           // 植物大战僵尸2(华为)
            "com.tencent.tmgp.pvz2",          // 植物大战僵尸2(应用宝)
            // 云游戏/云游戏平台
            "com.tencent.tmgp.cloudgame",     // 腾讯云游戏
            "com.netease.cloudgame",          // 网易云游戏
            "cn.changyou.wap.miguyouxizhibo", // 咪咕快游
            "com.tencent.start",              // 腾讯START云游戏
            // 模拟器/游戏厅
            "com.androidemu.mame",            // 小鸡模拟器(示例)
            "com.wufan.test2",                // 悟饭游戏厅
            // 游戏平台/盒子
            "com.tencent.gamehelper",         // 腾讯手游助手
            "com.tencent.wxgame",             // 微信游戏
            "com.egame.tv",                   // 游戏中心
            "com.oppo.market.game",           // OPPO游戏中心
            "com.vivo.game",                  // vivo游戏中心
            "com.hihonor.gamecenter",         // 荣耀游戏中心
            "com.hihonor.gamecenter.overlay", // 荣耀游戏中心悬浮窗(真机实测包名)
            "com.hihonor.quickgame",          // 荣耀快游戏中心(免下载即点即玩H5游戏!)
            "com.hihonor.gameassistant",      // 荣耀游戏助手(游戏空间)
            "com.huawei.gamecenter",          // 华为游戏中心
            "com.xiaomi.gamecenter",          // 小米游戏中心
            "com.qihoo.gamecenter",           // 360游戏中心
            "com.bilibili.game",              // B站游戏中心
            "com.huluxia.game",               // 葫芦侠游戏盒子
            "com.7723.gamebox",               // 7723游戏盒
            "com.ourplay",                    // OurPlay加速器
            "com.kkkhelper",                  // 酷酷跑游戏盒
            // 应用下载器/游戏分发渠道（内含游戏下载入口）
            "com.tencent.android.qqdownloader", // 应用宝(内嵌游戏下载)
            "com.tencent.gamecenter.app"        // QQ游戏中心独立版
        )

        // ======== 浏览器包名列表（H5游戏检测时使用，不直接拦截App，只检测页面内容）========
        val BROWSER_PACKAGES = setOf(
            "com.android.browser",            // 原生浏览器
            "com.hihonor.browser",            // 荣耀浏览器
            "com.huawei.browser",             // 华为浏览器
            "com.android.chrome",             // Google Chrome
            "com.microsoft.emmx",             // Edge 浏览器
            "org.mozilla.firefox",            // Firefox
            "com.oupeng.browser",             // 欧朋浏览器
            "com.ijinshan.browser",           // 猎豹浏览器
            "com.mx.browser",                 // 遨游浏览器
            "com.UCMobile",                   // UC浏览器
            "com.quark.browser",              // 夸克浏览器
            "com.tencent.mtt",                // QQ浏览器
            "com.sogou.mobiletoolassist",     // 搜狗浏览器
            "com.baidu.searchbox",            // 百度(内置浏览器)
            "com.bytedance.search",           // 头条搜索
            "com.xbrowser.play"               // XBrowser
        )

        // ======== 短视频/社交App包名列表（内含小游戏，需做内容级检测）========
        val SHORTVIDEO_AND_SOCIAL_PACKAGES = setOf(
            "com.tencent.mobileqq",           // QQ
            "com.tencent.qqlite",             // QQ极速版
            "com.tencent.tim",                // QQ TIM
            "com.ss.android.ugc.aweme",       // 抖音
            "com.ss.android.ugc.aweme.lite",  // 抖音极速版
            "com.ss.android.ugc.aweme.mobile",// 抖音移动版
            "com.smile.gifmaker",             // 快手
            "com.smile.gifmaker.lite",        // 快手极速版
            "tv.danmaku.bili",                // B站
            "tv.danmaku.bilibilihd",          // B站HD
            "com.eg.android.AlipayGphone",    // 支付宝(有小程序游戏)
            "com.baidu.searchbox",            // 百度APP（小程序）
            "com.dragon.read",                // 番茄小说(内含小游戏)
            "com.ss.android.article.news",    // 今日头条(小游戏)
            "com.ss.android.article.lite",    // 今日头条极速版
            "com.sina.weibo"                  // 微博(内嵌小游戏)
        )
    }
}
