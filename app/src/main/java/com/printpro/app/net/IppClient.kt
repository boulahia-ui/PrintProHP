package com.printpro.app.net

import com.printpro.app.model.PrintSettings
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class IppException(message: String) : IOException(message)

/** Capacités annoncées par l'imprimante (Get-Printer-Attributes). */
data class PrinterCaps(
    val name: String,
    val model: String,
    val formats: List<String>,
    val media: List<String>,
    val sides: List<String>,
    val colorModes: List<String>,
    val colorSupported: Boolean?,
    val qualities: List<Int>,
    val maxCopies: Int,
    val scalings: List<String>,
    val state: Int,
) {
    val supportsPdf get() = formats.isEmpty() || formats.any { it.equals("application/pdf", ignoreCase = true) }
    val supportsDuplex get() = sides.any { it.startsWith("two-sided") }
    val supportsColor get() = if (colorModes.isNotEmpty()) "color" in colorModes else colorSupported != false
    val stateLabel get() = when (state) { 3 -> "Prête"; 4 -> "En cours d'impression"; 5 -> "Arrêtée"; else -> "Inconnu" }
}

data class JobOptions(
    val copies: Int,
    val sides: String?,
    val media: String?,
    val colorMode: String?,
    val quality: Int?,
    val scalingNone: Boolean,
) {
    companion object {
        /** N'envoie que les options que l'imprimante déclare accepter. */
        fun from(s: PrintSettings, c: PrinterCaps, copies: Int, sides: String) = JobOptions(
            copies = copies.coerceAtLeast(1),
            sides = sides.takeIf { it in c.sides },
            media = s.paper.ippKeyword.takeIf { it in c.media },
            colorMode = s.color.ipp.takeIf { it in c.colorModes },
            quality = s.quality.ipp.takeIf { it in c.qualities },
            scalingNone = s.pagesPerSheet == 1 && s.effectiveScale != com.printpro.app.model.ScaleMode.FIT && "none" in c.scalings,
        )
    }
}

/** Client IPP minimal (RFC 8010/8011) : impression PDF directe sans PC ni pilote. */
object IppClient {
    private const val OP_PRINT_JOB = 0x0002
    private const val OP_GET_JOB_ATTRIBUTES = 0x0009
    private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    private const val TAG_OPERATION = 0x01
    private const val TAG_JOB = 0x02
    private const val TAG_END = 0x03

    private const val INTEGER = 0x21
    private const val ENUM = 0x23
    private const val NAME = 0x42
    private const val KEYWORD = 0x44
    private const val URI = 0x45
    private const val CHARSET = 0x47
    private const val LANGUAGE = 0x48
    private const val MIME = 0x49

    private val nextId = AtomicInteger(1)

    class Response(val status: Int, private val attrs: Map<String, List<Any>>) {
        val ok get() = status < 0x0100
        fun strings(n: String) = attrs[n]?.filterIsInstance<String>().orEmpty()
        fun string(n: String) = strings(n).firstOrNull()
        fun ints(n: String) = attrs[n]?.filterIsInstance<Int>().orEmpty()
        fun bool(n: String) = attrs[n]?.filterIsInstance<Boolean>()?.firstOrNull()
        fun range(n: String) = attrs[n]?.filterIsInstance<IntRange>()?.firstOrNull()
    }

    private class Writer(operation: Int) {
        private val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)

        init {
            out.writeShort(0x0200) // IPP 2.0
            out.writeShort(operation)
            out.writeInt(nextId.getAndIncrement())
        }

        fun group(tag: Int) = apply { out.writeByte(tag) }
        fun string(tag: Int, name: String, value: String) = apply {
            writeName(tag, name)
            val v = value.toByteArray(Charsets.UTF_8)
            out.writeShort(v.size); out.write(v)
        }
        fun strings(tag: Int, name: String, values: List<String>) = apply {
            values.forEachIndexed { i, v -> string(tag, if (i == 0) name else "", v) }
        }
        fun integer(tag: Int, name: String, value: Int) = apply {
            writeName(tag, name); out.writeShort(4); out.writeInt(value)
        }
        private fun writeName(tag: Int, name: String) {
            out.writeByte(tag)
            val n = name.toByteArray(Charsets.US_ASCII)
            out.writeShort(n.size); out.write(n)
        }
        fun operationHeader(printerUri: String) = group(TAG_OPERATION)
            .string(CHARSET, "attributes-charset", "utf-8")
            .string(LANGUAGE, "attributes-natural-language", "fr")
            .string(URI, "printer-uri", printerUri)
            .string(NAME, "requesting-user-name", "PrintPro")
        fun finish(): ByteArray { out.writeByte(TAG_END); out.flush(); return bytes.toByteArray() }
    }

    fun getPrinterAttributes(p: DiscoveredPrinter): PrinterCaps {
        val req = Writer(OP_GET_PRINTER_ATTRIBUTES).operationHeader(p.ippUri)
            .strings(KEYWORD, "requested-attributes", listOf(
                "printer-name", "printer-make-and-model", "printer-state",
                "document-format-supported", "media-supported", "sides-supported",
                "print-color-mode-supported", "color-supported", "print-quality-supported",
                "copies-supported", "print-scaling-supported",
            ))
            .finish()
        val r = send(p, req, null, 15_000)
        if (!r.ok) throw IppException(describe(r))
        return PrinterCaps(
            name = r.string("printer-name") ?: p.name,
            model = r.string("printer-make-and-model") ?: p.model,
            formats = r.strings("document-format-supported"),
            media = r.strings("media-supported"),
            sides = r.strings("sides-supported"),
            colorModes = r.strings("print-color-mode-supported"),
            colorSupported = r.bool("color-supported"),
            qualities = r.ints("print-quality-supported"),
            maxCopies = r.range("copies-supported")?.last ?: 1,
            scalings = r.strings("print-scaling-supported"),
            state = r.ints("printer-state").firstOrNull() ?: 0,
        )
    }

    /** Envoie un PDF ; retourne l'identifiant du travail (0 si non communiqué). */
    fun printJob(p: DiscoveredPrinter, pdf: File, jobName: String, o: JobOptions): Int {
        val w = Writer(OP_PRINT_JOB).operationHeader(p.ippUri)
            .string(NAME, "job-name", jobName.take(200))
            .string(MIME, "document-format", "application/pdf")
            .group(TAG_JOB)
        if (o.copies > 1) w.integer(INTEGER, "copies", o.copies)
        o.sides?.let { w.string(KEYWORD, "sides", it) }
        o.media?.let { w.string(KEYWORD, "media", it) }
        o.colorMode?.let { w.string(KEYWORD, "print-color-mode", it) }
        o.quality?.let { w.integer(ENUM, "print-quality", it) }
        if (o.scalingNone) w.string(KEYWORD, "print-scaling", "none")
        val r = send(p, w.finish(), pdf, 300_000)
        if (!r.ok) throw IppException(describe(r))
        return r.ints("job-id").firstOrNull() ?: 0
    }

    /** Attend la fin d'un travail. Retourne job-state (7 annulé, 8 interrompu, 9 terminé) ou null. */
    suspend fun waitForJob(p: DiscoveredPrinter, jobId: Int, timeoutMs: Long): Int? {
        if (jobId <= 0) return null
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            delay(2500)
            val state = runCatching {
                val req = Writer(OP_GET_JOB_ATTRIBUTES).operationHeader(p.ippUri)
                    .integer(INTEGER, "job-id", jobId)
                    .strings(KEYWORD, "requested-attributes", listOf("job-state"))
                    .finish()
                send(p, req, null, 15_000).ints("job-state").firstOrNull()
            }.getOrNull() ?: continue
            if (state >= 7) return state
        }
        return null
    }

    private fun send(p: DiscoveredPrinter, header: ByteArray, doc: File?, readTimeoutMs: Int): Response {
        val conn = try {
            URL(p.httpUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IppException("Adresse d'imprimante invalide : ${p.host}")
        }
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setFixedLengthStreamingMode(header.size.toLong() + (doc?.length() ?: 0L))
            conn.outputStream.use { os ->
                os.write(header)
                doc?.inputStream()?.use { it.copyTo(os, 64 * 1024) }
            }
            val code = conn.responseCode
            if (code == 426) throw IppException("L'imprimante exige une connexion chiffrée. Utilisez « Via Android ».")
            if (code != 200) throw IppException("L'imprimante a répondu HTTP $code.")
            return parse(conn.inputStream.use { it.readBytes() })
        } catch (e: IppException) {
            throw e
        } catch (e: IOException) {
            throw IppException("Connexion impossible à ${p.host}. Le téléphone et l'imprimante sont-ils sur le même Wi-Fi ? (${e.message})")
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(data: ByteArray): Response {
        if (data.size < 8) throw IppException("Réponse de l'imprimante invalide.")
        val b = ByteBuffer.wrap(data)
        b.short
        val status = b.short.toInt() and 0xFFFF
        b.int
        val attrs = LinkedHashMap<String, MutableList<Any>>()
        var last: String? = null
        var depth = 0
        while (b.remaining() >= 1) {
            val tag = b.get().toInt() and 0xFF
            if (tag == TAG_END) break
            if (tag < 0x10) { last = null; continue }
            if (b.remaining() < 2) break
            val name = String(ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }, Charsets.UTF_8)
            if (b.remaining() < 2) break
            val raw = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }
            when {
                tag == 0x34 -> { depth++; continue }     // begCollection : ignorée
                tag == 0x37 -> { depth--; continue }     // endCollection
                depth > 0 -> continue
            }
            val value = decode(tag, raw)
            if (name.isNotEmpty()) {
                last = name
                attrs.getOrPut(name) { mutableListOf() }.add(value)
            } else {
                last?.let { attrs[it]?.add(value) }
            }
        }
        return Response(status, attrs)
    }

    private fun decode(tag: Int, raw: ByteArray): Any = when (tag) {
        0x21, 0x23 -> if (raw.size == 4) ByteBuffer.wrap(raw).int else 0
        0x22 -> raw.isNotEmpty() && raw[0].toInt() != 0
        0x33 -> if (raw.size == 8) ByteBuffer.wrap(raw).let { IntRange(it.int, it.int) } else IntRange(1, 1)
        0x35, 0x36 -> runCatching {
            val l = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val tl = ((raw[2 + l].toInt() and 0xFF) shl 8) or (raw[3 + l].toInt() and 0xFF)
            String(raw, 4 + l, tl, Charsets.UTF_8)
        }.getOrDefault("")
        in 0x40..0x4F -> String(raw, Charsets.UTF_8)
        else -> raw
    }

    private fun describe(r: Response): String {
        val text = when (r.status) {
            0x0400 -> "requête refusée"
            0x0401, 0x0403 -> "accès refusé par l'imprimante"
            0x0402 -> "authentification requise"
            0x0406 -> "imprimante introuvable à cette adresse"
            0x0408 -> "document trop volumineux"
            0x040A -> "format PDF non accepté : utilisez « Via Android »"
            0x040B -> "option non prise en charge"
            0x0411 -> "document illisible par l'imprimante"
            0x0500, 0x0504 -> "erreur de l'imprimante"
            0x0502, 0x0505, 0x0507 -> "imprimante occupée, réessayez"
            0x0506 -> "l'imprimante n'accepte pas de travaux"
            else -> "code 0x%04X".format(r.status)
        }
        val detail = r.string("status-message")?.let { " ($it)" } ?: ""
        return "Erreur imprimante : $text$detail"
    }
}
