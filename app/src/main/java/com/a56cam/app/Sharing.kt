package com.a56cam.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.a56cam.camera.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sends files through the Android share sheet (to a Claude chat, Drive, email, ...). */
object Sharing {

    fun shareFiles(context: Context, files: List<File>, subject: String) {
        val existing = files.filter { it.isFile }
        if (existing.isEmpty()) {
            AppLog.w("Nothing to share for '$subject'")
            return
        }
        val authority = "${context.packageName}.files"
        val uris = ArrayList<Uri>(existing.map { FileProvider.getUriForFile(context, authority, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = if (existing.all { it.extension == "zip" }) "application/zip" else "*/*"
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(subject, uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        AppLog.i("Shared ${existing.map { it.name }}")
    }

    /** Zips [files] into the app cache so they can be shared as one attachment. */
    fun zip(context: Context, name: String, files: List<File>): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, name)
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (f in files.filter { it.isFile }) {
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
