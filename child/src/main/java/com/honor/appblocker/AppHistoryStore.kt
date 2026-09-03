package com.honor.appblocker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * APP 打开历史记录（孩子端）
 *
 * - ForegroundPollerService 每次轮询检测到前台包名变化时调用 onForeground(pkg)
 * - 前台变为桌面/系统界面/息屏时调用 onLeaveApp() 关闭当前记录
 * - 每条记录：{pkg, label, start, end}，保留最近 100 条，持久化到 SharedPreferences
 * - 家长端通过 /app_history 命令读取：当前正在用的 APP + 最近打开记录（含时间）
 */
object AppHistoryStore {
    private const val PREFS = "app_history"
    private const val KEY = "entries"        // JSON 数组，最旧在前
    private const val KEY_CUR = "current_pkg"
    private const val KEY_CUR_START = "current_start"
    private const val MAX = 100              // 最多保留条数

    @Volatile private var cached: JSONArray? = null  // 内存缓存

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): JSONArray {
        if (cached == null) {
            cached = runCatching { JSONArray(prefs(ctx).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        }
        return cached!!
    }

    @Synchronized
    private fun save(ctx: Context, arr: JSONArray) {
        cached = arr
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 轮询到真实 APP 前台时调用：包名变化则滚动记录 */
    @Synchronized
    fun onForeground(ctx: Context, pkg: String) {
        val now = System.currentTimeMillis()
        val cur = prefs(ctx).getString(KEY_CUR, null)
        if (pkg == cur) return  // 没变化，不重复记录

        val arr = load(ctx)
        // 关闭上一条记录的结束时间
        if (cur != null && arr.length() > 0) {
            val last = arr.optJSONObject(arr.length() - 1)
            if (last != null && last.optString("pkg") == cur && last.optLong("end", 0) == 0L) {
                last.put("end", now)
            }
        }
        // 新开一条
        arr.put(JSONObject().put("pkg", pkg).put("start", now).put("end", 0L))
        while (arr.length() > MAX) arr.remove(0)

        prefs(ctx).edit().putString(KEY_CUR, pkg).putLong(KEY_CUR_START, now).apply()
        save(ctx, arr)
    }

    /** 回到桌面/息屏等非 APP 界面时调用：关闭当前记录 */
    @Synchronized
    fun onLeaveApp(ctx: Context) {
        val cur = prefs(ctx).getString(KEY_CUR, null) ?: return
        val now = System.currentTimeMillis()
        val arr = load(ctx)
        if (arr.length() > 0) {
            val last = arr.optJSONObject(arr.length() - 1)
            if (last != null && last.optString("pkg") == cur && last.optLong("end", 0) == 0L) {
                last.put("end", now)
            }
        }
        prefs(ctx).edit().remove(KEY_CUR).apply()
        save(ctx, arr)
    }

    /** 生成返回给家长端的 JSON：当前正在用的 + 最近历史（新在前） */
    @Synchronized
    fun buildJson(ctx: Context, limit: Int = 25): String {
        val now = System.currentTimeMillis()
        val arr = load(ctx)

        val current = JSONObject()
        val cur = prefs(ctx).getString(KEY_CUR, null)
        val curStart = prefs(ctx).getLong(KEY_CUR_START, 0L)
        if (cur != null) {
            current.put("pkg", cur)
            current.put("label", label(ctx, cur))
            current.put("start", curStart)
        } else if (arr.length() > 0) {
            // 兜底：最后一条 end==0 视为当前
            val last = arr.optJSONObject(arr.length() - 1)
            if (last != null && last.optLong("end", 0) == 0L) {
                current.put("pkg", last.optString("pkg"))
                current.put("label", label(ctx, last.optString("pkg")))
                current.put("start", last.optLong("start", now))
            }
        }
        if (current.length() > 0) {
            current.put("duration_sec", ((now - current.optLong("start")) / 1000).coerceAtLeast(0))
        }

        val history = JSONArray()
        var count = 0
        for (i in arr.length() - 1 downTo 0) {
            if (count >= limit) break
            val e = arr.optJSONObject(i) ?: continue
            history.put(
                JSONObject()
                    .put("pkg", e.optString("pkg"))
                    .put("label", label(ctx, e.optString("pkg")))
                    .put("start", e.optLong("start", 0))
                    .put("end", e.optLong("end", 0))
            )
            count++
        }
        return JSONObject().put("ok", true).put("current", current).put("history", history).toString()
    }

    /** 获取 APP 显示名（找不到用包名） */
    private fun label(ctx: Context, pkg: String): String {
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
