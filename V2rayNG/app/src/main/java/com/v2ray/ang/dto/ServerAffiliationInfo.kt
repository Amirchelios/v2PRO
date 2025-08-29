package com.v2ray.ang.dto

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var isProblematic: Boolean = false,
    var isStable: Boolean = false,
    var lastSuccessfulTestTime: Long = 0L,
    var failureCount: Int = 0
) {
    fun getTestDelayString(): String {
        if (testDelayMillis == 0L) {
            return ""
        }
        return testDelayMillis.toString() + "ms"
    }
}
