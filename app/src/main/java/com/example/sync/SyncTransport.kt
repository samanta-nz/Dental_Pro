package com.example.sync

import com.example.util.StorageCategory
import com.example.util.Supabase
import java.io.File

/** Boundary for durable outbox delivery; lets failure and retry scenarios run without real accounts. */
interface SyncTransport {
    suspend fun ensureAccountId(): String
    suspend fun upsert(table: String, syncId: String, data: Map<String, Any>)
    suspend fun delete(table: String, syncId: String)
    suspend fun upload(file: File, bucket: String, category: StorageCategory): String?
}
object SupabaseSyncTransport : SyncTransport {
    override suspend fun ensureAccountId(): String = Supabase.ensureAccountId()
    override suspend fun upsert(table: String, syncId: String, data: Map<String, Any>) = Supabase.upsertRow(table, syncId, data)
    override suspend fun delete(table: String, syncId: String) = Supabase.deleteRow(table, syncId)
    override suspend fun upload(file: File, bucket: String, category: StorageCategory): String? = Supabase.uploadToBucket(file, bucket, category)
}
