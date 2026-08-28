package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.rpc.debug.DebugRpc
import io.github.aoguai.sesameag.task.reserve.ReserveRpcCall
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil
import org.json.JSONObject
import java.io.File

/**
 * 已种树×地区落盘 + 每日「可兑换但未种」检测 + 通知栏提醒。
 *
 * 职责：
 * 1. savePlantedSnapshot(userId, ...) 把 DebugRpc.fetchPlantedByAlias 查出来的已种树+地区
 *    连同保护地真名一起写到账号目录下的 planted_tree_region.json（供每日比对读取，避免每次都重查接口）。
 *    读取方若发现缓存不存在或过期会自动用 DebugRpc 再拉一次。
 * 2. runIfEnabled(userId)：
 *      - 若今日已经提醒过则跳过。
 *      - 拉 queryTreeItemsForExchange → AVAILABLE → 逐个 queryTreeForExchange 拿到
 *        (treeName, region, budget) 三元组 → 与已种数据对比，得到"当前可兑换但还没种的"
 *        树×地区清单。
 *      - 如有缺项：合并成一条文本，Notify.sendAlert() 通知栏提醒。
 *      - 无论结果都落今日标记（有缺项 or 已检查但无缺项都不再同日重复），
 *        避免每次 TaskRunner 循环都撞接口。
 *
 * 注意：
 * - 普通树才进「可兑换但未种」检测；保护地没有「当前预算」概念，不参与检测。
 * - 差集使用 "全等 + 双向子串" 匹配，兼容少打「县/旗/区」等后缀的情况（与 compareWithFullConfig 一致）。
 * - 单个 queryTreeForExchange 调用后 sleepCompat(60ms)，避免一次性扫过多项目触发风控。
 */
object ForestExchangeNotifier {

    private const val TAG = "ForestExchangeNotifier"

    data class PlantedSnapshot(
        val userId: String,
        val savedAt: Long,
        val trees: Map<String, Set<String>>,
        /** 陆地保护地真名（项目名，如"五宝山保护地"） */
        val reserves: Set<String>,
        /** 海洋项目真名（证书 projectName，如"鳗草""金沙湾海滩""守护中华白海豚"），独立于 reserves */
        val oceans: Set<String>
    ) {
        fun toJsonText(): String {
            val m = LinkedHashMap<String, Any>()
            m["userId"] = userId
            m["savedAt"] = savedAt
            m["savedAtStr"] = TimeUtil.getCommonDate(savedAt)
            val treesArr = LinkedHashMap<String, List<String>>()
            for ((k, v) in trees.entries.sortedBy { it.key }) treesArr[k] = v.sorted()
            m["trees"] = treesArr
            m["reserves"] = reserves.sorted()
            m["oceans"] = oceans.sorted()
            return JsonUtil.formatJson(m) ?: ""
        }

        companion object {
            @JvmStatic
            fun fromJsonText(text: String): PlantedSnapshot? {
                val jo = runCatching { JSONObject(text) }.getOrNull() ?: return null
                val uid = jo.optString("userId", "")
                val savedAt = jo.optLong("savedAt", 0L)
                val trees = LinkedHashMap<String, Set<String>>()
                jo.optJSONObject("trees")?.let { tj ->
                    val keys = tj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = tj.optJSONArray(k) ?: continue
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                        trees[k] = set
                    }
                }
                val reserves = HashSet<String>()
                jo.optJSONArray("reserves")?.let { rj ->
                    for (i in 0 until rj.length()) reserves.add(rj.optString(i, ""))
                }
                val oceans = HashSet<String>()
                // 旧版 JSON 没有 oceans 字段也 OK：保持空集合，不抛错。
                // 调用方（runIfEnabled）若发现 oceans 空 但 可兑换海洋项目不为空，只是跳过/报告未种，不会反向覆盖文件（我们本来就不写）
                jo.optJSONArray("oceans")?.let { oj ->
                    for (i in 0 until oj.length()) oceans.add(oj.optString(i, ""))
                }
                return PlantedSnapshot(uid, savedAt, trees, reserves, oceans)
            }
        }
    }

    data class AvailableItem(
        val treeName: String,
        val region: String,
        val budget: Int,
        val projectId: String
    )

    data class AvailableReserve(
        val displayName: String,
        val projectId: String,
        val rawProjectType: String
    ) {
        /**
         * 用于比对已种的"核心名字"：去掉末尾的"保护地"后缀（如果有的话）。
         * 枚举 itemName 是 "五宝山保护地"这种格式，但配置/已种里有时写 "五宝山"，
         * 比较时要兼容两种写法（全等 + 双向子串 + core name 兜底）。
         */
        val coreName: String
            get() {
                val n = displayName.trim()
                return when {
                    n.endsWith("保护地") -> n.dropLast(3).trim()
                    else -> n
                }
            }
    }

    data class AvailableOcean(
        /** cultivationName：中文展示名，如"鳗草"、"金沙湾海滩" */
        val displayName: String,
        val code: String,
        /** 每份所需能量 */
        val energy: Long,
        /** cultivationItemVOList.certNum = 本项目用户已领证书数 */
        val certNum: Int,
        val cultivationType: String,
        /** 原始 applyAction：AVAILABLE / NO_STOCK 等，仅作日志展示提示，不参与比对 */
        val rawAction: String = ""
    ) {
        /** 海洋名字兼容比对时用的核心名：一般就是展示名本身 */
        val coreName: String get() = displayName.trim()
    }

    /**
     * 取"蚂蚁森林-海洋"全部种植项目条目（不限 AVAILABLE / NO_STOCK）。
     *
     * 用于展示当前登录账号"已保护过哪些海洋项目"——海洋项目专属 cultivationList 接口的
     * cultivationItemVOList[].certNum 就是最准的已领证书计数（certNum>0 代表用户至少保护过一次）。
     * 接口请求参数**不带 targetUserId**，所以永远返回"当前登录账号自己"的数字；查询他人时不要
     * 调用此方法展示"对方的海洋保护记录"，否则会把自己的数据误算成别人的。
     */
    @JvmStatic
    fun fetchAllCultivationItems(): List<AvailableOcean> {
        val all = ArrayList<AvailableOcean>()
        val resp = ReserveRpcCall.queryOceanCultivationList()
        if (resp.isBlank()) return all
        val jo = runCatching { JSONObject(resp) }.getOrNull() ?: return all
        if (!ResChecker.checkRes(TAG, jo)) return all
        val arr = jo.optJSONArray("cultivationItemVOList") ?: return all
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val name = it.optString("cultivationName", "").trim()
            if (name.isEmpty()) continue
            val code = it.optString("cultivationCode", "")
            val ctype = it.optString("cultivationType", "")
            val energy = it.optLong("energy", 0L)
            val certN = it.optInt("certNum", 0)
            val action = it.optString("applyAction", "")
            val prevIdx = all.indexOfFirst { o -> o.displayName == name }
            if (prevIdx >= 0) {
                val prev = all[prevIdx]
                if (certN > prev.certNum) {
                    all[prevIdx] = prev.copy(
                        code = code.ifBlank { prev.code },
                        energy = if (energy > 0) energy else prev.energy,
                        certNum = certN,
                        cultivationType = ctype.ifBlank { prev.cultivationType },
                        rawAction = action.ifBlank { prev.rawAction }
                    )
                }
                continue
            }
            all.add(
                AvailableOcean(
                    displayName = name,
                    code = code,
                    energy = energy,
                    certNum = certN,
                    cultivationType = ctype,
                    rawAction = action
                )
            )
        }
        return all
    }

    data class AvailableBundle(
        val trees: List<AvailableItem>,
        val reserves: List<AvailableReserve>,
        val oceans: List<AvailableOcean>
    )

    /** 给 DebugRpc 等外部调用方复用：查"当前可兑换的全部普通树×地区"，已按树+地区去重合并预算。 */
    @JvmStatic
    fun fetchAllAvailable(): List<AvailableItem> =
        runCatching { fetchAvailableBundle().trees }.getOrElse { t: Throwable ->
            Log.runtime(TAG, "fetchAllAvailable 失败：")
            Log.printStackTrace(TAG, t)
            emptyList()
        }

    /** 给 DebugRpc / runIfEnabled 复用：查"可兑换的（普通树×地区 + 保护地 + 海洋项目）"三者。 */
    @JvmStatic
    fun fetchAllAvailableBundle(): AvailableBundle =
        runCatching { fetchAvailableBundle() }.getOrElse { t: Throwable ->
            Log.runtime(TAG, "fetchAllAvailableBundle 失败：")
            Log.printStackTrace(TAG, t)
            AvailableBundle(emptyList(), emptyList(), emptyList())
        }

    /** 给外部复用的"已种地区/配置地区"兼容比对（全等优先、双向子串兜底）。 */
    @JvmStatic
    fun regionMatchCompat(plantedRegions: Set<String>, cfgRegion: String): Boolean =
        regionMatch(plantedRegions, cfgRegion)

    /** 保护地名字兼容比对：已种真名 vs 可兑换条目的显示名。 */
    @JvmStatic
    fun reserveNameMatch(plantedReserveName: String, availableReserve: AvailableReserve): Boolean {
        val a = plantedReserveName.trim()
        if (a.isEmpty()) return false
        val b = availableReserve.displayName.trim()
        val c = availableReserve.coreName
        if (a == b || a == c) return true
        if (a.equals(b, ignoreCase = true) || a.equals(c, ignoreCase = true)) return true
        if (a.contains(b) || b.contains(a)) return true
        if (c.isNotEmpty() && (a.contains(c) || c.contains(a))) return true
        return false
    }

    /** 海洋项目名字兼容比对：已种真名（来自 projectName） vs 可兑换海洋 cultivationName。 */
    @JvmStatic
    fun oceanNameMatch(plantedReserveName: String, availableOcean: AvailableOcean): Boolean {
        val a = plantedReserveName.trim()
        if (a.isEmpty()) return false
        val b = availableOcean.displayName.trim()
        if (b.isEmpty()) return false
        if (a == b) return true
        if (a.equals(b, ignoreCase = true)) return true
        if (a.contains(b) || b.contains(a)) return true
        // 陆地 alias 是 "丛生鳗草"，但海洋 cultivationName 可能是 "鳗草"。
        // 反之若陆地有多个 alias 对应同一植物，也允许名字子串命中。
        val simplA = a.replace("丛生", "").replace("公益", "").replace("自然", "").replace("保护地", "").trim()
        val simplB = b.replace("保护地", "").trim()
        if (simplA.isNotEmpty() && simplB.isNotEmpty()) {
            if (simplA == simplB) return true
            if (simplA.contains(simplB) || simplB.contains(simplA)) return true
        }
        return false
    }

    /**
     * 把已种结果写入账号目录。
     * @param userId  当前账号 userId（为 "" 时退化为 "default" 子目录）
     * @param cnByTree  普通树种中文名 → 中文地区集合（已包含合种）
     * @param reserveNames  已种陆地保护地真名集合（projectName，不是面积/alias）
     * @param oceanNames  已种海洋项目真名集合（与陆地保护地独立拆分存储）
     */
    @JvmStatic
    fun savePlantedSnapshot(
        userId: String,
        cnByTree: Map<String, Set<String>>,
        reserveNames: Set<String>,
        oceanNames: Set<String> = emptySet()
    ): File {
        val safeUid = userId.trim().ifBlank { "default" }
        val snap = PlantedSnapshot(
            userId = safeUid,
            savedAt = System.currentTimeMillis(),
            trees = cnByTree,
            reserves = reserveNames,
            oceans = oceanNames
        )
        val file = Files.getPlantedSnapshotFile(safeUid)
        Files.write2File(snap.toJsonText(), file)
        Log.debug(
            TAG,
            "已种树快照已写入：${file.absolutePath} (trees=${cnByTree.size}, reserves=${reserveNames.size}, oceans=${oceanNames.size})"
        )
        return file
    }

    private fun loadSnapshot(userId: String): PlantedSnapshot? {
        val safeUid = userId.trim().ifBlank { "default" }
        val file = Files.getPlantedSnapshotFile(safeUid)
        if (!file.exists() || file.length() == 0L) return null
        val text = Files.readFromFile(file).ifBlank { return null }
        val snap = runCatching { PlantedSnapshot.fromJsonText(text) }.getOrNull()
        if (snap == null) {
            Log.runtime(TAG, "已种树快照解析失败：${file.absolutePath}")
            return null
        }
        return snap
    }

    /**
     * 取得已种快照：**只读文件**，绝不自动做 RPC 查询。
     *
     * 原因：自动任务循环初期（会话刚建立）调用 queryAreaTrees + 证书补名 极易拿到"半残数据"
     * （regionConfig 为空或保护地证书全 0），一旦落盘会反向覆盖掉"手动查询"写进去的真数据，
     * 导致后续对比全错、通知栏疯狂误报。
     *
     * 所以只有一个数据源：用户手动点「查询已种树+地区」时，getPlantedTreeItems() 末尾写入的那份
     * planted_tree_region.json。文件不存在就返回 null（调用方跳过今天的检查并且不打已检查标记，
     * 等用户哪天手动查完、生成了文件，下一次 TaskRunner 自动循环就能直接用）。
     *
     * 缓存有效期：23 小时。超过就再尝试读一次（若文件还在就用新内容），绝不主动 RPC。
     */
    @JvmStatic
    fun getOrRefreshPlanted(userId: String, @Suppress("UNUSED_PARAMETER") freshNow: Boolean = false): PlantedSnapshot? {
        val safeUid = userId.trim().ifBlank { "default" }
        val cached = loadSnapshot(safeUid) ?: return null
        val ageHours = (System.currentTimeMillis() - cached.savedAt) / (1000L * 60 * 60)
        // 文件太老（>23h）重新尝试读一次；若被用户手动更新过则取最新值，若仍是同一份就返回它
        if (ageHours >= 23) {
            val newer = loadSnapshot(safeUid)
            if (newer != null) return newer
        }
        return cached
    }

    private fun fetchAvailableBundle(): AvailableBundle {
        val treeList = ArrayList<AvailableItem>()
        val reserveList = ArrayList<AvailableReserve>()
        val oceanList = ArrayList<AvailableOcean>()

        // (1) 陆地：queryTreeItemsForExchange 拉普通树(TREE) + 保护地(RESERVE)
        val resp1 = ReserveRpcCall.queryTreeItemsForExchange()
        if (resp1.isNotBlank()) {
            val jo = JSONObject(resp1)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.debug(TAG, "陆地queryTreeItemsForExchange失败：${jo.optString("resultDesc", "未知错误")}")
            } else {
                val arr = jo.optJSONArray("treeItems")
                if (arr != null) {
                    val sb = StringBuilder("陆地树/保护地枚举：")
                    var count = 0
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val applyAction = item.optString("applyAction")
                        val projectTypeRaw = item.optString("projectType", "<null>")
                        val projectType = if (item.isNull("projectType")) "" else item.optString("projectType", "")
                        val itemId = item.optString("itemId", "")
                        val itemName = item.optString("itemName", "")
                        if (applyAction != "AVAILABLE") continue
                        count += 1
                        sb.append(" [(${projectTypeRaw})${itemName.ifEmpty { "<no-name>" }}/${itemId.ifEmpty { "id?" }}]")
                        if (projectType == "RESERVE" || (projectType.isEmpty() && itemName.contains("保护地"))) {
                            if (itemName.isNotBlank() && itemId.isNotBlank()) {
                                reserveList.add(
                                    AvailableReserve(
                                        displayName = itemName.trim(),
                                        projectId = itemId,
                                        rawProjectType = projectTypeRaw
                                    )
                                )
                            }
                            continue
                        }
                        if (projectType != "TREE") continue
                        val projectId = itemId.ifBlank { continue }
                        val detailResp = ReserveRpcCall.queryTreeForExchange(projectId)
                        if (detailResp.isBlank()) {
                            GlobalThreadPools.sleepCompat(60)
                            continue
                        }
                        try {
                            val dj = JSONObject(detailResp)
                            if (!ResChecker.checkRes(TAG, dj)) {
                                continue
                            }
                            val tree = dj.optJSONObject("exchangeableTree") ?: continue
                            val region = tree.optString("region", "").trim()
                            val treeName = tree.optString("treeName", itemName).trim()
                            val budget = tree.optInt("currentBudget", 0)
                            if (region.isNotEmpty() && treeName.isNotEmpty()) {
                                treeList.add(AvailableItem(treeName, region, budget, projectId))
                            }
                        } catch (t: Throwable) {
                            Log.debug(TAG, "解析queryTreeForExchange[$projectId]失败：${t.message}")
                        } finally {
                            GlobalThreadPools.sleepCompat(60)
                        }
                    }
                }
            }
        }

        // (2) 海洋：queryOceanCultivationList 拉 cultivationItemVOList 中 applyAction=AVAILABLE 的条目
        val resp2 = ReserveRpcCall.queryOceanCultivationList()
        if (resp2.isNotBlank()) {
            val jo = runCatching { JSONObject(resp2) }.getOrNull()
            if (jo != null && ResChecker.checkRes(TAG, jo)) {
                val arr = jo.optJSONArray("cultivationItemVOList")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i)
                        val action = it.optString("applyAction", "")
                        val name = it.optString("cultivationName", "").trim()
                        val code = it.optString("cultivationCode", "")
                        val ctype = it.optString("cultivationType", "")
                        val energy = it.optLong("energy", 0L)
                        val certN = it.optInt("certNum", 0)
                        if (action == "AVAILABLE" && name.isNotBlank()) {
                            oceanList.add(
                                AvailableOcean(
                                    displayName = name,
                                    code = code,
                                    energy = energy,
                                    certNum = certN,
                                    cultivationType = ctype,
                                    rawAction = action
                                )
                            )
                        }
                    }
                }
            } else {
                Log.debug(TAG, "海洋queryOceanCultivationList失败")
            }
        }

        // 去重
        val mergedTrees = LinkedHashMap<String, AvailableItem>()
        for (av in treeList) {
            val key = av.treeName + "|" + av.region
            val prev = mergedTrees[key]
            mergedTrees[key] = if (prev == null) av else prev.copy(budget = prev.budget + av.budget.coerceAtLeast(0))
        }
        val seenRes = HashSet<String>()
        val dedupReserves = ArrayList<AvailableReserve>()
        for (r in reserveList) if (seenRes.add(r.displayName)) dedupReserves.add(r)
        val seenOcean = HashSet<String>()
        val dedupOceans = ArrayList<AvailableOcean>()
        for (o in oceanList) if (seenOcean.add(o.displayName)) dedupOceans.add(o)
        return AvailableBundle(mergedTrees.values.toList(), dedupReserves, dedupOceans)
    }

    private fun regionMatch(plantedRegions: Set<String>, cfgRegion: String): Boolean {
        val rTrim = cfgRegion.trim()
        if (rTrim.isEmpty()) return false
        for (p in plantedRegions) {
            if (p == rTrim) return true
            if (p.equals(rTrim, ignoreCase = true)) return true
            if (p.contains(rTrim) || rTrim.contains(p)) return true
        }
        return false
    }

    @JvmStatic
    fun runIfEnabled(userId: String) {
        try {
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "离线模式，跳过「可兑换未种树」检查")
                return
            }
            val safeUid = userId.trim().ifBlank { "default" }
            val flagKey = StatusFlags.FLAG_FOREST_EXCHANGE_UNPLANTED_ALERT_TODAY + "::$safeUid"
            if (Status.hasFlagToday(flagKey)) {
                Log.debug(TAG, "[$safeUid]今日已做过「可兑换未种树」检查，跳过")
                return
            }
            val planted = getOrRefreshPlanted(safeUid, freshNow = false)
            if (planted == null) {
                Log.debug(TAG, "[$safeUid]拿不到已种树快照，跳过本次检查（下次重试）")
                return
            }
            val bundle = fetchAvailableBundle()
            if (bundle.trees.isEmpty() && bundle.reserves.isEmpty() && bundle.oceans.isEmpty()) {
                Log.debug(TAG, "[$safeUid]无可兑换项目，标记今日已检查")
                Status.setFlagToday(flagKey)
                return
            }
            // 差集(1)：可兑换普通树×地区 - 已种树×地区
            val missingTreeLines = ArrayList<String>()
            var totalMissingBudget = 0
            for (av in bundle.trees) {
                val have = planted.trees[av.treeName] ?: emptySet()
                if (regionMatch(have, av.region)) continue
                missingTreeLines.add("${av.treeName}@${av.region}(余${av.budget}株)")
                totalMissingBudget += av.budget.coerceAtLeast(0)
            }
            // 差集(2)：可兑换保护地 - 已种保护地真名
            val missingReserveNames = ArrayList<String>()
            for (ar in bundle.reserves) {
                val hit = planted.reserves.any { r -> reserveNameMatch(r, ar) }
                if (hit) continue
                missingReserveNames.add(ar.displayName)
            }
            // 差集(3)：可兑换海洋项目 - 判断已种/未种的依据是 cultivationItemVOList[i].certNum
            //   certNum > 0 → 该用户在本海洋项目已经领过至少一份证书（=已种/已保护），直接跳过；
            //   certNum == 0 且 applyAction==AVAILABLE → 还没保护过，进未种清单。
            // 不再从 planted.oceans / EnvironmentCertDetailList 反查，因为陆地 Qiuqie / CSMANCAO 等 alias
            //   虽然名字像海洋物种，但其实是陆地种树版的；海洋项目自己的 certNum 就是最准的口径。
            val missingOceanLines = ArrayList<String>()
            for (oc in bundle.oceans) {
                if (oc.certNum > 0) continue
                missingOceanLines.add(oc.displayName)
            }
            val noMissing =
                missingTreeLines.isEmpty() && missingReserveNames.isEmpty() && missingOceanLines.isEmpty()
            if (noMissing) {
                Log.debug(
                    TAG,
                    "[$safeUid]可兑换树${bundle.trees.size}项×保护地${bundle.reserves.size}项×海洋${bundle.oceans.size}项 × 已种全对齐，无缺项"
                )
                Status.setFlagToday(flagKey)
                return
            }
            // 发通知：把树缺项 + 保护地缺项 + 海洋缺项都塞进正文
            val titleParts = ArrayList<String>()
            if (missingTreeLines.isNotEmpty()) titleParts.add("${missingTreeLines.size}个树×地区")
            if (missingReserveNames.isNotEmpty()) titleParts.add("${missingReserveNames.size}个保护地")
            if (missingOceanLines.isNotEmpty()) titleParts.add("${missingOceanLines.size}个海洋")
            val title = "🌱发现${titleParts.joinToString(" + ")}可兑换但未种"
            val summary = buildString {
                if (missingTreeLines.isNotEmpty()) {
                    append("树木：")
                    append(
                        if (missingTreeLines.size <= 6) missingTreeLines.joinToString("、")
                        else missingTreeLines.take(6).joinToString("、") + " 等${missingTreeLines.size}项(总余${totalMissingBudget}株)"
                    )
                }
                if (missingReserveNames.isNotEmpty()) {
                    if (isNotEmpty()) append("｜")
                    append("保护地：${missingReserveNames.joinToString("、")}")
                }
                if (missingOceanLines.isNotEmpty()) {
                    if (isNotEmpty()) append("｜")
                    append(
                        "海洋：" + if (missingOceanLines.size <= 8) missingOceanLines.joinToString("、")
                        else missingOceanLines.take(8).joinToString("、") + " 等${missingOceanLines.size}项"
                    )
                }
            }
            val body = "$summary｜账号:$safeUid｜已种快照：config/$safeUid/planted_tree_region.json"
            Notify.sendAlert(title, body)
            val logTail = buildString {
                if (missingTreeLines.isNotEmpty()) {
                    append("树木缺${missingTreeLines.size}：${missingTreeLines.joinToString("、")}")
                }
                if (missingReserveNames.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("保护地缺${missingReserveNames.size}：${missingReserveNames.joinToString("、")}")
                }
                if (missingOceanLines.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("海洋缺${missingOceanLines.size}：${missingOceanLines.joinToString("、")}")
                }
            }
            Log.debug(TAG, "[$safeUid]$title｜$logTail")
            Status.setFlagToday(flagKey)
        } catch (t: Throwable) {
            Log.runtime(TAG, "ForestExchangeNotifier.runIfEnabled 异常：")
            Log.printStackTrace(TAG, t)
        }
    }
}
