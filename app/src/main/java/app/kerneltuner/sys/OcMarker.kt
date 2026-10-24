package app.kerneltuner.sys

import android.content.Context
import app.kerneltuner.core.OcMarkerData
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** Write-ahead "an overclock is being applied" marker. Written and fsynced BEFORE the first above-stock write. */
object OcMarker {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "oc-applying.json")

    @Synchronized
    fun write(ctx: Context, d: OcMarkerData) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "oc-applying.tmp")
        FileOutputStream(tmp).use { it.write(json.encodeToString(OcMarkerData.serializer(), d).toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) {
            FileOutputStream(f).use { it.write(json.encodeToString(OcMarkerData.serializer(), d).toByteArray()); it.fd.sync() }
        }
    }

    @Synchronized
    fun read(ctx: Context): OcMarkerData? = try {
        file(ctx).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(OcMarkerData.serializer(), it) }
    } catch (e: Exception) {
        // A marker we cannot parse is still evidence that something was in progress.
        if (file(ctx).isFile) OcMarkerData("unknown", null, listOf("unknown"), 0) else null
    }

    @Synchronized
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }
}
