package com.v2ray.ang.dto

enum class Language(val code: String) {
    AUTO("auto"),
    ENGLISH("en"),
    PERSIAN("fa");

    companion object {
        fun fromCode(code: String): Language {
            return entries.find { it.code == code } ?: AUTO
        }
    }
}
