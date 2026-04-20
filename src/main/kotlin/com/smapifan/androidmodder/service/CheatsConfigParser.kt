package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import kotlinx.serialization.json.Json

class CheatsConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(json: String): List<CheatDefinition> {
        return this.json.decodeFromString<List<CheatDefinition>>(json)
    }
}
