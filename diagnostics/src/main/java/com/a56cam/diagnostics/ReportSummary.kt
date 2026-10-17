package com.a56cam.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** The readable report.txt: the facts that decide Phases 2 to 4, pulled out of report.json. */
internal object ReportSummary {

    fun build(r: JSONObject): String = buildString {
        val device = r.optJSONObject("device") ?: JSONObject()
        appendLine("A56Cam device report  ${r.optString("startedAt")}")
        appendLine("${device.optString("manufacturer")} ${device.optString("model")}  Android ${device.optString("release")} (API ${device.optInt("sdkInt")})  SoC ${device.optString("socModel")}")
        appendLine("App ${r.optJSONObject("app")?.optString("version")}  native: ${r.optString("native")}")
        appendLine("Run time: ${r.optLong("durationMs") / 1000} s, errors: ${r.optJSONArray("errors")?.length() ?: 0}")
        appendLine()

        appendLine("== Cameras (getCameraIdList: ${r.optJSONArray("cameraIdList")}) ==")
        val cameras = r.optJSONObject("cameras") ?: JSONObject()
        for (id in cameras.keys()) {
            val c = cameras.getJSONObject(id)
            appendLine("ID $id ${c.optString("lensFacing")}  level ${c.optString("hardwareLevel")}")
            appendLine("  capabilities: ${list(c.optJSONArray("capabilities"))}")
            val modes = c.optJSONObject("modes") ?: JSONObject()
            appendLine("  AE: ${list(modes.optJSONArray("ae"))}  NR: ${list(modes.optJSONArray("noiseReduction"))}  edge: ${list(modes.optJSONArray("edge"))}")
            appendLine("  tonemap: ${list(modes.optJSONArray("tonemap"))}  exposure ns: ${modes.opt("exposureTimeRangeNs")}  ISO: ${modes.opt("sensitivityRange")}")
            val formats = c.optJSONObject("streamConfigurations")?.optJSONObject("formats")
            for (f in listOf("YUV_420_888", "JPEG", "JPEG_R", "HEIC", "RAW_SENSOR")) {
                val fo = formats?.optJSONObject(f) ?: continue
                if (!fo.optBoolean("supported")) {
                    appendLine("  $f: not supported")
                } else {
                    val biggest = fo.optJSONArray("sizes")?.optJSONObject(0)
                    appendLine("  $f: max ${biggest?.optString("size")} at up to ${biggest?.opt("maxFps")} fps")
                }
            }
            appendLine("  extensions: ${list(c.optJSONObject("extensions")?.optJSONArray("supported"))}")
            val vendor = c.optJSONArray("vendorKeys")?.length() ?: 0
            appendLine("  vendor keys: $vendor  physical cameras: ${c.optJSONObject("physicalCameras")?.names() ?: "none"}")
        }
        appendLine()

        appendLine("== Hidden camera IDs ==")
        val hidden = r.optJSONArray("hiddenIdProbe") ?: JSONArray()
        val found = (0 until hidden.length()).map { hidden.getJSONObject(it) }
            .filter { it.optBoolean("characteristics") || it.optBoolean("opened") }
        if (found.isEmpty()) appendLine("None found (${hidden.length()} IDs tried).")
        for (h in found) {
            appendLine("ID ${h.optString("id")}: ${h.optString("lensFacing")} opened=${h.optBoolean("opened")} streamed=${h.optBoolean("streamed")} focal=${h.opt("focalLengths")}")
        }
        appendLine()

        val live = r.optJSONObject("liveTests") ?: JSONObject()
        appendLine("== Live tests on camera ${live.optString("cameraId")} ==")
        val burst = live.optJSONObject("burst")
        appendLine("Burst at ${burst?.optString("size")} (advertised max ${burst?.opt("advertisedMaxFps")} fps):")
        val variants = burst?.optJSONArray("variants") ?: JSONArray()
        for (i in 0 until variants.length()) {
            val v = variants.getJSONObject(i)
            appendLine("  ${v.optString("variant")}: ${v.opt("fps")} fps, ${v.optInt("imagesReceived")}/${v.optInt("requested")} images, ${v.optInt("failures")} failures, max gap ${v.opt("maxIntervalMs")} ms")
        }

        val me = live.optJSONObject("manualExposure")
        appendLine("Manual exposure: AE OFF offered=${me?.optBoolean("aeOffAdvertised")} " +
            "exposure honoured=${me?.opt("exposureHonoured")} ISO honoured=${me?.opt("isoHonoured")} " +
            "brightness follows exposure=${me?.opt("brightnessFollowsExposure")} ISO=${me?.opt("brightnessFollowsIso")}")
        val sweep = me?.optJSONArray("exposureSweep") ?: JSONArray()
        for (i in 0 until sweep.length()) {
            val p = sweep.getJSONObject(i)
            appendLine("  asked ${p.optLong("requestedExposureNs") / 1000} us ISO ${p.optInt("requestedIso")} -> got ${p.optLong("resultExposureNs") / 1000} us ISO ${p.optInt("resultIso")} luma ${p.opt("meanLuma")}")
        }

        val ev = live.optJSONObject("evBracketing")?.optJSONArray("series") ?: JSONArray()
        appendLine("EV bracketing:")
        for (i in 0 until ev.length()) {
            val s = ev.getJSONObject(i)
            appendLine("  ${s.opt("ev")} EV: luma ${s.opt("finalLuma")} (x${s.opt("lumaRatioVs0Ev")} vs 0 EV), settled after ${s.opt("framesToSettle")} frames")
        }

        val pm = live.optJSONObject("processingModes")?.optJSONArray("captures") ?: JSONArray()
        appendLine("Noise reduction / edge modes (crop gradient energy: higher = sharper or noisier):")
        for (i in 0 until pm.length()) {
            val p = pm.getJSONObject(i)
            appendLine("  NR ${p.optString("requestedNr")} edge ${p.optString("requestedEdge")}: honoured=${p.opt("honoured")} gradient ${p.opt("cropGradientEnergy")} stddev ${p.opt("cropLumaStdDev")}")
        }

        val ext = live.optJSONObject("extensions")
        appendLine("Camera Extensions: ${list(ext?.optJSONArray("supported"))}")
        val caps = ext?.optJSONArray("captures") ?: JSONArray()
        for (i in 0 until caps.length()) {
            val c = caps.getJSONObject(i)
            appendLine("  ${c.optString("extension")}: success=${c.opt("success")} latency ${c.opt("latencyMs")} ms ${c.optString("error")}")
        }
        val hdr = live.optJSONObject("ultraHdr")
        appendLine("Ultra HDR (JPEG_R): listed=${hdr?.opt("jpegRListed")} success=${hdr?.opt("success")} gain map=${hdr?.opt("hasGainMapXmp")}")
        appendLine()

        val errs = r.optJSONArray("errors") ?: JSONArray()
        if (errs.length() > 0) {
            appendLine("== Errors ==")
            for (i in 0 until errs.length()) {
                val e = errs.getJSONObject(i)
                appendLine("${e.optString("step")}: ${e.optString("error")}")
            }
        }
    }

    private fun list(a: JSONArray?): String =
        if (a == null || a.length() == 0) "none" else (0 until a.length()).joinToString(", ") { a.get(it).toString() }
}
