package com.example.myfridge.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    @SerialName("user_id")
    val userId: String,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null
)