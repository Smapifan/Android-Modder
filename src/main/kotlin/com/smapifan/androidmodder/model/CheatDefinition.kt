package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

@Serializable
data class CheatDefinition(
    val appName: String,
    val saveFileRelativePath: String,
    val saveAddress: String,
    val memoryAddress: String? = null
)
