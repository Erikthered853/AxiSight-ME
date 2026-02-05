package com.etrsystems.axisight

import android.content.Context
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CsvLogger(private val ctx: Context) {
    private val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exportOverlay(overlay: OverlayView, mmPerPx: Double?) {
        try {
            val now = df.format(Date())
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply {
                if (!mkdirs() && !exists()) {
                    Toast.makeText(ctx, "Failed to create logs directory", Toast.LENGTH_LONG).show()
                    return
                }
            }
            val file = File(dir, "axisight_$now.csv")

            val sb = StringBuilder()
            sb.appendLine("index,x_px,y_px")
            sb.appendLine("# mm_per_px=${mmPerPx ?: Double.NaN}")

            val points = overlay.getPoints()
            if (points.isEmpty()) {
                Toast.makeText(ctx, "No points to export", Toast.LENGTH_SHORT).show()
                return
            }

            points.forEachIndexed { idx, p ->
                sb.appendLine("$idx,${p.first},${p.second}")
            }

            file.writeText(sb.toString())
            Toast.makeText(ctx, "Exported to ${file.name}", Toast.LENGTH_LONG).show()
            android.util.Log.d("CsvLogger", "Export successful: ${file.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("CsvLogger", "Export failed", e)
        }
    }
}
