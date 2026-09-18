package com.bagent.app.tools.filesystem

import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Core filesystem tools. Real operations on real files inside the workspace
 * roots granted to B Agent. Paths are validated for containment at every call.
 */
class FilesystemTools(private val fs: FsService) {

    fun all(): List<ToolBase> = mutableListOf(
        ListDirectory(), ReadFile(), WriteFile(), AppendFile(), CreateDirectory(),
        DeleteFile(), MoveFile(), CopyFile(), RenameFile(), FileExists(), FileMetadata(),
        SearchFiles(), SearchText(), FindSymbol(), CalculateHash(), CompareFiles(),
        ArchiveFile(), ExtractArchive(), DetectEncoding(), DetectProject(),
        ListUri(), ReadUri(), WriteUri()
    )

    private fun root(env: ToolEnv): File = fs.safeWorkspaceRoot(env.workspace)

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun ensureFile(env: ToolEnv, args: JsonObject, key: String): File? {
        val path = args.str(key) ?: return null
        return fs.resolveInside(root(env), path)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824f)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    private fun entryJson(file: File, root: File): JsonObject = buildJsonObject {
        put("name", file.name)
        put("path", file.absolutePath.removePrefix(root.absolutePath).removePrefix("/"))
        put("directory", file.isDirectory)
        put("size", file.length())
        put("sizeHuman", fmtSize(file.length()))
        put("lastModified", file.lastModified())
        put("extension", if (file.isFile) file.extension.lowercase() else "")
    }

    private fun ignored(env: ToolEnv): List<String> {
        val custom = env.workspace?.ignoredPaths?.split(',')?.map { it.trim().trim('/') }?.filter { it.isNotEmpty() } ?: emptyList()
        return (listOf(".git", "node_modules", ".gradle", "build", ".idea", ".venv", "venv", "__pycache__", ".bagent", "target") + custom).distinct()
    }

    private fun skipWalk(file: File, ignored: List<String>, hiddenToo: Boolean): Boolean {
        if (file.name.startsWith(".") && !hiddenToo) return true
        return file.name in ignored
    }

    private fun walk(root: File, ignored: List<String>, hiddenToo: Boolean, max: Int): List<File> {
        val results = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty() && results.size < max) {
            val dir = queue.removeFirst()
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (results.size >= max) return@forEach
                    results.add(f)
                    if (f.isDirectory && !skipWalk(f, ignored, hiddenToo)) queue.add(f)
                }
            }
        }
        return results
    }

    // ---------------------------------------------------------------- tools

    inner class ListDirectory : ToolBase() {
        override val id = "list_directory"
        override val name = "list_directory"
        override val category = "filesystem"
        override val description = "List entries inside a directory of the current workspace."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string","description":"directory path relative to workspace root"},"includeHidden":{"type":"boolean"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val dir = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!dir.isDirectory) return ToolResult.Failure("not a directory: ${dir.path}")
            val list = runCatching { dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) }.getOrNull() ?: emptyList()
            return ToolResult.Success(buildJsonObject {
                put("path", dir.absolutePath)
                put("count", list.size)
                put("entries", buildJsonArray { list.forEach { add(entryJson(it, dir)) } })
            }.toString())
        }
    }

    inner class ReadFile : ToolBase() {
        override val id = "read_file"
        override val name = "read_file"
        override val category = "filesystem"
        override val description = "Read a text file inside the workspace. Binary files are reported as such (never printed)."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"maxBytes":{"type":"integer","description":"cap output size (default 200000)"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!file.isFile) return ToolResult.Failure("not a file: ${file.path}")
            val maxBytes = (args["maxBytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200_000).coerceIn(1_000, 2_000_000)
            val bytes = runCatching { file.readBytes() }.getOrElse { return ToolResult.Failure("read failed: ${it.message}") }
            if (bytes.take(4096).contains(0.toByte())) return ToolResult.Failure("binary file detected; not printed")
            val charset = detectCharset(bytes)
            val text = String(bytes, charset)
            val truncated = text.length > maxBytes
            val body = if (truncated) text.substring(0, maxBytes) else text
            return ToolResult.Success(buildJsonObject {
                put("path", file.absolutePath)
                put("size", bytes.size)
                put("encoding", charset.name())
                put("truncated", truncated)
                put("content", body)
            }.toString())
        }
    }

    inner class WriteFile : ToolBase() {
        override val id = "write_file"
        override val name = "write_file"
        override val category = "filesystem"
        override val description = "Create or overwrite a text file in the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"encoding":{"type":"string"}},"required":["path","content"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            val content = args.str("content") ?: return ToolResult.Failure("missing content")
            file.parentFile?.mkdirs()
            val encoding = args.str("encoding") ?: "UTF-8"
            return runCatching {
                file.writeText(content, Charset.forName(encoding))
                ToolResult.Success(fs.metadata(file).toString())
            }.getOrElse { ToolResult.Failure("write failed: ${it.message}") }
        }
    }

    inner class AppendFile : ToolBase() {
        override val id = "append_file"
        override val name = "append_file"
        override val category = "filesystem"
        override val description = "Append text to a file in the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            val content = args.str("content") ?: return ToolResult.Failure("missing content")
            file.parentFile?.mkdirs()
            return runCatching {
                file.appendText(content)
                ToolResult.Success(fs.metadata(file).toString())
            }.getOrElse { ToolResult.Failure("append failed: ${it.message}") }
        }
    }

    inner class CreateDirectory : ToolBase() {
        override val id = "create_directory"
        override val name = "create_directory"
        override val category = "filesystem"
        override val description = "Create a directory (and parents) in the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val dir = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            return runCatching {
                if (dir.mkdirs()) ToolResult.Success("created ${dir.path}")
                else ToolResult.Failure("could not create ${dir.path}")
            }.getOrElse { ToolResult.Failure("mkdir failed: ${it.message}") }
        }
    }

    inner class DeleteFile : ToolBase() {
        override val id = "delete_file"
        override val name = "delete_file"
        override val category = "filesystem"
        override val description = "Permanently delete a file or empty directory. Confirmation required."
        override val requiredPermission = PermAction.DELETE_FILES
        override val risk = RiskLevel.HIGH
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!file.exists()) return ToolResult.Failure("not found: ${file.path}")
            return runCatching {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                ToolResult.Success("deleted ${file.path}")
            }.getOrElse { ToolResult.Failure("delete failed: ${it.message}") }
        }
    }

    inner class MoveFile : ToolBase() {
        override val id = "move_file"
        override val name = "move_file"
        override val category = "filesystem"
        override val description = "Move (or rename) a file or directory within the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val from = ensureFile(env, args, "from") ?: return ToolResult.Failure("invalid from path")
            val to = ensureFile(env, args, "to") ?: return ToolResult.Failure("invalid to path")
            return runCatching {
                to.parentFile?.mkdirs()
                if (from.renameTo(to)) ToolResult.Success("moved ${from.path} -> ${to.path}")
                else ToolResult.Failure("rename failed")
            }.getOrElse { ToolResult.Failure("move failed: ${it.message}") }
        }
    }

    inner class CopyFile : ToolBase() {
        override val id = "copy_file"
        override val name = "copy_file"
        override val category = "filesystem"
        override val description = "Copy a file or directory within the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val from = ensureFile(env, args, "from") ?: return ToolResult.Failure("invalid from path")
            val to = ensureFile(env, args, "to") ?: return ToolResult.Failure("invalid to path")
            return runCatching {
                to.parentFile?.mkdirs()
                if (from.isDirectory) from.copyRecursively(to, overwrite = true)
                else from.copyTo(to, overwrite = true)
                ToolResult.Success("copied ${from.path} -> ${to.path}")
            }.getOrElse { ToolResult.Failure("copy failed: ${it.message}") }
        }
    }

    inner class RenameFile : ToolBase() {
        override val id = "rename_file"
        override val name = "rename_file"
        override val category = "filesystem"
        override val description = "Rename a file or directory in place."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"newName":{"type":"string"}},"required":["path","newName"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            val newName = args.str("newName") ?: return ToolResult.Failure("missing newName")
            val target = File(file.parentFile ?: root(env), newName)
            return runCatching {
                if (file.renameTo(target)) ToolResult.Success("renamed to ${target.path}")
                else ToolResult.Failure("rename failed")
            }.getOrElse { ToolResult.Failure("rename failed: ${it.message}") }
        }
    }

    inner class FileExists : ToolBase() {
        override val id = "file_exists"
        override val name = "file_exists"
        override val category = "filesystem"
        override val description = "Check whether a path exists in the workspace."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path")
            return ToolResult.Success(buildJsonObject {
                put("exists", file?.exists() == true)
                put("path", file?.absolutePath ?: "")
            }.toString())
        }
    }

    inner class FileMetadata : ToolBase() {
        override val id = "file_metadata"
        override val name = "file_metadata"
        override val category = "filesystem"
        override val description = "Read metadata (size, dates, permissions) and optional hash of a file."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"hash":{"type":"boolean"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!file.exists()) return ToolResult.Failure("not found")
            return ToolResult.Success(fs.metadata(file, includeHash = args["hash"]?.jsonPrimitive?.contentOrNull == "true").toString())
        }
    }

    inner class SearchFiles : ToolBase() {
        override val id = "search_files"
        override val name = "search_files"
        override val category = "filesystem"
        override val description = "Recursively search for files by name glob pattern (e.g. *.kt) inside the workspace."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"pattern":{"type":"string"},"maxResults":{"type":"integer"},"includeHidden":{"type":"boolean"}},"required":["pattern"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val pattern = args.str("pattern") ?: return ToolResult.Failure("missing pattern")
            val max = (args["maxResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500).coerceIn(1, 2000)
            val hidden = args["includeHidden"]?.jsonPrimitive?.contentOrNull == "true"
            val regex = globToRegex(pattern)
            val found = walk(root(env), ignored(env), hidden, max).filter { it.isFile && regex.matches(it.name) }.take(max)
            return ToolResult.Success(buildJsonObject {
                put("count", found.size)
                put("matches", buildJsonArray { found.forEach { add(entryJson(it, root(env))) } })
            }.toString())
        }
    }

    inner class SearchText : ToolBase() {
        override val id = "search_text"
        override val name = "search_text"
        override val category = "filesystem"
        override val description = "Search file contents for a query string or regex inside the workspace."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"query":{"type":"string"},"regex":{"type":"boolean"},"extensions":{"type":"array","items":{"type":"string"}},"maxResults":{"type":"integer"}},"required":["query"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val query = args.str("query") ?: return ToolResult.Failure("missing query")
            val isRegex = args["regex"]?.jsonPrimitive?.contentOrNull == "true"
            val max = (args["maxResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300).coerceIn(1, 1000)
            val exts = runCatching { args["extensions"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull }?.filterNotNull() ?: emptyList() }.getOrDefault(emptyList())
            val pattern = try {
                if (isRegex) Regex(query) else Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return ToolResult.Failure("invalid regex: ${e.message}")
            }
            val ignore = ignored(env)
            val hits = ArrayList<JsonObject>()
            val queue = ArrayDeque<File>()
            queue.add(root(env))
            outer@ while (queue.isNotEmpty() && hits.size < max) {
                val dir = queue.removeFirst()
                val files = runCatching { dir.listFiles() }.getOrNull() ?: emptyList()
                for (f in files) {
                    if (hits.size >= max) break@outer
                    if (f.isDirectory) {
                        if (!skipWalk(f, ignore, args["includeHidden"]?.jsonPrimitive?.contentOrNull == "true")) queue.add(f)
                        continue
                    }
                    if (exts.isNotEmpty() && f.extension.lowercase() !in exts) continue
                    runCatching {
                        f.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (index > 100_000) return@forEachIndexed
                                if (pattern.containsMatchIn(line)) {
                                    hits.add(buildJsonObject {
                                        put("path", f.absolutePath)
                                        put("line", index + 1)
                                        put("text", line.trim().take(200))
                                    })
                                }
                            }
                        }
                    }
                }
            }
            return ToolResult.Success(buildJsonObject {
                put("count", hits.size)
                put("hits", buildJsonArray { hits.forEach { add(it) } })
            }.toString())
        }
    }

    inner class FindSymbol : ToolBase() {
        override val id = "find_symbol"
        override val name = "find_symbol"
        override val category = "filesystem"
        override val description = "Find occurrences of a symbol or identifier in source files (smart word search)."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"symbol":{"type":"string"},"maxResults":{"type":"integer"}},"required":["symbol"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val symbol = args.str("symbol") ?: return ToolResult.Failure("missing symbol")
            val max = (args["maxResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200).coerceIn(1, 1000)
            val pattern = Regex("\\b\\Q${symbol.replace("\\E", "\\E\\Q")}\\E\\b")
            val sourceExts = setOf(
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
                "go", "rs", "rb", "php", "swift", "xml", "gradle", "json", "yml", "yaml", "toml", "md"
            )
            val hits = ArrayList<JsonObject>()
            val queue = ArrayDeque<File>()
            queue.add(root(env))
            while (queue.isNotEmpty() && hits.size < max) {
                val dir = queue.removeFirst()
                val files = runCatching { dir.listFiles() }.getOrNull() ?: emptyList()
                for (f in files) {
                    if (hits.size >= max) break
                    if (f.isDirectory) continue
                    if (f.extension.lowercase() !in sourceExts) continue
                    runCatching {
                        f.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (pattern.containsMatchIn(line)) {
                                    hits.add(buildJsonObject {
                                        put("path", f.absolutePath)
                                        put("line", index + 1)
                                        put("text", line.trim().take(200))
                                    })
                                }
                            }
                        }
                    }
                }
            }
            return ToolResult.Success(buildJsonObject {
                put("count", hits.size)
                put("hits", buildJsonArray { hits.forEach { add(it) } })
            }.toString())
        }
    }

    inner class CalculateHash : ToolBase() {
        override val id = "calculate_hash"
        override val name = "calculate_hash"
        override val category = "filesystem"
        override val description = "Compute MD5 or SHA-256 of a file."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"algorithm":{"type":"string","enum":["md5","sha256"]}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!file.isFile) return ToolResult.Failure("not a file")
            val algo = when (args.str("algorithm")?.uppercase()) {
                "SHA256", "SHA-256" -> "SHA-256"
                else -> "MD5"
            }
            return ToolResult.Success(buildJsonObject {
                put("path", file.absolutePath)
                put("algorithm", algo)
                put("hash", fs.hash(file, algo))
            }.toString())
        }
    }

    inner class CompareFiles : ToolBase() {
        override val id = "compare_files"
        override val name = "compare_files"
        override val category = "filesystem"
        override val description = "Compare two files for equality and produce a unified diff."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a","b"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val a = ensureFile(env, args, "a") ?: return ToolResult.Failure("invalid a")
            val b = ensureFile(env, args, "b") ?: return ToolResult.Failure("invalid b")
            if (!a.isFile || !b.isFile) return ToolResult.Failure("both must be files")
            val linesA = runCatching { a.readLines() }.getOrElse { return ToolResult.Failure("read a failed: ${it.message}") }
            val linesB = runCatching { b.readLines() }.getOrElse { return ToolResult.Failure("read b failed: ${it.message}") }
            val equal = a.length() == b.length() && linesA == linesB
            return ToolResult.Success(buildJsonObject {
                put("equal", equal)
                put("aName", a.name)
                put("bName", b.name)
                put("diff", if (equal) "" else Diff.unified(a.name, b.name, linesA, linesB).take(6000))
            }.toString())
        }
    }

    inner class ArchiveFile : ToolBase() {
        override val id = "archive_file"
        override val name = "archive_file"
        override val category = "filesystem"
        override val description = "Create a zip archive of a file or directory in the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"},"target":{"type":"string"}},"required":["path","target"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val src = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            val target = ensureFile(env, args, "target") ?: return ToolResult.Failure("invalid target")
            return runCatching {
                ZipOutputStream(target.outputStream().buffered()).use { zos ->
                    fun add(file: File, base: String) {
                        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
                        if (file.isDirectory) {
                            zos.putNextEntry(ZipEntry("$entryName/"))
                            zos.closeEntry()
                            file.listFiles()?.forEach { add(it, entryName) }
                        } else {
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    add(src, "")
                }
                ToolResult.Success(fs.metadata(target).toString())
            }.getOrElse { ToolResult.Failure("archive failed: ${it.message}") }
        }
    }

    inner class ExtractArchive : ToolBase() {
        override val id = "extract_archive"
        override val name = "extract_archive"
        override val category = "filesystem"
        override val description = "Extract a zip archive into a directory in the workspace."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"archive":{"type":"string"},"destination":{"type":"string"}},"required":["archive","destination"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val archive = ensureFile(env, args, "archive") ?: return ToolResult.Failure("invalid archive")
            val dest = ensureFile(env, args, "destination") ?: return ToolResult.Failure("invalid destination")
            if (!archive.isFile) return ToolResult.Failure("not found")
            return runCatching {
                dest.mkdirs()
                var count = 0
                ZipInputStream(archive.inputStream().buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val target = File(dest, entry.name)
                        val base = dest.canonicalPath
                        val child = target.canonicalPath
                        if (!child.startsWith(base + File.separator)) return ToolResult.Failure("zip slip blocked")
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it) }
                            count++
                        }
                        zis.closeEntry()
                    }
                }
                ToolResult.Success("extracted $count files to ${dest.path}")
            }.getOrElse { ToolResult.Failure("extract failed: ${it.message}") }
        }
    }

    inner class DetectEncoding : ToolBase() {
        override val id = "detect_encoding"
        override val name = "detect_encoding"
        override val category = "filesystem"
        override val description = "Detect likely text encoding (UTF-8, UTF-16, ISO-8859-1, ASCII)."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val file = ensureFile(env, args, "path") ?: return ToolResult.Failure("invalid path")
            if (!file.isFile) return ToolResult.Failure("not a file")
            val bytes = runCatching { file.readBytes() }.getOrElse { return ToolResult.Failure("read failed") }
            val charset = detectCharset(bytes)
            return ToolResult.Success(buildJsonObject {
                put("path", file.absolutePath)
                put("encoding", charset.name())
                put("bom", bomName(bytes))
            }.toString())
        }
    }

    inner class DetectProject : ToolBase() {
        override val id = "detect_project"
        override val name = "detect_project"
        override val category = "filesystem"
        override val description = "Inspect a directory for project type, build system, languages and package managers."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val dir = ensureFile(env, args, "path") ?: root(env)
            val files = dir.listFiles()?.map { it.name } ?: emptyList()
            val isGit = File(dir, ".git").exists()
            val type = detectType(files)
            val langs = detectLanguages(files, dir)
            val pkg = detectPackageManagers(files)
            return ToolResult.Success(buildJsonObject {
                put("path", dir.absolutePath)
                put("git", isGit)
                put("projectType", type)
                put("languages", buildJsonArray { langs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("packageManagers", buildJsonArray { pkg.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }.toString())
        }
    }

    inner class ListUri : ToolBase() {
        override val id = "list_uri"
        override val name = "list_uri"
        override val category = "filesystem"
        override val description = "List contents of a SAF tree that the user already granted."
        override val requiredPermission = PermAction.READ_FILES
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"uri":{"type":"string"}},"required":["uri"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val uri = args.str("uri") ?: return ToolResult.Failure("missing uri")
            val doc = fs.treeDocument(parseUri(uri)) ?: return ToolResult.Failure("not a tree uri")
            val children = doc.listFiles().sortedWith(compareBy<androidx.documentfile.provider.DocumentFile> { !it.isDirectory }.thenBy { it.name })
            return ToolResult.Success(buildJsonObject {
                put("uri", uri)
                put("count", children.size)
                put("entries", buildJsonArray {
                    children.forEach { c ->
                        add(buildJsonObject {
                            put("name", c.name ?: "")
                            put("directory", c.isDirectory)
                            put("size", c.length())
                            put("uri", c.uri.toString())
                        })
                    }
                })
            }.toString())
        }
    }

    inner class ReadUri : ToolBase() {
        override val id = "read_uri"
        override val name = "read_uri"
        override val category = "filesystem"
        override val description = "Read text content of an SAF document the user granted."
        override val requiredPermission = PermAction.READ_FILES
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"uri":{"type":"string"},"maxBytes":{"type":"integer"}},"required":["uri"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val uri = args.str("uri") ?: return ToolResult.Failure("missing uri")
            val maxBytes = (args["maxBytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200_000).coerceIn(1_000, 2_000_000)
            return runCatching {
                val bytes = env.context.contentResolver.openInputStream(parseUri(uri))?.use { it.readBytes() }
                    ?: return ToolResult.Failure("cannot open uri")
                val text = String(bytes, charsetOf(bytes))
                ToolResult.Success(buildJsonObject {
                    put("uri", uri)
                    put("size", bytes.size)
                    put("truncated", text.length > maxBytes)
                    put("content", text.take(maxBytes))
                }.toString())
            }.getOrElse { ToolResult.Failure("read uri failed: ${it.message}") }
        }
    }

    inner class WriteUri : ToolBase() {
        override val id = "write_uri"
        override val name = "write_uri"
        override val category = "filesystem"
        override val description = "Write text content to an SAF document the user granted."
        override val requiredPermission = PermAction.WRITE_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"uri":{"type":"string"},"content":{"type":"string"}},"required":["uri","content"]}""")

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val uri = args.str("uri") ?: return ToolResult.Failure("missing uri")
            val content = args.str("content") ?: return ToolResult.Failure("missing content")
            return runCatching {
                env.context.contentResolver.openOutputStream(parseUri(uri), "wt")?.use { it.write(content.toByteArray()) }
                    ?: return ToolResult.Failure("cannot open uri for write")
                ToolResult.Success("wrote ${content.length} bytes to $uri")
            }.getOrElse { ToolResult.Failure("write uri failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun parseUri(raw: String): android.net.Uri = android.net.Uri.parse(raw)

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Regex("^${sb}\$")
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        val nul = bytes.count { it == 0.toByte() }
        if (charsetOf(bytes).name().startsWith("UTF-8")) return Charsets.UTF_8
        return if (nul > 0) Charsets.UTF_16LE else Charsets.UTF_8
    }

    private fun charsetOf(sample: ByteArray): Charset = Charsets.UTF_8

    private fun bomName(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8 BOM"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16 LE BOM"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16 BE BOM"
        return "none"
    }

    private fun detectType(files: List<String>): String = when {
        files.contains("settings.gradle.kts") || files.contains("build.gradle.kts") || files.contains("settings.gradle") -> "android|jvm gradle"
        files.contains("package.json") -> "node"
        files.contains("pyproject.toml") || files.contains("requirements.txt") || files.contains("setup.py") -> "python"
        files.contains("Cargo.toml") -> "rust"
        files.contains("go.mod") -> "go"
        files.contains("pom.xml") -> "java maven"
        files.contains("Makefile") -> "make"
        files.contains(".bagent") || files.contains("bagent.json") -> "bagent"
        files.contains("README.md") || files.contains("README") -> "documented"
        else -> "general"
    }

    private fun detectLanguages(files: List<String>, dir: File): List<String> {
        val set = linkedSetOf<String>()
        files.forEach { f ->
            val ext = f.substringAfterLast('.', "").lowercase()
            val map = mapOf(
                "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "py" to "python",
                "js" to "javascript", "ts" to "typescript", "tsx" to "typescript", "jsx" to "javascript",
                "c" to "c", "h" to "c", "cpp" to "cpp", "hpp" to "cpp", "go" to "go", "rs" to "rust",
                "rb" to "ruby", "php" to "php", "swift" to "swift", "xml" to "xml", "yml" to "yaml",
                "yaml" to "yaml", "sh" to "shell", "gradle" to "gradle", "json" to "json", "md" to "markdown"
            )
            map[ext]?.let { set.add(it) }
        }
        return set.toList().sorted()
    }

    private fun detectPackageManagers(files: List<String>): List<String> = files.mapNotNull { f ->
        when (f) {
            "package.json" -> "npm"
            "yarn.lock" -> "yarn"
            "pnpm-lock.yaml" -> "pnpm"
            "Cargo.toml" -> "cargo"
            "go.mod" -> "go modules"
            "pom.xml" -> "maven"
            "build.gradle.kts", "settings.gradle.kts", "build.gradle" -> "gradle"
            "pyproject.toml" -> "poetry"
            "requirements.txt" -> "pip"
            "Gemfile" -> "bundler"
            else -> null
        }
    }

    companion object {
        fun diff(a: String, b: String): String = Diff.unified("a", "b", a.lines(), b.lines())
    }
}

/** Minimal LCS-based unified diff. */
object Diff {
    fun unified(aName: String, bName: String, a: List<String>, b: List<String>): String {
        val sb = StringBuilder()
        sb.append("--- $aName\n+++ $bName\n")
        val lcs = lcs(a, b)
        var i = 0
        var j = 0
        for (common in lcs) {
            while (i < a.size && a[i] != common) {
                sb.append("-").append(a[i]).append('\n'); i++
            }
            while (j < b.size && b[j] != common) {
                sb.append("+").append(b[j]).append('\n'); j++
            }
            if (i < a.size && j < b.size) {
                sb.append(" ").append(common).append('\n'); i++; j++
            }
        }
        while (i < a.size) { sb.append("-").append(a[i]).append('\n'); i++ }
        while (j < b.size) { sb.append("+").append(b[j]).append('\n'); j++ }
        return sb.toString().take(100_000)
    }

    private fun lcs(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val result = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                result.add(a[i]); i++; j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        return result
    }
}