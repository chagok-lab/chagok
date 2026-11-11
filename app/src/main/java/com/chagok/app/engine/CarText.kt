package com.chagok.app.engine

/** 차량에서 받은 문자열 값 정리 */
object CarText {
    private val UNUSABLE = setOf("", "invalid", "unknown", "null", "none", "n/a", "undefined")

    /** 차종 문자열에서 따옴표·공백을 걷어내고, 의미 없는 값("invalid" 등)이면 null */
    fun cleanModel(raw: String?): String? {
        val v = raw?.trim()?.trim('"', '\'')?.trim() ?: return null
        return if (v.lowercase() in UNUSABLE) null else v
    }
}
