package com.chagok.app.data

import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 추가 전용 원장 저장소 (차량 내 파일). 외부 전송은 사용자가 내보내기를 요청할 때만 수행합니다.
 */
class TripStore(private val file: File) {

    fun load(): List<Trip> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun save(ledger: List<Trip>) {
        val arr = JSONArray()
        ledger.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        tmp.renameTo(file)
    }

    companion object {
        /** 원장 끝에 새 줄을 붙이고 봉인합니다. */
        fun append(ledger: List<Trip>, draft: Trip): Trip {
            val prev = ledger.lastOrNull()?.hash ?: HashChain.GENESIS
            return HashChain.seal(draft.copy(seq = ledger.size.toLong(), prevHash = prev, hash = ""))
        }

        /** 용도 확정·수정: 같은 tripId 의 새 revision. 비업무로 확정하면 목적지를 지웁니다. */
        fun revise(ledger: List<Trip>, tripId: Long, purpose: Purpose, note: String?): Trip? {
            val last = ledger.filter { it.tripId == tripId }.maxByOrNull { it.revision } ?: return null
            val draft = last.copy(
                revision = last.revision + 1,
                purpose = purpose,
                destination = if (purpose == Purpose.PRIVATE) null else last.destination,
                note = note,
            )
            return append(ledger, draft)
        }

        private fun toJson(t: Trip) = JSONObject().apply {
            put("seq", t.seq); put("tripId", t.tripId); put("revision", t.revision)
            put("start", t.startEpochMs); put("end", t.endEpochMs)
            put("startOdo", t.startOdoKm); put("endOdo", t.endOdoKm)
            put("destination", t.destination ?: JSONObject.NULL)
            put("purpose", t.purpose.name); put("suggested", t.suggested.name)
            put("user", t.user); put("source", t.source)
            put("note", t.note ?: JSONObject.NULL)
            put("prevHash", t.prevHash); put("hash", t.hash)
        }

        private fun fromJson(o: JSONObject) = Trip(
            seq = o.getLong("seq"),
            tripId = o.getLong("tripId"),
            revision = o.getInt("revision"),
            startEpochMs = o.getLong("start"),
            endEpochMs = o.getLong("end"),
            startOdoKm = o.getDouble("startOdo"),
            endOdoKm = o.getDouble("endOdo"),
            destination = if (o.isNull("destination")) null else o.getString("destination"),
            purpose = Purpose.valueOf(o.getString("purpose")),
            suggested = Purpose.valueOf(o.getString("suggested")),
            user = o.getString("user"),
            source = o.getString("source"),
            note = if (o.isNull("note")) null else o.getString("note"),
            prevHash = o.getString("prevHash"),
            hash = o.getString("hash"),
        )
    }
}
