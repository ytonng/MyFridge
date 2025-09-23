package com.example.myfridge.data

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://aufzcnempfnvtmosbraw.supabase.co", // Replace with your Supabase URL
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImF1ZnpjbmVtcGZudnRtb3NicmF3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTc4NjQ1NjQsImV4cCI6MjA3MzQ0MDU2NH0.9Am2l5XoLVmLgFVYDuxrUCOmf-YTFTHyzYp8VnUhDyQ" // Replace with your Supabase anon key
    ) {
        install(Auth)
        {
            host = "login-callback"
            scheme = "io.supabase.user-management"
        }
        install(Postgrest)
    }
}