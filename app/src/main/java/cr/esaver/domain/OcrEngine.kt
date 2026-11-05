/*
 * OCR de la pantalla del cargador.
 *
 * Estrategia:
 *  1. Detectar el LCD por densidad de píxeles blancos/azules (sin confundirlo
 *     con el arco verde, el logo ni los reflejos).
 *  2. Recortar y ampliar la pantalla; ML Kit lee tiempo, voltaje, corriente y
 *     potencia.
 *  3. Para la cifra grande de kWh se usa un decodificador propio de 7 segmentos,
 *     porque ML Kit suele ignorarla. Así se obtiene, por ejemplo, 22.7 o 24.9.
 */
package cr.esaver.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cr.esaver.data.ChargerReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lee la pantalla del cargador. La pantalla suele ser lo único brillante de la
 * foto, así que primero se detecta ese rectángulo, se recorta, se amplía y se
 * procesa en blanco y negro. Eso mejora mucho la lectura de los dígitos de 7
 * segmentos frente a mandar la foto completa a ML Kit.
 */
class OcrEngine(private val context: Context) {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun analyze(path: String): ChargerReading = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext ChargerReading()

        val base = runCatching { decodeRotated(file) }.getOrNull()
            ?: return@withContext ChargerReading()

        val texts = mutableListOf<String>()
        try {
            val cropRect = runCatching { findContentRect(base) }.getOrNull()
            if (cropRect != null && cropRect.width() > 40 && cropRect.height() > 20) {
                val crop = runCatching {
                    Bitmap.createBitmap(base, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                }.getOrNull()
                if (crop != null) {
                    val scaled = scaleForOcr(crop)
                    if (scaled !== crop) crop.recycle()

                    val binary = runCatching { preprocess(scaled, threshold = true) }.getOrNull()
                    val gray = runCatching { preprocess(scaled, threshold = false) }.getOrNull()
                    val binaryText = binary?.let {
                        runCatching { recognize(InputImage.fromBitmap(it, 0)) }.getOrNull()
                    }
                    val grayText = gray?.let {
                        runCatching { recognize(InputImage.fromBitmap(it, 0)) }.getOrNull()
                    }
                    binary?.recycle()
                    gray?.recycle()
                    scaled.recycle()

                    if (!binaryText.isNullOrBlank()) texts += binaryText
                    if (!grayText.isNullOrBlank()) texts += grayText
                }
            }

            // Si el recorte no encontró la unidad kWh, probamos la foto completa.
            if (texts.none { it.contains("KWH", ignoreCase = true) }) {
                runCatching {
                    val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                    val full = Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS)
                    if (full.text.isNotBlank()) {
                        texts += full.text
                    }
                }
            }
        } finally {
            base.recycle()
        }

        var reading = ChargerOcrParser.parse(texts)

        // ML Kit no reconoce bien la cifra grande de 7 segmentos. La leemos con
        // un decodificador propio y la usamos si es más coherente con la
        // potencia y la duración (o si ML Kit no encontró ningún kWh).
        if (file.exists()) {
            val decoded = runCatching { decodeSevenSegmentEnergy(file) }.getOrNull()
            if (decoded != null) {
                val power = reading.powerKw
                val duration = reading.durationSec
                val expected = if (power != null && duration != null && duration > 0) {
                    power * duration / 3600.0
                } else null
                val mlEnergy = reading.energyKwh
                // Si no hay potencia para validar, preferimos la cifra del
                // centro (la del periodo) sobre otras lecturas como el total
                // acumulado del cargador.
                val useDecoded = mlEnergy == null || expected == null || expected <= 0.2 ||
                    abs(ln(decoded / expected)) <= abs(ln(mlEnergy / expected))
                if (useDecoded) {
                    reading = reading.copy(energyKwh = decoded)
                }
            }
        }

        return@withContext reading
    }

    /**
     * Decodifica la cifra grande de 7 segmentos del centro del cargador.
     * No depende de ML Kit: segmenta los dígitos y evalúa los siete segmentos.
     */
    private fun decodeSevenSegmentEnergy(file: File): Double? {
        val base = runCatching { decodeRotated(file) }.getOrNull() ?: return null
        return try {
            val lcdRect = findContentRect(base)
            if (lcdRect.width() < 80 || lcdRect.height() < 40) return null
            val lcd = Bitmap.createBitmap(
                base,
                lcdRect.left,
                lcdRect.top,
                lcdRect.width(),
                lcdRect.height(),
            )
            try {
                decodeSevenSegment(lcd)
            } finally {
                lcd.recycle()
            }
        } finally {
            base.recycle()
        }
    }

    private fun decodeSevenSegment(lcd: Bitmap): Double? {
        val width = lcd.width
        val height = lcd.height
        if (width < 80 || height < 40) return null
        val pixels = IntArray(width * height)
        lcd.getPixels(pixels, 0, width, 0, 0, width, height)

        fun lumAt(x: Int, y: Int): Int {
            val color = pixels[y * width + x]
            return (Color.red(color) * 299 + Color.green(color) * 587 +
                Color.blue(color) * 114) / 1000
        }

        // La línea separadora superior de la pantalla es la fila más brillante
        // de la zona superior. La franja de los kWh está debajo de ella.
        var topLine = -1
        var topLineScore = 0
        val lineStart = (height * 0.15f).toInt()
        val lineEnd = (height * 0.50f).toInt()
        for (y in lineStart until lineEnd) {
            var count = 0
            for (x in 0 until width) if (lumAt(x, y) > 135) count++
            if (count > topLineScore) {
                topLineScore = count
                topLine = y
            }
        }
        val hasLine = topLine >= 0 && topLineScore > width * 0.30f
        val bandTop = if (hasLine) topLine + 3 else (height * 0.25f).toInt()
        val bandHeight = (height * 0.32f).toInt().coerceAtLeast(30)
            .coerceAtMost((height - bandTop - 2).coerceAtLeast(30))
        if (bandHeight < 25) return null

        // Componentes conexos dentro de la franja de los kWh.
        val bandSize = width * bandHeight
        val on = BooleanArray(bandSize)
        for (y in 0 until bandHeight) {
            for (x in 0 until width) {
                on[y * width + x] = lumAt(x, bandTop + y) > 145
            }
        }
        val visited = BooleanArray(bandSize)
        val comps = mutableListOf<Component>()
        val queue = ArrayDeque<Int>()
        for (y in 0 until bandHeight) {
            for (x in 0 until width) {
                val start = y * width + x
                if (!on[start] || visited[start]) continue
                visited[start] = true
                queue.addLast(start)
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0
                while (queue.isNotEmpty()) {
                    val index = queue.removeFirst()
                    val cx = index % width
                    val cy = index / width
                    area++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until width && ny in 0 until bandHeight) {
                                val ni = ny * width + nx
                                if (on[ni] && !visited[ni]) {
                                    visited[ni] = true
                                    queue.addLast(ni)
                                }
                            }
                        }
                    }
                }
                if (area >= 10) comps += Component(minX, minY, maxX, maxY, area)
            }
        }
        if (comps.isEmpty()) return null

        val digitLeftLimit = (width * 0.38f).toInt()
        val digitRightLimit = (width * 0.90f).toInt()
        val upper = comps.filter {
            it.top < bandHeight * 0.42f &&
                it.left > digitLeftLimit &&
                it.left < digitRightLimit &&
                it.width >= 3 &&
                it.height >= 5
        }.sortedBy { it.left }
        if (upper.size < 1) return null

        // El punto decimal es un pequeño bloque aislado en la parte baja.
        val dot = comps.firstOrNull {
            it.width in 3..12 && it.height in 3..12 &&
                it.top > bandHeight * 0.60f &&
                it.left > digitLeftLimit && it.right < digitRightLimit
        }

        val patterns = mapOf(
            0 to "abcdef",
            1 to "bc",
            2 to "abdeg",
            3 to "abcdg",
            4 to "bcfg",
            5 to "acdfg",
            6 to "acdefg",
            7 to "abc",
            8 to "abcdefg",
            9 to "abcdfg",
        )
        val regions = mapOf(
            'a' to floatArrayOf(0.18f, 0.82f, 0.01f, 0.15f),
            'b' to floatArrayOf(0.78f, 0.99f, 0.10f, 0.48f),
            'c' to floatArrayOf(0.78f, 0.99f, 0.52f, 0.90f),
            'd' to floatArrayOf(0.18f, 0.82f, 0.85f, 0.99f),
            'e' to floatArrayOf(0.01f, 0.22f, 0.52f, 0.90f),
            'f' to floatArrayOf(0.01f, 0.22f, 0.10f, 0.48f),
            'g' to floatArrayOf(0.18f, 0.82f, 0.43f, 0.57f),
        )

        fun segmentOn(cellLeft: Float, cellRight: Float, segment: Char): Boolean {
            val region = regions[segment] ?: return false
            val ax = (cellLeft + (cellRight - cellLeft) * region[0]).toInt()
            val bx = (cellLeft + (cellRight - cellLeft) * region[1]).toInt()
            val ay = bandTop + (bandHeight * region[2]).toInt()
            val by = bandTop + (bandHeight * region[3]).toInt()
            var total = 0
            var bright = 0
            for (y in ay until by) {
                for (x in ax until bx) {
                    if (x in 0 until width && y in 0 until height) {
                        total++
                        if (lumAt(x, y) > 140) bright++
                    }
                }
            }
            return total > 0 && bright.toFloat() / total > 0.28f
        }

        val maxUpperWidth = upper.maxOf { it.width }
        val cellWidth = (maxUpperWidth * 1.25f)
            .coerceIn(bandHeight * 0.42f, bandHeight * 0.72f)
        val digits = mutableListOf<Int>()
        var decimalAfter: Int? = null

        fun classifyCell(cellLeft: Float, cellRight: Float): Int? {
            val bits = "abcdefg".map { segmentOn(cellLeft, cellRight, it) }
            var bestDigit = -1
            var bestDistance = Int.MAX_VALUE
            for ((digit, pattern) in patterns) {
                var distance = 0
                for ((index, segment) in "abcdefg".withIndex()) {
                    if (bits[index] != pattern.contains(segment)) distance++
                }
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestDigit = digit
                }
            }
            return if (bestDistance <= 2 && bestDigit >= 0) bestDigit else null
        }

        fun addZone(zoneLeft: Int, zoneRight: Int, cellCount: Int): Boolean {
            val zoneWidth = zoneRight - zoneLeft
            if (zoneWidth < 4 || cellCount !in 1..4) return false
            for (i in 0 until cellCount) {
                val left = zoneLeft + zoneWidth * i.toFloat() / cellCount
                val right = zoneLeft + zoneWidth * (i + 1).toFloat() / cellCount
                digits += classifyCell(left, right) ?: return false
            }
            return true
        }

        if (dot != null) {
            val leftUpper = upper.filter { it.right < dot.left }
            val rightUpper = upper.filter { it.left > dot.right }
            if (leftUpper.isEmpty() || rightUpper.isEmpty()) return null
            val leftZoneLeft = leftUpper.minOf { it.left }
            val leftZoneRight = leftUpper.maxOf { it.right }
            val rightZoneLeft = rightUpper.minOf { it.left }
            val rightZoneRight = rightUpper.maxOf { it.right }
            val leftCount = ((leftZoneRight - leftZoneLeft) / cellWidth)
                .roundToInt().coerceIn(1, 4)
            val rightCount = ((rightZoneRight - rightZoneLeft) / cellWidth)
                .roundToInt().coerceIn(1, 4)
            if (leftCount + rightCount > 4) return null
            if (!addZone(leftZoneLeft, leftZoneRight, leftCount)) return null
            decimalAfter = digits.size
            if (!addZone(rightZoneLeft, rightZoneRight, rightCount)) return null
        } else {
            val zoneLeft = upper.minOf { it.left }
            val zoneRight = upper.maxOf { it.right }
            val count = ((zoneRight - zoneLeft) / cellWidth).roundToInt().coerceIn(1, 4)
            if (!addZone(zoneLeft, zoneRight, count)) return null
        }
        if (digits.isEmpty()) return null

        val number = StringBuilder()
        for ((index, digit) in digits.withIndex()) {
            if (index == decimalAfter) number.append('.')
            number.append(digit)
        }
        val value = number.toString().toDoubleOrNull() ?: return null
        return if (value in 0.1..500.0) value else null
    }

    private suspend fun recognize(image: InputImage): String =
        Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS).text

    private fun decodeRotated(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * Busca el rectángulo de la pantalla iluminada. Para no confundirse con el
     * LED rosado o los reflejos verdes, solo mira la zona central de la foto.
     */
    private fun findContentRect(source: Bitmap): Rect {
        val width = source.width
        val height = source.height
        val searchLeft = (width * 0.18f).toInt()
        val searchRight = (width * 0.82f).toInt()
        // La pantalla del cargador suele estar en el centro; así evitamos el
        // arco verde, el logo iluminado y los reflejos de la parte superior.
        val searchTop = (height * 0.36f).toInt()
        val searchBottom = (height * 0.72f).toInt()
        val regionWidth = searchRight - searchLeft
        val regionHeight = searchBottom - searchTop
        if (regionWidth < 20 || regionHeight < 20) return fallbackCenterRect(width, height)

        val step = 2
        val rowCounts = IntArray(regionHeight)
        val colCounts = IntArray(regionWidth)
        var y = searchTop
        while (y < searchBottom) {
            var x = searchLeft
            while (x < searchRight) {
                val color = source.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                val isGreenLight = green > red + 70 && green > blue + 40
                // La pantalla es blanca/azulada; el fondo naranja o verde se descarta.
                if (luminance >= 90 && !isGreenLight && red <= blue + 60) {
                    rowCounts[y - searchTop]++
                    colCounts[x - searchLeft]++
                }
                x += step
            }
            y += step
        }

        // Usamos densidad por filas/columnas en lugar del mínimo/máximo absoluto:
        // así un reflejo aislado no agranda el recorte.
        val samplesAcross = regionWidth / step
        val samplesDown = regionHeight / step
        val rowThreshold = maxOf(3, (samplesAcross * 0.015f).toInt())
        val colThreshold = maxOf(3, (samplesDown * 0.015f).toInt())
        val rows = rowCounts.indices.filter { rowCounts[it] >= rowThreshold }
        val cols = colCounts.indices.filter { colCounts[it] >= colThreshold }
        if (rows.isEmpty() || cols.isEmpty()) return fallbackCenterRect(width, height)

        val minX = searchLeft + cols.first()
        val maxX = searchLeft + cols.last()
        val minY = searchTop + rows.first()
        val maxY = searchTop + rows.last()
        val detectedWidth = maxX - minX
        val detectedHeight = maxY - minY
        if (detectedWidth < 40 || detectedHeight < 20) return fallbackCenterRect(width, height)

        val paddingX = maxOf(10, (detectedWidth * 0.08f).toInt())
        val paddingY = maxOf(10, (detectedHeight * 0.15f).toInt())
        // Damos un poco de margen inferior para que no se corte la fila de kWh.
        val bottomExtra = (detectedHeight * 0.20f).toInt()
        val left = (minX - paddingX).coerceIn(0, width - 2)
        val top = (minY - paddingY).coerceIn(0, height - 2)
        val right = (maxX + paddingX).coerceIn(left + 1, width)
        val bottom = (maxY + paddingY + bottomExtra).coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun fallbackCenterRect(width: Int, height: Int): Rect {
        val cropWidth = (width * 0.80f).toInt().coerceAtLeast(1)
        val cropHeight = (height * 0.34f).toInt().coerceAtLeast(1)
        val left = (width - cropWidth) / 2
        val top = (height * 0.42f - cropHeight / 2f).toInt()
            .coerceIn(0, (height - cropHeight).coerceAtLeast(0))
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun scaleForOcr(source: Bitmap): Bitmap = scaleToWidth(source, 1800)

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        if (source.width >= targetWidth) return source
        val scale = min(8f, targetWidth.toFloat() / source.width)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                targetHeight,
                true,
            )
        }.getOrDefault(source)
    }

    /**
     * Pasa la pantalla a gris, la invierte si el fondo es oscuro y aplica
     * contraste (y opcionalmente umbral) para que ML Kit vea texto negro
     * sobre fondo blanco.
     */
    private fun preprocess(source: Bitmap, threshold: Boolean): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(pixels.size)
        var total = 0L
        for (i in pixels.indices) {
            val color = pixels[i]
            val value = (Color.red(color) * 299 + Color.green(color) * 587 +
                Color.blue(color) * 114) / 1000
            luminance[i] = value
            total += value
        }
        val average = total / pixels.size
        val invert = average < 120

        for (i in luminance.indices) {
            var value = if (invert) 255 - luminance[i] else luminance[i]
            value = ((value - 128) * 1.55 + 128).toInt().coerceIn(0, 255)
            if (threshold) value = if (value > 135) 255 else 0
            val gray = value.coerceIn(0, 255)
            pixels[i] = Color.rgb(gray, gray, gray)
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val area: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

}
