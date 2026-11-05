package br.com.dxlabs.editorassistente

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retomada persistente (ideia do Descarrega, adaptada).
 *
 * Um arquivo manifest-descarga.json fica na pasta do evento, no primário,
 * com uma entrada por arquivo já conferido. O manifest é só um atalho:
 * quem decide se o arquivo conta como "já conferido" é o HD — na análise
 * a entrada só vale se os arquivos de destino existirem com o mesmo
 * tamanho. Se alguém apagar ou mover a pasta no HD, o arquivo volta
 * a ser copiado, mesmo com a entrada no manifest.
 */
data class ManifestEntry(
    val professional: String,
    val kind: String, // "jpg", "raw" ou "video"
    val name: String, // nome no cartão (origem)
    val destName: String, // nome de fato usado no destino (pode ter _1, _2...)
    val subfolder: String?, // ex.: "ZEROU - A PARTIR DE C0001", ou null
    val leva: Int?, // leva do JPG (Cartao_N), ou null para vídeo/RAW
    val size: Long,
    val hash: String? // SHA-256 quando o modo hash está ligado, senão null
)

object ManifestStore {

    const val FILE_NAME = "manifest-descarga.json"

    fun load(context: Context, eventDir: DocumentFile): MutableList<ManifestEntry> {
        return try {
            val file = eventDir.findFile(FILE_NAME) ?: return mutableListOf()
            val text = SafTree.readText(context, file) ?: return mutableListOf()
            val root = JSONObject(text)
            val arr = root.optJSONArray("entries") ?: return mutableListOf()
            val out = mutableListOf<ManifestEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val size = obj.optLong("size", -1L)
                if (size < 0) continue
                out.add(
                    ManifestEntry(
                        professional = obj.optString("professional", ""),
                        kind = obj.optString("kind", ""),
                        name = obj.optString("name", ""),
                        destName = obj.optString("destName", obj.optString("name", "")),
                        subfolder = if (obj.isNull("subfolder")) null else obj.optString("subfolder"),
                        leva = if (obj.isNull("leva")) null else obj.optInt("leva"),
                        size = size,
                        hash = if (obj.isNull("hash")) null else obj.optString("hash")
                    )
                )
            }
            out
        } catch (e: Exception) {
            // Manifest corrompido ou ilegível: começa do zero. A descarga
            // continua normal e os arquivos são conferidos pelo HD.
            mutableListOf()
        }
    }

    fun save(
        context: Context,
        eventDir: DocumentFile,
        eventFolderName: String,
        entries: List<ManifestEntry>
    ): Boolean {
        return try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("event", eventFolderName)
            val arr = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                obj.put("professional", entry.professional)
                obj.put("kind", entry.kind)
                obj.put("name", entry.name)
                obj.put("destName", entry.destName)
                if (entry.subfolder != null) obj.put("subfolder", entry.subfolder)
                else obj.put("subfolder", JSONObject.NULL)
                if (entry.leva != null) obj.put("leva", entry.leva)
                else obj.put("leva", JSONObject.NULL)
                obj.put("size", entry.size)
                if (entry.hash != null) obj.put("hash", entry.hash)
                else obj.put("hash", JSONObject.NULL)
                arr.put(obj)
            }
            root.put("entries", arr)
            SafTree.writeText(context, eventDir, FILE_NAME, root.toString(2))
        } catch (e: Exception) {
            false
        }
    }
}
