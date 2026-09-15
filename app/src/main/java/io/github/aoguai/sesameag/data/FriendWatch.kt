package io.github.aoguai.sesameag.data

import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 好友能量统计（按账号维度持久化）
 *
 * 记录每个好友被当前用户收取了多少能量：
 * - weekGet：本周（周一重置）累计收取
 * - allGet：历史总累计
 * - startTime：首次记录日期
 *
 * 文件存储：`config/{uid}/friendWatch.json`
 * JSON 结构与衍生版本兼容：
 * ```
 * { "friendId": {"name":"张三","allGet":100,"weekGet":30,"startTime":"2026-09-01"} }
 * ```
 */
object FriendWatch {

    private const val TAG = "FriendWatch"

    @Volatile
    private var loadedUserId: String? = null

    @Volatile
    private var joFriendWatch: JSONObject = JSONObject()

    data class FriendWatchEntry(
        val id: String,
        val name: String,
        val weekGet: Int,
        val allGet: Int,
        val startTime: String,
    ) : Comparable<FriendWatchEntry> {
        override fun compareTo(other: FriendWatchEntry): Int {
            val c = other.weekGet.compareTo(this.weekGet)
            return if (c != 0) c else this.name.compareTo(other.name)
        }
    }

    private fun getFile(userId: String): File? =
        Files.getTargetFileofUser(userId, "friendWatch.json")

    private fun ensureLoaded(userId: String?): Boolean {
        if (userId.isNullOrEmpty()) {
            Log.error(TAG, "Invalid userId for FriendWatch")
            return false
        }
        if (userId != loadedUserId) {
            load(userId)
        }
        return true
    }

    /**
     * 记录从某好友处收取了能量。
     *
     * @param currentUid    当前登录账号 uid（决定写入哪个用户目录）
     * @param friendId      好友 uid
     * @param friendName    好友显示名
     * @param collectedEnergy 本次收取能量（g）
     */
    @JvmStatic
    @Synchronized
    fun friendWatch(currentUid: String?, friendId: String?, friendName: String?, collectedEnergy: Int) {
        if (collectedEnergy <= 0 || friendId.isNullOrEmpty()) return
        if (!ensureLoaded(currentUid)) return

        try {
            var joSingle = joFriendWatch.optJSONObject(friendId)
            if (joSingle == null) {
                joSingle = JSONObject().apply {
                    put("name", friendName?.takeIf { it.isNotBlank() } ?: friendId)
                    put("allGet", 0)
                    put("weekGet", 0)
                    put("startTime", TimeUtil.getDateStr())
                }
                joFriendWatch.put(friendId, joSingle)
            } else {
                if (!friendName.isNullOrBlank()) {
                    joSingle.put("name", friendName)
                }
            }
            joSingle.put("weekGet", joSingle.optInt("weekGet", 0) + collectedEnergy)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "friendWatch err", t)
        }
    }

    @JvmStatic
    @Synchronized
    fun load(userId: String?) {
        if (userId.isNullOrEmpty()) {
            loadedUserId = null
            joFriendWatch = JSONObject()
            return
        }
        loadedUserId = userId
        val file = getFile(userId)
        try {
            if (file != null && file.exists() && file.length() > 0) {
                val json = Files.readFromFile(file)
                if (json.isNotBlank()) {
                    joFriendWatch = JSONObject(json)
                } else {
                    joFriendWatch = JSONObject()
                }
            } else {
                joFriendWatch = JSONObject()
            }
            updateDay()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "friendWatch load err", t)
            joFriendWatch = JSONObject()
        }
    }

    @JvmStatic
    @Synchronized
    fun save(userId: String?) {
        if (!ensureLoaded(userId)) return
        val uid = userId ?: return
        try {
            updateDay()
            val file = getFile(uid)
            if (file != null) {
                Files.write2File(joFriendWatch.toString(2), file)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "friendWatch save err", t)
        }
    }

    /**
     * 周一结转：把 weekGet 累加到 allGet，然后 weekGet 归零。
     * 基于文件最后修改时间判断，避免一天内多次 load 重复结转。
     */
    @JvmStatic
    @Synchronized
    fun updateDay() {
        val uid = loadedUserId ?: return
        val file = getFile(uid)
        val lastModified = file?.lastModified() ?: 0L
        if (!needUpdateWeek(lastModified)) return

        try {
            val keys = joFriendWatch.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val single = joFriendWatch.optJSONObject(id) ?: continue
                single.put("allGet", single.optInt("allGet", 0) + single.optInt("weekGet", 0))
                single.put("weekGet", 0)
                if (!single.has("startTime")) {
                    single.put("startTime", TimeUtil.getDateStr())
                }
            }
            val f = getFile(uid)
            if (f != null) {
                Files.write2File(joFriendWatch.toString(2), f)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "friendWatch updateDay err", t)
        }
    }

    private fun needUpdateWeek(last: Long): Boolean {
        if (last <= 0L) return true
        val cLast = Calendar.getInstance().apply { timeInMillis = last }
        val cNow = Calendar.getInstance()
        if (cLast.get(Calendar.DAY_OF_YEAR) == cNow.get(Calendar.DAY_OF_YEAR) &&
            cLast.get(Calendar.YEAR) == cNow.get(Calendar.YEAR)
        ) {
            return false
        }
        return cNow.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
    }

    /**
     * 按 weekGet 降序返回好友列表，用于展示。
     */
    @JvmStatic
    @Synchronized
    fun getList(userId: String?): List<FriendWatchEntry> {
        if (!ensureLoaded(userId)) return emptyList()
        val list = ArrayList<FriendWatchEntry>()
        try {
            val keys = joFriendWatch.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val single = joFriendWatch.optJSONObject(id) ?: continue
                val weekGet = single.optInt("weekGet", 0)
                val allGet = single.optInt("allGet", 0) + weekGet
                list.add(
                    FriendWatchEntry(
                        id = id,
                        name = single.optString("name", id),
                        weekGet = weekGet,
                        allGet = allGet,
                        startTime = single.optString("startTime", "无"),
                    )
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "friendWatch getList err", t)
        }
        return list.sorted()
    }

    /**
     * 生成可读的统计文本（含周/总累计 + 开始统计时间）。
     */
    @JvmStatic
    @Synchronized
    fun getText(userId: String?): String {
        val list = getList(userId)
        if (list.isEmpty()) return "（暂无好友能量统计）"
        val sb = StringBuilder()
        sb.appendLine("好友能量统计（按本周收取降序）")
        sb.appendLine("=".repeat(40))
        for (entry in list) {
            sb.append("${entry.name}")
                .append("  周收:${entry.weekGet}g")
                .append("  总收:${entry.allGet}g")
                .append("  起:${entry.startTime}")
                .appendLine()
        }
        return sb.toString()
    }

    @JvmStatic
    @Synchronized
    fun unload() {
        loadedUserId = null
        joFriendWatch = JSONObject()
    }
}
