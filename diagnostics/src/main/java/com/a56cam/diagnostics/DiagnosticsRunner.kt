package com.a56cam.diagnostics

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraThread
import com.a56cam.processing.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsResult(
    val runDir: File,
    val reportJson: File,
    val reportTxt: File,
    /** Public folder the files were copied to, or null if the copy failed. */
    val exportedTo: String?,
    val summary: String,
)

/**
 * Phase 0 device report: static characteristics of every camera, a hidden-ID probe and live
 * capture tests on the back camera. Output goes to the app's folder and to Documents/A56Cam/diagnostics.
 */
class DiagnosticsRunner(private val context: Context) {

    suspend fun run(appVersion: String, progress: (String) -> Unit): DiagnosticsResult = withContext(Dispatchers.Default) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val runDir = File(context.getExternalFilesDir(null), "diagnostics/run-$stamp").apply { mkdirs() }
        val manager = context.getSystemService(CameraManager::class.java)
        val errors = ErrorLog()
        val report = JSONObject()
        val started = System.currentTimeMillis()
        AppLog.i("Diagnostics run started: ${runDir.absolutePath}")

        report.put("schema", 1)
        report.put("app", JSONObject().put("version", appVersion))
        report.put("startedAt", stamp)
        report.put("device", deviceInfo())
        report.put("native", errors.step("native self-test") { NativeBridge.selfTest() } ?: "FAILED")

        CameraThread("A56Cam-camera").use { cameraThread ->
            CameraThread("A56Cam-images").use { imageThread ->
                val ids = errors.step("getCameraIdList") { manager.cameraIdList.toList() } ?: emptyList()
                report.put("cameraIdList", org.json.JSONArray(ids))

                progress("Reading camera characteristics")
                report.put("cameras", StaticReport(manager, errors).build(ids))

                progress("Probing hidden camera IDs")
                report.put("hiddenIdProbe", HiddenIdProbe(manager, cameraThread, imageThread, errors).run(ids, progress))

                val backId = ids.firstOrNull { id ->
                    errors.step("facing $id") {
                        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    } == true
                }
                val live = JSONObject()
                if (backId == null) {
                    AppLog.e("No back camera found in $ids; live tests skipped")
                    live.put("skipped", "no back camera")
                } else {
                    live.put("cameraId", backId)
                    val chars = manager.getCameraCharacteristics(backId)
                    val tests = LiveTests(manager, backId, chars, runDir, cameraThread, imageThread, errors)
                    val ext = ExtensionTests(manager, backId, chars, runDir, cameraThread, imageThread, errors)
                    if (tests.hasYuv()) {
                        progress("Live test 1/6: burst speed")
                        live.put("burst", tests.burstSpeed())
                        progress("Live test 2/6: manual exposure (long exposures take a while)")
                        live.put("manualExposure", tests.manualExposure())
                        progress("Live test 3/6: EV bracketing")
                        live.put("evBracketing", tests.evBracketing())
                        progress("Live test 4/6: noise-reduction and edge modes")
                        live.put("processingModes", tests.processingModes())
                    } else {
                        AppLog.e("Camera $backId has no YUV_420_888 output; YUV tests skipped")
                        live.put("yuvTestsSkipped", "no YUV_420_888 output")
                    }
                    progress("Live test 5/6: Camera Extensions")
                    live.put("extensions", ext.extensions())
                    progress("Live test 6/6: Ultra HDR")
                    live.put("ultraHdr", ext.ultraHdr())
                }
                report.put("liveTests", live)
            }
        }

        report.put("durationMs", System.currentTimeMillis() - started)
        report.put("errors", errors.entries)

        progress("Writing report")
        val json = File(runDir, "report.json").apply { writeText(report.toString(2)) }
        val summary = ReportSummary.build(report)
        val txt = File(runDir, "report.txt").apply { writeText(summary) }
        val exported = exportToDocuments(runDir)
        AppLog.i("Diagnostics run finished in ${report.getLong("durationMs")} ms, ${errors.entries.length()} errors")
        DiagnosticsResult(runDir, json, txt, exported, summary)
    }

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("board", Build.BOARD)
        .put("socManufacturer", Build.SOC_MANUFACTURER)
        .put("socModel", Build.SOC_MODEL)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("securityPatch", Build.VERSION.SECURITY_PATCH)
        .put("incremental", Build.VERSION.INCREMENTAL)
        .put("fingerprint", Build.FINGERPRINT)

    /** Copies the run into Documents/A56Cam/diagnostics/<run>/ through MediaStore (no storage permission needed). */
    private fun exportToDocuments(runDir: File): String? {
        val relative = "${Environment.DIRECTORY_DOCUMENTS}/A56Cam/diagnostics/${runDir.name}/"
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var failed = 0
        for (file in runDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IllegalStateException("insert returned null")
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                failed++
                AppLog.e("Export of ${file.name} to $relative failed", e)
            }
        }
        return if (failed == 0) relative else null
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "json" -> "application/json"
        "txt" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
