package com.chagok.app.data

import com.chagok.app.model.Trip
import java.security.MessageDigest
import java.util.Locale

/**
 * 수정 이력 추적·무결성 검증: 각 원장 줄의 해시에 직전 줄의 해시를 포함시켜 한 줄만 고쳐도 이후 검증이 모두 실패하게 합니다.
 */
object HashChain {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun canonical(t: Trip): String = listOf(
        t.seq,
        t.tripId,
        t.revision,
        t.startEpochMs,
        t.endEpochMs,
        String.format(Locale.ROOT, "%.1f", t.startOdoKm),
        String.format(Locale.ROOT, "%.1f", t.endOdoKm),
        t.destination ?: "",
        t.purpose.name,
        t.suggested.name,
        t.user,
        t.source,
        t.note ?: "",
        t.prevHash,
    ).joinToString("|")

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> String.format(Locale.ROOT, "%02x", b.toInt() and 0xff) }

    /** prevHash 가 채워진 줄에 hash 를 계산해 봉인합니다. */
    fun seal(unsealed: Trip): Trip = unsealed.copy(hash = sha256(canonical(unsealed.copy(hash = ""))))

    /** 처음부터 검증해 통과한 줄 수를 돌려줍니다. 모두 통과하면 ledger.size 와 같습니다. */
    fun verify(ledger: List<Trip>): Int {
        var prev = GENESIS
        ledger.forEachIndexed { index, t ->
            if (t.prevHash != prev) return index
            if (t.hash != sha256(canonical(t.copy(hash = "")))) return index
            prev = t.hash
        }
        return ledger.size
    }
}
