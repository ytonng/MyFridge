package com.example.myfridge.data

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.functions.Functions

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://aufzcnempfnvtmosbraw.supabase.co", // Replace with your Supabase URL
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImF1ZnpjbmVtcGZudnRtb3NicmF3Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc1Nzg2NDU2NCwiZXhwIjoyMDczNDQwNTY0fQ.vtH0jI64R6vh720c7RAnw5RYVejw9cM25ddOLhA-NZo" // Replace with your Supabase anon key
    ) {
        install(Auth)
        {
            host = "login-callback"
            scheme = "io.supabase.user-management"
        }
        install(Functions)
        install(Postgrest)
        install(Realtime)
    }
}