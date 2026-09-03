package com.honor.appblocker

/**
 * 游戏拦截匹配器 —— 纯逻辑（无 Android 上下文依赖）
 * 供 AccessibilityService 和 ForegroundPollerService 共用同一套匹配规则
 */
object GameBlockMatcher {

    // ===== 微信小游戏/小程序 特征 =====
    val WECHAT_GAME_ACTIVITY_KEYWORDS = listOf(
        "WxGameStubActivity", "GameCenter", "MiniGame", "MiniProgram",
        "AppBrandLauncher", "AppBrand", "WeAppLauncher", "game"
    )

    val WECHAT_GAME_WINDOW_KEYWORDS = listOf(
        "游戏中心", "微信游戏", "小游戏", "游戏详情", "开始游戏",
        "游戏直播", "gamecenter", "weapp", "wx_game",
        "腾讯游戏", "领取礼包", "游戏好友", "排行榜", "游戏圈",
        "王者荣耀", "和平精英", "游戏推送", "游戏特权"
    )

    // ===== QQ/抖音/快手等 小游戏 Activity =====
    val SHORTVIDEO_GAME_ACTIVITY_KEYWORDS = listOf(
        // QQ 小程序/小游戏
        "MiniAppActivity", "MiniGameActivity", "QQMini", "QQMiniGame", "QMPActivity",
        "MiniProgram", "QQBrowserMini",
        // QQ 游戏中心/应用宝
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

    // ===== 浏览器 H5 游戏 关键词 =====
    val BROWSER_GAME_URL_OR_TITLE_KEYWORDS = listOf(
        "4399", "7k7k", "7723", "2144", "h5game",
        "h5游戏", "在线游戏", "网页游戏", "在线玩",
        "开始游戏", "立即游戏", "进入游戏", "继续游戏",
        "点击玩", "免费玩",
        "/game/", "/games/", "/play/", "/h5/",
        "wan.qq.com", "game.qq.com", "h.4399.com", "m.4399.com",
        "gamecenter", "game_page", "minigame"
    )

    // ===== 强特征词（单次命中即拦，用于 QQ 动态页入口等）=====
    val STRONG_GAME_TEXT_KEYWORDS = listOf(
        "游戏中心", "小游戏", "QQ小游戏", "开始游戏", "进入游戏"
    )

    // ===== 聊天页特征（豁免文本检测，防止聊天内容误拦）=====
    val CHAT_PAGE_ACTIVITY_KEYWORDS = listOf(
        "chatactivity", "chatting", "troopchat", "c2cmessage", "chatmsg"
    )

    // ===== 短视频/社交 游戏页面文本 =====
    val SHORTVIDEO_GAME_WINDOW_KEYWORDS = listOf(
        "小游戏中心", "游戏中心", "小游戏", "小程序游戏",
        "开始游戏", "进入游戏", "继续玩",
        "游戏推荐", "热门游戏", "游戏礼包", "福利领取",
        "抖音小游戏", "摸摸鱼",
        "QQ小游戏", "厘米游戏", "好友在玩",
        "应用宝", "游戏下载", "下载游戏", "游戏安装包",
        "王者荣耀", "和平精英", "QQ经典农场", "经典农场"
    )

    // ===== 浏览器包名 =====
    val BROWSER_PACKAGES = listOf(
        "com.android.chrome", "com.huawei.browser", "com.hihonor.browser",
        "com.sec.android.app.sbrowser", "com.microsoft.emmx",
        "com.UCMobile", "com.uc.browser", "com.quark.browser",
        "com.tencent.mtt", "com.tencent.browser", "com.baidu.browser",
        "org.mozilla.firefox", "com.vivo.browser", "com.heytap.browser"
    )

    // ===== 微信/QQ/抖音/快手 等 社交 App 包名 =====
    val SOCIAL_PACKAGES = listOf(
        "com.tencent.mm",           // 微信
        "com.tencent.mobileqq",     // QQ
        "com.tencent.tim",          // TIM
        "com.ss.android.ugc.aweme", // 抖音
        "com.smile.gifmaker",       // 快手
        "com.kuaishou.nebula",      // 快手极速版
        "com.kuaishou.android",
        "tv.danmaku.bili",          // B站
        "com.eg.android.AlipayGphone", // 支付宝
        "com.netease.cloudmusic",   // 网易云（直播间也可能有小游戏）
        "com.tencent.qqmusic"
    )

    // ===== 设置包名 =====
    const val PKG_SETTINGS = "com.android.settings"

    // ===== 判断函数 =====

    /** 根据包名 + Activity 类名 + 窗口标题判断是否应拦截（返回拦截原因，null=放行）*/
    fun shouldBlock(pkg: String, activityName: String?, prefs: PrefsManager, windowTitle: String = "", hasWebView: Boolean = false): String? {
        // 暂停期内全放行（家长通过远程指令临时放行小程序）
        if (prefs.isPaused()) return null
        val cls = activityName?.lowercase() ?: ""
        val clsAvailable = activityName != null && cls.isNotEmpty() && cls != "android.widget.framelayout" && cls != "android.widget.linearlayout"
        val title = windowTitle.lowercase()

        // 主界面/Tab 页豁免表：包名 -> 已知的非游戏 title
        // 微信 Tab：微信(聊天)、通讯录、发现、我；QQ Tab：消息、联系人、动态、我
        val mainUIExemptMap = mapOf(
            "com.tencent.mm" to listOf("微信", "wechat", "通讯录", "通信录", "发现", "我", "me"),
            "com.tencent.mobileqq" to listOf("qq", "消息", "联系人", "动态", "我"),
            "com.tencent.tim" to listOf("tim", "腾讯tim", "消息", "联系人", "我"),
            "com.ss.android.ugc.aweme" to listOf("抖音", "首页", "消息", "我"),
            "com.smile.gifmaker" to listOf("快手", "发现", "我"),
            "com.kuaishou.nebula" to listOf("极速版", "快手极速", "发现", "我"),
            "tv.danmaku.bili" to listOf("bilibili", "哔哩哔哩", "首页", "推荐", "热门", "追番", "影视", "分区", "动态", "我的", "消息"),
            "com.netease.cloudmusic" to listOf("网易云音乐", "我的", "发现", "云村", "播客"),
            "com.tencent.qqmusic" to listOf("qq音乐", "我的", "发现", "歌单", "播客"),
            "com.eg.android.AlipayGphone" to listOf("支付宝", "首页", "理财", "生活", "我的"),
        )
        val isMainUI = mainUIExemptMap[pkg]?.any { it.lowercase() == title } == true

        // 0) 自定义黑名单 App（王者荣耀/和平精英等独立游戏 App）—— 最高优先级
        //    DeviceOwner 丢失后无法禁止安装，但可以在运行时踢回桌面
        if (pkg in prefs.blockedGamePackages) {
            return "🚫 游戏 App 已禁止运行"
        }

        // 0.5) 微信游戏中心页（发现→游戏，下载游戏的入口）—— title 含游戏关键词即拦
        if (prefs.blockWechatGame && pkg == "com.tencent.mm" && title.isNotEmpty() &&
            WECHAT_GAME_WINDOW_KEYWORDS.any { it.lowercase() in title }) {
            return "🚫 微信游戏中心"
        }

        // 1) 浏览器 + 浏览器 H5 游戏
        if (prefs.blockBrowserGame && BROWSER_PACKAGES.any { pkg == it }) {
            if (clsAvailable && (cls.contains("webview") || cls.contains("browser") || cls.contains("webpage") ||
                BROWSER_GAME_URL_OR_TITLE_KEYWORDS.any { it.lowercase() in cls })) {
                return "🚫 浏览器 H5 游戏页面"
            }
            if (hasWebView) return "🚫 浏览器 H5 游戏（WebView）"
            return null  // 浏览器主界面放行
        }

        // 2) 微信小游戏/小程序容器
        if (prefs.blockWechatGame && pkg == "com.tencent.mm") {
            if (isMainUI) return null  // 主界面/Tab 页放行
            // 精确匹配：Activity 类名含 AppBrand 等关键词
            if (clsAvailable && WECHAT_GAME_ACTIVITY_KEYWORDS.any { it.lowercase() in cls } &&
                !CHAT_PAGE_ACTIVITY_KEYWORDS.any { it in cls }) {
                return "🚫 微信小游戏/小程序"
            }
            // 荣耀 ROM 兜底：小游戏窗口 title 是空字符串
            // （主界面 title='微信'，通信录/发现/我 title 各自名，聊天页 title=对方名字）
            // title 为空 → 一定是小游戏/小程序容器
            if (windowTitle.isEmpty()) {
                return "🚫 微信小游戏/小程序（空标题）"
            }
            // 其他放行（聊天页 title 是对方名字，没法穷举，宁可不拦）
            return null
        }

        // 3) QQ/抖音/快手/B站/支付宝/网易云 等 社交/内容 App 内小游戏
        if (prefs.blockWechatGame && SOCIAL_PACKAGES.any { pkg == it }) {
            if (isMainUI) return null  // 主界面/Tab 页放行

            // ===== B站 单独处理：只拦精确的 游戏中心 + 小游戏容器 =====
            if (pkg == "tv.danmaku.bili") {
                if (clsAvailable && (cls.contains("bilimini") || cls.contains("bilibilimini") ||
                    cls.contains("bvgame") || cls.contains("biligame"))) {
                    return "🚫 B站游戏中心/小游戏"
                }
                // 其他全放行（视频播放页、分区页等）
                return null
            }

            // ===== 网易云音乐 / QQ音乐 / 支付宝 单独处理 =====
            if (pkg == "com.netease.cloudmusic" || pkg == "com.tencent.qqmusic" || pkg == "com.eg.android.AlipayGphone") {
                if (clsAvailable && (cls.contains("miniapp") || cls.contains("mini_app") || cls.contains("minigame") || cls.contains("mini_game"))) {
                    return "🚫 App 内小游戏"
                }
                return null  // 其他全放行
            }

            // ===== QQ/抖音/快手 继续用通用规则 =====
            // 3.0) 游戏中心页（title 含"游戏中心"等关键词）—— 一进入就拦
            if (windowTitle.isNotEmpty() &&
                SHORTVIDEO_GAME_WINDOW_KEYWORDS.any { it.lowercase() in windowTitle.lowercase() }) {
                return "🚫 社交 App 游戏中心"
            }
            if (clsAvailable && SHORTVIDEO_GAME_ACTIVITY_KEYWORDS.any { it.lowercase() in cls } &&
                !CHAT_PAGE_ACTIVITY_KEYWORDS.any { it in cls }) {
                return "🚫 社交 App 内小游戏"
            }
            // 兜底：title 为空 → 小游戏（只对 QQ/抖音/快手 生效，B 站等上面已 return null）
            if (windowTitle.isEmpty()) {
                return "🚫 社交 App 内小游戏（空标题）"
            }
            return null
        }

        return null
    }
}
