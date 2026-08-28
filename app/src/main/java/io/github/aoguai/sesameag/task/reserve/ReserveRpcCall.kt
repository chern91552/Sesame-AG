package io.github.aoguai.sesameag.task.reserve

import io.github.aoguai.sesameag.hook.RequestManager

/**
 * 保护地RPC调用
 */
object ReserveRpcCall {

    private const val VERSION = "20230501"
    private const val VERSION2 = "20230522"

    /**
     * 查询可兑换的树木列表
     *
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeItemsForExchange(): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryTreeItemsForExchange",
            "[{\"cityCode\":\"370100\",\"itemTypes\":\"\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"version\":\"$VERSION2\"}]"
        )
    }

    /**
     * 查询指定项目的兑换树木信息
     *
     * @param projectId 项目ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeForExchange(projectId: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryTreeForExchange",
            "[{\"projectId\":\"$projectId\",\"version\":\"$VERSION\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 兑换树木
     *
     * @param projectId 项目ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun exchangeTree(projectId: String): String {
        val projectIdNum = projectId.toInt()
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.exchangeTree",
            "[{\"projectId\":$projectIdNum,\"sToken\":\"${System.currentTimeMillis()}\",\"version\":\"$VERSION\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 查询地图树苗（已种地图数据）
     *
     * @param userId 目标用户 uid；留空则查询当前登录账号自己。
     *               服务端 queryAreaTrees 支持按 userId 返回指定账号的已种地图，
     *               返回体的 self 字段标识是否为本人。
     * @return RPC响应字符串
     */
    @JvmStatic
    @JvmOverloads
    fun queryAreaTrees(userId: String = ""): String {
        val args = if (userId.isBlank()) "[{}]" else "[{\"userId\":\"$userId\"}]"
        return RequestManager.requestString("alipay.antmember.forest.h5.queryAreaTrees", args)
    }

    /**
     * 查询指定类型的可兑换树木
     *
     * @param applyActions 应用操作
     * @param itemTypes 物品类型
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeItemsForExchange(applyActions: String, itemTypes: String): String {
        val args = "[{\"applyActions\":\"$applyActions\",\"itemTypes\":\"$itemTypes\"}]"
        return RequestManager.requestString("alipay.antforest.forest.h5.queryTreeItemsForExchange", args)
    }

    /**
     * 查询某个 alias（树种/保护地）的环境证书详情列表。
     *
     * 主要用途：保护地在 queryAreaTrees 里 name 只有认领面积（“1平方米”等），
     * 通过本接口按 alias 查证书，返回体每条带 treeName（保护地真实名称），用于给已种保护地补名。
     *
     * @param alias      目标树种 / 保护地的 alias。
     * @param targetUserID 目标账号 uid；留空=查当前登录账号自己。
     * @return RPC响应字符串
     */
    @JvmStatic
    @JvmOverloads
    fun queryEnvironmentCertDetailList(alias: String, targetUserID: String = ""): String {
        val args = "[{\"activityId\":\"\",\"alias\":\"$alias\",\"certId\":\"\"," +
            "\"certificateType\":\"all_plant\",\"cursor\":0,\"newCertDetail\":false,\"pageNum\":1," +
            "\"shareId\":\"\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"," +
            "\"targetUserID\":\"$targetUserID\",\"version\":\"20230701\"}]"
        return RequestManager.requestString("alipay.antforest.forest.h5.queryEnvironmentCertDetailList", args)
    }

    /**
     * 查询海洋保护项目的可种植/可兑换列表（保护海洋"种植页"内容）。
     *
     * 方法：alipay.antocean.ocean.h5.queryCultivationList
     * 请求参数：[{"source":"ANT_FOREST", "version":"20231031"}]
     * 响应关键字段：cultivationItemVOList[]
     *   - applyAction : AVAILABLE / NO_STOCK （是否可兑换）
     *   - cultivationCode：唯一项目 code（mancaooneoff、qiuqie、海滩拼音…）
     *   - cultivationName：中文展示名（鳗草、秋茄、金沙湾海滩…）
     *   - energy：每份需要能量
     *   - certNum：该项目用户已领取证书数（注意≠剩余库存）
     *   - cultivationType：ONE_TIME / BATCH
     *
     * cultivationName 与 queryEnvironmentCertDetailList 返回的 projectName 字段一致，
     * 故可直接按名字判断"该海洋项目有没有已种过"。
     */
    @JvmStatic
    @JvmOverloads
    fun queryOceanCultivationList(source: String = "ANT_FOREST", version: String = "20231031"): String {
        val args = "[{\"source\":\"$source\",\"version\":\"$version\"}]"
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryCultivationList", args)
    }
}

