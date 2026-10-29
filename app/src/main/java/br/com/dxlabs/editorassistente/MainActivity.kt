package br.com.dxlabs.editorassistente

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("editor_assistente", Context.MODE_PRIVATE) }

    private var sourceUri: Uri? = null
    private var primaryUri: Uri? = null
    private var copyUri: Uri? = null

    private var lastAnalysis: AnalyzeResult? = null
    private var lastEventPrimary: DocumentFile? = null
    private var dischargeRunning = false

    private lateinit var txtSource: TextView
    private lateinit var txtPrimary: TextView
    private lateinit var txtCopy: TextView
    private lateinit var edtDate: EditText
    private lateinit var edtEvent: EditText
    private lateinit var edtProfessional: EditText
    private lateinit var chkCopyJpg: CheckBox
    private lateinit var chkCopyVideo: CheckBox
    private lateinit var chkCopyRaw: CheckBox
    private lateinit var chkHash: CheckBox
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnDischarge: Button

    private val pickSource = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takePermission(uri)
            sourceUri = uri
            prefs.edit().putString("source_uri", uri.toString()).apply()
            refreshLabels()
        }
    }

    private val pickPrimary = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takePermission(uri)
            primaryUri = uri
            prefs.edit().putString("primary_uri", uri.toString()).apply()
            refreshLabels()
        }
    }

    private val pickCopy = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takePermission(uri)
            copyUri = uri
            prefs.edit().putString("copy_uri", uri.toString()).apply()
            refreshLabels()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtSource = findViewById(R.id.txtSource)
        txtPrimary = findViewById(R.id.txtPrimary)
        txtCopy = findViewById(R.id.txtCopy)
        edtDate = findViewById(R.id.edtDate)
        edtEvent = findViewById(R.id.edtEvent)
        edtProfessional = findViewById(R.id.edtProfessional)
        chkCopyJpg = findViewById(R.id.chkCopyJpg)
        chkCopyVideo = findViewById(R.id.chkCopyVideo)
        chkCopyRaw = findViewById(R.id.chkCopyRaw)
        chkHash = findViewById(R.id.chkHash)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        progress = findViewById(R.id.progress)
        btnDischarge = findViewById(R.id.btnDischarge)

        sourceUri = prefs.getString("source_uri", null)?.let { Uri.parse(it) }
        primaryUri = prefs.getString("primary_uri", null)?.let { Uri.parse(it) }
        copyUri = prefs.getString("copy_uri", null)?.let { Uri.parse(it) }

        if (edtDate.text.isEmpty()) {
            edtDate.setText(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
        }
        edtEvent.setText(prefs.getString("event_name", ""))
        edtProfessional.setText(prefs.getString("professional", ""))
        chkCopyJpg.isChecked = prefs.getBoolean("copy_jpg", true)
        chkCopyVideo.isChecked = prefs.getBoolean("copy_video", true)
        chkCopyRaw.isChecked = prefs.getBoolean("copy_raw", true)
        chkHash.isChecked = prefs.getBoolean("use_hash", false)

        findViewById<Button>(R.id.btnSource).setOnClickListener { pickSource.launch(null) }
        findViewById<Button>(R.id.btnPrimary).setOnClickListener { pickPrimary.launch(null) }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { pickCopy.launch(null) }
        findViewById<Button>(R.id.btnAnalyze).setOnClickListener { analyze() }
        btnDischarge.setOnClickListener { discharge() }
        findViewById<Button>(R.id.btnChecklist).setOnClickListener { showChecklist() }
        findViewById<Button>(R.id.btnReturned).setOnClickListener { markReturned() }
        findViewById<Button>(R.id.btnEject).setOnClickListener { showEjectHelp() }

        refreshLabels()
    }

    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Permissão persistente negada pelo provedor: a pasta continua
            // valendo nesta sessão, mas terá de ser escolhida de novo depois.
            Toast.makeText(this, "Permissão persistente não concedida para esta pasta.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshLabels() {
        txtSource.text = "Origem: " + (sourceUri?.let { labelFor(it) } ?: "nenhuma pasta escolhida")
        txtPrimary.text = "Primário (SD): " + (primaryUri?.let { labelFor(it) } ?: "nenhuma pasta escolhida")
        txtCopy.text = "Cópias (unidade externa): " + (copyUri?.let { labelFor(it) } ?: "nenhuma pasta escolhida (cópias vão para o SD)")
    }

    private fun labelFor(uri: Uri): String {
        val root = SafTree.root(this, uri) ?: return uri.toString()
        return SafTree.displayName(root)
    }

    private fun currentInputs(): Triple<String, String, String>? {
        val date = edtDate.text.toString().trim()
        val event = edtEvent.text.toString().trim()
        val professional = IngestEngine.sanitizeProfessional(edtProfessional.text.toString())
        if (date.isEmpty() || event.isEmpty() || professional.isEmpty()) {
            Toast.makeText(this, "Preencha data, nome do evento e profissional.", Toast.LENGTH_LONG).show()
            return null
        }
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) {
            Toast.makeText(this, "Data no formato AAAA-MM-DD.", Toast.LENGTH_LONG).show()
            return null
        }
        return Triple(date, event, professional)
    }

    private fun analyze() {
        if (dischargeRunning) return
        val inputs = currentInputs() ?: return
        val (date, event, professional) = inputs
        val sourceRoot = sourceUri?.let { SafTree.root(this, it) }
        if (sourceRoot == null) {
            Toast.makeText(this, "Escolha primeiro a pasta do cartão.", Toast.LENGTH_LONG).show()
            return
        }
        val primaryBase = primaryUri?.let { SafTree.root(this, it) }
        if (primaryBase == null) {
            Toast.makeText(this, "Escolha primeiro a pasta do SD (primário).", Toast.LENGTH_LONG).show()
            return
        }
        val eventFolder = IngestEngine.eventFolderName(date, event)
        txtStatus.text = "Analisando…"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = IngestEngine.analyze(this@MainActivity, sourceRoot, primaryBase, eventFolder, professional)
            withContext(Dispatchers.Main) {
                lastAnalysis = result
                val text = StringBuilder()
                text.append("Evento: $eventFolder\n")
                text.append("Próxima leva na 01: Cartao_${result.nextLeva}\n")
                text.append("JPG: ${result.jpg} | RAW: ${result.raw} | Vídeo: ${result.video} | Outros ignorados: ${result.other}\n")
                text.append("Total a copiar (só primário, sem cópias): ${SafTree.formatBytes(result.totalBytes)}\n")
                if (result.jpgWithoutRaw > 0) text.append("Atenção: ${result.jpgWithoutRaw} JPG sem RAW.\n")
                if (result.rawWithoutJpg > 0) text.append("Atenção: ${result.rawWithoutJpg} RAW sem JPG.\n")
                text.append("Confira o espaço livre no SD e na unidade externa antes de descarregar.")
                txtLog.text = text.toString()
                txtStatus.text = "Análise pronta."
            }
        }
    }

    private fun discharge() {
        if (dischargeRunning) return
        val analysis = lastAnalysis
        if (analysis == null) {
            Toast.makeText(this, "Clique em Analisar cartão antes de descarregar.", Toast.LENGTH_LONG).show()
            return
        }
        val inputs = currentInputs() ?: return
        val (_, event, professional) = inputs
        val primaryBase = primaryUri?.let { SafTree.root(this, it) }
        if (primaryBase == null) {
            Toast.makeText(this, "Escolha primeiro a pasta do SD (primário).", Toast.LENGTH_LONG).show()
            return
        }
        val copyBase = copyUri?.let { SafTree.root(this, it) }

        prefs.edit()
            .putString("event_name", event)
            .putString("professional", professional)
            .putBoolean("copy_jpg", chkCopyJpg.isChecked)
            .putBoolean("copy_video", chkCopyVideo.isChecked)
            .putBoolean("copy_raw", chkCopyRaw.isChecked)
            .putBoolean("use_hash", chkHash.isChecked)
            .apply()

        dischargeRunning = true
        btnDischarge.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EditorAssistente:discharge")
        wakeLock.acquire(10 * 60 * 1000L)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = IngestEngine.discharge(
                this@MainActivity,
                analysis,
                primaryBase,
                copyBase,
                professional,
                chkCopyJpg.isChecked,
                chkCopyVideo.isChecked,
                chkCopyRaw.isChecked,
                chkHash.isChecked
            ) { done, total, currentName ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progress.progress = if (total > 0) (done * 100 / total) else 0
                    txtStatus.text = "Descarregando $done de $total: $currentName"
                }
            }
            val eventPrimary = SafTree.dir(primaryBase, analysis.eventFolderName)
            if (eventPrimary != null) {
                lastEventPrimary = eventPrimary
                IngestEngine.appendReport(
                    this@MainActivity, eventPrimary, professional, result,
                    labelFor(sourceUri!!), analysis.jpg, analysis.raw, analysis.video
                )
                IngestEngine.appendChecklist(
                    this@MainActivity, eventPrimary, professional, result,
                    labelFor(sourceUri!!), analysis.jpg, analysis.raw, analysis.video
                )
            }
            withContext(Dispatchers.Main) {
                progress.progress = 100
                val text = StringBuilder()
                text.append("Descarga concluída.\n")
                text.append("Leva: Cartao_${result.leva}\n")
                text.append("Copiados: ${result.copied} | Pulados: ${result.skipped} | Falhas: ${result.failed}\n")
                if (result.failures.isNotEmpty()) {
                    text.append("Falhas:\n")
                    result.failures.take(20).forEach { text.append("- $it\n") }
                }
                if (result.failed == 0) {
                    text.append("Sem falhas: pode ejetar o cartão pelo sistema.")
                } else {
                    text.append("Com falhas: NÃO ejete ainda. Veja o relatório na pasta do evento.")
                }
                txtLog.text = text.toString()
                txtStatus.text = if (result.failed == 0) "Concluído sem falhas." else "Concluído com falhas."
                dischargeRunning = false
                btnDischarge.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (wakeLock.isHeld) wakeLock.release()
                if (result.failed == 0) {
                    Toast.makeText(this@MainActivity, "Descarga concluída sem falhas.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showChecklist() {
        val inputs = currentInputs() ?: return
        val (date, event, _) = inputs
        val primaryBase = primaryUri?.let { SafTree.root(this, it) } ?: run {
            Toast.makeText(this, "Escolha primeiro a pasta do SD.", Toast.LENGTH_LONG).show()
            return
        }
        val eventFolder = IngestEngine.eventFolderName(date, event)
        lifecycleScope.launch(Dispatchers.IO) {
            val eventDir = primaryBase.findFile(eventFolder)
            val text = if (eventDir != null) IngestEngine.readChecklist(this@MainActivity, eventDir) else null
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Checklist: $eventFolder")
                    .setMessage(text ?: "Nenhuma descarga registrada para este evento ainda.")
                    .setPositiveButton("Fechar", null)
                    .show()
            }
        }
    }

    private fun markReturned() {
        val eventPrimary = lastEventPrimary
        if (eventPrimary == null) {
            Toast.makeText(this, "Faça primeiro uma descarga neste evento.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = IngestEngine.markLastReturned(this@MainActivity, eventPrimary)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Última descarga marcada como devolvida." else "Não consegui marcar. Veja o checklist.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showEjectHelp() {
        AlertDialog.Builder(this)
            .setTitle("Ejetar com segurança")
            .setMessage(
                "O Android não tem o botão de ejetar do Windows. Depois de uma descarga sem falhas:\n\n" +
                "1) Deslize a notificação do cartão/unidade e toque em Ejetar, ou\n" +
                "2) Abra Ajustes > Armazenamento e ejete a unidade.\n\n" +
                "Só remova o cartão ou a unidade depois disso."
            )
            .setPositiveButton("Abrir Ajustes de armazenamento") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Não consegui abrir os ajustes. Ejete pela notificação.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
}
