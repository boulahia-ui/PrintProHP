@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.printpro.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.printpro.app.model.*
import com.printpro.app.net.DiscoveredPrinter
import com.printpro.app.net.PrinterCaps
import com.printpro.app.pdf.Imposer
import com.printpro.app.pdf.PreviewRenderer
import kotlin.math.roundToInt

private val MIME_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
)

@Composable
fun MainScreen(vm: MainViewModel, onSystemPrint: () -> Unit) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val s = st.settings
    val caps = st.caps
    val snackbar = remember { SnackbarHostState() }
    var showAddIp by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        val m = st.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.clearMessage()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.loadFiles(uris)
    }
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.savePdf(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print Pro HP", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            PrintBar(
                st,
                onPrint = { if (st.pdfOutput) pdfSaver.launch(vm.suggestedPdfName()) else vm.print() },
                onSystemPrint = onSystemPrint,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- Document ----------
            Section("📄 Document") {
                if (st.sourceName == null) {
                    Text("Choisissez un PDF, une ou plusieurs images, ou un fichier Word (.docx).")
                } else {
                    Text("${st.sourceName}", fontWeight = FontWeight.SemiBold)
                    Text("${st.sourcePages} page(s)", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { picker.launch(MIME_TYPES) }) {
                    Text(if (st.sourceName == null) "Choisir des fichiers" else "Changer de document")
                }
            }

            // ---------- Imprimante ----------
            Section("🖨️ Imprimante") {
                SwitchRow("Imprimantes HP uniquement", st.hpOnly) { vm.setHpOnly(it) }
                if (st.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (st.printers.isEmpty()) {
                    Text(
                        if (st.searching) "Recherche sur le réseau Wi-Fi…"
                        else "Aucune imprimante trouvée. Vérifiez que le téléphone et l'imprimante sont sur le même Wi-Fi, ou ajoutez-la par son adresse IP.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PdfOutputRow(selected = st.pdfOutput, onClick = vm::selectPdfOutput)
                st.printers.forEach { p ->
                    PrinterRow(p, selected = !st.pdfOutput && st.selected?.id == p.id) { vm.selectPrinter(p) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::refreshPrinters) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Actualiser")
                    }
                    OutlinedButton(onClick = { showAddIp = true }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter par IP")
                    }
                }
                when {
                    st.pdfOutput -> Text(
                        "La mise en page est enregistrée dans un fichier PDF (pages par feuille, marges, échelle, copies). " +
                            "La couleur et la qualité s'appliqueront lors de l'impression du fichier.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    st.capsLoading -> Text("Interrogation de l'imprimante…", style = MaterialTheme.typography.bodySmall)
                    caps != null -> CapsSummary(caps)
                }
            }

            // ---------- Mise en page ----------
            Section("📑 Mise en page") {
                FieldLabel("Pages par feuille")
                ChoiceChips(PAGES_PER_SHEET, s.pagesPerSheet, { "$it" }) { n -> vm.updateSettings { it.copy(pagesPerSheet = n) } }

                if (s.pagesPerSheet > 1) {
                    FieldLabel("Contenu de chaque feuille")
                    ChoiceChips(LayoutMode.entries, s.layoutMode, { it.label }) { m -> vm.updateSettings { it.copy(layoutMode = m) } }
                }

                OutlinedTextField(
                    value = s.pageRange,
                    onValueChange = { v -> vm.updateSettings { it.copy(pageRange = v) } },
                    label = { Text("📃 Pages à imprimer") },
                    placeholder = { Text("Toutes — ex. 1-5, 8, 10-12") },
                    isError = st.layoutError != null,
                    supportingText = { st.layoutError?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                Dropdown(
                    label = "📐 Format", options = PaperSize.entries, selected = s.paper, text = { it.label },
                    enabled = { p -> caps == null || caps.media.isEmpty() || p.ippKeyword in caps.media },
                ) { p -> vm.updateSettings { it.copy(paper = p) } }

                FieldLabel("↔️ Orientation")
                ChoiceChips(Orientation.entries, s.orientation, { it.label }) { o -> vm.updateSettings { it.copy(orientation = o) } }

                FieldLabel("📏 Marges")
                ChoiceChips(MarginMode.entries, s.margins, { it.label }) { m -> vm.updateSettings { it.copy(margins = m) } }
                if (s.margins == MarginMode.CUSTOM) {
                    Text("${s.customMarginMm.roundToInt()} mm", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = s.customMarginMm, valueRange = 0f..30f, steps = 29,
                        onValueChange = { v -> vm.updateSettings { it.copy(customMarginMm = v.roundToInt().toFloat()) } },
                    )
                }

                FieldLabel("🔍 Échelle")
                ChoiceChips(
                    ScaleMode.entries, s.effectiveScale, { it.label },
                    enabled = { s.pagesPerSheet == 1 || it == ScaleMode.FIT },
                ) { m -> vm.updateSettings { it.copy(scale = m) } }
                if (s.pagesPerSheet > 1) {
                    Text("Avec plusieurs pages par feuille, chaque page est ajustée à sa case.", style = MaterialTheme.typography.bodySmall)
                } else if (s.scale == ScaleMode.CUSTOM) {
                    Text("${s.customScalePercent} %", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = s.customScalePercent.toFloat(), valueRange = 25f..400f,
                        onValueChange = { v -> vm.updateSettings { it.copy(customScalePercent = (v / 5).roundToInt() * 5) } },
                    )
                }

                SwitchRow("Bordure autour de chaque page", s.drawBorders) { b -> vm.updateSettings { it.copy(drawBorders = b) } }
            }

            // ---------- Options d'impression ----------
            Section("⚙️ Options d'impression") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📚 Copies", Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.updateSettings { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) } }, enabled = s.copies > 1) { Text("−") }
                    Text("${s.copies}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.updateSettings { it.copy(copies = (it.copies + 1).coerceAtMost(99)) } }, enabled = s.copies < 99) { Text("+") }
                }

                Dropdown(
                    label = "🔄 Recto verso", options = DuplexMode.entries, selected = s.duplex, text = { it.label },
                    enabled = { d -> !d.isAuto || caps == null || caps.supportsDuplex },
                ) { d -> vm.updateSettings { it.copy(duplex = d) } }
                if (s.duplex == DuplexMode.MANUAL) {
                    SwitchRow("Versos en ordre inverse", s.reverseBackSides) { b -> vm.updateSettings { it.copy(reverseBackSides = b) } }
                    Text(
                        "Les rectos s'impriment d'abord, puis l'application vous demande de remettre les feuilles dans le bac.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                FieldLabel("🎨 Couleur")
                ChoiceChips(
                    ColorMode.entries, s.color, { it.label },
                    enabled = { it == ColorMode.MONO || caps == null || caps.supportsColor },
                ) { c -> vm.updateSettings { it.copy(color = c) } }

                FieldLabel("🖼️ Qualité")
                ChoiceChips(
                    Quality.entries, s.quality, { it.label },
                    enabled = { q -> caps == null || caps.qualities.isEmpty() || q.ipp in caps.qualities },
                ) { q -> vm.updateSettings { it.copy(quality = q) } }
            }

            // ---------- Aperçu ----------
            Section("👁️ Aperçu") {
                if (st.faceCount == 0) {
                    Text("L'aperçu de la mise en page apparaîtra ici.")
                } else {
                    Text("${st.faceCount} face(s) imprimée(s) par copie", style = MaterialTheme.typography.bodySmall)
                    val (sw, sh) = Imposer.sheetSize(s)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                        items(st.faceCount, key = { "${st.previewVersion}-$it" }) { i ->
                            SheetPreview(vm.preview, i, st.faceCount, st.previewVersion, s.color == ColorMode.MONO, sw / sh)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    st.busy?.let { text ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(text)
                }
            }
        }
    }

    if (st.manualDuplexPrompt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Retournez les feuilles") },
            text = {
                Text(
                    "Les rectos sont imprimés. Attendez la dernière feuille, prenez la pile, " +
                        "retournez-la et replacez-la dans le bac sans changer l'ordre, puis appuyez sur Continuer.\n\n" +
                        "Si les versos sortent dans le mauvais ordre, activez « Versos en ordre inverse » la prochaine fois."
                )
            },
            confirmButton = { Button(onClick = vm::continueManualDuplex) { Text("Continuer") } },
            dismissButton = { TextButton(onClick = vm::cancelManualDuplex) { Text("Annuler") } },
        )
    }

    if (showAddIp) {
        var ip by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddIp = false },
            title = { Text("Ajouter une imprimante") },
            text = {
                Column {
                    Text("Adresse IP indiquée sur la page de configuration de l'imprimante (ex. 192.168.1.25).", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, singleLine = true, label = { Text("Adresse IP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.addManualPrinter(ip); showAddIp = false }, enabled = ip.isNotBlank()) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAddIp = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PrintBar(st: UiState, onPrint: () -> Unit, onSystemPrint: () -> Unit) {
    val s = st.settings
    val paper = (if (s.duplex == DuplexMode.SIMPLEX) st.faceCount else (st.faceCount + 1) / 2) * s.copies
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSystemPrint, enabled = st.faceCount > 0) { Text("Via Android") }
            Button(
                onClick = onPrint,
                enabled = st.faceCount > 0 && (st.selected != null || st.pdfOutput) && st.busy == null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        st.pdfOutput -> "Enregistrer le PDF"
                        st.faceCount > 0 -> "Imprimer · $paper feuille(s)"
                        else -> "Imprimer"
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetPreview(renderer: PreviewRenderer, index: Int, total: Int, version: Int, mono: Boolean, aspect: Float) {
    val bitmap by produceState<Bitmap?>(null, index, version) { value = renderer.render(index, 720) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: aspect
        Box(
            Modifier
                .height(260.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .shadow(3.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b == null) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Face ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = if (mono) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Face ${index + 1} / $total", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PdfOutputRow(selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text("Enregistrer en PDF", fontWeight = FontWeight.SemiBold)
            Text("Crée un fichier avec la mise en page, sans imprimante", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PrinterRow(p: DiscoveredPrinter, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = if (p.isHp) FontWeight.SemiBold else FontWeight.Normal)
            Text(listOf(p.model, p.host).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapsSummary(c: PrinterCaps) {
    fun yn(b: Boolean) = if (b) "oui" else "non"
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(c.model, fontWeight = FontWeight.SemiBold)
            Text(
                "État : ${c.stateLabel} · PDF direct : ${yn(c.supportsPdf)} · Recto verso auto : ${yn(c.supportsDuplex)} · Couleur : ${yn(c.supportsColor)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceChips(
    options: List<T>, selected: T, label: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, enabled = enabled(o))
        }
    }
}

@Composable
private fun <T> Dropdown(
    label: String, options: List<T>, selected: T, text: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label : ${text(selected)}", Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, enabled = enabled(o), onClick = { open = false; onSelect(o) })
            }
        }
    }
}
