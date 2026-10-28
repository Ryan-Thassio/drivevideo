package com.example.drivevideo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFile(val id: String, val name: String, val mime: String, val size: Long, val modified: String, val parent: String?) {
    val isFolder get() = mime == "application/vnd.google-apps.folder"
}

object DriveApi {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val BASE = "https://www.googleapis.com/drive/v3/files"
    const val VIDEO_OR_FOLDER = "(mimeType='application/vnd.google-apps.folder' or mimeType contains 'video/')"

    fun mediaUrl(id: String) = "$BASE/$id?alt=media&supportsAllDrives=true"
    fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Lista arquivos conforme a query do Drive (até 300 itens). Lança IOException("HTTP 401") se o token expirou. */
    suspend fun list(token: String, q: String, order: String = "folder,name"): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        repeat(3) {
            val url = "$BASE?q=${enc(q)}&orderBy=${enc(order)}&pageSize=100&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&fields=${enc("nextPageToken,files(id,name,mimeType,size,modifiedTime,parents)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = j.getJSONArray("files")
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                out += DriveFile(it.getString("id"), it.getString("name"), it.getString("mimeType"),
                    it.optString("size", "0").toLong(), it.optString("modifiedTime").take(10),
                    it.optJSONArray("parents")?.optString(0))
            }
            page = j.optString("nextPageToken").ifEmpty { null }
            if (page == null) return@withContext out
        }
        out
    }
}
