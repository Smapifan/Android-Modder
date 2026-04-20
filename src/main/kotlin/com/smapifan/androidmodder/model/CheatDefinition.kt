package com.smapifan.androidmodder.model

data class CheatDefinition(
    val appName: String,
    val saveFileRelativePath: String,
    val saveAddress: String,
    val memoryAddress: String? = null
)
