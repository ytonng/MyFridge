package com.example.myfridge.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FridgeRow(
    val id: Long,
    val serial_number: String,
    val name: String? = null
)