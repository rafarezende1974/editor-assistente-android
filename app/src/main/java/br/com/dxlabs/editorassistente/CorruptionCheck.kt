package br.com.dxlabs.editorassistente

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * Alerta de arquivo possivelmente corrompido ou interrompido
 * (ideia do Descarrega, adaptada).
 *
 * Isto é um ALERTA, não um laudo: ele só sinaliza os sinais mais comuns
 * de gravação interrompida. O arquivo é copiado mesmo assim, nunca
 * pulado por causa do alerta — e a mensagem é clara: não formate o
 * cartão antes de ver o arquivo.
 */
object CorruptionCheck {

    // Fragmentos de gravação interrompida que algumas câmeras deixam
    // no cartão. O .rsv é o fragmento da Sony; .tmp/.part são
    // parciais genéricos. Todos são copiados junto com os vídeos.
    private val interruptedExtensions = setOf("rsv", "tmp", "part")

    /**
     * Retorna o motivo do alerta, ou null se não há nenhum sinal.
     * Só lê bytes do cartão para o teste do átomo 'moov' (MP4/MOV);
     * os outros sinais usam só nome e tamanho.
     */
    fun check(context: Context, file: DocumentFile, kind: FileKind): String? {
        val name = file.name ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (file.length() == 0L) {
            return "tamanho 0 — pode estar interrompido"
        }
        if (ext in interruptedExtensions) {
            return "extensão .$ext — pode estar interrompido"
        }
        if (kind == FileKind.VIDEO) {
            if (file.length() < 1024L) {
                return "vídeo com menos de 1 KB — pode estar interrompido"
            }
            if ((ext == "mp4" || ext == "mov") && !hasMoovAtom(context, file)) {
                return "sem átomo 'moov' no início nem no fim — pode estar interrompido"
            }
        }
        return null
    }

    /**
     * Procura o átomo 'moov' só no primeiro e no último 1 MB do arquivo.
     * Em MP4/MOV válido o 'moov' fica no começo (faststart) ou no fim;
     * se não está em nenhum dos dois, a gravação provavelmente foi
     * interrompida. Se não der para ler o arquivo, não acusa
     * (melhor não alarmar do que alarmar à toa).
     */
    private fun hasMoovAtom(context: Context, file: DocumentFile): Boolean {
        val size = file.length()
        if (size <= 0L) return false
        val moov = byteArrayOf(0x6D, 0x6F, 0x6F, 0x76) // "moov"
        return try {
            if (containsBytes(context, file, 0L, 1024L * 1024L, moov)) return true
            if (size > 1024L * 1024L) {
                containsBytes(context, file, size - 1024L * 1024L, 1024L * 1024L, moov)
            } else {
                false
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun containsBytes(
        context: Context,
        file: DocumentFile,
        offset: Long,
        maxBytes: Long,
        needle: ByteArray
    ): Boolean {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) return false
                remaining -= skipped
            }
            val buffer = ByteArray(256 * 1024)
            var totalRead = 0L
            var tail = ByteArray(0)
            while (totalRead < maxBytes) {
                val toRead = minOf(buffer.size.toLong(), maxBytes - totalRead).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break
                totalRead += read
                // O "moov" pode cruzar o limite entre dois pedaços lidos:
                // procura no pedaço atual somado à sobra do anterior.
                val combined = tail + buffer.copyOf(read)
                if (indexOf(combined, needle) >= 0) return true
                tail = if (combined.size >= needle.size - 1) {
                    combined.copyOfRange(combined.size - (needle.size - 1), combined.size)
                } else {
                    combined
                }
            }
            return false
        } ?: return false
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
