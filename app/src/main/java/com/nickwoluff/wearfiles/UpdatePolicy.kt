package com.nickwoluff.wearfiles

import java.math.BigInteger

internal const val RELEASES_URL = "https://github.com/NickWoluff/WearFiles/releases"

/** 按数字段比较正式版本，允许 v 前缀和省略末尾零，拒绝预发布等不明确格式。 */
internal fun isNewerVersion(remote: String, local: String): Boolean {
    fun parts(version: String): List<BigInteger> {
        require(version.matches(Regex("[vV]?[0-9]+(?:\\.[0-9]+)*"))) { "Invalid release version" }
        return version.removePrefix("v").removePrefix("V").split('.').map { it.toBigInteger() }
    }
    val remoteParts = parts(remote)
    val localParts = parts(local)
    for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
        val comparison = (remoteParts.getOrNull(index) ?: BigInteger.ZERO)
            .compareTo(localParts.getOrNull(index) ?: BigInteger.ZERO)
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal fun updateErrorMessage(status: Int): String = when (status) {
    403, 429 -> "检查过于频繁，请稍后重试"
    404 -> "暂无正式发布版本"
    else -> "检查失败，请检查网络后重试"
}

internal fun copyrightText(currentYear: Int): String = buildString {
    append("© 2026")
    if (currentYear > 2026) append('-').append(currentYear)
    append(" Nick Woluff. All Rights Reserved.")
}
