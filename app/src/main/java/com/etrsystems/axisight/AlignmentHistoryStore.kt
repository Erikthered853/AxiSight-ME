package com.etrsystems.axisight

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One logged alignment reading: when, which tool/machine, on what source, and how good the fit was. */
data class AlignmentRecord(
    val timestampMs: Long,
    val toolLabel: String,
    val cameraSource: String,
    val dxIn: Double,
    val dyIn: Double,
    val fitRmsPx: Double?
)

/**
 * Append-only CSV history of logged alignments, so an operator can later answer
 * "did tool 7 drift this month" by reviewing readings over time.
 */
class AlignmentHistoryStore(private val ctx: Context) {
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val file: File
        get() = File(File(ctx.getExternalFilesDir(null), "logs"), "alignment_history.csv")

    fun append(record: AlignmentRecord) {
        val f = file
        val isNew = !f.exists()
        f.parentFile?.mkdirs()
        f.appendText(buildString {
            if (isNew) appendLine("timestamp,tool,camera_source,dx_in,dy_in,fit_rms_px")
            append(timestampFormat.format(Date(record.timestampMs)))
            append(',')
            append(record.toolLabel.replace(",", " "))
            append(',')
            append(record.cameraSource)
            append(',')
            append(record.dxIn)
            append(',')
            append(record.dyIn)
            append(',')
            append(record.fitRmsPx?.toString() ?: "")
            appendLine()
        })
    }

    fun readAll(): List<AlignmentRecord> {
        val f = file
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 6) return@mapNotNull null
            val ts = try {
                timestampFormat.parse(parts[0])?.time
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            AlignmentRecord(
                timestampMs = ts,
                toolLabel = parts[1],
                cameraSource = parts[2],
                dxIn = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                dyIn = parts[4].toDoubleOrNull() ?: return@mapNotNull null,
                fitRmsPx = parts.getOrNull(5)?.toDoubleOrNull()
            )
        }
    }

    fun exportPath(): String = file.absolutePath
}
