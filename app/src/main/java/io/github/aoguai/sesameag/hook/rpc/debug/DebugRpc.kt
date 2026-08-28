package io.github.aoguai.sesameag.hook.rpc.debug

import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.HookUtil
import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.task.reserve.ReserveRpcCall
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.Dispatchers
import org.json.JSONException
import org.json.JSONObject

/**
 * RPC调试工具类
 */
class DebugRpc {
    fun start(broadcastFun: String, broadcastData: String, testType: String) {
        GlobalThreadPools.execute(Dispatchers.IO) {
            when (testType) {
                "Rpc" -> {
                    val result = test(broadcastFun, broadcastData)
                    Log.debug("收到测试消息:\n方法:$broadcastFun\n数据:$broadcastData\n结果:$result")
                }
                "getNewTreeItems" -> getNewTreeItems() // 获取新树上苗🌱信息
                "getTreeItems" -> getTreeItems() // 🔍查询树苗余量
                "queryAreaTrees" -> queryAreaTrees()
                "getUnlockTreeItems" -> getUnlockTreeItems()
                "getPlantedTreeItems" -> getPlantedTreeItems(broadcastData.trim()) // data 为目标 uid，留空=查自己
                "walkGrid" -> walkGrid() // 走格子
                "ensureFullTreeRegionConfig" -> {
                    val f = ensureFullTreeRegionConfig(reset = false)
                    Log.debug(TAG, "全量树×地区配置文件路径：${f.absolutePath}（不存在已自动创建默认模板）")
                }
                "resetFullTreeRegionConfig" -> {
                    val f = ensureFullTreeRegionConfig(reset = true)
                    Log.debug(TAG, "全量树×地区配置已重置为默认模板：${f.absolutePath}")
                }
                else -> Log.debug("未知的测试类型: $testType")
            }
        }
    }

    private fun test(method: String, data: String): String? = RequestManager.requestString(method, data)

    private fun getNewTreeItems() {
        try {
            val s = ReserveRpcCall.queryTreeItemsForExchange() ?: return
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (!item.has("projectType")) continue
                    if (item.optString("projectType") != "TREE") continue
                    if (item.optString("applyAction") != "COMING") continue
                    val projectId = item.optString("itemId")
                    queryTreeForExchange(projectId)
                }
            } else {
                Log.runtime(TAG, jo.optString("resultDesc", "Unknown error"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "getTreeItems err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 查询特定项目下可交换树木的信息
     *
     * @param projectId 项目ID
     */
    private fun queryTreeForExchange(projectId: String) {
        try {
            val response = ReserveRpcCall.queryTreeForExchange(projectId) ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val exchangeableTree = jo.getJSONObject("exchangeableTree")
                val currentBudget = exchangeableTree.getInt("currentBudget")
                val region = exchangeableTree.optString("region", "")
                val treeName = exchangeableTree.optString("treeName", "")
                
                val tips = if (exchangeableTree.optBoolean("canCoexchange", false)) {
                    val coexchangeTypeIdList = exchangeableTree
                        .getJSONObject("extendInfo")
                        .optString("cooperate_template_id_list", "")
                    "可以合种-合种类型：$coexchangeTypeIdList"
                } else {
                    "不可合种"
                }
                
                Log.debug(TAG, "新树上苗🌱[$region-$treeName]#${currentBudget}株-$tips")
            } else {
                Log.debug("${jo.optString("resultDesc", "Error")} projectId: $projectId")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "查询树木交换信息过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 获取可交换的树木项目列表，并对每个可用的项目查询当前预算
     */
    private fun getTreeItems() {
        try {
            val response = ReserveRpcCall.queryTreeItemsForExchange() ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (!item.has("projectType")) continue
                    if (item.optString("applyAction") != "AVAILABLE") continue
                    val projectId = item.optString("itemId")
                    val itemName = item.optString("itemName")
                    getTreeCurrentBudget(projectId, itemName)
                    GlobalThreadPools.sleepCompat(100)
                }
            } else {
                Log.runtime(TAG, jo.optString("resultDesc", "Unknown error"))
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "获取树木项目列表过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 树苗查询
     *
     * @param projectId 项目ID
     * @param treeName 树木名称
     */
    private fun getTreeCurrentBudget(projectId: String, treeName: String) {
        try {
            val response = ReserveRpcCall.queryTreeForExchange(projectId) ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val exchangeableTree = jo.getJSONObject("exchangeableTree")
                val currentBudget = exchangeableTree.getInt("currentBudget")
                val region = exchangeableTree.optString("region", "")
                Log.debug(TAG, "树苗查询🌱[$region-$treeName]#剩余:$currentBudget")
            } else {
                Log.debug("${jo.optString("resultDesc", "Error")} projectId: $projectId")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "查询树木交换信息过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 模拟网格行走过程，处理行走中的事件，如完成迷你游戏和广告任务
     */
    private fun walkGrid() {
        try {
            val s = DebugRpcCall.walkGrid() ?: return
            val jo = JSONObject(s)
            if (jo.getBoolean("success")) {
                val data = jo.getJSONObject("data")
                if (!data.has("mapAwards")) return
                
                val mapAwards = data.getJSONArray("mapAwards")
                val mapAward = mapAwards.getJSONObject(0)
                
                if (mapAward.has("miniGameInfo")) {
                    val miniGameInfo = mapAward.getJSONObject("miniGameInfo")
                    val gameId = miniGameInfo.optString("gameId")
                    val key = miniGameInfo.optString("key")
                    
                    GlobalThreadPools.sleepCompat(4000L)
                    val gameResultStr = DebugRpcCall.miniGameFinish(gameId, key) ?: return
                    val gameResult = JSONObject(gameResultStr)
                    
                    if (gameResult.getBoolean("success")) {
                        val miniGamedata = gameResult.getJSONObject("data")
                        if (miniGamedata.has("adVO")) {
                            val adVO = miniGamedata.getJSONObject("adVO")
                            if (adVO.has("adBizNo")) {
                                val adBizNo = adVO.optString("adBizNo")
                                val taskResultStr = DebugRpcCall.taskFinish(adBizNo) ?: return
                                val taskResult = JSONObject(taskResultStr)
                                
                                if (taskResult.getBoolean("success")) {
                                    val queryResultStr = DebugRpcCall.queryAdFinished(adBizNo, "NEVERLAND_DOUBLE_AWARD_AD") ?: return
                                    val queryResult = JSONObject(queryResultStr)
                                    if (queryResult.getBoolean("success")) {
                                        Log.debug("完成双倍奖励🎁")
                                    }
                                }
                            }
                        }
                    }
                }
                
                val leftCount = data.getInt("leftCount")
                if (leftCount > 0) {
                    GlobalThreadPools.sleepCompat(3000L)
                    walkGrid() // 递归调用，继续行走
                }
            } else {
                Log.debug("${jo.optString("errorMsg", "Error")}$s")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "行走网格过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun queryAreaTrees() {
        try {
            val resultStr = ReserveRpcCall.queryAreaTrees() ?: return
            val jo = JSONObject(resultStr)
            if (!ResChecker.checkRes(TAG, jo)) return
            
            val areaTrees = jo.getJSONObject("areaTrees")
            val regionConfig = jo.getJSONObject("regionConfig")
            val regionKeys = regionConfig.keys()
            
            while (regionKeys.hasNext()) {
                val regionKey = regionKeys.next()
                if (!areaTrees.has(regionKey)) {
                    val region = regionConfig.getJSONObject(regionKey)
                    val regionName = region.optString("regionName")
                    Log.debug(TAG, "未解锁地区🗺️[$regionName]")
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryAreaTrees err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun getUnlockTreeItems() {
        try {
            val resultStr = ReserveRpcCall.queryTreeItemsForExchange("", "project") ?: return
            val jo = JSONObject(resultStr)
            if (!ResChecker.checkRes(TAG, jo)) return

            val ja = jo.getJSONArray("treeItems")
            for (i in 0 until ja.length()) {
                val item = ja.getJSONObject(i)
                if (!item.has("projectType")) continue

                val certCountForAlias = item.optInt("certCountForAlias", -1)
                if (certCountForAlias == 0) {
                    val itemName = item.optString("itemName")
                    val region = item.optString("region")
                    val organization = item.optString("organization")
                    Log.debug(TAG, "未解锁项目🐘[$region-$itemName]#$organization")
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "getUnlockTreeItems err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 查询已种树 + 地区（只列种了什么，不做未解锁差集）。
     *
     * 数据全部以 queryAreaTrees 一次返回的全国地图数据为准（含自种 + 合种 + 保护地），
     * 按树种(alias)归类，列出每个树种已种的地区；地区名取返回体 regionConfig 的中文名。
     * 可指定目标账号 uid 查他人数据（需对方在可查询范围内），并在头部展示支付宝昵称。
     *
     * @param targetUid 目标账号 uid；留空=查询当前登录账号自己。
     */
    private fun getPlantedTreeItems(targetUid: String = "") {
        try {
            val who =
                if (targetUid.isBlank()) {
                    "本账号"
                } else {
                    val nick = fetchAlipayNickName(targetUid)
                    if (nick.isNotEmpty()) "账号[$nick]($targetUid)" else "账号[$targetUid]"
                }
            val planted = fetchPlantedByAlias(targetUid)
            if (planted.byAlias.isEmpty()) {
                Log.debug(TAG, "已种树🌳（$who）：无已种数据")
                return
            }
            // 区分普通树与保护地：queryAreaTrees 里保护地的 name 是认领面积（“1平方米”“10平方米”…），
            // 普通树的 name 是真实树名。保护地真名不在该接口里，需按 alias 查证书补名。
            //   此外还有一批"看起来像保护地/公益/动物园/生物多样性"项目的 alias，
            //   name 显示为普通树种名但证书接口会返回对应的保护地 projectName（如"红山动物园"）。
            //   两类 alias 合并后查证书，补全 plantedReserveRealNames 集合，避免【缺失汇总-保护地】漏判。
            val allAliases = planted.byAlias.keys.toList()
            val reserveAliases = ArrayList<String>(allAliases.filter { isReserveArea(planted.aliasNames[it]) })
            val extraLikeAliases = allAliases.filter { a ->
                !reserveAliases.contains(a) && looksLikeReserveProject(planted.aliasNames[a])
            }
            // 去重后再查证书：先查标准"XX平方米"保护地，再查额外"类保护地"别名；两批结果合并入同一个 map。
            val reserveNameByAlias = HashMap(fetchReserveNames(reserveAliases, targetUid))
            // 为了兼容 "MONOPOLY 类证书 projectName=红山动物园<br/>生物多样性保护" 这种拼接描述，
            // 额外再跑一遍 fetchReserveExtraAliases：取每 alias 的所有别名（拆分<br/>各段 + ngoName/forestName
            // 里的合作方名如"红山森林动物园"），全部并入 plantedReserveRealNames。
            // 这样即便配置文件里写"红山动物园"或"红山森林动物园"，都能命中该 alias 已种集合。
            val allReserveAliasesForLookup = (reserveAliases + extraLikeAliases).distinct()
            val extraAliases = HashMap<String, Set<String>>()
            if (allReserveAliasesForLookup.isNotEmpty()) {
                extraAliases.putAll(fetchReserveExtraAliases(allReserveAliasesForLookup, targetUid))
            }
            if (extraLikeAliases.isNotEmpty()) {
                reserveNameByAlias.putAll(fetchReserveNames(extraLikeAliases, targetUid))
                Log.debug(
                    TAG,
                    "补充查询${extraLikeAliases.size}个类保护地 alias（命中保护/动物园/生物多样性关键字）：" +
                        extraLikeAliases.joinToString(",") { alias ->
                            val display = planted.aliasNames[alias].orEmpty().replace("<br/>", "｜")
                            val extraNamesStr = extraAliases[alias]
                                ?.filter { it.length in 2..30 }
                                ?.sorted()
                                ?.take(10)
                                ?.joinToString("/")
                                .orEmpty()
                            val tag = if (extraNamesStr.isNotEmpty()) " 证书提取名=$extraNamesStr" else ""
                            if (display.isNotEmpty()) "[$alias=$display$tag]" else "[$alias$tag]"
                        }
                )
            }
            val plantedReserveRealNames = HashSet<String>(reserveNameByAlias.values.filter { it.isNotBlank() })
            for ((_, extraSet) in extraAliases) plantedReserveRealNames.addAll(extraSet)
            // 顺带把 aliasNames 里用户能直观看到的显示名（去<br/>后各段）也塞进去（例如"红山动物园"），
            // 兜底极端情况：证书接口没返回可靠 projectName，但 areaTrees.name 本身就是用户认可的官方展示名。
            for (alias in allReserveAliasesForLookup) {
                val raw = planted.aliasNames[alias].orEmpty().replace("<br/>", "\n").replace("｜", "\n")
                raw.split("\n").map { it.trim() }.filter { it.length >= 2 }.forEach { plantedReserveRealNames.add(it) }
            }
            val totalRegions = planted.byAlias.values.sumOf { it.size }
            Log.debug(
                TAG,
                "已种树🌳汇总（$who / 共 ${planted.byAlias.size} 个树种 / $totalRegions 个树×地区" +
                    " / 陆地保护地已种${plantedReserveRealNames.size}）："
            )
            for ((alias, regions) in planted.byAlias) {
                val regionNames = regions.map { planted.regionNames[it] ?: it }
                if (isReserveArea(planted.aliasNames[alias])) {
                    val area = planted.aliasNames[alias].orEmpty()
                    val realName = reserveNameByAlias[alias]?.ifEmpty { null } ?: alias
                    val label = if (area.isNotEmpty()) "$realName($area)" else realName
                    Log.debug(TAG, "🏞️$label：已种${regions.size}地[${regionNames.joinToString("、")}]")
                } else {
                    val name = planted.aliasNames[alias]?.ifEmpty { alias } ?: alias
                    Log.debug(TAG, "🌳$name：已种${regions.size}地[${regionNames.joinToString("、")}]")
                }
            }
            // 🌊海洋保护记录：直接看 cultivationList（它本身 certNum=已保护证书数，最准）。
            //   注意：该接口请求参数里没有 targetUserId，所以永远返回"当前登录账号本人"的数字；
            //   查别人时绝不调用它，避免把自己的海洋保护记录误展示成别人的。
            val classLoader = ApplicationHook.classLoader
            val currentUid: String =
                if (classLoader != null) runCatching { HookUtil.getUserId(classLoader).orEmpty() }.getOrDefault("") else ""
            val isSelf: Boolean =
                targetUid.isBlank() || (currentUid.isNotEmpty() && targetUid.trim() == currentUid)
            var protectedOceanNamesForCompare: Set<String>? = null
            var fullOceanDisplayNamesForCompare: Set<String>? = null
            if (isSelf) {
                try {
                    val allOcean = io.github.aoguai.sesameag.hook.ForestExchangeNotifier.fetchAllCultivationItems()
                    fullOceanDisplayNamesForCompare = allOcean.map { it.displayName }.toHashSet()
                    if (allOcean.isEmpty()) {
                        Log.debug(TAG, "🌊已保护海洋项目汇总：（海洋接口无返回）")
                        protectedOceanNamesForCompare = emptySet()
                    } else {
                        val protectedItems = allOcean.filter { it.certNum > 0 }.sortedByDescending { it.certNum }
                        protectedOceanNamesForCompare = protectedItems.map { it.displayName }.toHashSet()
                        val totalCerts = protectedItems.sumOf { it.certNum }
                        if (protectedItems.isEmpty()) {
                            Log.debug(
                                TAG,
                                "🌊已保护海洋项目汇总：（海洋全部项目共 ${allOcean.size} 个，当前登录账号还没有保护过任何海洋项目）"
                            )
                        } else {
                            Log.debug(
                                TAG,
                                "🌊已保护海洋项目汇总：（海洋全部项目共 ${allOcean.size} 个 / 已保护 ${protectedItems.size} 项，累计领证书 $totalCerts 份）："
                            )
                            for (oc in protectedItems) {
                                val energyStr = if (oc.energy > 0) " / 每份${oc.energy}g" else ""
                                val state = when (oc.rawAction) {
                                    "AVAILABLE" -> " / 状态：开放可领"
                                    "NO_STOCK" -> " / 状态：已下架无库存"
                                    "" -> ""
                                    else -> " / 状态：${oc.rawAction}"
                                }
                                Log.debug(TAG, "  🐚 ${oc.displayName}（证书${oc.certNum}份${energyStr}${state}）")
                            }
                        }
                    }
                } catch (to: Throwable) {
                    Log.debug(TAG, "🌊已保护海洋项目汇总：（查询失败：${to.message}）")
                    Log.printStackTrace(TAG, to)
                }
            } else {
                Log.debug(
                    TAG,
                    "🌊已保护海洋项目汇总：（注：海洋 cultivationList 接口不带目标账号 uid，仅展示当前登录账号本人的保护记录；" +
                        "查询对方海洋保护记录请切换至目标账号登录后手动查询）"
                )
            }
            // 与用户全量配置对比，输出缺失汇总：树木+陆地保护地 + 海洋（海洋不读 cfg.oceans，直接用 cultivationList 全部项目作为全量）
            compareWithFullConfig(
                planted,
                reserveNameByAlias,
                plantedReserveRealNames,
                who,
                fullOceanDisplayNamesForCompare,
                protectedOceanNamesForCompare
            )
            // 先把本次已种结果整理成中文名→中文地区集合（注意：cnByTree 同时用于下面"可兑换未种"交叉，不能省）
            //   currentUid / isSelf 已经在前面🌊海洋块内声明并计算好，这里复用同名变量，不要再重复声明。
            val saveUid = targetUid.ifBlank { currentUid }.ifBlank { "default" }
            val cnByTree = LinkedHashMap<String, MutableSet<String>>()
            for ((alias, codes) in planted.byAlias) {
                if (isReserveArea(planted.aliasNames[alias])) continue
                val cnName = planted.aliasNames[alias]?.ifEmpty { null } ?: continue
                val bucket = cnByTree.getOrPut(cnName) { HashSet() }
                for (c in codes) {
                    val cn = planted.regionNames[c]?.trim() ?: continue
                    if (cn.isNotEmpty()) bucket.add(cn)
                }
            }
            // 只有"查自己"才落盘快照（查别人绝不写文件）。陆地保护地真名独立落盘；海洋是否种过直接看 cultivationList.certNum，
            //   此处 planted.oceans 传空集合，仅保持 PlantedSnapshot 字段结构兼容（将来若扩展海洋全量配置再使用）。
            if (isSelf) {
                try {
                    io.github.aoguai.sesameag.hook.ForestExchangeNotifier.savePlantedSnapshot(
                        saveUid,
                        cnByTree,
                        plantedReserveRealNames,
                        emptySet()
                    )
                } catch (ts: Throwable) {
                    Log.runtime(TAG, "savePlantedSnapshot 写入失败：")
                    Log.printStackTrace(TAG, ts)
                }
            }
            // 在已种明细+缺失汇总之后，再按"当前可兑换项目"做一轮交叉检查（查自己还是查别人都要跑，可兑换项目对所有人一样）
            try {
                val bundle = io.github.aoguai.sesameag.hook.ForestExchangeNotifier.fetchAllAvailableBundle()
                if (bundle.trees.isEmpty() && bundle.reserves.isEmpty() && bundle.oceans.isEmpty()) {
                    Log.debug(TAG, "【可兑换未种】（$who）当前无可兑换项目")
                } else {
                    // 树木差集
                    val regionMatchFn = io.github.aoguai.sesameag.hook.ForestExchangeNotifier::regionMatchCompat
                    val missingTreeLines = ArrayList<String>()
                    var totalMissing = 0
                    for (av in bundle.trees) {
                        val have = cnByTree[av.treeName] ?: emptySet()
                        if (regionMatchFn(have, av.region)) continue
                        missingTreeLines.add("${av.treeName}@${av.region}(余${av.budget}株)")
                        totalMissing += av.budget.coerceAtLeast(0)
                    }
                    // 保护地差集（利用本查询刚拿到的保护地真名 plantedReserveRealNames）
                    val reserveMatchFn: (String, io.github.aoguai.sesameag.hook.ForestExchangeNotifier.AvailableReserve) -> Boolean =
                        { r, ar -> io.github.aoguai.sesameag.hook.ForestExchangeNotifier.reserveNameMatch(r, ar) }
                    val missingReserves = ArrayList<String>()
                    for (ar in bundle.reserves) {
                        val hit = plantedReserveRealNames.any { r -> reserveMatchFn(r, ar) }
                        if (hit) continue
                        missingReserves.add(ar.displayName)
                    }
                    // 海洋项目差集：
                    //   查自己 → 直接看 cultivationItemVOList[i].certNum（certNum>0=本账号已领至少一份证书，跳过；否则进未种）；
                    //     不再依赖 EnvironmentCertDetailList 反查证书（会把陆地 Qiuqie/CSMANCAO 这种"名字像海洋、实则是陆地种树版"的 alias 误混进来）。
                    //   查别人 → cultivationList 请求参数里不带 targetUserId，返回的 certNum 永远是"当前登录账号本人"的，不能代表被查对象。
                    //     因此不做差集，直接打提示行，避免把"本账号已保护"误当成"对方账号也已保护"判成全对齐。
                    val missingOceans = ArrayList<String>()
                    var oceanSkipped = false
                    var oceanSkipReason = ""
                    if (isSelf) {
                        for (oc in bundle.oceans) {
                            if (oc.certNum > 0) continue
                            missingOceans.add(oc.displayName)
                        }
                    } else {
                        oceanSkipped = true
                        oceanSkipReason =
                            "cultivationList 接口不带目标账号 uid，无法确认对方是否已保护${bundle.oceans.size}项可兑换海洋项目，跳过比对"
                    }
                    val anyTree = missingTreeLines.isNotEmpty()
                    val anyReserve = missingReserves.isNotEmpty()
                    val anyOcean = !oceanSkipped && missingOceans.isNotEmpty()
                    if (!anyTree && !anyReserve && (!oceanSkipped && missingOceans.isEmpty())) {
                        Log.debug(
                            TAG,
                            "【可兑换未种】（$who）普通树可兑换${bundle.trees.size}项 × 保护地可兑换${bundle.reserves.size}项 × 海洋可兑换${bundle.oceans.size}项 × 已种全对齐，无缺项"
                        )
                    } else {
                        if (anyTree) {
                            val head = "【可兑换未种-树木】（$who）未种${missingTreeLines.size}/${bundle.trees.size}项，总余量${totalMissing}株："
                            Log.debug(TAG, head + missingTreeLines.joinToString("、"))
                        } else if (bundle.trees.isNotEmpty()) {
                            Log.debug(TAG, "【可兑换未种-树木】（$who）可兑换${bundle.trees.size}项 × 已种全对齐，无缺项")
                        }
                        if (anyReserve) {
                            Log.debug(TAG, "【可兑换未种-保护地】（$who）未种${missingReserves.size}/${bundle.reserves.size}项：${missingReserves.joinToString("、")}")
                        } else if (bundle.reserves.isNotEmpty()) {
                            Log.debug(TAG, "【可兑换未种-保护地】（$who）可兑换${bundle.reserves.size}项 × 已种全对齐，无缺项")
                        }
                        if (oceanSkipped) {
                            Log.debug(TAG, "【可兑换未种-海洋】（$who）：（注：$oceanSkipReason）")
                        } else if (anyOcean) {
                            Log.debug(TAG, "【可兑换未种-海洋】（$who）未种${missingOceans.size}/${bundle.oceans.size}项：${missingOceans.joinToString("、")}")
                        } else if (bundle.oceans.isNotEmpty()) {
                            Log.debug(TAG, "【可兑换未种-海洋】（$who）可兑换${bundle.oceans.size}项 × 已种全对齐，无缺项")
                        }
                    }
                }
            } catch (t2: Throwable) {
                Log.runtime(TAG, "【可兑换未种】检查失败：")
                Log.printStackTrace(TAG, t2)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "getPlantedTreeItems err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /** 判断 queryAreaTrees 的 name 是否为保护地的认领面积（如“1平方米”“10平方米”），是则该 alias 为保护地。 */
    private fun isReserveArea(name: String?): Boolean = name != null && name.contains("平方米")

    /**
     * 从 EnvironmentCertDetailList 返回的单条证书里提取「可用于『保护地/公益项目真名对比』」的规范化名字集合。
     *
     * - 普通保护地（type=RESERVE）：projectName 通常是干净的保护地名（例："五宝山"、"汪清"）。
     * - 公益/专属保护证书（type=MONOPOLY / CONSERVATION 等）：projectName 常带 "<br/>" 拼接描述
     *   （例："红山动物园<br/>生物多样性保护"、"唐家河大熊猫栖息地<br/>XX生态展区"），此时除原始串外，
     *   再补一个 "<br/>" 前半段的"纯名称"（"红山动物园"、"唐家河大熊猫栖息地"）入集合，
     *   避免"配置里写简称 <=> 返回里带描述"造成的匹配失败。
     * - 如果 projectName 为空，退而求其次取 treeName（RESERVE 里是面积，没用；但 MONOPOLY 场景可能与
     *   projectName 一致，能兜底一次），仍按 <br/> 规则规范化。
     * - 额外把 ngoName / forestName 中 "<机构> <br/> <核心合作方>" 后半段（常是保护地/动物园实际合作方名
     *   如"红山森林动物园"）提取出来当别名匹配；过滤掉"基金会/NGO"这类通用名。
     */
    private fun extractReserveLikeDisplayNames(entry: JSONObject): Set<String> {
        val out = HashSet<String>()
        val type = entry.optString("type", "").uppercase()

        val primaryFields = listOfNotNull(entry.optString("projectName"), entry.optString("treeName"))
        for (raw in primaryFields) {
            if (raw.isBlank()) continue
            out += raw
            val parts = raw.split("<br/>", "\n", "｜")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > 1) {
                if (type != "RESERVE") out += parts.first()
                parts.filter { it.length >= 2 }.forEach { out += it }
            }
        }

        val secondFields = listOfNotNull(entry.optString("forestName"), entry.optString("ngoName"))
        for (raw in secondFields) {
            if (raw.isBlank()) continue
            val parts = raw.split("<br/>", "\n", "｜")
                .map { it.trim() }
                .filter { it.length >= 2 }
                .filterNot { seg -> seg.contains("基金会") || seg.contains("NGO") }
            if (parts.isNotEmpty()) out += parts
        }
        return out
    }

    /**
     * 额外挑选"看起来像公益/保护地项目"的 alias（除了 isReserveArea 识别的"XX平方米"之外）。
     * 这些 alias 的 name 不是面积，而是显示为普通树种 name（例："红山动物园<br/>生物多样性保护"），
     * 但业务上其实属于"保护地/公益/动物园/生物多样性保护"类别，证书接口会返回对应的保护地 projectName
     * （例："红山动物园"）供【缺失汇总-保护地】比对。命中关键字后即视为"类保护地"，
     * 按 alias 查一次 EnvironmentCertDetailList 拿证书的 projectName，合并入陆地保护地真名集合。
     */
    private fun looksLikeReserveProject(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        if (n.isBlank()) return false
        val keywords = listOf(
            "保护",
            "保护地",
            "生物多样性",
            "多样性",
            "公益",
            "动物园",
            "自然保护",
            "湿地",
            "红枫林",
            "珍稀"
        )
        return keywords.any { n.contains(it.lowercase()) }
    }

    /**
     * 按 alias 逐个查环境证书（queryEnvironmentCertDetailList），聚合出保护地 alias -> 真实名称。
     *
     * queryAreaTrees 里保护地的 name 只有认领面积，拿不到真正的保护地名；证书接口返回体每条带
     * type=RESERVE，其 projectName 才是保护地真实名称（如“京西保护地”，注意保护地的 treeName
     * 反而是“1平方米”这类面积，普通树的 projectName 则是“SEE20号”这类项目编号，故必须取 projectName）。
     * 仅对保护地 alias 逐个请求，普通树不受影响。单个 alias 查询失败/无证书则跳过（该保护地退化为 alias）。
     *
     * 对"类保护地/公益/动物园"的 alias（如 wubanyuwa，显示名"红山动物园<br/>生物多样性保护"），证书
     * 返回的 projectName 常带 "<br/>" 描述且 type=MONOPOLY。此处统一调用 extractReserveLikeDisplayNames
     * 提取「主名+别名」，plantedReserveRealNames 在 DebugRpc 主入口会把这些别名全部加入集合。
     *
     * @param aliases   需补名的保护地 / 类保护地 alias 列表。
     * @param targetUid 目标账号 uid；留空=查自己。
     */
    private fun fetchReserveNames(aliases: List<String>, targetUid: String = ""): Map<String, String> {
        val names = HashMap<String, String>()
        for (alias in aliases) {
            try {
                val resp = ReserveRpcCall.queryEnvironmentCertDetailList(alias, targetUid) ?: continue
                val jo = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, jo)) continue
                val arr = jo.optJSONArray("environmentCertDetailVOList")
                    ?: jo.optJSONArray("userProjectTreeVOList")
                    ?: continue
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val candidates = extractReserveLikeDisplayNames(entry)
                    if (candidates.isEmpty()) continue
                    val projectName = entry.optString("projectName").takeIf { it.isNotBlank() }
                        ?: entry.optString("treeName").takeIf { it.isNotBlank() }
                        ?: candidates.first()
                    val short = projectName.split("<br/>", "\n", "｜").firstOrNull { it.isNotBlank() }?.trim()
                        ?: projectName.trim()
                    names[alias] = short
                    break
                }
            } catch (t: Throwable) {
                Log.runtime(TAG, "fetchReserveNames[$alias] err:")
                Log.printStackTrace(TAG, t)
            }
            GlobalThreadPools.sleepCompat(80)
        }
        return names
    }

    /**
     * 除 alias -> 主名（Map）外，再额外提取每 alias 对应的完整别名集合（含 <br/> 拆分后的所有独立段、
     * ngoName/forestName 兜底提取的机构名等），用于构建 plantedReserveRealNames 的超大集合，
     * 保证【缺失汇总-保护地】能正确命中"配置里写简称 <=> 证书里带 <br/> 全称/机构名"的场景。
     *
     * 例：wubanyuwa 的 projectName="红山动物园<br/>生物多样性保护"，
     *     ngoName="爱德基金会<br/>红山森林动物园"
     * 本调用会返回 {"红山动物园", "生物多样性保护", "红山森林动物园",
     *              "红山动物园<br/>生物多样性保护"} —— 如此无论配置里写"红山动物园"还是
     *     "红山森林动物园"都能命中该 alias 已种集合。
     */
    private fun fetchReserveExtraAliases(aliases: List<String>, targetUid: String = ""): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        for (alias in aliases) {
            try {
                val resp = ReserveRpcCall.queryEnvironmentCertDetailList(alias, targetUid) ?: continue
                val jo = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, jo)) continue
                val arr = jo.optJSONArray("environmentCertDetailVOList")
                    ?: jo.optJSONArray("userProjectTreeVOList")
                    ?: continue
                val aliasAliases = HashSet<String>()
                for (i in 0 until arr.length()) {
                    aliasAliases += extractReserveLikeDisplayNames(arr.getJSONObject(i))
                }
                if (aliasAliases.isNotEmpty()) out[alias] = aliasAliases
            } catch (t: Throwable) {
                Log.runtime(TAG, "fetchReserveExtraAliases[$alias] err:")
                Log.printStackTrace(TAG, t)
            }
            GlobalThreadPools.sleepCompat(80)
        }
        return out
    }

    /**
     * 接口返回的已种地图数据（queryAreaTrees 全量）。
     *
     * @property byAlias     alias -> 已种地区 regionCode 集合（含自种 + 合种 + 保护地）
     * @property regionNames regionCode -> 中文地区名（来自返回体 regionConfig）
     * @property aliasNames  alias -> 树种中文名（来自 areaTrees 每条的 name 字段）
     */
    private class PlantedData {
        val byAlias = LinkedHashMap<String, MutableSet<String>>()
        val regionNames = HashMap<String, String>()
        val aliasNames = HashMap<String, String>()
    }

    /**
     * 调 queryAreaTrees（一次性返回全国地图数据），聚合出已种全量数据。
     *
     * areaTrees 结构：{ regionCode: { treeCode: {alias, name, regionCode, number, ...} } }，
     * 含自种 + 合种 + 保护地，每条都带真实 alias。regionConfig 提供 regionCode->中文地区名。
     * 单次请求即为全量，无需分片/翻页。
     *
     * @param targetUid 目标账号 uid；留空=查自己。
     */
    private fun fetchPlantedByAlias(targetUid: String = ""): PlantedData {
        val planted = PlantedData()
        try {
            val resp = ReserveRpcCall.queryAreaTrees(targetUid) ?: return planted
            val jo = JSONObject(resp)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.debug(TAG, "查询地图已种数据失败：${jo.optString("resultDesc", "未知错误")}")
                return planted
            }
            if (targetUid.isNotBlank()) {
                // self=false 表示服务端确认返回的是目标账号（非本人）的数据
                Log.debug(TAG, "已种数据目标账号[$targetUid] self=${jo.optBoolean("self", true)}")
            }
            // regionConfig: regionCode -> 中文地区名
            jo.optJSONObject("regionConfig")?.let { rc ->
                val keys = rc.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val name = rc.optJSONObject(code)?.optString("regionName").orEmpty()
                    if (name.isNotEmpty()) planted.regionNames[code] = name
                }
            }
            val areaTrees = jo.optJSONObject("areaTrees") ?: return planted
            val regionKeys = areaTrees.keys()
            while (regionKeys.hasNext()) {
                val regionCode = regionKeys.next()
                val trees = areaTrees.optJSONObject(regionCode) ?: continue
                val treeKeys = trees.keys()
                while (treeKeys.hasNext()) {
                    val treeObj = trees.optJSONObject(treeKeys.next()) ?: continue
                    val alias = treeObj.optString("alias")
                    if (alias.isEmpty()) continue
                    planted.byAlias.getOrPut(alias) { HashSet() }.add(regionCode)
                    val treeName = treeObj.optString("name")
                    if (treeName.isNotEmpty()) planted.aliasNames.putIfAbsent(alias, treeName)
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "fetchPlantedByAlias err:")
            Log.printStackTrace(TAG, t)
        }
        return planted
    }

    /**
     * 全量配置数据模型。
     *
     * @property trees    树中文名 -> 该树所有应覆盖的中文地区名集合。
     *                    缺失比较时：按「树名 + 地区名」匹配（树比较地区信息）。
     * @property reserves 全量保护地名称集合。保护地只看名字是否种过，不涉及地区。
     * @property oceans   全量海洋项目名称集合（中文展示名，与 cultivationList.cultivationName 一致，
     *                    比如"鳗草""金沙湾海滩""守护中华白海豚"等）。
     *                    海洋只看名字是否保护过（certNum>0），不涉及地区。默认空数组表示不参与海洋对比。
     */
    private class FullTreeRegionConfig {
        val trees = LinkedHashMap<String, MutableSet<String>>()
        val reserves = LinkedHashSet<String>()
        val oceans = LinkedHashSet<String>()
    }

    /**
     * 确保配置文件存在；不存在或为空时写入默认配置。
     *
     * 若用户后续手工改坏 JSON，加载处会 try/catch 并给出错误日志，
     * 调用 reset=true 可一键覆盖回默认模板。
     *
     * @param reset true=无条件覆盖为默认配置（用于「重置全量配置」菜单）
     * @return 配置文件本身（绝对路径可展示给用户 / 打开）
     */
    fun ensureFullTreeRegionConfig(reset: Boolean = false): java.io.File {
        val file = Files.getFullTreeRegionFile()
        if (!reset && file.isFile && file.length() > 0L) return file
        val defaultJson = buildDefaultFullTreeRegionJson()
        Files.write2File(defaultJson, file)
        return file
    }

    /** 从文件加载用户全量配置；首次运行不存在会先写默认模板。 */
    private fun loadFullTreeRegionConfig(): FullTreeRegionConfig? {
        val file = ensureFullTreeRegionConfig(reset = false)
        val text = Files.readFromFile(file).trim()
        if (text.isEmpty()) return null
        return try {
            val cfg = FullTreeRegionConfig()
            val jo = JSONObject(text)
            val treesJo = jo.optJSONObject("trees") ?: JSONObject()
            val tKeys = treesJo.keys()
            while (tKeys.hasNext()) {
                val name = tKeys.next()
                val arr = treesJo.optJSONArray(name) ?: continue
                val set = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val r = arr.optString(i).trim()
                    if (r.isNotEmpty()) set.add(r)
                }
                if (set.isNotEmpty()) cfg.trees[name] = set
            }
            val reservesArr = jo.optJSONArray("reserves") ?: org.json.JSONArray()
            for (i in 0 until reservesArr.length()) {
                val r = reservesArr.optString(i).trim()
                if (r.isNotEmpty()) cfg.reserves.add(r)
            }
            val oceansArr = jo.optJSONArray("oceans") ?: org.json.JSONArray()
            for (i in 0 until oceansArr.length()) {
                val r = oceansArr.optString(i).trim()
                if (r.isNotEmpty()) cfg.oceans.add(r)
            }
            cfg
        } catch (t: Throwable) {
            Log.runtime(TAG, "loadFullTreeRegionConfig err：${file.absolutePath}")
            Log.printStackTrace(TAG, t)
            null
        }
    }

    /** 生成默认配置 JSON：按飞书 wiki 表格（森林证书30种 + 保护地35个）中的正确名称填写。 */
    private fun buildDefaultFullTreeRegionJson(): String {
        val sb = StringBuilder(8192)
        sb.append("{\n")
        sb.append("  \"_description\": \"用户自定义全量树×地区配置。编辑后保存即可，查询已种树时会自动对比。树木按中文地区名逐个对照；保护地只看名字是否种过。数据来源：蚂蚁森林树种→地区汇总（整理：你要开心的笑 2026.08.27）\",\n")
        sb.append("  \"trees\": {\n")
        val defaults = linkedMapOf(
            "柠条" to arrayOf(
                "科右中旗","庆阳","海东","乌兰察布","赤峰","阿拉善","锡林郭勒","定西","鄂尔多斯",
                "中卫","临夏","武威","海南州","白银","忻州","巴彦淖尔","兴安盟"
            ),
            "梭梭树" to arrayOf(
                "阿拉善","武威","鄂尔多斯","巴彦淖尔","敦煌","金昌","张掖","酒泉","白银","阿克苏"
            ),
            "沙棘" to arrayOf(
                "科右中旗","通辽","呼和浩特","赤峰","忻州","阿坝州","乌兰察布","甘南","定西",
                "鄂尔多斯","长治","天水","吕梁","庆阳","晋中","大同","呼伦贝尔","白银","邯郸"
            ),
            "沙柳" to arrayOf("库布其","鄂尔多斯"),
            "杨柴" to arrayOf("鄂尔多斯","赤峰"),
            "花棒" to arrayOf("阿拉善","鄂尔多斯","武威","酒泉","中卫"),
            "连翘" to arrayOf("铜川","邯郸","石家庄","延安","保定","承德"),
            "红柳" to arrayOf(
                "阿拉善","巴彦淖尔","酒泉","张掖","敦煌","海南州","锡林郭勒","白银","鄂尔多斯",
                "中卫","乌兰察布"
            ),
            "黄柳" to arrayOf("赤峰","锡林郭勒"),
            "山桃" to arrayOf(
                "庆阳","乌兰察布","通辽","白银","中卫","延安","大同","晋城","运城","忻州",
                "保定","定西","平凉","武威"
            ),
            "山杏" to arrayOf(
                "庆阳","通辽","白银","邢台","邯郸","科右中旗","保定","延安","忻州","长治",
                "晋城","临夏","中卫","乌兰察布","石家庄","平凉","兴安盟","定西","锡林郭勒",
                "海东","武威"
            ),
            "酸枣" to arrayOf("保定","承德","邯郸"),
            "榆树" to arrayOf(
                "武威","临夏","锡林郭勒","庆阳","定西","赤峰","通辽","兴安盟","乌兰察布","承德"
            ),
            "侧柏" to arrayOf(
                "邯郸","邢台","铜川","白银","保定","延安","定西","石家庄","承德","武威","平凉"
            ),
            "油松" to arrayOf(
                "邯郸","承德","邢台","延安","铜川","保定","太原","定西","雄安新区","石家庄",
                "忻州","武威"
            ),
            "秋茄" to arrayOf("宁德"),
            "丛生鳗草" to arrayOf("威海"),
            "文冠果" to arrayOf(
                "兴安盟","赤峰","通辽","白银","临夏","海东","乌兰察布","平凉"
            ),
            "桦木" to arrayOf("重庆","眉山","文山","玉溪","曲靖","红河"),
            "相思树" to arrayOf(
                "昆明","玉溪","大理","楚雄","曲靖","保山","红河","普洱","昭通"
            ),
            "槭树" to arrayOf("重庆","眉山","兴安盟","赤峰","红河","凉山州"),
            "樟子松" to arrayOf(
                "库布其","通辽","兰州","赤峰","武威","锡林郭勒","承德","呼和浩特","白银",
                "庆阳","兴安盟","临夏","中卫","张家口","海东"
            ),
            "云南松" to arrayOf("昆明","玉溪","曲靖"),
            "湿地松" to arrayOf("杭州"),
            "栎树" to arrayOf("承德","保定","张家口"),
            "华山松" to arrayOf("大理","迪庆","怒江","凉山州","红河","昆明"),
            "云杉" to arrayOf(
                "大理","迪庆","怒江","西宁","定西","赤峰","临夏","武威","平凉","凉山州",
                "白银"
            ),
            "崖柏" to arrayOf("重庆","达州"),
            "胡杨" to arrayOf(
                "库布其","阿拉善","巴彦淖尔","酒泉","鄂尔多斯","金昌","敦煌","锡林郭勒",
                "阿克苏","嘉峪关","喀什","巴音郭楞","乌鲁木齐"
            ),
            "冷杉" to arrayOf("大理","怒江","迪庆","凉山州","昆明","眉山")
        )
        var firstTree = true
        for ((name, regions) in defaults) {
            if (!firstTree) sb.append(",\n")
            firstTree = false
            sb.append("    \"$name\": [")
            val uniq = LinkedHashSet<String>()
            for (r in regions) if (r.isNotEmpty()) uniq.add(r)
            sb.append(uniq.joinToString(",") { "\"$it\"" })
            sb.append("]")
        }
        sb.append("\n  },\n")
        sb.append("  \"reserves\": [\n")
        val reserves = arrayOf(
            "粽子溪","新龙","珲春","嘉塘","索加牙曲","老河沟","柏林","八月林","秋千架","和顺",
            "大古坪","五宝山","南仁萨勇","五源河","然者涌","东觉涌","君乃涌","囊谦白扎","沉湖","东宁",
            "乌禽嶂","福寿","芒杏河","老君山","墨脱格当","京西","鞍子河","德钦","塔城","汪清",
            "洋县","清水河","关坝","条子泥","洋湖","红山动物园"
        )
        sb.append(reserves.joinToString(",\n") { "    \"$it\"" })
        sb.append("\n  ],\n")
        sb.append("  \"oceans\": []\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * 已种数据 vs 用户全量配置 的缺失汇总。
     *
     * 比较规则（用户需求）：
     *  1) 普通树：按配置「trees 中中文名」比对。配置里的每个地区逐一对照已种数据。
     *     命中方式：配置的中文地区名 == regionNames[regionCode]（完全一致优先）；
     *     兼容：配置地名为地区名的子串 / 地区名包含配置地名（避免如「江安」vs「江安县」漏匹配）。
     *  2) 保护地：仅看「名字是否种过」，不涉及地区。
     *     命中方式：配置的保护地名字串 == 证书返回的 projectName，或一方是另一方子串（兼容如「京西」vs「京西保护地」）。
     *
     * 所有日志写入 debug.log，缺失分块输出，最后给出统计数。
     */
    private fun compareWithFullConfig(
        planted: PlantedData,
        reserveNames: Map<String, String>,
        plantedReserveFull: Set<String>,
        who: String,
        fullOceanDisplayNames: Set<String>?,
        protectedOceanNames: Set<String>?
    ) {
        val cfg = loadFullTreeRegionConfig()
        if (cfg == null) {
            Log.debug(TAG, "【缺失汇总】未加载到全量配置文件（$who），跳过对比")
            return
        }
        val cfgFile = Files.getFullTreeRegionFile()
        Log.debug(TAG, "【缺失汇总】对比配置文件：${cfgFile.absolutePath}")
        // 已种普通树（中文名 -> 中文地区集合）
        val plantedTreeRegions = LinkedHashMap<String, HashSet<String>>()
        for ((alias, codes) in planted.byAlias) {
            if (isReserveArea(planted.aliasNames[alias])) continue
            val cnName = planted.aliasNames[alias]?.ifEmpty { null } ?: continue
            val bucket = plantedTreeRegions.getOrPut(cnName) { HashSet() }
            for (code in codes) {
                val cn = planted.regionNames[code]?.trim() ?: continue
                if (cn.isNotEmpty()) bucket.add(cn)
            }
        }
        // 已种保护地真名集合：直接用主入口合并好的 plantedReserveFull（含：
        //   ①标准"XX平方米"保护地查证书的 projectName 主名
        //   ②类保护地 alias 查证书的主名
        //   ③fetchReserveExtraAliases 返回的 <br/> 分段 + ngoName/forestName 合作方名（去基金会/NGO）
        //   ④aliasNames 显示名 <br/> 分段兜底
        // ），不再在内部按 isReserveArea 重造一份残缺集合。
        val plantedReserveLcLower = plantedReserveFull.map { it.trim().lowercase() }

        // 1) 树木缺失比对
        var totalMissingTreeRegions = 0
        val missingTreeLines = ArrayList<String>()
        if (cfg.trees.isNotEmpty()) {
            for ((treeName, cfgRegions) in cfg.trees) {
                val got = plantedTreeRegions[treeName] ?: emptySet<String>()
                val missing = ArrayList<String>()
                for (r in cfgRegions) {
                    if (r in got) continue
                    val rTrim = r.trim()
                    if (rTrim.isEmpty()) continue
                    val hit = got.any { g ->
                        g.equals(rTrim, ignoreCase = true) ||
                            (g.contains(rTrim) || rTrim.contains(g))
                    }
                    if (!hit) missing.add(rTrim)
                }
                if (missing.isNotEmpty()) {
                    totalMissingTreeRegions += missing.size
                    missingTreeLines.add("  🌳$treeName：缺${missing.size}地[${missing.joinToString("、")}]")
                }
            }
        }
        Log.debug(TAG, "【缺失汇总-树木】（$who）：共缺${totalMissingTreeRegions}地" +
            (if (missingTreeLines.isEmpty()) "（全部集齐）" else "，明细如下"))
        missingTreeLines.forEach { Log.debug(TAG, it) }
        // 2) 保护地缺失比对
        var missingReserves = 0
        val missingList = ArrayList<String>()
        if (cfg.reserves.isNotEmpty()) {
            for (name in cfg.reserves) {
                val trimName = name.trim()
                if (trimName.isEmpty()) continue
                val hit = plantedReserveLcLower.any { plantedLc ->
                    val nameLc = trimName.lowercase()
                    plantedLc == nameLc || plantedLc.contains(nameLc) || nameLc.contains(plantedLc)
                }
                if (!hit) {
                    missingList.add(trimName)
                    missingReserves++
                }
            }
        }
        if (missingList.isNotEmpty()) {
            Log.debug(TAG, "【缺失汇总-保护地】（$who）：未种${missingReserves}/共${cfg.reserves.size}个：[${missingList.joinToString("、")}]")
        } else {
            Log.debug(TAG, "【缺失汇总-保护地】（$who）：${cfg.reserves.size}个全部集齐")
        }
        // 3) 海洋缺失比对：不再读 cfg.oceans，直接用 cultivationList 接口返回的**全部项目 displayName**作为"海洋全量清单"
        //   - 全量：fullOceanDisplayNames（接口返回去重后的所有海洋项目 displayName）
        //   - 已保护：protectedOceanNames（certNum>0 的项目 displayName）
        //   - 二者都非空 = 是查自己且接口可用 → 直接做差集输出，无条件显示在缺失汇总里
        //   - 任一为 null = 是查别人 → 打提示行，不做差集避免误导
        //   - 如果 fullOceanDisplayNames 是空集合 → 代表 cultivationList 本次无返回，跳过差集
        var missingOceans = 0
        val missingOceanList = ArrayList<String>()
        val totalOceans: Int
        when {
            fullOceanDisplayNames == null || protectedOceanNames == null -> {
                Log.debug(
                    TAG,
                    "【缺失汇总-海洋】（$who）：（注：海洋 cultivationList 接口不带目标账号 uid，查他人时无法确认是否已保护；" +
                        "如需对比请切换至目标账号登录后手动查询）"
                )
                totalOceans = 0
            }
            fullOceanDisplayNames.isEmpty() -> {
                Log.debug(TAG, "【缺失汇总-海洋】（$who）：（海洋 cultivationList 本次无返回，跳过）")
                totalOceans = 0
            }
            else -> {
                totalOceans = fullOceanDisplayNames.size
                val protectedLower = protectedOceanNames.map { it.trim().lowercase() }.toHashSet()
                // 排序让输出顺序稳定
                val orderedAll = fullOceanDisplayNames.sortedBy { it }
                for (name in orderedAll) {
                    val trimName = name.trim()
                    if (trimName.isEmpty()) continue
                    val nameLc = trimName.lowercase()
                    val hit = protectedLower.any { plantedLc ->
                        plantedLc == nameLc || plantedLc.contains(nameLc) || nameLc.contains(plantedLc)
                    }
                    if (!hit) {
                        missingOceanList.add(trimName)
                        missingOceans += 1
                    }
                }
                if (missingOceanList.isNotEmpty()) {
                    val body = if (missingOceanList.size <= 8) {
                        missingOceanList.joinToString("、")
                    } else {
                        missingOceanList.take(8).joinToString("、") + " …等${missingOceanList.size}项"
                    }
                    Log.debug(
                        TAG,
                        "【缺失汇总-海洋】（$who）：未保护${missingOceans}/共${totalOceans}项：[$body]"
                    )
                } else {
                    Log.debug(TAG, "【缺失汇总-海洋】（$who）：${totalOceans}项全部集齐")
                }
            }
        }
        // 4) 汇总尾巴（海洋参与了对比才纳入总判断）
        val oceanParticipated = fullOceanDisplayNames != null && protectedOceanNames != null && totalOceans > 0
        val nothingMissing =
            totalMissingTreeRegions == 0 && missingReserves == 0 && (!oceanParticipated || missingOceans == 0)
        val summarySb = StringBuilder("【缺失汇总-总结】（$who）：")
        if (nothingMissing) {
            summarySb.append("🎉全部集齐")
        } else {
            var first = true
            if (totalMissingTreeRegions > 0) {
                summarySb.append("树木缺${totalMissingTreeRegions}地")
                first = false
            }
            if (missingReserves > 0) {
                if (!first) summarySb.append(" × ")
                summarySb.append("保护地缺${missingReserves}个")
                first = false
            }
            if (oceanParticipated && missingOceans > 0) {
                if (!first) summarySb.append(" × ")
                summarySb.append("海洋缺${missingOceans}项")
            }
        }
        summarySb.append("。可编辑 ${cfgFile.name} 调整树木/保护地全量清单（海洋直接以 cultivationList 接口为准）。")
        Log.debug(TAG, summarySb.toString())
    }
    /**
     * 通过 uid 查询支付宝昵称（用于缺失汇总头部展示）。
     *
     * 走 queryFriendHomePage（好友森林主页），昵称在返回 JSON 的 userEnergy.displayName 字段。
     * 查询失败/对方未开通/无权限时返回空串，调用方回退为仅显示 uid。
     */
    private fun fetchAlipayNickName(userId: String): String {
        if (userId.isBlank()) return ""
        try {
            val resp = AntForestRpcCall.queryFriendHomePage(userId, null)
            if (resp.isBlank()) return ""
            val jo = JSONObject(resp)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.debug(TAG, "查询昵称失败[$userId]：${jo.optString("resultDesc", "未知错误")}")
                return ""
            }
            return jo.optJSONObject("userEnergy")?.optString("displayName").orEmpty()
        } catch (t: Throwable) {
            Log.runtime(TAG, "fetchAlipayNickName err:")
            Log.printStackTrace(TAG, t)
        }
        return ""
    }

    companion object {
        private const val TAG = "DebugRpc"

        /** 判断 queryAreaTrees 的 name 是否为保护地的认领面积（如“1平方米”“10平方米”），是则该 alias 为保护地。 */
        private fun isReserveAreaStatic(name: String?): Boolean = name != null && name.contains("平方米")

        /** 与 DebugRpc 实例方法 looksLikeReserveProject 语义一致（静态版本供 companion object 内 freshPlantedSnapshot 复用）。 */
        private fun looksLikeReserveProjectStatic(name: String?): Boolean {
            if (name == null) return false
            val n = name.lowercase()
            if (n.isBlank()) return false
            val keywords = listOf(
                "保护",
                "保护地",
                "生物多样性",
                "多样性",
                "公益",
                "动物园",
                "自然保护",
                "湿地",
                "红枫林",
                "珍稀"
            )
            return keywords.any { n.contains(it.lowercase()) }
        }

        /** 与 DebugRpc 实例方法 extractReserveLikeDisplayNames 语义一致（静态版本）。 */
        private fun extractReserveLikeDisplayNamesStatic(entry: org.json.JSONObject): Set<String> {
            val out = HashSet<String>()
            val type = entry.optString("type", "").uppercase()
            val primaryFields = listOfNotNull(entry.optString("projectName"), entry.optString("treeName"))
            for (raw in primaryFields) {
                if (raw.isBlank()) continue
                out += raw
                val parts = raw.split("<br/>", "\n", "｜").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size > 1) {
                    if (type != "RESERVE") parts.firstOrNull()?.let { out += it }
                    parts.filter { it.length >= 2 }.forEach { out += it }
                }
            }
            val secondFields = listOfNotNull(entry.optString("forestName"), entry.optString("ngoName"))
            for (raw in secondFields) {
                if (raw.isBlank()) continue
                val parts = raw.split("<br/>", "\n", "｜")
                    .map { it.trim() }.filter { it.length >= 2 }
                    .filterNot { seg -> seg.contains("基金会") || seg.contains("NGO") }
                if (parts.isNotEmpty()) out += parts
            }
            return out
        }

        /**
         * 公开入口：立即从 RPC 查最新的「已种树×地区」结果，返回便于后续落盘/比较的两部分数据。
         *
         * @param targetUid 目标账号 uid；空串=查当前登录账号自己。
         * @return first=普通树中文名→中文地区集合；second=已种保护地真名(projectName)集合；失败抛异常。
         */
        @JvmStatic
        fun freshPlantedSnapshot(targetUid: String = ""): Triple<Map<String, Set<String>>, Set<String>, Set<String>> {
            val planted = fetchPlantedByAliasStatic(targetUid)
            val allAliases = planted.byAlias.keys.toList()
            val reserveAliases = ArrayList<String>(allAliases.filter { isReserveAreaStatic(planted.aliasNames[it]) })
            val extraLikeAliases = allAliases.filter { a ->
                !reserveAliases.contains(a) && looksLikeReserveProjectStatic(planted.aliasNames[a])
            }
            val reserveMap = HashMap(fetchReserveNamesStatic(reserveAliases, targetUid))
            if (extraLikeAliases.isNotEmpty()) {
                reserveMap.putAll(fetchReserveNamesStatic(extraLikeAliases, targetUid))
            }
            // 注意：海洋项目是否"已种"不再通过本函数反查——海洋自己的 cultivationList 接口返回的 certNum
            //   就是最准的口径（certNum>0=用户已领过至少一份证书）。此处第三个字段保留空集合，
            //   仅用于兼容 PlantedSnapshot.oceans 的结构序列化，将来扩展"海洋全量配置清单比对"时再用。
            val cnByTree = LinkedHashMap<String, MutableSet<String>>()
            for ((alias, codes) in planted.byAlias) {
                if (isReserveAreaStatic(planted.aliasNames[alias])) continue
                val cnName = planted.aliasNames[alias]?.ifEmpty { null } ?: continue
                val bucket = cnByTree.getOrPut(cnName) { HashSet() }
                for (c in codes) {
                    val cn = planted.regionNames[c]?.trim() ?: continue
                    if (cn.isNotEmpty()) bucket.add(cn)
                }
            }
            val reservesReal = reserveMap.values.filter { it.isNotBlank() }.toHashSet()
            val oceansReal = emptySet<String>()
            return Triple(cnByTree, reservesReal, oceansReal)
        }

        private fun fetchPlantedByAliasStatic(targetUid: String = ""): DebugRpcPlantedData {
            val planted = DebugRpcPlantedData()
            try {
                val resp = ReserveRpcCall.queryAreaTrees(targetUid) ?: return planted
                val jo = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.debug(TAG, "查询地图已种数据失败：${jo.optString("resultDesc", "未知错误")}")
                    return planted
                }
                jo.optJSONObject("regionConfig")?.let { rc ->
                    val it = rc.keys()
                    while (it.hasNext()) {
                        val code = it.next()
                        val entry = rc.optJSONObject(code) ?: continue
                        val name = entry.optString("name")
                        if (name.isNotEmpty()) planted.regionNames[code] = name
                    }
                }
                val at = jo.optJSONObject("areaTrees") ?: return planted
                val regionIt = at.keys()
                while (regionIt.hasNext()) {
                    val regionCode = regionIt.next().ifBlank { continue }
                    val treeObj = at.optJSONObject(regionCode) ?: continue
                    val aliasIt = treeObj.keys()
                    while (aliasIt.hasNext()) {
                        val alias = aliasIt.next().ifBlank { continue }
                        val rec = treeObj.optJSONObject(alias) ?: continue
                        val treeName = rec.optString("name")
                        planted.byAlias.getOrPut(alias) { HashSet() }.add(regionCode)
                        if (treeName.isNotEmpty()) planted.aliasNames.putIfAbsent(alias, treeName)
                    }
                }
            } catch (t: Throwable) {
                Log.runtime(TAG, "fetchPlantedByAliasStatic err:")
                Log.printStackTrace(TAG, t)
            }
            return planted
        }

        private fun fetchReserveNamesStatic(aliases: List<String>, targetUid: String = ""): Map<String, String> {
            val names = HashMap<String, String>()
            for (alias in aliases) {
                try {
                    val resp = ReserveRpcCall.queryEnvironmentCertDetailList(alias, targetUid) ?: continue
                    val jo = JSONObject(resp)
                    if (!ResChecker.checkRes(TAG, jo)) continue
                    val arr = jo.optJSONArray("environmentCertDetailVOList")
                        ?: jo.optJSONArray("userProjectTreeVOList")
                        ?: continue
                    for (i in 0 until arr.length()) {
                        val entry = arr.getJSONObject(i)
                        val candidates = extractReserveLikeDisplayNamesStatic(entry)
                        if (candidates.isEmpty()) continue
                        val projectName = entry.optString("projectName").takeIf { it.isNotBlank() }
                            ?: entry.optString("treeName").takeIf { it.isNotBlank() }
                            ?: candidates.first()
                        val short = projectName.split("<br/>", "\n", "｜").firstOrNull { it.isNotBlank() }?.trim()
                            ?: projectName.trim()
                        names[alias] = short
                        break
                    }
                } catch (t: Throwable) {
                    Log.runtime(TAG, "fetchReserveNamesStatic[$alias] err:")
                    Log.printStackTrace(TAG, t)
                }
                GlobalThreadPools.sleepCompat(80)
            }
            return names
        }
    }
}

internal class DebugRpcPlantedData {
    val byAlias = LinkedHashMap<String, MutableSet<String>>()
    val regionNames = HashMap<String, String>()
    val aliasNames = HashMap<String, String>()
}

