package br.com.dxlabs.editorassistente

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FileKind { JPG, RAW, VIDEO, OTHER }

data class SourceFile(
    val file: DocumentFile,
    val kind: FileKind,
    val size: Long
)

data class AnalyzeResult(
    val files: List<SourceFile>,
    val jpg: Int,
    val raw: Int,
    val video: Int,
    val other: Int,
    val totalBytes: Long,
    val jpgWithoutRaw: Int,
    val rawWithoutJpg: Int,
    val nextLeva: Int,
    val eventFolderName: String
)

data class DischargeResult(
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<String>,
    val leva: Int,
    val eventFolderName: String
)

object IngestEngine {

    private val jpgExt = setOf("jpg", "jpeg")
    private val rawExt = setOf(
        "cr2", "cr3", "nef", "arw", "raf", "dng", "rw2", "orf",
        "pef", "x3f", "3fr", "mos", "erf"
    )
    private val videoExt = setOf(
        "mp4", "mov", "mxf", "mts", "m2ts", "avi", "wmv",
        "mkv", "mpg", "mpeg", "insv"
    )

    fun classify(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in jpgExt -> FileKind.JPG
            ext in rawExt -> FileKind.RAW
            ext in videoExt -> FileKind.VIDEO
            else -> FileKind.OTHER
        }
    }

    fun eventFolderName(date: String, event: String): String {
        val cleanEvent = event.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "${date.trim()}_$cleanEvent"
    }

    fun sanitizeProfessional(name: String): String {
        return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    fun analyze(
        context: Context,
        sourceRoot: DocumentFile,
        primaryBase: DocumentFile?,
        eventFolder: String,
        professional: String
    ): AnalyzeResult {
        val all = mutableListOf<DocumentFile>()
        SafTree.walkFiles(sourceRoot, all)
        val files = all.map { file ->
            SourceFile(file, classify(file.name ?: ""), file.length())
        }
        val jpg = files.count { it.kind == FileKind.JPG }
        val raw = files.count { it.kind == FileKind.RAW }
        val video = files.count { it.kind == FileKind.VIDEO }
        val other = files.count { it.kind == FileKind.OTHER }
        val total = files.filter { it.kind != FileKind.OTHER }.sumOf { it.size }

        val jpgBases = files.filter { it.kind == FileKind.JPG }
            .map { (it.file.name ?: "").substringBeforeLast('.') }.toSet()
        val rawBases = files.filter { it.kind == FileKind.RAW }
            .map { (it.file.name ?: "").substringBeforeLast('.') }.toSet()
        val jpgWithoutRaw = (jpgBases - rawBases).size
        val rawWithoutJpg = (rawBases - jpgBases).size

        var nextLeva = 1
        if (primaryBase != null) {
            val eventDir = primaryBase.findFile(eventFolder)
            val jpgDir = eventDir
                ?.findFile("01_Fotos_JPG")
                ?.findFile(professional)
            if (jpgDir != null) {
                val numbers = SafTree.children(jpgDir).mapNotNull { child ->
                    val name = child.name ?: return@mapNotNull null
                    if (child.isDirectory && name.startsWith("Cartao_")) {
                        name.removePrefix("Cartao_").toIntOrNull()
                    } else null
                }
                if (numbers.isNotEmpty()) nextLeva = numbers.max() + 1
            }
        }

        return AnalyzeResult(
            files, jpg, raw, video, other, total,
            jpgWithoutRaw, rawWithoutJpg, nextLeva, eventFolder
        )
    }

    /**
     * Copia com staging: origem -> .staging (conferência) -> final -> cópia.
     * No SAF não existe "mover" confiável entre pastas, por isso o staging
     * aqui é uma cópia a mais dentro do primário, e não um rename: o editor
     * nunca vê arquivo parcial, ao custo de uma cópia extra. Documentado.
     */
    fun discharge(
        context: Context,
        analysis: AnalyzeResult,
        primaryBase: DocumentFile,
        copyBase: DocumentFile?,
        professional: String,
        copyJpg: Boolean,
        copyVideo: Boolean,
        copyRaw: Boolean,
        useHash: Boolean,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit
    ): DischargeResult {
        val eventFolder = analysis.eventFolderName
        val eventPrimary = SafTree.dir(primaryBase, eventFolder)
            ?: return DischargeResult(0, 0, 1, listOf("Não consegui criar a pasta do evento no primário."), analysis.nextLeva, eventFolder)
        val eventCopy = if (copyBase != null) {
            SafTree.dir(copyBase, eventFolder)
        } else {
            eventPrimary
        }
        val leva = analysis.nextLeva
        val levaName = "Cartao_$leva"

        // Pastas primárias.
        val dir01 = SafTree.dirPath(eventPrimary, listOf("01_Fotos_JPG", professional, levaName))
        val dir03 = SafTree.dirPath(eventPrimary, listOf("03_Videos", professional))
        val dir05 = SafTree.dirPath(eventPrimary, listOf("05_RAW", professional))
        // Pastas de cópia (planas por profissional, sem Cartao_N).
        val copyBaseForEvent = eventCopy ?: eventPrimary
        val dir02 = if (copyJpg) SafTree.dirPath(copyBaseForEvent, listOf("02_Fotos_JPG_Copia", professional)) else null
        val dir04 = if (copyVideo) SafTree.dirPath(copyBaseForEvent, listOf("04_Videos_Copia", professional)) else null
        val dir06 = if (copyRaw) SafTree.dirPath(copyBaseForEvent, listOf("06_RAW_Copia", professional)) else null
        val staging = SafTree.dir(eventPrimary, ".staging")
            ?: return DischargeResult(0, 0, 1, listOf("Não consegui criar a pasta .staging."), leva, eventFolder)

        var copied = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        val toProcess = analysis.files.filter { it.kind != FileKind.OTHER }
        var done = 0

        for (item in toProcess) {
            done++
            onProgress(done, toProcess.size, item.file.name ?: "")
            val name = item.file.name ?: continue
            val (primaryDir, copyDir) = when (item.kind) {
                FileKind.JPG -> Pair(dir01, dir02)
                FileKind.VIDEO -> Pair(dir03, dir04)
                FileKind.RAW -> Pair(dir05, dir06)
                FileKind.OTHER -> Pair(null, null)
            }
            if (primaryDir == null) {
                failures.add("$name: pasta de destino indisponível.")
                continue
            }
            try {
                val primaryDest = placeFile(context, item, primaryDir, useHash, staging)
                when (primaryDest) {
                    PlaceResult.SKIPPED -> {
                        skipped++
                        // Cópia plana ainda é garantida mesmo quando o JPG
                        // já existia numa leva anterior (regra da v1.5).
                        if (copyDir != null) {
                            val copyOutcome = placeFile(context, item, copyDir, useHash, staging)
                            if (copyOutcome == PlaceResult.COPIED) copied++
                            if (copyOutcome == PlaceResult.FAILED) failures.add("$name: falha na cópia.")
                        }
                    }
                    PlaceResult.COPIED -> {
                        copied++
                        if (copyDir != null) {
                            val copyOutcome = placeFile(context, item, copyDir, useHash, staging)
                            if (copyOutcome == PlaceResult.COPIED) copied++
                            if (copyOutcome == PlaceResult.SKIPPED) skipped++
                            if (copyOutcome == PlaceResult.FAILED) failures.add("$name: falha na cópia.")
                        }
                    }
                    PlaceResult.FAILED -> failures.add("$name: falha na cópia para o primário.")
                }
            } catch (e: Exception) {
                failures.add("$name: ${e.message ?: "erro desconhecido"}")
            }
        }

        // Limpa o staging se ficou vazio.
        try {
            if (SafTree.children(staging).isEmpty()) staging.delete()
        } catch (ignored: Exception) { }

        return DischargeResult(copied, skipped, failures.size, failures, leva, eventFolder)
    }

    private enum class PlaceResult { COPIED, SKIPPED, FAILED }

    private fun placeFile(
        context: Context,
        item: SourceFile,
        destDir: DocumentFile,
        useHash: Boolean,
        staging: DocumentFile
    ): PlaceResult {
        val name = item.file.name ?: return PlaceResult.FAILED
        val existing = destDir.findFile(name)
        if (existing != null && existing.isFile) {
            if (existing.length() == item.size) {
                if (!useHash) return PlaceResult.SKIPPED
                val sourceHash = SafTree.sha256(context, item.file)
                val destHash = SafTree.sha256(context, existing)
                if (sourceHash != null && sourceHash == destHash) return PlaceResult.SKIPPED
                // Mesmo tamanho, conteúdo diferente: recopia por cima.
                existing.delete()
            } else {
                // Nome repetido, conteúdo diferente: sufixo _1, _2...
                return placeWithSuffix(context, item, destDir, useHash, staging)
            }
        }
        return copyViaStaging(context, item, destDir, name, useHash, staging)
    }

    private fun placeWithSuffix(
        context: Context,
        item: SourceFile,
        destDir: DocumentFile,
        useHash: Boolean,
        staging: DocumentFile
    ): PlaceResult {
        val name = item.file.name ?: return PlaceResult.FAILED
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
            val existing = destDir.findFile(candidate)
            if (existing == null) {
                return copyViaStaging(context, item, destDir, candidate, useHash, staging)
            }
            if (existing.isFile && existing.length() == item.size && !useHash) {
                return PlaceResult.SKIPPED
            }
            index++
            if (index > 999) return PlaceResult.FAILED
        }
    }

    private fun copyViaStaging(
        context: Context,
        item: SourceFile,
        destDir: DocumentFile,
        destName: String,
        useHash: Boolean,
        staging: DocumentFile
    ): PlaceResult {
        // 1) Origem -> staging, com conferência de tamanho.
        val staged = staging.createFile(SafTree.mimeFor(item.file.name ?: ""), destName)
            ?: return PlaceResult.FAILED
        val stagedBytes = SafTree.copyStream(context, item.file, staged)
        if (stagedBytes != item.size) {
            staged.delete()
            return PlaceResult.FAILED
        }
        if (useHash) {
            val sourceHash = SafTree.sha256(context, item.file)
            val stagedHash = SafTree.sha256(context, staged)
            if (sourceHash == null || stagedHash == null || sourceHash != stagedHash) {
                staged.delete()
                return PlaceResult.FAILED
            }
        }
        // 2) Staging -> final (mesma unidade, via streams; o SAF não
        // oferece mover confiável entre pastas).
        val finalFile = destDir.createFile(SafTree.mimeFor(destName), destName)
            ?: run { staged.delete(); return PlaceResult.FAILED }
        val finalBytes = SafTree.copyStream(context, staged, finalFile)
        staged.delete()
        if (finalBytes != item.size) {
            finalFile.delete()
            return PlaceResult.FAILED
        }
        if (useHash) {
            val sourceHash = SafTree.sha256(context, item.file)
            val finalHash = SafTree.sha256(context, finalFile)
            if (sourceHash == null || finalHash == null || sourceHash != finalHash) {
                finalFile.delete()
                return PlaceResult.FAILED
            }
        }
        return PlaceResult.COPIED
    }

    fun appendReport(
        context: Context,
        eventPrimary: DocumentFile,
        professional: String,
        result: DischargeResult,
        sourceLabel: String,
        jpg: Int,
        raw: Int,
        video: Int
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val section = StringBuilder()
        section.append("=== Descarga $timestamp ===\n")
        section.append("Profissional: $professional\n")
        section.append("Leva: Cartao_${result.leva}\n")
        section.append("Origem: $sourceLabel\n")
        section.append("JPG: $jpg | RAW: $raw | Video: $video\n")
        section.append("Copiados: ${result.copied} | Pulados: ${result.skipped} | Falhas: ${result.failed}\n")
        if (result.failures.isNotEmpty()) {
            section.append("Falhas:\n")
            result.failures.forEach { section.append("- $it\n") }
        }
        section.append("\n")
        val existing = eventPrimary.findFile("relatorio-descarga.txt")
        val oldText = if (existing != null) SafTree.readText(context, existing) ?: "" else ""
        SafTree.writeText(context, eventPrimary, "relatorio-descarga.txt", oldText + section.toString())
    }

    fun appendChecklist(
        context: Context,
        eventPrimary: DocumentFile,
        professional: String,
        result: DischargeResult,
        sourceLabel: String,
        jpg: Int,
        raw: Int,
        video: Int
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val header = "data_hora,profissional,leva,origem,jpg,raw,video,copiados,pulados,falhas,devolvido\n"
        val line = "$timestamp,$professional,Cartao_${result.leva},$sourceLabel,$jpg,$raw,$video,${result.copied},${result.skipped},${result.failed},NAO\n"
        val existing = eventPrimary.findFile("checklist-descargas.csv")
        val oldText = if (existing != null) SafTree.readText(context, existing) ?: "" else ""
        val base = if (oldText.isEmpty()) header else oldText
        SafTree.writeText(context, eventPrimary, "checklist-descargas.csv", base + line)
    }

    fun markLastReturned(context: Context, eventPrimary: DocumentFile): Boolean {
        val existing = eventPrimary.findFile("checklist-descargas.csv") ?: return false
        val text = SafTree.readText(context, existing) ?: return false
        val lines = text.lines().toMutableList()
        // Remove linhas vazias do fim, sem perder o cabeçalho.
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.size < 2) return false
        val lastIndex = lines.lastIndex
        val parts = lines[lastIndex].split(",").toMutableList()
        if (parts.size < 11) return false
        parts[10] = "SIM"
        lines[lastIndex] = parts.joinToString(",")
        return SafTree.writeText(context, eventPrimary, "checklist-descargas.csv", lines.joinToString("\n") + "\n")
    }

    fun readChecklist(context: Context, eventPrimary: DocumentFile): String? {
        val existing = eventPrimary.findFile("checklist-descargas.csv") ?: return null
        return SafTree.readText(context, existing)
    }
}
