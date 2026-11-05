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

data class SuspectFile(
    val name: String,
    val reason: String
)

data class Rollover(
    val prefix: String,
    val kind: FileKind,
    val firstPostName: String,
    val subfolder: String
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
    val eventFolderName: String,
    val suspects: List<SuspectFile>,
    val rollovers: List<Rollover>,
    val rolloverSubfolder: Map<String, String>, // uri da origem -> subpasta ZEROU
    val verifiedUris: Set<String>,
    val alreadyVerifiedCount: Int,
    val bytesRemainingPrimary: Long,
    val bytesRemainingCopy: Long,
    val copiesEnabled: Boolean
)

data class DischargeResult(
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<String>,
    val leva: Int,
    val eventFolderName: String,
    val manifestEntries: Int
)

object IngestEngine {

    private val jpgExt = setOf("jpg", "jpeg")
    private val rawExt = setOf(
        "cr2", "cr3", "nef", "arw", "raf", "dng", "rw2", "orf",
        "pef", "x3f", "3fr", "mos", "erf"
    )
    private val videoExt = setOf(
        "mp4", "mov", "mxf", "mts", "m2ts", "avi", "wmv",
        "mkv", "mpg", "mpeg", "insv",
        // Fragmentos de gravação interrompida: são copiados junto com
        // os vídeos e sinalizados pelo alerta de arquivo corrompido.
        "rsv", "tmp", "part"
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

    fun kindName(kind: FileKind): String {
        return when (kind) {
            FileKind.JPG -> "jpg"
            FileKind.RAW -> "raw"
            FileKind.VIDEO -> "video"
            FileKind.OTHER -> "other"
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
        copyBase: DocumentFile?,
        eventFolder: String,
        professional: String,
        copyJpg: Boolean,
        copyVideo: Boolean,
        copyRaw: Boolean,
        useHash: Boolean,
        copiesEnabled: Boolean = true
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
        val eventDir = primaryBase?.findFile(eventFolder)
        if (eventDir != null) {
            val jpgDir = eventDir
                .findFile("01_Fotos_JPG")
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

        // 2) Alerta de arquivo possivelmente corrompido/interrompido.
        // O arquivo é copiado mesmo assim; o alerta só avisa.
        val suspects = files.mapNotNull { item ->
            val reason = CorruptionCheck.check(context, item.file, item.kind)
            if (reason != null) SuspectFile(item.file.name ?: "", reason) else null
        }

        // 3) Contador zerado (C9999 -> C0001).
        val (rollovers, rolloverSubfolder) = detectRollovers(files)

        // 1) Retomada persistente: o manifest só vale se o HD confirmar.
        val manifest = if (eventDir != null) {
            ManifestStore.load(context, eventDir)
        } else {
            emptyList()
        }
        val eventCopyDir = copyBase?.findFile(eventFolder) ?: eventDir
        val verifiedUris = mutableSetOf<String>()
        var bytesRemainingPrimary = 0L
        var bytesRemainingCopy = 0L
        for (item in files) {
            if (item.kind == FileKind.OTHER) continue
            // Chave mestra (v0.2.1): com as cópias desligadas, nenhuma
            // cópia secundária é feita nem contada, seja qual for o tipo.
            val copyEnabled = copiesEnabled && when (item.kind) {
                FileKind.JPG -> copyJpg
                FileKind.VIDEO -> copyVideo
                FileKind.RAW -> copyRaw
                FileKind.OTHER -> false
            }
            val verified = isAlreadyVerified(
                context, item, professional, manifest, eventDir, eventCopyDir,
                copyEnabled, rolloverSubfolder[item.file.uri.toString()]
            )
            if (verified) {
                verifiedUris.add(item.file.uri.toString())
            } else {
                bytesRemainingPrimary += item.size
                if (copyEnabled) bytesRemainingCopy += item.size
            }
        }

        return AnalyzeResult(
            files, jpg, raw, video, other, total,
            jpgWithoutRaw, rawWithoutJpg, nextLeva, eventFolder,
            suspects, rollovers, rolloverSubfolder, verifiedUris,
            verifiedUris.size, bytesRemainingPrimary, bytesRemainingCopy,
            copiesEnabled
        )
    }

    /**
     * Um arquivo conta como "já conferido" só se a entrada existir no
     * manifest E os arquivos de destino existirem com o mesmo tamanho.
     * O hash do manifest, quando existe, fica registrado para auditoria;
     * a conferência da retomada usa existência + tamanho no HD, para
     * não precisar reler (e re-hashar) o cartão inteiro a cada análise.
     */
    private fun isAlreadyVerified(
        context: Context,
        item: SourceFile,
        professional: String,
        manifest: List<ManifestEntry>,
        eventDir: DocumentFile?,
        eventCopyDir: DocumentFile?,
        copyEnabled: Boolean,
        currentSubfolder: String?
    ): Boolean {
        if (eventDir == null) return false
        val name = item.file.name ?: return false
        val entry = manifest.firstOrNull {
            it.professional == professional &&
                it.kind == kindName(item.kind) &&
                it.name == name &&
                it.size == item.size
        } ?: return false
        val destName = entry.destName
        val subfolders = setOf(entry.subfolder, currentSubfolder)
        val primaryOk = when (item.kind) {
            FileKind.JPG -> {
                val jpgRoot = eventDir.findFile("01_Fotos_JPG")?.findFile(professional)
                    ?: return false
                SafTree.children(jpgRoot).any { levaDir ->
                    levaDir.isDirectory &&
                        destFileWithSize(levaDir, destName, item.size, subfolders)
                }
            }
            FileKind.VIDEO -> {
                val dir = eventDir.findFile("03_Videos")?.findFile(professional)
                destFileWithSize(dir, destName, item.size, subfolders)
            }
            FileKind.RAW -> {
                val dir = eventDir.findFile("05_RAW")?.findFile(professional)
                destFileWithSize(dir, destName, item.size, subfolders)
            }
            FileKind.OTHER -> false
        }
        if (!primaryOk) return false
        if (!copyEnabled) return true
        val copyRoot = eventCopyDir ?: return false
        val copyDir = when (item.kind) {
            FileKind.JPG -> copyRoot.findFile("02_Fotos_JPG_Copia")?.findFile(professional)
            FileKind.VIDEO -> copyRoot.findFile("04_Videos_Copia")?.findFile(professional)
            FileKind.RAW -> copyRoot.findFile("06_RAW_Copia")?.findFile(professional)
            FileKind.OTHER -> null
        }
        return destFileWithSize(copyDir, destName, item.size, subfolders)
    }

    /** Existe um arquivo com este nome e tamanho, na pasta ou numa das subpastas. */
    private fun destFileWithSize(
        dir: DocumentFile?,
        name: String,
        size: Long,
        subfolders: Set<String?>
    ): Boolean {
        if (dir == null) return false
        val direct = dir.findFile(name)
        if (direct?.isFile == true && direct.length() == size) return true
        for (sub in subfolders) {
            if (sub == null) continue
            val subDir = dir.findFile(sub) ?: continue
            if (!subDir.isDirectory) continue
            val inside = subDir.findFile(name)
            if (inside?.isFile == true && inside.length() == size) return true
        }
        return false
    }

    /**
     * Contador zerado: agrupa por prefixo (nome sem os dígitos finais)
     * e tipo, ordena por data de modificação e procura a virada em que
     * o número cai de >= 9900 para <= 100. Os arquivos depois da
     * virada vão para a subpasta "ZEROU - A PARTIR DE <prefixo><número>".
     * Limitação honesta: a detecção depende da data de modificação que
     * o cartão/SAF entrega; se ela não existir (0 para todos), a ordem
     * é a da varredura e a virada pode não ser detectada.
     */
    private fun detectRollovers(
        files: List<SourceFile>
    ): Pair<List<Rollover>, Map<String, String>> {
        val rollovers = mutableListOf<Rollover>()
        val subfolderByUri = mutableMapOf<String, String>()
        val groups = files
            .filter { it.kind != FileKind.OTHER }
            .groupBy { item ->
                val base = (item.file.name ?: "").substringBeforeLast('.')
                val match = Regex("^(.*?)(\\d+)$").find(base)
                val prefix = match?.groupValues?.getOrNull(1) ?: base
                Pair(prefix, item.kind)
            }
        for ((key, group) in groups) {
            if (group.size < 2) continue
            val prefix = key.first
            val kind = key.second
            val ordered = group.sortedBy { it.file.lastModified() }
            val numbered = ordered.mapNotNull { item ->
                val base = (item.file.name ?: "").substringBeforeLast('.')
                val number = Regex("(\\d+)$").find(base)?.value?.toIntOrNull()
                if (number == null) null else Pair(item, number)
            }
            for (i in 0 until numbered.size - 1) {
                val currentNumber = numbered[i].second
                val (next, nextNumber) = numbered[i + 1]
                if (currentNumber >= 9900 && nextNumber <= 100) {
                    val firstPostName = (next.file.name ?: "").substringBeforeLast('.')
                    val subfolder = "ZEROU - A PARTIR DE $firstPostName"
                    rollovers.add(Rollover(prefix, kind, firstPostName, subfolder))
                    for (j in i + 1 until numbered.size) {
                        subfolderByUri[numbered[j].first.file.uri.toString()] = subfolder
                    }
                    break
                }
            }
        }
        return Pair(rollovers, subfolderByUri)
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
        copiesEnabled: Boolean = true,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit
    ): DischargeResult {
        val eventFolder = analysis.eventFolderName
        val eventPrimary = SafTree.dir(primaryBase, eventFolder)
            ?: return DischargeResult(0, 0, 1, listOf("Não consegui criar a pasta do evento no primário."), analysis.nextLeva, eventFolder, 0)
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
        // Chave mestra desligada (v0.2.1): nenhuma cópia secundária —
        // nem JPG, nem vídeo, nem RAW — só o primário (01, 03, 05).
        val copyBaseForEvent = eventCopy ?: eventPrimary
        val dir02 = if (copiesEnabled && copyJpg) SafTree.dirPath(copyBaseForEvent, listOf("02_Fotos_JPG_Copia", professional)) else null
        val dir04 = if (copiesEnabled && copyVideo) SafTree.dirPath(copyBaseForEvent, listOf("04_Videos_Copia", professional)) else null
        val dir06 = if (copiesEnabled && copyRaw) SafTree.dirPath(copyBaseForEvent, listOf("06_RAW_Copia", professional)) else null
        val staging = SafTree.dir(eventPrimary, ".staging")
            ?: return DischargeResult(0, 0, 1, listOf("Não consegui criar a pasta .staging."), leva, eventFolder, 0)

        // Retomada persistente: carrega o manifest e grava cada arquivo
        // conferido nele ao concluir (primário + cópia).
        val manifest = ManifestStore.load(context, eventPrimary).toMutableList()
        fun saveManifest() {
            ManifestStore.save(context, eventPrimary, eventFolder, manifest)
        }

        var copied = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        val toProcess = analysis.files.filter { it.kind != FileKind.OTHER }
        var done = 0

        for (item in toProcess) {
            done++
            onProgress(done, toProcess.size, item.file.name ?: "")
            val name = item.file.name ?: continue
            // Retomada: já conferido (manifest + HD) não é recopiado.
            // Arquivo suspeito também só é pulado se já estava conferido;
            // o alerta, por si só, nunca pula arquivo.
            if (analysis.verifiedUris.contains(item.file.uri.toString())) {
                skipped++
                continue
            }
            val subfolder = analysis.rolloverSubfolder[item.file.uri.toString()]
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
            val primaryDirActual = if (subfolder != null) {
                SafTree.dir(primaryDir, subfolder) ?: primaryDir
            } else {
                primaryDir
            }
            val copyDirActual = if (subfolder != null && copyDir != null) {
                SafTree.dir(copyDir, subfolder) ?: copyDir
            } else {
                copyDir
            }
            try {
                // Regra da v1.5 (Windows): JPG já presente numa leva
                // anterior não entra de novo na 01; a cópia plana em 02
                // continua garantida. Vale também quando o manifest sumiu.
                val primaryOutcome = if (item.kind == FileKind.JPG && existsInPreviousLevas(eventPrimary, professional, leva, name, item.size, subfolder)) {
                    PlaceOutcome(PlaceResult.SKIPPED, name)
                } else {
                    placeFile(context, item, primaryDirActual, useHash, staging)
                }
                var itemOk = false
                var primaryDestName: String? = null
                when (primaryOutcome.result) {
                    PlaceResult.SKIPPED -> {
                        skipped++
                        itemOk = true
                        primaryDestName = primaryOutcome.destName
                        if (copyDirActual != null) {
                            val copyOutcome = placeFile(context, item, copyDirActual, useHash, staging)
                            if (copyOutcome.result == PlaceResult.COPIED) copied++
                            if (copyOutcome.result == PlaceResult.SKIPPED) skipped++
                            if (copyOutcome.result == PlaceResult.FAILED) {
                                failures.add("$name: falha na cópia.")
                                itemOk = false
                            }
                        }
                    }
                    PlaceResult.COPIED -> {
                        copied++
                        itemOk = true
                        primaryDestName = primaryOutcome.destName
                        if (copyDirActual != null) {
                            val copyOutcome = placeFile(context, item, copyDirActual, useHash, staging)
                            if (copyOutcome.result == PlaceResult.COPIED) copied++
                            if (copyOutcome.result == PlaceResult.SKIPPED) skipped++
                            if (copyOutcome.result == PlaceResult.FAILED) {
                                failures.add("$name: falha na cópia.")
                                itemOk = false
                            }
                        }
                    }
                    PlaceResult.FAILED -> failures.add("$name: falha na cópia para o primário.")
                }
                if (itemOk) {
                    val hash = if (useHash) SafTree.sha256(context, item.file) else null
                    manifest.removeAll {
                        it.professional == professional &&
                            it.kind == kindName(item.kind) &&
                            it.name == name
                    }
                    manifest.add(
                        ManifestEntry(
                            professional = professional,
                            kind = kindName(item.kind),
                            name = name,
                            destName = primaryDestName ?: name,
                            subfolder = subfolder,
                            leva = if (item.kind == FileKind.JPG) leva else null,
                            size = item.size,
                            hash = hash
                        )
                    )
                    saveManifest()
                }
            } catch (e: Exception) {
                failures.add("$name: ${e.message ?: "erro desconhecido"}")
            }
        }

        // Limpa o staging se ficou vazio.
        try {
            if (SafTree.children(staging).isEmpty()) staging.delete()
        } catch (ignored: Exception) { }

        return DischargeResult(copied, skipped, failures.size, failures, leva, eventFolder, manifest.size)
    }

    /** JPG com mesmo nome e tamanho numa leva anterior (qualquer subpasta ZEROU). */
    private fun existsInPreviousLevas(
        eventPrimary: DocumentFile,
        professional: String,
        currentLeva: Int,
        name: String,
        size: Long,
        subfolder: String?
    ): Boolean {
        val jpgRoot = eventPrimary.findFile("01_Fotos_JPG")?.findFile(professional)
            ?: return false
        val subfolders = setOf(subfolder)
        return SafTree.children(jpgRoot).any { levaDir ->
            if (!levaDir.isDirectory) return@any false
            val number = (levaDir.name ?: "").removePrefix("Cartao_").toIntOrNull()
            if (number == null || number >= currentLeva) return@any false
            destFileWithSize(levaDir, name, size, subfolders)
        }
    }

    private enum class PlaceResult { COPIED, SKIPPED, FAILED }

    private data class PlaceOutcome(val result: PlaceResult, val destName: String?)

    private fun placeFile(
        context: Context,
        item: SourceFile,
        destDir: DocumentFile,
        useHash: Boolean,
        staging: DocumentFile
    ): PlaceOutcome {
        val name = item.file.name ?: return PlaceOutcome(PlaceResult.FAILED, null)
        val existing = destDir.findFile(name)
        if (existing != null && existing.isFile) {
            if (existing.length() == item.size) {
                if (!useHash) return PlaceOutcome(PlaceResult.SKIPPED, name)
                val sourceHash = SafTree.sha256(context, item.file)
                val destHash = SafTree.sha256(context, existing)
                if (sourceHash != null && sourceHash == destHash) {
                    return PlaceOutcome(PlaceResult.SKIPPED, name)
                }
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
    ): PlaceOutcome {
        val name = item.file.name ?: return PlaceOutcome(PlaceResult.FAILED, null)
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
                return PlaceOutcome(PlaceResult.SKIPPED, candidate)
            }
            index++
            if (index > 999) return PlaceOutcome(PlaceResult.FAILED, null)
        }
    }

    private fun copyViaStaging(
        context: Context,
        item: SourceFile,
        destDir: DocumentFile,
        destName: String,
        useHash: Boolean,
        staging: DocumentFile
    ): PlaceOutcome {
        // 1) Origem -> staging, com conferência de tamanho.
        val staged = staging.createFile(SafTree.mimeFor(item.file.name ?: ""), destName)
            ?: return PlaceOutcome(PlaceResult.FAILED, null)
        val stagedBytes = SafTree.copyStream(context, item.file, staged)
        if (stagedBytes != item.size) {
            staged.delete()
            return PlaceOutcome(PlaceResult.FAILED, null)
        }
        if (useHash) {
            val sourceHash = SafTree.sha256(context, item.file)
            val stagedHash = SafTree.sha256(context, staged)
            if (sourceHash == null || stagedHash == null || sourceHash != stagedHash) {
                staged.delete()
                return PlaceOutcome(PlaceResult.FAILED, null)
            }
        }
        // 2) Staging -> final (mesma unidade, via streams; o SAF não
        // oferece mover confiável entre pastas).
        val finalFile = destDir.createFile(SafTree.mimeFor(destName), destName)
            ?: run { staged.delete(); return PlaceOutcome(PlaceResult.FAILED, null) }
        val finalBytes = SafTree.copyStream(context, staged, finalFile)
        staged.delete()
        if (finalBytes != item.size) {
            finalFile.delete()
            return PlaceOutcome(PlaceResult.FAILED, null)
        }
        if (useHash) {
            val sourceHash = SafTree.sha256(context, item.file)
            val finalHash = SafTree.sha256(context, finalFile)
            if (sourceHash == null || finalHash == null || sourceHash != finalHash) {
                finalFile.delete()
                return PlaceOutcome(PlaceResult.FAILED, null)
            }
        }
        return PlaceOutcome(PlaceResult.COPIED, destName)
    }

    fun appendReport(
        context: Context,
        eventPrimary: DocumentFile,
        professional: String,
        result: DischargeResult,
        sourceLabel: String,
        analysis: AnalyzeResult
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val section = StringBuilder()
        section.append("=== Descarga $timestamp ===\n")
        section.append("Profissional: $professional\n")
        section.append("Leva: Cartao_${result.leva}\n")
        section.append("Origem: $sourceLabel\n")
        section.append("JPG: ${analysis.jpg} | RAW: ${analysis.raw} | Video: ${analysis.video}\n")
        if (analysis.copiesEnabled) {
            section.append("Cópias: ligadas (02, 04, 06)\n")
        } else {
            section.append("Cópias: DESLIGADAS — só primário (01, 03, 05); backup manual depois.\n")
        }
        section.append("Copiados: ${result.copied} | Pulados: ${result.skipped} | Falhas: ${result.failed}\n")
        section.append("Já conferidos (retomada): ${analysis.alreadyVerifiedCount} | Arquivos conferidos no manifest: ${result.manifestEntries}\n")
        if (analysis.suspects.isNotEmpty()) {
            section.append("Arquivos possivelmente interrompidos (alerta, não é laudo — não formate o cartão antes de ver):\n")
            analysis.suspects.forEach { section.append("- ${it.name}: ${it.reason}\n") }
        }
        if (analysis.rollovers.isNotEmpty()) {
            section.append("Contador zerado:\n")
            analysis.rollovers.forEach {
                section.append("- ${it.prefix} (${kindName(it.kind)}): virada após C9999, arquivos a partir de ${it.firstPostName} na subpasta \"${it.subfolder}\"\n")
            }
        }
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
