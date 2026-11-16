package cc.adamu.odosync.diag

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tunables, all user-adjustable except where noted. Pressures kPa, temperatures °C. */
data class HealthThresholds(
    /** A cylinder counts as "pulling" once it runs this much less advance than the others. */
    val pullEventDeg: Double = 1.5,
    /** A single pull this big raises an alert by itself. */
    val severePullDeg: Double = 3.0,
    /** Boost below target by this much for a second or more counts as underboost. */
    val underboostKpa: Double = 15.0,
    val overboostKpa: Double = 20.0,
    val oilAlertC: Double = 130.0,
    val iatAlertC: Double = 65.0,
) {
    companion object {
        /** In boost from 20 kPa (~3 psi) above barometric; out again below 10 kPa. Not user-tunable. */
        const val BOOST_ON_KPA = 20.0
        const val BOOST_OFF_KPA = 10.0
        /** Boost deviation is only judged once the DME has asked for at least this much for a while. */
        const val DEV_TARGET_KPA = 40.0
        const val DEV_SETTLE_MS = 1500L
        const val OIL_HOT_C = 120.0
        const val OIL_COLD_C = 70.0
    }
}

/**
 * Streams DME readings from one drive into a [TripSummary].
 *
 * Timing pulls: the DME exposes each cylinder's final ignition angle, not the knock-control
 * correction itself. Under boost every cylinder runs the same base timing, so when knock control
 * retards one cylinder it shows up as that cylinder sitting below the others. Each read is
 * compared with the median of every monitored cylinder's latest value: one
 * [HealthThresholds.pullEventDeg] or more below it starts a pull (confirmed by the partner read
 * in the same reply, or by a second read in a row), which ends once it's back within half that.
 * Pulls on several cylinders at once merge into one event.
 *
 * Cylinders 5 and 6 come from inferred DIDs (see [DmeSignals]). Until a drive shows them
 * following cylinders 1-4 as timing moves around, they're logged but not used for pulls.
 */
class TripAnalyzer(
    private val t0: Long,
    private val cfg: HealthThresholds = HealthThresholds(),
    /** Verdicts from earlier drives for the inferred cylinder 5/6 channels. */
    initialVerdicts: Map<Sig, Boolean> = emptyMap(),
    private val startKm: Long = -1,
) {
    private data class Reading(val v: Double, val t: Long)

    private val latest = HashMap<Sig, Reading>()
    fun latest(s: Sig): Double? = latest[s]?.v

    // ---------------------------------------------------------------- boost state
    var inBoost = false
        private set
    private var lastBoostT = 0L
    private var boostMs = 0L
    private var episodes = 0
    private var peakBoost: Double? = null
    private var peakTarget: Double? = null
    private var episodeIatStart: Double? = null
    private var episodeIatMax: Double? = null
    private var iatRiseMax: Double? = null
    private var targetHighSince = 0L
    private var devSum = 0.0
    private var devMs = 0L
    private var worstUnder: Double? = null
    private var worstOver: Double? = null
    private var underRunMs = 0L
    private var overRunMs = 0L
    private var underEvents = 0
    private var overEvents = 0
    private var underCounted = false
    private var overCounted = false

    // ---------------------------------------------------------------- timing state
    private val verdicts = HashMap(initialVerdicts)
    private var lastTimingT = 0L
    private var framesInBoost = 0
    private val cylActive = BooleanArray(6)
    private val cylEvents = IntArray(6)
    private val cylMax = DoubleArray(6)
    private val cylSuspect = BooleanArray(6)
    private var pullEvents = 0
    private val events = mutableListOf<PullEvent>()
    private var currentEvent: PullEvent? = null
    private var timingSum = 0.0
    private var timingMs = 0L
    // Channel checks for cylinders 5/6: agreement with the median of 1-4 across all frames.
    private val checkFrames = IntArray(6)
    private val checkAgree = IntArray(6)
    private var refMin = Double.MAX_VALUE
    private var refMax = -Double.MAX_VALUE

    // ---------------------------------------------------------------- temperatures, fuel, misc
    private var iatMax: Double? = null
    private var iatMaxBoost: Double? = null
    private var oilMax: Double? = null
    private var oilHotMs = 0L
    private var lastOilT = 0L
    private var coolantMax: Double? = null
    private var coldBoostMs = 0L
    private var railMinBoost: Double? = null
    private var railMax: Double? = null
    private var lowFuelMinBoost: Double? = null
    private var lambdaMin: Double? = null
    private var lambdaMax: Double? = null
    private var speedKm = 0.0
    private var lastSpeedT = 0L
    private val misfireStart = arrayOfNulls<Int>(6)
    private val misfireLast = arrayOfNulls<Int>(6)
    var polls = 0
    var pollErrors = 0
    var fastMs = 0L

    /** Feed one poll's decoded values, all taken at [t]. */
    fun onValues(t: Long, values: Map<Sig, Double>) {
        for ((s, v) in values) latest[s] = Reading(v, t)
        if (Sig.BOOST in values || Sig.BOOST_TARGET in values) onBoost(t)
        values[Sig.IAT]?.let { iat ->
            iatMax = maxOf(iatMax, iat)
            if (inBoost) {
                iatMaxBoost = maxOf(iatMaxBoost, iat)
                episodeIatMax = maxOf(episodeIatMax, iat)
                if (episodeIatStart == null) episodeIatStart = iat
            }
        }
        values[Sig.OIL]?.let { oil ->
            oilMax = maxOf(oilMax, oil)
            if (lastOilT > 0 && oil >= HealthThresholds.OIL_HOT_C) oilHotMs += (t - lastOilT).coerceIn(0, 60_000)
            lastOilT = t
        }
        values[Sig.COOLANT]?.let { coolantMax = maxOf(coolantMax, it) }
        values[Sig.RAIL]?.let { r ->
            railMax = maxOf(railMax, r)
            if (devActive(t)) railMinBoost = minOf(railMinBoost, r)
        }
        values[Sig.LOW_FUEL]?.let { if (devActive(t)) lowFuelMinBoost = minOf(lowFuelMinBoost, it) }
        values[Sig.LAMBDA]?.let { l ->
            if (inBoost) { lambdaMin = minOf(lambdaMin, l); lambdaMax = maxOf(lambdaMax, l) }
        }
        values[Sig.SPEED]?.let { v ->
            if (lastSpeedT > 0) speedKm += v * (t - lastSpeedT).coerceIn(0, 10_000) / 3_600_000.0
            lastSpeedT = t
        }
        for ((i, s) in Sig.MISFIRE.withIndex()) {
            val v = values[s]?.toInt() ?: continue
            if (misfireStart[i] == null) misfireStart[i] = v
            misfireLast[i] = v
        }
        val polled = (0 until 6).filter { Sig.TIMING[it] in values }
        if (polled.isNotEmpty()) onTimingPoll(t, polled)
    }

    private fun baro(): Double = latest[Sig.BARO]?.v ?: 101.3

    private fun devActive(t: Long) = targetHighSince > 0 && t - targetHighSince >= HealthThresholds.DEV_SETTLE_MS

    private fun onBoost(t: Long) {
        val boost = latest[Sig.BOOST]?.takeIf { t - it.t < 3000 }?.v
        val target = latest[Sig.BOOST_TARGET]?.takeIf { t - it.t < 3000 }?.v
        val baro = baro()
        val gauge = boost?.let { it - baro }
        val tgtGauge = target?.let { it - baro }
        val dt = if (lastBoostT > 0) (t - lastBoostT).coerceIn(0, 2000) else 0
        lastBoostT = t

        if (inBoost) {
            boostMs += dt
            if ((latest[Sig.OIL]?.v ?: 100.0) < HealthThresholds.OIL_COLD_C) coldBoostMs += dt
        }
        val nowInBoost = when {
            gauge != null -> if (inBoost) gauge >= HealthThresholds.BOOST_OFF_KPA else gauge >= HealthThresholds.BOOST_ON_KPA
            tgtGauge != null -> tgtGauge >= HealthThresholds.BOOST_ON_KPA + 10
            else -> false
        }
        if (nowInBoost && !inBoost) {
            episodes++
            episodeIatStart = latest[Sig.IAT]?.v
            episodeIatMax = episodeIatStart
        } else if (!nowInBoost && inBoost) {
            closeEpisode()
        }
        inBoost = nowInBoost
        if (gauge != null) peakBoost = maxOf(peakBoost, gauge)
        if (tgtGauge != null) peakTarget = maxOf(peakTarget, tgtGauge)

        // Boost against target, once the request has been high long enough to have spooled.
        if (tgtGauge != null && tgtGauge >= HealthThresholds.DEV_TARGET_KPA) {
            if (targetHighSince == 0L) targetHighSince = t
        } else {
            targetHighSince = 0
            underRunMs = 0; overRunMs = 0; underCounted = false; overCounted = false
        }
        if (devActive(t) && boost != null && target != null) {
            val dev = boost - target
            devSum += dev * dt
            devMs += dt
            if (dt > 0) {
                worstUnder = minOf(worstUnder, dev)
                worstOver = maxOf(worstOver, dev)
            }
            if (dev <= -cfg.underboostKpa) {
                underRunMs += dt
                if (underRunMs >= 1000 && !underCounted) { underEvents++; underCounted = true }
            } else { underRunMs = 0; underCounted = false }
            if (dev >= cfg.overboostKpa) {
                overRunMs += dt
                if (overRunMs >= 500 && !overCounted) { overEvents++; overCounted = true }
            } else { overRunMs = 0; overCounted = false }
        }
    }

    private fun closeEpisode() {
        val start = episodeIatStart
        val top = episodeIatMax
        if (start != null && top != null) iatRiseMax = maxOf(iatRiseMax, top - start)
        episodeIatStart = null
        episodeIatMax = null
    }

    /** Which cylinders' timing is trusted right now (0-based). */
    private fun trustedChannels(): List<Int> = (0 until 6).filter { i ->
        val s = Sig.TIMING[i]
        s in latest && (i < 4 || verdicts[s] == true)
    }

    /**
     * Judge the cylinders just read ([polled], 0-based) against the latest of the others.
     *
     * Reads are sequential (two cylinders per request at best), so the "others" are a few
     * hundred ms old. When timing swings for every cylinder at once (tip-in, or the ignition
     * cut the gearbox asks for on a shift), that age alone can look like one cylinder lagging.
     * So a pull counts straight away only when the cylinder also sits below its partner read in
     * the *same* reply, which shares its instant; otherwise it has to show up on two reads in
     * a row.
     */
    private fun onTimingPoll(t: Long, polled: List<Int>) {
        checkInferredChannels(t, polled)
        val recent = trustedChannels().filter { i -> latest[Sig.TIMING[i]]?.let { t - it.t <= 1500 } == true }
        val judged = polled.filter { it in recent }
        if (!inBoost) {
            endEvent()
            lastTimingT = t
            return
        }
        if (judged.isEmpty() || recent.size < 3) return
        val dt = if (lastTimingT > 0) (t - lastTimingT).coerceIn(0, 2000) else 0
        lastTimingT = t
        val ref = median(recent.map { latest.getValue(Sig.TIMING[it]).v })
        if (judged.first() == recent.first()) framesInBoost++ // one per round of reads
        timingSum += ref * dt
        timingMs += dt

        val th = cfg.pullEventDeg
        for (i in judged) {
            val v = latest.getValue(Sig.TIMING[i]).v
            val pull = ref - v
            val partner = judged.firstOrNull { it != i }
            val sameInstant = partner != null && latest.getValue(Sig.TIMING[partner]).v - v >= 0.75 * th
            val suspect = pull >= th && !sameInstant
            val active = when {
                cylActive[i] -> pull >= th / 2
                pull >= th && sameInstant -> true
                else -> suspect && cylSuspect[i]
            }
            cylSuspect[i] = suspect
            if (active && !cylActive[i]) cylEvents[i]++
            cylActive[i] = active
            if (active) cylMax[i] = max(cylMax[i], pull)
        }
        for (i in (0 until 6) - recent.toSet()) { cylActive[i] = false; cylSuspect[i] = false }

        val involved = (0 until 6).filter { cylActive[it] }
        if (involved.isNotEmpty()) {
            val frameMax = involved.maxOf { ref - latest.getValue(Sig.TIMING[it]).v }
            val boostKpa = latest[Sig.BOOST]?.v?.let { it - baro() }
            val e = currentEvent
            if (e == null) {
                pullEvents++
                currentEvent = PullEvent((t - t0) / 1000.0, involved.map { it + 1 }, frameMax, boostKpa,
                    latest[Sig.IAT]?.v, latest[Sig.OIL]?.v, latest[Sig.SPEED]?.v)
            } else {
                currentEvent = e.copy(
                    cylinders = (e.cylinders + involved.map { it + 1 }).distinct().sorted(),
                    maxDeg = max(e.maxDeg, frameMax),
                    boostKpa = if (frameMax > e.maxDeg) boostKpa else e.boostKpa,
                )
            }
        } else if (currentEvent != null) {
            endEvent()
        }
    }

    private fun endEvent() {
        currentEvent?.let { if (events.size < MAX_EVENTS) events += it }
        currentEvent = null
        cylActive.fill(false)
        cylSuspect.fill(false)
    }

    /**
     * Cylinders 5/6 are trusted once, over a drive where timing moved at least 5°, they stayed
     * within 3° of cylinders 1-4 in nine reads out of ten (20+ reads). They're rejected if,
     * over 60+ such reads, they agreed less than half the time.
     */
    private fun checkInferredChannels(t: Long, polled: List<Int>) {
        val base = (0 until 4).mapNotNull { i -> latest[Sig.TIMING[i]]?.takeIf { t - it.t <= 1500 }?.v }
        if (base.size < 3) return
        val ref = median(base)
        refMin = min(refMin, ref)
        refMax = max(refMax, ref)
        for (i in polled.filter { it >= 4 }) {
            val s = Sig.TIMING[i]
            if (verdicts.containsKey(s)) continue
            val v = latest.getValue(s).v
            checkFrames[i]++
            if (abs(v - ref) <= 3.0) checkAgree[i]++
            val moved = refMax - refMin >= 5.0
            val share = checkAgree[i].toDouble() / checkFrames[i]
            if (moved && checkFrames[i] >= 20 && share >= 0.9) verdicts[s] = true
            else if (moved && checkFrames[i] >= 60 && share < 0.5) verdicts[s] = false
        }
    }

    /** Verdicts to remember for the next drive (only those settled on this one or before). */
    fun channelVerdicts(): Map<Sig, Boolean> = HashMap(verdicts)

    /** For the live view: each trusted cylinder's degrees behind the median right now. */
    fun livePulls(now: Long): List<Double?> {
        val chans = trustedChannels().filter { i -> latest[Sig.TIMING[i]]?.let { now - it.t <= 3000 } == true }
        if (chans.size < 3) return List(6) { null }
        val ref = median(chans.map { latest.getValue(Sig.TIMING[it]).v })
        return (0 until 6).map { i -> if (i in chans) (ref - latest.getValue(Sig.TIMING[i]).v).coerceAtLeast(0.0) else null }
    }

    val pullEventsSoFar: Int get() = pullEvents
    val maxPullSoFar: Double get() = cylMax.maxOrNull() ?: 0.0
    val boostSecondsSoFar: Double get() = boostMs / 1000.0

    /** Gauge boost and target right now (kPa above barometric), for choosing the sampling pace. */
    fun boostNow(now: Long): Pair<Double?, Double?> {
        val b = latest[Sig.BOOST]?.takeIf { now - it.t < 3000 }?.v?.minus(baro())
        val g = latest[Sig.BOOST_TARGET]?.takeIf { now - it.t < 3000 }?.v?.minus(baro())
        return b to g
    }

    fun latestValues(): Map<Sig, Double> = latest.mapValues { it.value.v }

    fun finish(endedAt: Long, endKm: Long = -1, logFile: String? = null): TripSummary {
        if (inBoost) closeEpisode()
        endEvent()
        val maxIdx = (0 until 6).maxByOrNull { cylMax[it] }?.takeIf { cylMax[it] > 0 }
        return TripSummary(
            startedAt = t0, endedAt = endedAt, startKm = startKm, endKm = endKm,
            speedDistanceKm = speedKm,
            boostSeconds = boostMs / 1000.0, boostEpisodes = episodes,
            peakBoostKpa = peakBoost, peakTargetKpa = peakTarget,
            meanBoostDevKpa = if (devMs > 0) devSum / devMs else null,
            worstUnderboostKpa = worstUnder?.takeIf { it < 0 }, worstOverboostKpa = worstOver?.takeIf { it > 0 },
            underboostEvents = underEvents, overboostEvents = overEvents,
            timingFramesInBoost = framesInBoost, pullEvents = pullEvents,
            maxPullDeg = maxIdx?.let { cylMax[it] } ?: 0.0, maxPullCylinder = maxIdx?.plus(1),
            perCylEvents = cylEvents.toList(), perCylMaxPull = cylMax.toList(),
            meanTimingInBoost = if (timingMs > 0) timingSum / timingMs else null,
            cylindersMonitored = trustedChannels().map { it + 1 },
            events = events.toList(),
            iatMaxC = iatMax, iatMaxInBoostC = iatMaxBoost, iatRiseMaxC = iatRiseMax,
            oilMaxC = oilMax, oilHotSeconds = oilHotMs / 1000.0, coolantMaxC = coolantMax,
            coldBoostSeconds = coldBoostMs / 1000.0,
            railMinInBoostBar = railMinBoost, railMaxBar = railMax, lowFuelMinInBoostBar = lowFuelMinBoost,
            lambdaMinInBoost = lambdaMin, lambdaMaxInBoost = lambdaMax,
            misfires = (0 until 6).map { i ->
                val a = misfireStart[i]; val b = misfireLast[i]
                if (a == null || b == null) null else if (b >= a) b - a else b // counter reset mid-drive
            },
            polls = polls, pollErrors = pollErrors, fastSeconds = fastMs / 1000.0,
            logFile = logFile,
        )
    }

    companion object {
        const val MAX_EVENTS = 60

        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }

        private fun maxOf(a: Double?, b: Double): Double = if (a == null || b > a) b else a
        private fun minOf(a: Double?, b: Double): Double = if (a == null || b < a) b else a
    }
}
