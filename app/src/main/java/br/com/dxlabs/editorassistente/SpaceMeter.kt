package br.com.dxlabs.editorassistente

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract

data class VolumeSpace(
    val label: String,
    val freeBytes: Long?, // null quando não foi possível medir
    val totalBytes: Long? // null quando não foi possível medir
)

/**
 * Barra de espaço (ideia do Descarrega, adaptada ao Android/SAF).
 *
 * Via SAF não há caminho de arquivo direto: o volume é localizado pelo
 * UUID que vem no documentId da pasta escolhida (ex.: "1234-ABCD" para
 * o SD, "primary" para o armazenamento interno) e medido com StatFs
 * sobre o diretório do volume. Se o volume não expuser caminho
 * (alguns pendrives/OTG), freeBytes/totalBytes voltam null e a tela
 * mostra "não foi possível medir" em vez de inventar número.
 * A falta de medição nunca bloqueia a descarga.
 */
object SpaceMeter {

    fun measure(context: Context, treeUri: Uri): VolumeSpace? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            val uuid = docId.substringBefore(':')
            val storageManager = context.getSystemService(StorageManager::class.java)
                ?: return null
            val volume = storageManager.storageVolumes.firstOrNull { volume ->
                if (uuid == "primary") volume.isPrimary else volume.uuid == uuid
            } ?: return null
            val label = volume.getDescription(context) ?: uuid
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // getDirectory() só existe a partir do Android 11 (API 30).
                return VolumeSpace(label, null, null)
            }
            val dir = volume.directory ?: return VolumeSpace(label, null, null)
            val stat = StatFs(dir.path)
            VolumeSpace(label, stat.availableBytes, stat.totalBytes)
        } catch (e: Exception) {
            null
        }
    }
}
