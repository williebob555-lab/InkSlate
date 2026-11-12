package com.inkslate.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Copies or moves content chosen with Android's system picker into a real library folder. */
object SystemFileImport {
    data class Result(
        val imported: List<File>,
        val failed: Int = 0,
        val sourceRemovalFailed: Int = 0
    )

    fun files(context: Context, uris: List<Uri>, destination: File, move: Boolean): Result {
        destination.mkdirs()
        val imported = ArrayList<File>()
        var failed = 0
        var removalFailed = 0
        for (uri in uris) {
            val copied = runCatching {
                val name = displayName(context, uri).ifBlank { "Imported document" }
                if (name.endsWith(".zip", ignoreCase = true)) {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "The selected archive could not be opened" }
                        extractZip(input, destination, name.substringBeforeLast('.'))
                    }
                } else {
                    val out = unique(destination, safeName(name))
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "The selected file could not be opened" }
                        copyAtomically(input, out)
                    }
                    listOf(out)
                }
            }.getOrElse {
                EventLog.warn("import", "Could not import ${uri.lastPathSegment}: ${it.message}")
                failed++
                emptyList()
            }
            imported += copied
            if (move && copied.isNotEmpty() && !deleteUri(context, uri)) removalFailed++
        }
        return Result(imported, failed, removalFailed)
    }

    fun folder(context: Context, tree: Uri, destination: File, move: Boolean): Result {
        destination.mkdirs()
        val source = DocumentFile.fromTreeUri(context, tree)
            ?: return Result(emptyList(), failed = 1)
        var out: File? = null
        return runCatching {
            // Finish reading the provider's tree before making anything in the destination. This
            // also prevents a folder imported into one of its descendants from discovering and
            // recursively copying the destination that we just made.
            val plan = snapshotDirectory(source)
            out = uniqueDirectory(destination, safeName(source.name ?: "Imported folder"))
            copyDirectory(context, plan, requireNotNull(out))
            val removalFailed = if (move && !source.delete()) 1 else 0
            Result(listOf(requireNotNull(out)), sourceRemovalFailed = removalFailed)
        }.getOrElse {
            out?.deleteRecursively()
            EventLog.warn("import", "Could not import folder: ${it.message}")
            Result(emptyList(), failed = 1)
        }
    }

    private data class PickedNode(
        val name: String,
        val uri: Uri? = null,
        val children: List<PickedNode>? = null
    )

    private fun snapshotDirectory(source: DocumentFile): List<PickedNode> {
        var count = 0
        fun visit(directory: DocumentFile, depth: Int): List<PickedNode> {
            require(depth <= MAX_FOLDER_DEPTH) { "The selected folder is nested too deeply" }
            return directory.listFiles().map { child ->
                require(++count <= MAX_FOLDER_ENTRIES) { "The selected folder contains too many items" }
                val name = safeName(child.name ?: if (child.isDirectory) "Folder" else "Document")
                when {
                    child.isDirectory -> PickedNode(name, children = visit(child, depth + 1))
                    child.isFile -> PickedNode(name, uri = child.uri)
                    else -> error("The selected folder contains an unsupported item")
                }
            }
        }
        return visit(source, 0)
    }

    private fun copyDirectory(context: Context, nodes: List<PickedNode>, destination: File) {
        destination.mkdirs()
        for (node in nodes) {
            if (node.children != null) {
                copyDirectory(context, node.children, uniqueDirectory(destination, node.name))
            } else {
                val out = unique(destination, node.name)
                context.contentResolver.openInputStream(requireNotNull(node.uri)).use { input ->
                    requireNotNull(input) { "${node.name} could not be opened" }
                    copyAtomically(input, out)
                }
            }
        }
    }

    internal fun extractZip(input: InputStream, destination: File, name: String): List<File> {
        val root = uniqueDirectory(destination, safeName(name.ifBlank { "Imported archive" }))
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            var entries = 0
            var bytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ZIP_ENTRIES) { "The archive contains too many files" }
                    val out = File(root, entry.name).canonicalFile
                    require(out.toPath().startsWith(rootPath)) { "The archive contains an unsafe path" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                bytes += read
                                require(bytes <= MAX_ZIP_BYTES) { "The archive is too large to import safely" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            listOf(root)
        }.getOrElse { failure ->
            root.deleteRecursively()
            throw failure
        }
    }

    private fun copyAtomically(input: InputStream, out: File) {
        out.parentFile?.mkdirs()
        val temp = File(out.parentFile, ".${out.name}.importing-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { output -> input.copyTo(output, 128 * 1024) }
            if (!temp.renameTo(out)) {
                temp.copyTo(out, overwrite = false)
                temp.delete()
            }
        } catch (failure: Throwable) {
            temp.delete()
            out.delete()
            throw failure
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported document"
    }

    private fun deleteUri(context: Context, uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.getOrDefault(false)

    private fun safeName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
        .trim().trim('.').ifBlank { "Imported" }

    private fun unique(parent: File, requested: String): File {
        val base = requested.substringBeforeLast('.', requested)
        val suffix = requested.takeIf { '.' in it }?.let { ".${it.substringAfterLast('.')}" }.orEmpty()
        var candidate = File(parent, requested)
        var n = 2
        while (candidate.exists()) candidate = File(parent, "$base ($n)$suffix").also { n++ }
        return candidate
    }

    private fun uniqueDirectory(parent: File, requested: String): File {
        var candidate = File(parent, requested)
        var n = 2
        while (candidate.exists()) candidate = File(parent, "$requested ($n)").also { n++ }
        candidate.mkdirs()
        return candidate
    }

    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_FOLDER_ENTRIES = 20_000
    private const val MAX_FOLDER_DEPTH = 128
}
