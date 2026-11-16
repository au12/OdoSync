package cc.adamu.odosync.diag

import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.obd.CarReader
import cc.adamu.odosync.obd.DidResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * What this particular DME answers, learnt once and cached (see [DiagStore]): which DID serves
 * each signal, each DID's data length, and whether one request may carry two DIDs.
 */
data class DmeProfile(
    val chosen: Map<Sig, Int>,
    val lengths: Map<Int, Int>,
    val unsupported: Set<Int>,
    val multiDid: Boolean,
    val discoveredAt: Long,
    /** Cylinder 5/6 timing channels confirmed (true) or rejected (false) on a drive; absent = not yet known. */
    val channelVerdicts: Map<Sig, Boolean> = emptyMap(),
) {
    fun has(s: Sig) = s in chosen

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("chosen", JSONObject().apply { chosen.forEach { (s, d) -> put(s.name, d) } })
        .put("lengths", JSONObject().apply { lengths.forEach { (d, l) -> put(d.toString(), l) } })
        .put("unsupported", JSONArray(unsupported.sorted()))
        .put("multiDid", multiDid)
        .put("discoveredAt", discoveredAt)
        .put("channelVerdicts", JSONObject().apply { channelVerdicts.forEach { (s, ok) -> put(s.name, ok) } })

    companion object {
        const val VERSION = 1

        fun fromJson(o: JSONObject): DmeProfile? = runCatching {
            if (o.optInt("version") != VERSION) return null
            val chosen = o.getJSONObject("chosen").let { c ->
                c.keys().asSequence().mapNotNull { k ->
                    runCatching { Sig.valueOf(k) }.getOrNull()?.let { it to c.getInt(k) }
                }.toMap()
            }
            val lengths = o.getJSONObject("lengths").let { l -> l.keys().asSequence().associate { it.toInt() to l.getInt(it) } }
            val unsupported = o.getJSONArray("unsupported").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val verdicts = o.optJSONObject("channelVerdicts")?.let { v ->
                v.keys().asSequence().mapNotNull { k ->
                    runCatching { Sig.valueOf(k) }.getOrNull()?.let { it to v.getBoolean(k) }
                }.toMap()
            }.orEmpty()
            DmeProfile(chosen, lengths, unsupported, o.getBoolean("multiDid"), o.getLong("discoveredAt"), verdicts)
        }.getOrNull()
    }
}

/** Result of one poll: decoded values, or why there are none. */
sealed interface PollResult {
    data class Values(val values: Map<Sig, Double>) : PollResult
    /** The DME didn't answer at all (engine computer asleep, or the link dropped). */
    data object NoResponse : PollResult
    data class Failed(val why: String) : PollResult
}

/**
 * Reads [Sig]s from the DME through the existing ELM327/UDS plumbing ([CarReader.readDids]).
 */
class DmeReader(private val car: CarReader) {

    /**
     * Learn what this DME supports: one single-DID request per candidate (about 25 requests,
     * a few seconds), then one two-DID request to see whether batching works. Returns null if
     * the DME isn't answering (car asleep), so it can be retried on the next drive.
     */
    suspend fun discover(now: Long): DmeProfile? {
        val chosen = linkedMapOf<Sig, Int>()
        val lengths = mutableMapOf<Int, Int>()
        val unsupported = mutableSetOf<Int>()
        var answered = 0
        for ((sig, candidates) in DmeSignals.catalog) {
            for (def in candidates) {
                when (val r = car.readDids(DmeSignals.DME, listOf(def.did))) {
                    is DidResult.Data -> {
                        answered++
                        val data = DmeSignals.split(r.payload) { if (it == def.did) r.payload.size - 3 else null }[def.did]
                        val value = data?.let(def.decode)
                        if (data != null && value != null) {
                            chosen[sig] = def.did
                            lengths[def.did] = data.size
                        } else {
                            EventLog.log("DME ${def.hex} (${sig.label}): implausible reply ${r.payload.hex()}")
                        }
                    }
                    is DidResult.Negative -> { answered++; unsupported += def.did }
                    DidResult.NoResponse -> if (answered == 0) {
                        EventLog.log("Engine health: DME not answering; will try again on the next drive")
                        return null
                    }
                    is DidResult.Error -> EventLog.log("DME ${def.hex}: ${r.why}")
                }
                if (sig in chosen) break
            }
        }
        if (Sig.BOOST !in chosen && Sig.TIMING_1 !in chosen) {
            EventLog.log("Engine health: the DME answered none of the core values (boost, timing)")
            return null
        }
        val multi = probeMultiDid(chosen, lengths)
        EventLog.log("Engine health: DME supports ${chosen.size} values" +
            (if (unsupported.isNotEmpty()) ", not ${unsupported.joinToString { "%04X".format(it) }}" else "") +
            "; ${if (multi) "two values per request" else "one value per request"}")
        return DmeProfile(chosen, lengths, unsupported, multi, now)
    }

    private suspend fun probeMultiDid(chosen: Map<Sig, Int>, lengths: Map<Int, Int>): Boolean {
        val a = chosen[Sig.TIMING_1] ?: chosen[Sig.BOOST] ?: return false
        val b = chosen[Sig.TIMING_2] ?: chosen[Sig.BOOST_TARGET] ?: return false
        val r = car.readDids(DmeSignals.DME, listOf(a, b))
        if (r !is DidResult.Data) return false
        val parts = DmeSignals.split(r.payload) { lengths[it] }
        return a in parts && b in parts
    }

    /** Read [sigs] (at most two when the profile allows batching, otherwise one). */
    suspend fun read(profile: DmeProfile, sigs: List<Sig>): PollResult {
        val dids = sigs.mapNotNull { profile.chosen[it] }
        if (dids.isEmpty()) return PollResult.Values(emptyMap())
        return when (val r = car.readDids(DmeSignals.DME, dids, timeoutMs = 1500)) {
            is DidResult.Data -> {
                val parts = DmeSignals.split(r.payload) { profile.lengths[it] }
                val values = mutableMapOf<Sig, Double>()
                for (s in sigs) {
                    val did = profile.chosen[s] ?: continue
                    val v = parts[did]?.let { DmeSignals.def(did)?.decode?.invoke(it) } ?: continue
                    values[s] = v
                }
                PollResult.Values(values)
            }
            is DidResult.Negative -> PollResult.Failed("NRC %02X for %s".format(r.nrc, dids.joinToString { "%04X".format(it) }))
            DidResult.NoResponse -> PollResult.NoResponse
            is DidResult.Error -> PollResult.Failed(r.why)
        }
    }

    private fun IntArray.hex() = joinToString(" ") { "%02X".format(it) }
}
