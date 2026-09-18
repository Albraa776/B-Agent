package com.bagent.app.tools.filesystem

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.bagent.app.core.database.WorkspaceEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Filesystem layer over B Agent's sandboxed workspace roots plus SAF roots the
 * user has explicitly granted. All path tools validate containment inside an
 * allowed root before operating - never confuse paths and content URIs.
 */
class FsService(private val context: Context) {

    fun workspacesRoot(): File = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun workspaceDir(ws: WorkspaceEntity): File {
        val spec = ws.rootUriOrPath
        return if (spec.startsWith("/")) {
            File(spec).apply { mkdirs() }
        } else {
            File(workspacesRoot(), ws.id.toString()).apply { mkdirs() }
        }
    }

    fun safeWorkspaceRoot(ws: WorkspaceEntity?): File = ws?.let { workspaceDir(it) } ?: defaultRoot()

    fun defaultRoot(): File = File(context.filesDir, "workspaces/0").apply { mkdirs() }

    fun exportsRoot(): File = File(context.filesDir, "exports").apply { mkdirs() }
    fun logsRoot(): File = File(context.filesDir, "logs").apply { mkdirs() }
    fun extensionsRoot(): File = File(context.filesDir, "extensions").apply { mkdirs() }
    fun downloadsRoot(): File = File(context.filesDir, "downloads").apply { mkdirs() }

    /** All editable roots for containment checks. */
    fun allowedRoots(): List<File> =
        listOf(context.filesDir, context.cacheDir)

    /**
     * Resolves a user/model supplied relative path inside [root], refusing
     * absolute paths and any traversal that escapes the root.
     */
    fun resolveInside(root: File, relPath: String): File? {
        val normalized = relPath.trim().trim('"')
        if (normalized.isEmpty()) return root
        if (normalized.startsWith("/") || normalized.contains("..")) return null
        val candidate = File(root, normalized)
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: root
        val candidateCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: candidate
        val base = rootCanonical.absolutePath
        val path = candidateCanonical.absolutePath
        return if (path == base || path.startsWith(base + File.separator)) candidate else null
    }

    fun metadata(file: File, includeHash: Boolean = false): JsonObject {
        val b = buildJsonObject {
            put("name", file.name)
            put("path", file.absolutePath)
            put("directory", file.isDirectory)
            put("exists", file.exists())
            put("size", file.length())
            put("lastModified", file.lastModified())
            put("readable", file.canRead())
            put("writable", file.canWrite())
        }
        if (includeHash && file.isFile) {
            putTo(b, "md5", hash(file, "MD5"))
            putTo(b, "sha256", hash(file, "SHA-256"))
        }
        return b
    }

    private fun putTo(b: JsonObjectBuilder, key: String, value: String) {
        b.put(key, value)
    }

    fun hash(file: File, algo: String): String {
        return try {
            val digest = MessageDigest.getInstance(algo)
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "<unavailable: ${e.message}>"
        }
    }

    // ----- SAF helpers -----

    fun treeDocument(uri: Uri): DocumentFile? =
        if (DocumentsContract.isTreeUri(uri)) DocumentFile.fromTreeUri(context, uri) else null

    fun resolveSaf(root: DocumentFile, relPath: String): DocumentFile? {
        val clean = relPath.trim().trim('/')
        if (clean.isEmpty()) return root
        var current: DocumentFile = root
        for (segment in clean.split('/')) {
            if (segment == ".." || segment == "." || segment.isBlank()) continue
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}