package com.printpro.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.printpro.app.model.ColorMode
import com.printpro.app.model.DuplexMode
import com.printpro.app.model.PaperSize
import com.printpro.app.model.PrintSettings
import com.printpro.app.model.Quality
import com.printpro.app.net.DiscoveredPrinter
import com.printpro.app.net.IppClient
import com.printpro.app.net.IppException
import com.printpro.app.net.JobOptions
import com.printpro.app.net.PrinterCaps
import com.printpro.app.net.PrinterDiscovery
import com.printpro.app.pdf.DocumentConverter
import com.printpro.app.pdf.Imposer
import com.printpro.app.pdf.JobBuilder
import com.printpro.app.pdf.JobPlanner
import com.printpro.app.pdf.PreviewRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val sourceName: String? = null,
    val sourcePages: Int = 0,
    val settings: PrintSettings = PrintSettings(),
    val discovered: List<DiscoveredPrinter> = emptyList(),
    val manualPrinters: List<DiscoveredPrinter> = emptyList(),
    val hpOnly: Boolean = true,
    val searching: Boolean = false,
    val selected: DiscoveredPrinter? = null,
    val caps: PrinterCaps? = null,
    val capsLoading: Boolean = false,
    val faceCount: Int = 0,
    val previewVersion: Int = 0,
    val layoutError: String? = null,
    val busy: String? = null,
    val message: String? = null,
    val manualDuplexPrompt: Boolean = false,
) {
    val printers: List<DiscoveredPrinter>
        get() = (manualPrinters + discovered.filter { !hpOnly || it.isHp }).distinctBy { it.id }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val converter = DocumentConverter(app)
    private val discovery = PrinterDiscovery(app)
    private val workDir = File(app.cacheDir, "work").apply { mkdirs() }
    val preview = PreviewRenderer()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var source: File? = null
    @Volatile private var imposed: File? = null
    private var discoveryJob: Job? = null
    private var rebuildJob: Job? = null
    private val rebuildMutex = Mutex()
    private var pendingBack: PendingJob? = null

    private class PendingJob(val file: File, val name: String, val options: JobOptions)

    init {
        refreshPrinters()
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun setHpOnly(v: Boolean) = _ui.update { it.copy(hpOnly = v) }
    private fun msg(t: String) = _ui.update { it.copy(message = t) }

    // ---------------- Imprimantes ----------------

    fun refreshPrinters() {
        discoveryJob?.cancel()
        _ui.update { it.copy(discovered = emptyList(), searching = true) }
        discoveryJob = viewModelScope.launch {
            launch { delay(12_000); _ui.update { it.copy(searching = false) } }
            try {
                discovery.discover().collect { list ->
                    _ui.update { it.copy(discovered = list) }
                    if (_ui.value.selected == null) list.firstOrNull { it.isHp }?.let { selectPrinter(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(searching = false, message = e.message) }
            }
        }
    }

    fun addManualPrinter(input: String) {
        val cleaned = input.trim().removePrefix("http://").removePrefix("ipp://").substringBefore('/')
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "631").toIntOrNull() ?: 631
        if (host.isBlank()) return
        val p = DiscoveredPrinter("Imprimante $host", host, port, "ipp/print", "Ajoutée manuellement", isHp = true, manual = true)
        _ui.update { it.copy(manualPrinters = (it.manualPrinters + p).distinctBy { x -> x.id }) }
        selectPrinter(p)
    }

    fun selectPrinter(p: DiscoveredPrinter) {
        _ui.update { it.copy(selected = p, caps = null, capsLoading = true) }
        viewModelScope.launch {
            try {
                val caps = withContext(Dispatchers.IO) { IppClient.getPrinterAttributes(p) }
                if (_ui.value.selected?.id != p.id) return@launch
                _ui.update { st -> st.copy(caps = caps, capsLoading = false, settings = adaptTo(st.settings, caps)) }
                scheduleRebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(capsLoading = false, message = e.message ?: "Imprimante injoignable") }
            }
        }
    }

    /** Ajuste les réglages aux capacités réelles de l'imprimante. */
    private fun adaptTo(s: PrintSettings, c: PrinterCaps): PrintSettings {
        var r = s
        if (!c.supportsColor) r = r.copy(color = ColorMode.MONO)
        if (!c.supportsDuplex && r.duplex.isAuto) r = r.copy(duplex = DuplexMode.MANUAL)
        if (c.qualities.isNotEmpty() && r.quality.ipp !in c.qualities) {
            r = r.copy(quality = Quality.entries.firstOrNull { it == Quality.NORMAL && it.ipp in c.qualities }
                ?: Quality.entries.firstOrNull { it.ipp in c.qualities } ?: r.quality)
        }
        if (c.media.isNotEmpty() && r.paper.ippKeyword !in c.media) {
            PaperSize.entries.firstOrNull { it.ippKeyword in c.media }?.let { r = r.copy(paper = it) }
        }
        return r
    }

    // ---------------- Document et mise en page ----------------

    fun loadFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Préparation du document…") }
            try {
                val c = withContext(Dispatchers.IO) { converter.convert(uris, workDir) }
                source?.takeIf { it != c.file }?.delete()
                source = c.file
                _ui.update {
                    it.copy(sourceName = c.name, sourcePages = c.pageCount, busy = null,
                        settings = it.settings.copy(pageRange = ""))
                }
                rebuildJob?.cancel()
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, message = "Impossible d'ouvrir : ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun updateSettings(f: (PrintSettings) -> PrintSettings) {
        _ui.update { it.copy(settings = f(it.settings)) }
        scheduleRebuild()
    }

    private fun scheduleRebuild(delayMs: Long = 300) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch { delay(delayMs); rebuild() }
    }

    private suspend fun rebuild() {
        val src = source ?: return
        rebuildMutex.withLock {
            val s = _ui.value.settings
            try {
                val out = File(workDir, "imposed_${System.nanoTime()}.pdf")
                val faces = withContext(Dispatchers.IO) { Imposer.impose(src, s, out) }
                preview.open(out) // ferme et supprime l'aperçu précédent
                imposed = out
                _ui.update { it.copy(faceCount = faces, previewVersion = it.previewVersion + 1, layoutError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _ui.update { it.copy(layoutError = e.message) }
            } catch (e: Throwable) {
                _ui.update { it.copy(message = "Mise en page impossible : ${e.message}") }
            }
        }
    }

    suspend fun fileForSystemPrint(): File? {
        rebuildJob?.join()
        return imposed
    }

    // ---------------- Impression ----------------

    fun print() {
        viewModelScope.launch {
            val st = _ui.value
            val p = st.selected ?: return@launch msg("Choisissez une imprimante.")
            val caps = st.caps ?: return@launch msg("L'imprimante n'a pas répondu. Actualisez ou utilisez « Via Android ».")
            if (!caps.supportsPdf) return@launch msg(
                "Cette imprimante n'accepte pas le PDF en direct : utilisez « Via Android » (HP Print Service / Mopria)."
            )
            rebuildJob?.join()
            if (_ui.value.layoutError != null) return@launch msg(_ui.value.layoutError!!)
            val imp = imposed ?: return@launch msg("Aucun document à imprimer.")
            val s = _ui.value.settings
            val faces = _ui.value.faceCount
            val name = st.sourceName ?: "Document"

            _ui.update { it.copy(busy = "Envoi à l'imprimante…") }
            try {
                var manualStarted = false
                withContext(Dispatchers.IO) {
                    if (s.duplex == DuplexMode.MANUAL) {
                        val (front, back) = JobPlanner.manualDuplex(faces, s.copies, s.reverseBackSides)
                        val fFront = File(workDir, "job_front.pdf").also { JobBuilder.build(imp, front, it) }
                        val opts = JobOptions.from(s, caps, copies = 1, sides = "one-sided")
                        val id = IppClient.printJob(p, fFront, "$name (rectos)", opts)
                        if (back.any { it != null }) {
                            val fBack = File(workDir, "job_back.pdf").also { JobBuilder.build(imp, back, it) }
                            _ui.update { it.copy(busy = "Impression des rectos…") }
                            val state = IppClient.waitForJob(p, id, 10 * 60_000L)
                            if (state == 7 || state == 8) throw IppException("Les rectos n'ont pas été imprimés (travail annulé ou interrompu).")
                            pendingBack = PendingJob(fBack, "$name (versos)", opts)
                            manualStarted = true
                        }
                    } else {
                        val ippCopies = s.copies == 1 || caps.maxCopies >= s.copies
                        val opts = JobOptions.from(s, caps, copies = if (ippCopies) s.copies else 1, sides = s.duplex.ippSides)
                        val file = if (ippCopies) imp else File(workDir, "job_copies.pdf").also {
                            JobBuilder.build(imp, JobPlanner.collated(faces, s.copies, s.duplex.isAuto), it)
                        }
                        IppClient.printJob(p, file, name, opts)
                    }
                }
                if (manualStarted) _ui.update { it.copy(busy = null, manualDuplexPrompt = true) }
                else _ui.update { it.copy(busy = null, message = "Travail envoyé à ${p.name}.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = "Échec de l'impression : ${e.message}") }
            }
        }
    }

    fun continueManualDuplex() {
        val job = pendingBack ?: return
        val p = _ui.value.selected ?: return
        _ui.update { it.copy(manualDuplexPrompt = false, busy = "Impression des versos…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { IppClient.printJob(p, job.file, job.name, job.options) }
                pendingBack = null
                _ui.update { it.copy(busy = null, message = "Versos envoyés à ${p.name}.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, manualDuplexPrompt = true, message = "Échec : ${e.message}") }
            }
        }
    }

    fun cancelManualDuplex() {
        pendingBack = null
        _ui.update { it.copy(manualDuplexPrompt = false) }
    }

    override fun onCleared() {
        preview.close()
        super.onCleared()
    }
}
