package com.example.myfridge.util

import com.example.myfridge.data.SupabaseClient

object ImageUtils {
    private const val DEFAULT_BUCKET = "fridge_images"
    private val ALLOWED_BUCKETS = setOf(DEFAULT_BUCKET, "avatars", "fridge_tips")

    fun resolveUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        // Remove stray formatting characters like backticks, brackets, quotes
        val cleaned = path.trim()
            .trimStart('`', '[', '"', ' ')
            .trimEnd('`', ']', '"', ' ')

        // If already a URL, return as-is (after cleaning)
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return cleaned
        }

        // Determine bucket and object path
        val bucket: String
        val objectPath: String
        val slashIdx = cleaned.indexOf('/')

        if (slashIdx > 0) {
            val firstSegment = cleaned.substring(0, slashIdx)
            val rest = cleaned.substring(slashIdx + 1)
            if (ALLOWED_BUCKETS.contains(firstSegment)) {
                // Path is bucket-prefixed, e.g., "fridge_images/13/Mango/xxx.jpg"
                bucket = firstSegment
                objectPath = rest
            } else {
                // Path is an object key (e.g., "13/Mango/xxx.jpg"). Use default bucket.
                bucket = DEFAULT_BUCKET
                objectPath = cleaned
            }
        } else {
            // No slash, treat as object key in default bucket
            bucket = DEFAULT_BUCKET
            objectPath = cleaned
        }

        return "${SupabaseClient.SUPABASE_URL}/storage/v1/object/public/$bucket/$objectPath"
    }
}