package com.honor.appblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 游戏拦截无障碍服务
 *
 * 核心功能：
 * 1. 拦截微信游戏（发现页游戏、小程序游戏、游戏中心）
 * 2. 拦截弹窗类游戏广告/诱导下载
 * 3. 兜底拦截已知游戏APP包名（即使绕过DeviceOwner安装）
 *
 * 工作原理：
 * 通过AccessibilityService监听 TYPE_WINDOW_STATE_CHANGED 事件，
 * 实时分析当前窗口的包名、Activity名、控件文本，命中关键词后执行拦截动作。
 */
class GameBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameBlockSvc"

        /** 静态实例引用（供外部查询服务状态） */
        @Volatile
        private var instance: GameBlockAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /** 暴露实例给其他组件调用 performGlobalAction 等方法 */
        fun getServiceInstance(): GameBlockAccessibilityService? = instance

        // === 微信游戏特征关键词 ===
        private val WECHAT_GAME_WINDOW_KEYWORDS = listOf(
            "游戏中心", "微信游戏", "小游戏", "游戏详情", "开始游戏",
            "游戏直播", "gamecenter", "weapp", "wx_game",
            "腾讯游戏", "领取礼包", "游戏好友", "排行榜", "游戏圈",
            "王者荣耀", "和平精英", "游戏推送", "游戏特权"
        )

        private val WECHAT_GAME_ACTIVITY_KEYWORDS = listOf(
            "WxGameStubActivity",
            "GameCenter",
            "MiniGame",
            "MiniProgram",
            "AppBrandLauncher",
            // 微信小程序/小游戏通用容器（真机验证：小游戏实际运行在AppBrandUI里，
            // 画面是Canvas无文本，类名是唯一可靠信号。副作用：所有小程序都会拦）
            "AppBrand",
            "WeAppLauncher",
            "game"
        )

        // === 弹窗游戏/广告关键词（命中则尝试关闭弹窗或返回） ===
        private val POPUP_GAME_KEYWORDS = listOf(
            // 典型诱导式弹窗
            "立即下载", "立即体验", "立即游玩", "立即玩",
            "免费领取", "领取奖励", "点击领取", "新人福利",
            "开始游戏", "一键启动", "马上玩", "点击开始",
            // 游戏诱导
            "真人游戏", "传奇", "高爆", "元宝", "屠龙",
            "一刀999", "送VIP", "首充", "GM版", "公益服",
            "抢红包", "提现", "现金红包", "秒到账",
            // 弹窗按钮文本
            "同意并下载", "允许安装", "去安装",
            // 悬浮球/图标
            "游戏加速器", "游戏助手", "游戏中心"
        )

        // === 弹窗关闭按钮特征（尽量自动点击关闭） ===
        private val CLOSE_BUTTON_TEXT = listOf(
            "关闭", "取消", "跳过", "退出", "暂不", "稍后",
            "close", "cancel", "×", "✕", "✖", "✗", "✘",
            "X", "× 关闭", "知道了", "不再提示"
        )

        // ======== 新增：浏览器H5游戏 检测关键词（命中地址栏/标题/页面文本） ========
        private val BROWSER_GAME_URL_OR_TITLE_KEYWORDS = listOf(
            // 经典H5游戏站域名
            "4399", "7k7k", "7723", "2144", "game", "games", "h5game",
            "h5游戏", "小游戏", "在线游戏", "网页游戏", "在线玩",
            // 游戏类型关键词（出现在标题或页面大文本）
            "开始游戏", "立即游戏", "进入游戏", "继续游戏",
            "点击玩", "点击开始玩", "免费玩",
            "王者荣耀在线玩", "和平精英在线", "迷你世界在线", "我的世界在线",
            "香肠派对", "蛋仔派对", "roblox",
            "传奇", "高爆", "一刀", "元宝", "屠龙",
            // URL 中常见的游戏路径
            "/game/", "/games/", "/play/", "/h5/",
            "yueduwan.cn", "wan.qq.com", "game.qq.com",
            "h.4399.com", "m.4399.com", "h5.4399.cn",
            "xyx.7k7k.com", "h.7k7k.com",
            "9game.com", "18183.com", "gamersky.com",
            // 视频站里的游戏页面
            "gamecenter", "game_page", "minigame", "mini-game"
        )

        // 浏览器地址栏常见控件 resourceId（各种浏览器 URL 栏/搜索栏）
        private val BROWSER_URL_BAR_VIEW_IDS = listOf(
            "url_bar",                 // Chrome
            "address_bar",             // 原生
            "search_input",            // 通用
            "et_url",                  // 华为/荣耀
            "toolbar_search_edit",     // Edge / UC
            "id_search_input",         // 夸克
            "qqbrowser_url_edit",      // QQ浏览器
            "search_box",              // 百度
            "edittext_url"             // 其他
        )

        // ======== 新增：短视频/社交App内小游戏 Activity 特征词 ========
        // 这些 Activity 是打开"小游戏/小程序游戏"时会出现的类名
        private val SHORTVIDEO_GAME_ACTIVITY_KEYWORDS = listOf(
            // QQ 小程序/小游戏
            "MiniAppActivity", "MiniGameActivity", "QQMini", "QQMiniGame", "QMPActivity",
            "MiniProgram", "QQBrowserMini",
            // QQ 游戏中心/应用宝下载器（真机验证漏拦的Activity）
            "gamecenter", "game_center", "GameCenter",
            "minigame", "mini_game", "MiniGame",
            "qqgame", "qqdownloader", "yyb", "Yyb", "QQDownloader",
            // 抖音 小游戏
            "TinyAppActivity", "MicroAppActivity", "DouyinMini", "AwemeGame",
            "aweme.host.miniapp", "MicroApp", "ToutiaoMicroApp",
            // 快手 小游戏
            "KSMiniApp", "KwaiGame", "MiniProgram",
            // B站
            "BiliMini", "BilibiliMini", "bvgame",
            // 支付宝
            "H5Container", "AlipayMini", "MiniApp",
            // 通用小程序
            "MiniApp", "Miniapp", "miniapp", "minigame"
        )

        // 短视频/社交App进入游戏页面的 控件文本关键词
        private val SHORTVIDEO_GAME_WINDOW_KEYWORDS = listOf(
            "小游戏中心", "游戏中心", "小游戏", "小程序游戏",
            "开始游戏", "进入游戏", "继续玩",
            "游戏推荐", "热门游戏", "排行", "我的游戏",
            "游戏礼包", "福利领取",
            // 抖音内
            "抖音小游戏", "摸摸鱼",
            // QQ内
            "QQ小游戏", "厘米游戏", "好友在玩",
            // QQ游戏中心/应用宝下载页面（真机验证漏拦）
            "应用宝", "游戏下载", "下载游戏", "游戏安装包",
            "王者荣耀", "和平精英", "QQ经典农场", "经典农场"
            // 注意：蚂蚁庄园、蚂蚁森林等非游戏休闲不拦截，避免误伤支付
        )

        // ======== 强特征词：出现在可点击节点上，单次命中即拦截（QQ动态页入口等） ========
        private val STRONG_GAME_TEXT_KEYWORDS = listOf(
            "游戏中心", "小游戏", "QQ小游戏", "开始游戏", "进入游戏"
        )

        // ======== 聊天页Activity特征：豁免文本检测（防止聊天内容提到游戏被误拦） ========
        private val CHAT_PAGE_ACTIVITY_KEYWORDS = listOf(
            "chatactivity", "chatting", "troopchat", "c2cmessage", "chatmsg"
        )

        // === 微信包名 ===
        private const val PKG_WECHAT = "com.tencent.mm"

        // ======== 新增：Top4漏洞修复 - 管控设置页面自保拦截 ========
        // 系统设置里可能被孩子用来关闭我们的无障碍/停用管理员的危险页面特征词
        private val DANGER_SETTINGS_ACTIVITY_KEYWORDS = listOf(
            // 无障碍设置（所有ROM通用）
            "AccessibilitySettings", "accessibility_settings",
            "AccessibilityActivity", "com.android.settings.accessibility",
            // 荣耀/华为 MagicOS 的无障碍设置
            "HwAccessibilitySettings", "HwAccessibilityActivity", "HonorAccessibility",
            // 设备管理员/设备所有者页面
            "DeviceAdminSettings", "DeviceAdminAdd", "DevicePolicyManagement",
            "DeviceOwnerSettings", "AdminAppsSettings", "ManageDeviceAdmins",
            // 应用信息页（可以强行停止/卸载）
            "AppInfoDashboardFragment", "ApplicationDetailsSettings", "AppDetailsActivity"
        )

        // 当进入上述危险设置页面时，如果控件树里出现了我们的应用名/包名/服务名 → 判定孩子在找我们的开关准备关闭
        private val DANGER_PAGE_APP_IDENTIFIERS = listOf(
            "荣耀应用管控", "应用管控", "honor appblocker", "app blocker",
            "com.honor.appblocker", "GameBlockAccessibilityService", "AdminReceiver",
            "游戏拦截", "无障碍服务已", "下载服务", "设备管理员应用"
        )

        // 节流：避免短时间重复拦截同一个包导致toast刷屏
        private const val BLOCK_THROTTLE_MS = 3000L
        private val lastBlockTime = mutableMapOf<String, Long>()

        // 心跳：最近一次 onAccessibilityEvent 触发时间（uptime）
        // UI 自检可读取它判断服务是否真的在接收事件
        @Volatile private var lastEventTime: Long = 0L

        /** 服务是否真正绑定并运行（instance 非 null + 最近 60 秒内有事件）*/
        fun isAlive(): Boolean {
            val inst = instance ?: return false
            val last = lastEventTime
            if (last == 0L) return false  // 从未收到事件
            return SystemClock.elapsedRealtime() - last < 60_000L
        }

        /** 服务绑定但还没收到事件（刚启动场景）*/
        fun isBoundButQuiet(): Boolean = instance != null && lastEventTime == 0L

        /** 诊断报告（给 UI 自检按钮展示）*/
        fun diagnose(): String {
            val inst = instance != null
            val last = lastEventTime
            return buildString {
                append("进程实例: ${if (inst) "✅ 活着" else "❌ 未创建"}\n")
                append("事件心跳: ")
                if (last == 0L) append("❌ 从未收到事件（服务未真正绑定）")
                else {
                    val ago = (SystemClock.elapsedRealtime() - last) / 1000
                    append(if (ago < 60) "✅ ${ago}秒前刚收到" else "⚠️ ${ago / 60}分钟前（可能已停摆）")
                }
            }
        }
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var prefs: PrefsManager
    private var checkPending: Runnable? = null
    private var recheckRunnable: Runnable? = null

    // 待检查任务的目标（scheduleCheck防洪水合并用）
    @Volatile private var pendingPkg: String? = null
    @Volatile private var pendingCls: String? = null

    // 最近一次窗口切换事件的 包名+Activity类名 缓存
    // （rootInActiveWindow.className 是根View类名拿不到Activity，复检时需用缓存）
    // 改为 internal 让 ForegroundPollerService 也能读（荣耀 ROM 限制 ActivityManager.appTasks）
    internal var lastWindowPkg: String? = null
    internal var lastWindowCls: String? = null

    // ============= 生命周期 =============

    override fun onCreate() {
        super.onCreate()
        // 【关键修复】荣耀 ROM 可能不触发 onServiceConnected，
        // 但 onCreate 一定会被调用——所以这里必须赋值 instance！
        instance = this
        Log.i(TAG, "无障碍服务 onCreate（ROM 可能未触发 onServiceConnected）")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PrefsManager(this)
        Log.i(TAG, "无障碍服务已连接，游戏拦截功能生效")
        // 维护窗口超时自愈：家长忘开禁装开关时自动恢复
        if (!prefs.blockInstallApps && prefs.isInstallBlockMaintenanceExpired()) {
            prefs.blockInstallApps = true
            Log.w(TAG, "禁装开关关闭超过维护窗口(60分钟)，已自动恢复")
            Toast.makeText(this, "⏰ 维护时间已过，禁止安装APP防护已自动恢复", Toast.LENGTH_LONG).show()
        }
        // 服务启动后确保DeviceOwner策略仍然生效
        AdminReceiver.enforceAllPolicies(this)

        // 启动周期复检：服务死亡期间被打开的游戏窗口，30秒内自动补拦
        startPeriodicRecheck()
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        checkPending?.let { handler.removeCallbacks(it) }
        recheckRunnable?.let { handler.removeCallbacks(it) }
        Log.w(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    // ============= 事件接收 =============

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        // ===== DEBUG：最开头就记录收到的事件（不受任何条件影响）=====
        runCatching {
            val sp = getSharedPreferences("honor_debug", MODE_PRIVATE)
            sp.edit()
                .putString("debug_a11y_evt_pkg", packageName)
                .putString("debug_a11y_evt_type", event.eventType.toString())
                .putString("debug_a11y_evt_cls", event.className?.toString() ?: "")
                .putLong("debug_a11y_evt_time", System.currentTimeMillis())
                .apply()
        }

        // 心跳：记录最近一次事件时间（UI 自检按钮用）
        lastEventTime = SystemClock.elapsedRealtime()

        // 锁屏时不处理（避免误触）
        if (isScreenLocked()) return
        // 本应用界面不处理
        if (packageName == this.packageName) return

        when (event.eventType) {
            // 窗口切换：判断是否进入游戏或微信游戏页面
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 缓存窗口信息（周期复检时使用）——只缓存"像Activity"的类名，
                // 防止游戏内弹窗(DIALOG的className是View/Dialog类)污染缓存
                val evCls = event.className?.toString()
                lastWindowPkg = packageName
                if (isPlausibleActivityClass(evCls)) {
                    lastWindowCls = evCls
                }
                // 窗口标题快速匹配（QQ动态页/游戏中心标题等，单命中即拦，无需等控件树）
                val titleText = event.text?.joinToString(" ") { it?.toString() ?: "" } ?: ""
                if (titleText.isNotBlank()) {
                    checkWindowTitleForBlocking(packageName, titleText)
                }
                scheduleCheck(packageName, event.className?.toString())
            }
            // 窗口内容变化：检测弹窗游戏广告
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 仅在开启拦截时检测弹窗，节流避免过度消耗
                if (prefs.blockPopupGame && lastCheckPop.elapsed() > 1000L) {
                    lastCheckPop = SystemClock.elapsedRealtime()
                    schedulePopupCheck()
                }
                // QQ动态页等Tab切换不触发窗口切换事件，靠内容变化事件补检（独立节流）
                val pkgInSocial = PrefsManager.SHORTVIDEO_AND_SOCIAL_PACKAGES.contains(packageName) ||
                        packageName == PKG_WECHAT
                if (pkgInSocial && lastSocialCheck.elapsed() > 1200L) {
                    lastSocialCheck = SystemClock.elapsedRealtime()
                    scheduleCheck(packageName, null)
                }
            }
        }
    }

    private var lastCheckPop = 0L
    private var lastSocialCheck = 0L

    /** 窗口标题快速拦截：QQ/微信/短视频App的窗口标题命中强特征词 → 直接拦截 */
    private fun checkWindowTitleForBlocking(pkg: String, title: String) {
        val switchOn = prefs.blockShortVideoGame || prefs.blockWechatGame
        if (!switchOn) return
        val inScope = pkg == PKG_WECHAT || PrefsManager.SHORTVIDEO_AND_SOCIAL_PACKAGES.contains(pkg)
        if (!inScope) return
        val hit = STRONG_GAME_TEXT_KEYWORDS.any { title.contains(it, true) }
        if (hit) {
            Log.i(TAG, "命中窗口标题游戏关键词: pkg=$pkg, title=$title")
            blockAndToast(pkg, getString(R.string.toast_blocked_shortvideo_game))
        }
    }

    private inline fun Long.elapsed() = SystemClock.elapsedRealtime() - this

    /** 延迟检查（等待窗口完全加载）
     *  防洪水设计：同一包名的待检查任务已存在时，只补充类名信息而不重置任务——
     *  防止游戏Canvas渲染的内容事件洪水把带类名的检查无限推迟/冲掉 */
    private fun scheduleCheck(pkg: String?, cls: String?) {
        if (pkg != null && pkg == pendingPkg) {
            if (cls != null && pendingCls == null) pendingCls = cls
            return
        }
        checkPending?.let { handler.removeCallbacks(it) }
        pendingPkg = pkg
        pendingCls = cls
        checkPending = Runnable {
            val runPkg = pendingPkg
            val runCls = pendingCls
            pendingPkg = null
            pendingCls = null
            checkPending = null
            val currentPkg = runPkg ?: runCatching { getCurrentPackageName() }.getOrNull() ?: return@Runnable
            checkWindowForBlocking(currentPkg, runCls)
        }
        handler.postDelayed(checkPending!!, 180L)
    }

    private fun schedulePopupCheck() {
        handler.postDelayed({
            runCatching { checkPopupGameAndClose(rootInActiveWindow) }
        }, 300L)
    }

    // ============= 周期复检：服务死亡期间被打开的游戏窗口，30秒内补拦 =============

    private fun startPeriodicRecheck() {
        recheckRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                runCatching { performPeriodicRecheck() }
                handler.postDelayed(this, 30_000L)
            }
        }
        recheckRunnable = r
        // 服务连接后3秒先跑第一次，不等满30秒
        handler.postDelayed(r, 3_000L)
    }

    private fun performPeriodicRecheck() {
        if (isScreenLocked()) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString() ?: return
        // 跳过：自家界面、桌面、系统UI、输入法（这些不会是游戏）
        if (pkg == packageName) return
        if (pkg.contains("launcher", true)) return
        if (pkg == "com.android.systemui") return
        if (pkg.contains("inputmethod", true)) return
        // rootInActiveWindow 拿不到Activity类名，仅当缓存的窗口包名与当前一致时才用缓存类名
        val cls = if (pkg == lastWindowPkg) lastWindowCls else null
        checkWindowForBlocking(pkg, cls, fromPeriodicRecheck = true)
    }

    /** 判断事件className是否像Activity类名
     *  （排除对话框/系统View类名污染缓存：DIALOG窗口事件的className是android.widget.FrameLayout等） */
    private fun isPlausibleActivityClass(cls: String?): Boolean {
        if (cls.isNullOrBlank()) return false
        if (!cls.contains(".")) return false
        if (cls.startsWith("android.")) return false
        val simple = cls.substringAfterLast('.')
        if (simple.endsWith("Dialog") || simple.endsWith("PopupWindow") ||
            simple.endsWith("Toast") || simple.endsWith("Overlay")) return false
        return true
    }

    // ============= 核心：窗口拦截判定 =============

    private fun checkWindowForBlocking(pkg: String, activityName: String?, fromPeriodicRecheck: Boolean = false) {
        // 0) ========== Top4漏洞修复：设置页自保（防止孩子关无障碍/管理员/强行停止）==========
        if (pkg == "com.android.settings" || pkg.contains("settings", true)) {
            if (isDangerousSettingsPageForUs(activityName)) {
                val root = runCatching { rootInActiveWindow }.getOrNull()
                if (root != null && pageMentionsOurApp(root)) {
                    Log.i(TAG, "命中设置页危险操作[自保]：pkg=$pkg, cls=$activityName，立即回桌面")
                    goHomeQuiet()  // 静默回桌面，不给toast提示（避免暴露逻辑）
                    return
                }
            }
        }

        // 1) 兜底拦截：已知游戏包名直接踢回桌面（不受开关限制——黑名单全是确定的APP游戏，宁可错杀）
        if (prefs.blockedGamePackages.contains(pkg)) {
            Log.i(TAG, "命中已知游戏包名，直接拦截: $pkg")
            blockAndToast(pkg, "✋ 已拦截游戏: $pkg")
            return
        }

        // 聊天页豁免：QQ/微信聊天窗口内提到的游戏词汇不拦截（避免误伤正常聊天）
        val isChatPage = isChatPage(activityName)

        // 2) 微信内的游戏页面拦截
        if (prefs.blockWechatGame && pkg == PKG_WECHAT && !isChatPage) {
            if (isWechatGamePage(activityName)) {
                Log.i(TAG, "命中微信游戏页面: pkg=$pkg, cls=$activityName")
                blockAndToast(PKG_WECHAT, getString(R.string.toast_blocked_wechat_game))
                return
            }
            // 进一步分析控件树文本（例如「游戏中心」按钮被点击后）
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null && containsWechatGameText(root)) {
                Log.i(TAG, "命中微信游戏控件文本")
                blockAndToast(PKG_WECHAT, getString(R.string.toast_blocked_wechat_game))
                return
            }
        }

        // 3) ========== 新增：浏览器 H5 网页游戏拦截 ==========
        if (prefs.blockBrowserGame && PrefsManager.BROWSER_PACKAGES.contains(pkg)) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null && isBrowserOnGamePage(root)) {
                Log.i(TAG, "命中浏览器H5游戏页面: pkg=$pkg")
                blockAndToast(pkg, getString(R.string.toast_blocked_browser_game))
                return
            }
        }

        // 4) ========== 新增：短视频/社交App内小游戏拦截（QQ/抖音/快手/B站等） ==========
        if (prefs.blockShortVideoGame && PrefsManager.SHORTVIDEO_AND_SOCIAL_PACKAGES.contains(pkg) && !isChatPage) {
            // ===== B站/支付宝/网易云/QQ音乐 单独处理：只拦精确的 Activity 类名，不拦文本 =====
            // 这些 App 首页控件树里经常有"游戏"关键词（推荐视频标题、侧边栏入口等），文本匹配必误杀
            if (pkg == "tv.danmaku.bili") {
                // B站：只拦精确的 游戏中心 + 小游戏容器
                val cls = activityName?.lowercase() ?: ""
                runCatching {
                    getSharedPreferences("honor_debug", MODE_PRIVATE).edit()
                        .putString("debug_bili_a11y_cls", cls)
                        .putBoolean("debug_bili_a11y_block", cls.contains("bilimini") || cls.contains("bilibilimini") || cls.contains("bvgame") || cls.contains("biligame"))
                        .putBoolean("debug_bili_block_switch", prefs.blockShortVideoGame)
                        .apply()
                }
                if (cls.contains("bilimini") || cls.contains("bilibilimini") ||
                    cls.contains("bvgame") || cls.contains("biligame")) {
                    Log.i(TAG, "命中B站游戏页Activity: cls=$activityName")
                    blockAndToast(pkg, "🚫 B站游戏中心/小游戏")
                    return
                }
                return  // B站其他全放行（视频页等）
            }
            if (pkg == "com.eg.android.AlipayGphone" || pkg == "com.netease.cloudmusic" || pkg == "com.tencent.qqmusic") {
                val cls = activityName?.lowercase() ?: ""
                if (cls.contains("miniapp") || cls.contains("mini_app") || cls.contains("minigame") || cls.contains("mini_game")) {
                    Log.i(TAG, "命中[${pkg}]小游戏Activity: cls=$activityName")
                    blockAndToast(pkg, "🚫 App内小游戏容器")
                    return
                }
                return  // 其他全放行
            }

            // ===== QQ/抖音/快手 继续用通用规则 =====
            // 4.1 先检查 Activity 类名（小程序/小游戏容器类）
            if (isShortVideoGamePage(activityName)) {
                Log.i(TAG, "命中短视频App小游戏Activity: pkg=$pkg, cls=$activityName")
                blockAndToast(pkg, getString(R.string.toast_blocked_shortvideo_game))
                return
            }
            // 4.2 进一步分析控件树文本（进入小游戏中心页面）
            // ⚠️ 定期复检时跳过 QQ——QQ 主界面动态 Tab 有"游戏"入口，会误命中关键词
            //    QQ 拦截由 ForegroundPollerService 用 totalNodes 阈值精确处理（小游戏<22, 游戏中心>35）
            val skipTextCheck = fromPeriodicRecheck && pkg == "com.tencent.mobileqq"
            if (!skipTextCheck) {
                val root = runCatching { rootInActiveWindow }.getOrNull()
                if (root != null && containsShortVideoGameText(root)) {
                    Log.i(TAG, "命中短视频App小游戏控件文本: pkg=$pkg")
                    blockAndToast(pkg, getString(R.string.toast_blocked_shortvideo_game))
                    return
                }
            }
        }

        // 5) 其他包含游戏关键词的弹窗窗口（与TYPE_WINDOWS_CHANGED联动）
        //    注意：周期复检时跳过此分支——"点击关闭按钮"逻辑会误点普通页面的"跳过/取消"按钮
        if (prefs.blockPopupGame && !fromPeriodicRecheck) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null) {
                // 若检测到游戏弹窗但可以定位关闭按钮，则尝试点击关闭而不是直接回桌面
                if (tryFindAndClickClose(root)) {
                    Log.i(TAG, "检测到弹窗，已点击关闭按钮")
                    showToast(getString(R.string.toast_blocked_popup_game))
                } else if (isFullscreenGamePopup(root)) {
                    // 全屏游戏诱导页 → 直接回桌面
                    Log.i(TAG, "命中全屏游戏弹窗，返回桌面")
                    blockAndToast(pkg, getString(R.string.toast_blocked_popup_game))
                }
            }
        }
    }

    // ============= 微信游戏检测 =============

    private fun isWechatGamePage(clsName: String?): Boolean {
        if (clsName.isNullOrBlank()) return false
        val lower = clsName.lowercase()
        return WECHAT_GAME_ACTIVITY_KEYWORDS.any { lower.contains(it.lowercase()) }
    }

    /** 遍历控件树，检查是否含微信游戏相关文本 */
    private fun containsWechatGameText(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val textHit = Array(1) { false }
        traverseTree(root) { node ->
            val t = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val vId = (node.viewIdResourceName ?: "").toString()
            if (WECHAT_GAME_WINDOW_KEYWORDS.any { kw ->
                    t.contains(kw, true) || desc.contains(kw, true) || vId.contains(kw, true)
                }) {
                textHit[0] = true
                return@traverseTree true // stop
            }
            false
        }
        return textHit[0]
    }

    // ============= 新增：浏览器 H5 游戏检测 =============

    /**
     * 综合判断浏览器是否在 H5 游戏页面。
     * 检测两部分：
     * 1) 浏览器地址栏文本（URL）是否命中游戏站点/关键词
     * 2) 页面可见控件大文本中是否命中"开始游戏""继续游戏"等强诱导词
     */
    private fun isBrowserOnGamePage(root: AccessibilityNodeInfo): Boolean {
        var urlHit = false
        var pageHitCount = 0

        traverseTree(root) { node ->
            val t = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val vId = (node.viewIdResourceName ?: "").toString().lowercase()

            // ---- 地址栏检测：优先匹配 URL bar viewId ----
            val isLikelyUrlBar = BROWSER_URL_BAR_VIEW_IDS.any { id ->
                vId.endsWith(id) || vId.contains(id.replace("_", ""))
            }
            if (isLikelyUrlBar && t.isNotBlank()) {
                if (matchesBrowserGameKeyword(t)) {
                    urlHit = true
                    return@traverseTree true  // URL 已经命中，直接判定
                }
            }
            // URL 栏里的文本也会被展示成普通 TextView，另外检查 desc
            if (matchesBrowserGameKeyword(t) || matchesBrowserGameKeyword(desc)) {
                // 可能是地址栏，也可能是页面内链接文本
                if (t.length in 4..80 && (vId.contains("url") || vId.contains("address") ||
                            vId.contains("search") || vId.contains("edit"))) {
                    urlHit = true
                    return@traverseTree true
                }
            }

            // ---- 页面大文本检测：按钮或标题的"开始游戏""进入游戏" ----
            if (t.isNotBlank() && t.length in 2..20 && node.isClickable) {
                if (BROWSER_GAME_URL_OR_TITLE_KEYWORDS.any { kw ->
                        t.contains(kw, true) || desc.contains(kw, true)
                    }) {
                    pageHitCount++
                }
            }
            false
        }

        if (urlHit) return true
        // 页面内有 2 处以上游戏按钮文本也算游戏页
        return pageHitCount >= 2
    }

    private fun matchesBrowserGameKeyword(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return BROWSER_GAME_URL_OR_TITLE_KEYWORDS.any { kw ->
            lower.contains(kw.lowercase())
        }
    }

    // ============= 新增：短视频/社交App 小游戏检测 =============

    /** 检测短视频/社交App Activity 是否是小游戏容器页 */
    private fun isShortVideoGamePage(clsName: String?): Boolean {
        if (clsName.isNullOrBlank()) return false
        val lower = clsName.lowercase()
        return SHORTVIDEO_GAME_ACTIVITY_KEYWORDS.any { kw ->
            lower.contains(kw.lowercase())
        }
    }

    /** 检测是否是QQ/微信的聊天页面（聊天内容提到游戏词汇时不拦截，避免误伤正常聊天） */
    private fun isChatPage(clsName: String?): Boolean {
        if (clsName.isNullOrBlank()) return false
        val lower = clsName.lowercase()
        return CHAT_PAGE_ACTIVITY_KEYWORDS.any { lower.contains(it) }
    }

    /** 遍历控件树，检查短视频App是否进入游戏中心/小游戏页
     *  非聊天页阈值降为1次命中即拦（聊天页在调用方已豁免） */
    private fun containsShortVideoGameText(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        var hit = 0
        var strongClickable = false
        traverseTree(root) { node ->
            val t = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            if (t.isNotBlank() || desc.isNotBlank()) {
                // 强特征词出现在可点击节点（含父容器可点）上 → 立即判定（QQ动态页入口按钮等）
                if (!strongClickable) {
                    val strongHit = STRONG_GAME_TEXT_KEYWORDS.any { kw ->
                        t.contains(kw, true) || desc.contains(kw, true)
                    }
                    if (strongHit) {
                        val clickable = try {
                            node.isClickable || node.parent?.isClickable == true
                        } catch (e: Exception) {
                            node.isClickable
                        }
                        if (clickable) strongClickable = true
                    }
                }
                if (SHORTVIDEO_GAME_WINDOW_KEYWORDS.any { kw ->
                        t.contains(kw, true) || desc.contains(kw, true)
                    }) {
                    hit++
                    if (hit >= 1 || strongClickable) return@traverseTree true
                }
            }
            false
        }
        return hit >= 1 || strongClickable
    }

    // ============= 弹窗游戏/广告检测 =============

    private fun checkPopupGameAndClose(root: AccessibilityNodeInfo?) {
        root ?: return
        // 若含大量游戏关键词弹窗，优先点关闭
        if (countPopupKeywords(root) >= 1) {
            if (tryFindAndClickClose(root)) {
                showToast(getString(R.string.toast_blocked_popup_game))
            }
        }
    }

    private fun countPopupKeywords(root: AccessibilityNodeInfo?): Int {
        root ?: return 0
        var count = 0
        traverseTree(root) { node ->
            val text = ((node.text ?: "").toString() + " " + (node.contentDescription ?: "")).trim()
            if (text.isNotBlank()) {
                POPUP_GAME_KEYWORDS.forEach { kw ->
                    if (text.contains(kw, true)) count++
                }
            }
            false
        }
        return count
    }

    private fun isFullscreenGamePopup(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val hit = IntArray(2) // [keywords, total]
        traverseTree(root) { node ->
            hit[1]++
            val text = ((node.text ?: "").toString() + " " + (node.contentDescription ?: "")).trim()
            if (text.isNotBlank()) {
                POPUP_GAME_KEYWORDS.forEach { kw ->
                    if (text.contains(kw, true)) hit[0]++
                }
            }
            false
        }
        // 命中关键词数>=2 且 控件数不多（典型弹窗）→ 视为全屏游戏弹窗
        return hit[0] >= 2 && hit[1] in 5..80
    }

    /** 在控件树中查找关闭按钮并点击（返回true表示点到了） */
    private fun tryFindAndClickClose(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val found = arrayOfNulls<AccessibilityNodeInfo>(1)
        traverseTree(root) { node ->
            val t = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val cls = (node.className ?: "").toString()
            // 按钮类控件且文案匹配
            val isClickable = node.isClickable || node.parent?.isClickable == true
            if (isClickable) {
                if (CLOSE_BUTTON_TEXT.any { kw -> t.equals(kw, true) || desc.equals(kw, true) ||
                            t.contains(kw, true) && t.length <= 6 }
                    || (t.isBlank() && cls.contains("Image", true) && node.isClickable && desc.isBlank()
                            && node.childCount == 0)) {
                    // 找最可能的：优先 exact match 文本
                    val exactMatch = CLOSE_BUTTON_TEXT.any { kw ->
                        t.equals(kw, true) || desc.equals(kw, true)
                    }
                    if (exactMatch) {
                        found[0] = if (node.isClickable) node else node.parent
                        return@traverseTree true
                    }
                    if (found[0] == null) {
                        found[0] = if (node.isClickable) node else node.parent
                    }
                }
            }
            false
        }
        val target = found[0] ?: return false
        val clickable = if (target.isClickable) target else target.parent
        return runCatching {
            if (clickable != null && clickable.isClickable) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "自动点击关闭按钮: ${clickable.text}")
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    // ============= 拦截动作 =============

    private fun blockAndToast(pkg: String, msg: String) {
        // 节流
        val last = lastBlockTime[pkg] ?: 0L
        if (SystemClock.elapsedRealtime() - last < BLOCK_THROTTLE_MS) {
            goHome()
            return
        }
        lastBlockTime[pkg] = SystemClock.elapsedRealtime()

        // 先杀后台进程（可选，不一定有权限但尽量做）
        killBackgroundPackage(pkg)

        // 回桌面（核心拦截手段）
        goHome()

        // 提示
        showToast(msg)
    }

    private fun goHome() {
        // 首选：无障碍全局HOME动作（不受Android 10+后台启动限制BAL影响，100%生效）
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        // 双保险：部分ROM上HOME动作偶发不生效，200ms后补发一次startActivity
        handler.postDelayed({
            runCatching {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(home)
            }
        }, 200)
    }

    /** 静默回桌面（不杀进程、不弹toast，用于设置页自保——让孩子以为是系统崩了而不是我们拦的）*/
    private fun goHomeQuiet() {
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun killBackgroundPackage(pkg: String) {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 高版本系统限制只能杀自己，尽量尝试
                am.killBackgroundProcesses(pkg)
            } else {
                @Suppress("DEPRECATION")
                am.killBackgroundProcesses(pkg)
            }
        }
    }

    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(this@GameBlockAccessibilityService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ============= 工具函数 =============

    private fun isScreenLocked(): Boolean {
        return try {
            val kg = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            kg.isKeyguardLocked
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentPackageName(): String? {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** 深度遍历控件树，block返回true时停止 */
    private fun traverseTree(root: AccessibilityNodeInfo, depth: Int = 0, block: (AccessibilityNodeInfo) -> Boolean) {
        if (depth > 25) return // 防止过深
        if (block(root)) return
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            traverseTree(child, depth + 1, block)
        }
    }

    // ============= Top4漏洞修复：设置页自保辅助函数 =============

    /** Activity 类名命中危险设置页面（无障碍/设备管理员/应用信息） */
    private fun isDangerousSettingsPageForUs(clsName: String?): Boolean {
        if (clsName.isNullOrBlank()) return false
        val lower = clsName.lowercase()
        return DANGER_SETTINGS_ACTIVITY_KEYWORDS.any { kw ->
            lower.contains(kw.lowercase())
        }
    }

    /** 当前设置页面的控件树里是否出现了我们的应用名/包名/服务名（说明孩子正翻到我们这一行准备关闭）*/
    private fun pageMentionsOurApp(root: AccessibilityNodeInfo): Boolean {
        var hit = 0
        traverseTree(root) { node ->
            val t = (node.text ?: "").toString()
            val desc = (node.contentDescription ?: "").toString()
            val vId = (node.viewIdResourceName ?: "").toString()
            if (t.isNotBlank() || desc.isNotBlank() || vId.isNotBlank()) {
                if (DANGER_PAGE_APP_IDENTIFIERS.any { kw ->
                        t.contains(kw, true) || desc.contains(kw, true) || vId.contains(kw, true)
                    }) {
                    hit++
                    // 命中 >= 2 处标识符（比如同时出现"荣耀应用管控"+"下载服务"+"无障碍服务已"）= 100% 是针对我们的开关页
                    if (hit >= 2) return@traverseTree true
                }
            }
            false
        }
        // 单条精确命中"荣耀应用管控"也算（标题就是我们）
        if (hit == 0) {
            traverseTree(root) { node ->
                val t = (node.text ?: "").toString()
                val desc = (node.contentDescription ?: "").toString()
                if (t.equals("荣耀应用管控", true) || desc.equals("荣耀应用管控", true)) {
                    hit = 2
                    return@traverseTree true
                }
                false
            }
        }
        return hit >= 2
    }
}
