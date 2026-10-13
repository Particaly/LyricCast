package com.saihgupr.androidrelay

/**
 * 多会话选择器:系统 getActiveSessions 可能同时返回多个 App 的媒体会话
 * (音乐 + 视频 + 播客……),LAN 推送同一时刻只应跟随一个"活跃"会话。
 *
 * 选择规则(与推送协议约定一致):
 * 1. 先按白/黑名单过滤(白名单非空时只认白名单,否则排除黑名单);
 * 2. PLAYING 状态的会话优先;
 * 3. 同为 PLAYING(或都不在播放)时,取最近活跃的(见 Candidate.lastActiveAt);
 * 4. 全部被过滤掉时返回 null(调用方应按"无播放"处理,而不是回落到被排除的会话)。
 *
 * 本类为纯逻辑、无 Android 依赖,可被 JVM 单元测试直接覆盖。
 */
object SessionSelector {

    /**
     * 一个候选会话的最小描述。
     *
     * @param packageName 来源应用包名
     * @param isPlaying 该会话当前是否处于 PLAYING 状态
     * @param lastActiveAt 会话最近一次活跃时刻(服务以"最近收到媒体回调"的
     *                     SystemClock.elapsedRealtime() 计,统一时钟即可比较)
     */
    data class Candidate(
        val packageName: String,
        val isPlaying: Boolean,
        val lastActiveAt: Long
    )

    /**
     * 从候选中选出应当被 LAN 通道跟随的会话。
     *
     * @param candidates 全部活跃会话(不去重,由调用方保证来源)
     * @param blacklist 包名黑名单,空集合表示不启用
     * @param whitelist 包名白名单,非空时优先生效(黑名单被忽略)
     * @return 被选中的候选;列表为空或全部被过滤时为 null
     */
    fun select(
        candidates: List<Candidate>,
        blacklist: Set<String> = emptySet(),
        whitelist: Set<String> = emptySet()
    ): Candidate? {
        return candidates
            .filter { passesFilter(it.packageName, blacklist, whitelist) }
            .maxWithOrNull(
                compareBy<Candidate> { it.isPlaying }
                    .thenBy { it.lastActiveAt }
            )
    }

    /**
     * 判断一个包名是否通过过滤:
     * - 白名单非空:只允许白名单内的包;
     * - 否则:排除黑名单内的包(黑名单为空等于全放行)。
     * 比较忽略大小写,避免手写配置时因大小写不一致失效。
     */
    fun passesFilter(
        packageName: String,
        blacklist: Set<String>,
        whitelist: Set<String>
    ): Boolean {
        val pkg = packageName.lowercase()
        return if (whitelist.isNotEmpty()) {
            whitelist.any { it.lowercase() == pkg }
        } else {
            blacklist.none { it.lowercase() == pkg }
        }
    }
}
