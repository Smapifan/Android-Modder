package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition

class CheatsConfigParser {
    private val objectRegex = Regex("\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)

    fun parse(json: String): List<CheatDefinition> {
        return objectRegex
            .findAll(json)
            .mapNotNull { match -> parseObject(match.groupValues[1]) }
            .toList()
    }

    private fun parseObject(body: String): CheatDefinition? {
        val appName = extractString(body, "appName") ?: return null
        val saveFileRelativePath = extractString(body, "saveFileRelativePath") ?: return null
        val saveAddress = extractString(body, "saveAddress") ?: return null
        val memoryAddress = extractString(body, "memoryAddress")

        return CheatDefinition(
            appName = appName,
            saveFileRelativePath = saveFileRelativePath,
            saveAddress = saveAddress,
            memoryAddress = memoryAddress
        )
    }

    private fun extractString(body: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"(.*?)\"")
        return regex.find(body)?.groupValues?.get(1)
    }
}
