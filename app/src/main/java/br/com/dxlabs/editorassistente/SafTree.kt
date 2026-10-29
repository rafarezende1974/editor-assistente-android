package br.com.dxlabs.editorassistente

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Helpers para pastas escolhidas pelo Storage Access Framework (SAF).
 *
 * No Android não dá para escrever direto em "D:/..." nem em um caminho
 * absoluto do SD ou do pendrive: o usuário escolhe a pasta uma vez pelo
 * seletor do sistema, o app guarda a permissão persistente, e todo acesso
 * passa por DocumentFile. É por isso que não existe "caminho" aqui, só Uri.
 */
object SafTree {

    fun root(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun displayName(file: DocumentFile): String {
        return file.name ?: file.uri.toString()
    }

    /** Encontra ou cria uma subpasta. Retorna null se não conseguir. */
    fun dir(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        if (existing != null) return null
        return parent.createDirectory(name)
    }

    /** Caminha por uma lista de subpastas, criando as que faltam. */
    fun dirPath(parent: DocumentFile, parts: List<String>): DocumentFile? {
        var current = parent
        for (part in parts) {
            current = dir(current, part) ?: return null
        }
        return current
    }

    fun children(file: DocumentFile): List<DocumentFile> {
        return try {
            file.listFiles().toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Varredura recursiva, ignorando pastas ocultas que começam com ponto. */
    fun walkFiles(root: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in children(root)) {
            if (child.name?.startsWith(".") == true) continue
            if (child.isDirectory) {
                walkFiles(child, out)
            } else if (child.isFile) {
                out.add(child)
            }
        }
    }

    fun mimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".csv") -> "text/csv"
            else -> "application/octet-stream"
        }
    }

    /** Copia um arquivo via streams, contando os bytes copiados. */
    fun copyStream(
        context: Context,
        source: DocumentFile,
        dest: DocumentFile,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        var input: InputStream? = null
        var output: OutputStream? = null
        var total = 0L
        try {
            input = context.contentResolver.openInputStream(source.uri) ?: return -1
            output = context.contentResolver.openOutputStream(dest.uri)
                ?: return -1
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                onProgress?.invoke(total)
            }
            output.flush()
        } finally {
            try { output?.close() } catch (ignored: Exception) { }
            try { input?.close() } catch (ignored: Exception) { }
        }
        return total
    }

    fun sha256(context: Context, file: DocumentFile): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun readText(context: Context, file: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Reescreve um arquivo de texto inteiro (relatório/checklist são pequenos). */
    fun writeText(context: Context, parent: DocumentFile, name: String, text: String): Boolean {
        return try {
            val existing = parent.findFile(name)
            if (existing != null) existing.delete()
            val created = parent.createFile(mimeFor(name), name) ?: return false
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
