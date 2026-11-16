package cc.adamu.odosync.diag

import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.link.ObdLink
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls the DME during a drive and feeds [TripAnalyzer] (and the CSV log).
 *
 * Two paces:
 *  - cruise: boost and target every second, all six cylinders every 5 s, temperatures and the
 *    rest every 5-15 s. Under two requests a second, at the BLE link's low-power interval.
 *  - boost (from ~2 psi, held 4 s after it ends): cylinders and boost back to back as fast as
 *    the link answers, with intake temperature, fuel pressure and lambda slotted in. The BLE
 *    link moves to its balanced interval for the duration. A Veepeak answers 2-3 timing
 *    frames a second this way.
 * Each request carries two DIDs when the DME allows it; that's the most a plain ELM327 can
 * fit in one CAN frame.
 */
class DiagSampler(
    private val dme: DmeReader,
    private val link: ObdLink,
    val profile: DmeProfile,
    val analyzer: TripAnalyzer,
    private val log: TripCsvLog?,
    private val fastAllowed: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Item(val sigs: List<Sig>, val fastMs: Long, val slowMs: Long) {
        var nextAt = 0L
        var lastAt = 0L
        var refusals = 0
        var disabled = false
        fun interval(fast: Boolean) = if (fast) fastMs else slowMs
    }

    private val items: List<Item> = buildItems()
    var fast = false
        private set
    private var lastWantFastAt = 0L
    private var lastPollAt = 0L
    private var failures = 0
    private val recentPolls = ArrayDeque<Long>()
    private var lastLiveAt = 0L

    private fun buildItems(): List<Item> {
        val groups = listOf(
            Triple(listOf(Sig.BOOST, Sig.BOOST_TARGET), 0L, 1_000L),
            Triple(listOf(Sig.TIMING_1, Sig.TIMING_2), 0L, 5_000L),
            Triple(listOf(Sig.TIMING_3, Sig.TIMING_4), 0L, 5_000L),
            Triple(listOf(Sig.TIMING_5, Sig.TIMING_6), 0L, 5_000L),
            Triple(listOf(Sig.IAT, Sig.BARO), 2_000L, 10_000L),
            Triple(listOf(Sig.RAIL, Sig.LOW_FUEL), 1_000L, 15_000L),
            Triple(listOf(Sig.LAMBDA, Sig.SPEED), 1_500L, 5_000L),
            Triple(listOf(Sig.OIL, Sig.COOLANT), 20_000L, 15_000L),
            Triple(listOf(Sig.MISFIRE_1, Sig.MISFIRE_2), NEVER, 300_000L),
            Triple(listOf(Sig.MISFIRE_3, Sig.MISFIRE_4), NEVER, 300_000L),
            Triple(listOf(Sig.MISFIRE_5, Sig.MISFIRE_6), NEVER, 300_000L),
        )
        return groups.flatMap { (sigs, f, s) ->
            val have = sigs.filter { profile.has(it) }
            when {
                have.isEmpty() -> emptyList()
                profile.multiDid -> listOf(Item(have, f, s))
                else -> have.map { Item(listOf(it), f, s) }
            }
        }
    }

    /**
     * Sample for up to [ms]. Returns true if [wake] fired (the trip service has news), false
     * when the time slice ran out or the link dropped.
     */
    suspend fun runFor(ms: Long, wake: ReceiveChannel<Unit>): Boolean {
        val end = clock() + ms
        while (clock() < end && link.connected.value) {
            if (wake.tryReceive().isSuccess) return true
            val now = clock()
            updatePace(now)
            if (failures >= 5) {
                // The DME stopped answering (engine off, bus going to sleep): back off.
                failures = 0
                if (withTimeoutOrNull(minOf(5_000L, (end - now).coerceAtLeast(10))) { wake.receive() } != null) return true
                continue
            }
            val item = pick(now)
            if (item == null) {
                val next = items.filter { eligible(it) }.minOfOrNull { it.nextAt } ?: (now + 1000)
                val wait = minOf(next - now, end - now).coerceAtLeast(10)
                if (withTimeoutOrNull(wait) { wake.receive() } != null) return true
                continue
            }
            poll(item)
        }
        return false
    }

    /** One last pass over the misfire counters at the end of the drive, if the DME still answers. */
    suspend fun finalReads() {
        for (item in items.filter { it.sigs.any { s -> s in Sig.MISFIRE } && !it.disabled }) {
            if (!link.connected.value) return
            if (poll(item) !is PollResult.Values) return
        }
    }

    private fun eligible(i: Item) = !i.disabled && i.interval(fast) != NEVER

    private fun pick(now: Long): Item? =
        items.filter { eligible(it) && it.nextAt <= now }.minByOrNull { it.nextAt }

    private fun updatePace(now: Long) {
        val (boost, target) = analyzer.boostNow(now)
        val want = fastAllowed && (analyzer.inBoost || (boost ?: 0.0) >= 12.0 || (target ?: 0.0) >= 25.0)
        if (want) lastWantFastAt = now
        val newFast = want || (fast && now - lastWantFastAt < 4_000)
        if (newFast != fast) {
            fast = newFast
            link.setFastMode(fast)
            for (i in items) i.nextAt = if (i.lastAt == 0L) 0L else i.lastAt + i.interval(fast).coerceAtMost(HOUR)
        }
    }

    private suspend fun poll(item: Item): PollResult {
        val r = dme.read(profile, item.sigs)
        val t = clock()
        if (lastPollAt > 0 && fast) analyzer.fastMs += (t - lastPollAt).coerceIn(0, 2000)
        lastPollAt = t
        analyzer.polls++
        item.lastAt = t
        item.nextAt = t + item.interval(fast).coerceAtMost(HOUR)
        when (r) {
            is PollResult.Values -> {
                failures = 0
                item.refusals = 0
                if (r.values.isNotEmpty()) {
                    analyzer.onValues(t, r.values)
                    log?.row(t, analyzer.latestValues(), analyzer.inBoost)
                }
            }
            PollResult.NoResponse -> { failures++; analyzer.pollErrors++ }
            is PollResult.Failed -> {
                failures++
                analyzer.pollErrors++
                if (r.why.startsWith("NRC") && ++item.refusals >= 3) {
                    item.disabled = true
                    EventLog.log("Engine health: DME keeps refusing ${item.sigs.joinToString { it.label }} (${r.why}); skipping it this drive")
                }
            }
        }
        recentPolls.addLast(t)
        while (recentPolls.isNotEmpty() && t - recentPolls.first() > 5_000) recentPolls.removeFirst()
        publishLive(t)
        return r
    }

    private fun publishLive(t: Long) {
        if (t - lastLiveAt < 250) return
        lastLiveAt = t
        LiveDiag.update {
            it.copy(
                active = true, learning = false,
                values = analyzer.latestValues(),
                inBoost = analyzer.inBoost, fast = fast,
                pullNow = analyzer.livePulls(t),
                pullEvents = analyzer.pullEventsSoFar,
                maxPullDeg = analyzer.maxPullSoFar,
                boostSeconds = analyzer.boostSecondsSoFar,
                pollsPerSecond = recentPolls.size / 5.0,
            )
        }
    }

    companion object {
        const val NEVER = -1L
        private const val HOUR = 3_600_000L
    }
}
