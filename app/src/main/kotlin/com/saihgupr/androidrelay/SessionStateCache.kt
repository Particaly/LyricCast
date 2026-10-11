package com.saihgupr.androidrelay

/**
 * 各媒体会话最新快照的内存缓存(纯 Kotlin,JVM 可测)。
 *
 * 关键语义(协议 §4):每个包名只保留**最新一条** MediaInfo —— 断线期间事件持续
 * 到来时反复覆盖同一条目、不排队累积,重连后由 LanPushManager 以 snapshot 形式
 * 发出,天然满足"断线期间只保留最新状态"。
 *
 * 会话重选沿用 [SessionSelector]:playing 优先 → 最近活跃优先,再叠加黑/白名单;
 * 选中结果缓存于 [selectedPackage],供重连补发与"无会话"判定。
 * 线程模型:只在主线程读写(LanPushManager 保证)。
 */
class SessionStateCache {

    private val latest = LinkedHashMap<String, MediaInfo>()
    private val lastActiveAt = HashMap<String, Long>()

    /** 最近一次选择跟随的包名;无活跃会话时为 null。 */
    var selectedPackage: String? = null
        private set

    /** 记录一次媒体事件(同包名覆盖式更新)。 */
    fun update(info: MediaInfo, nowElapsed: Long) {
        latest[info.appPackage] = info
        lastActiveAt[info.appPackage] = nowElapsed
    }

    /** 重新执行会话选择并缓存选中包名;无符合条件会话时返回 null。 */
    fun select(blacklist: Set<String>, whitelist: Set<String>): MediaInfo? {
        val selected = SessionSelector.select(
            latest.map { (pkg, info) ->
                SessionSelector.Candidate(
                    packageName = pkg,
                    isPlaying = info.playback.status == PlaybackStatus.PLAYING,
                    lastActiveAt = lastActiveAt[pkg] ?: 0L
                )
            },
            blacklist = blacklist,
            whitelist = whitelist
        )
        selectedPackage = selected?.packageName
        return selectedInfo()
    }

    /** 当前选中会话的最新快照;未选中或缺失时为 null。 */
    fun selectedInfo(): MediaInfo? = selectedPackage?.let { latest[it] }

    /** 任意会话的最新快照(发送 state=none 空快照时取最后曲目元数据用)。 */
    fun anyInfo(): MediaInfo? = latest.values.firstOrNull()

    /** 测试与诊断用:某包名当前缓存的快照。 */
    fun latestOf(pkg: String): MediaInfo? = latest[pkg]

    /** 测试用:当前缓存的会话数(断线期间不增长,同包覆盖)。 */
    fun size(): Int = latest.size
}
