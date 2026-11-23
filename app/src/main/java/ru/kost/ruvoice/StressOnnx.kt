package ru.kost.ruvoice

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StressONNX FULL FP32: четыре RUAccent ONNX-модели + словари. Файлы берутся с закреплённого
 * Hugging Face revision, хранятся в filesDir и после первой загрузки работают без сети.
 * Ручные ударения сохраняются после полного контекстного инференса; результат — в '+'-нотации Silero.
 */
object StressOnnx {
    private const val TAG = "RuVoice-StressONNX"
    private const val REVISION = "cb1594ddf81928e678a3a7ee7a22415e6e3a218f"
    private const val HF_ROOT = "https://huggingface.co/TigreGotico/stressonnx-models/resolve/$REVISION/ru_ruaccent/"
    private const val PREFS = "stressonnx_full"
    private const val KEY_STATUS = "status"
    private const val KEY_ERROR = "error"
    private val executor = Executors.newSingleThreadExecutor()
    private val loading = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var appContext: Context? = null

    private data class Artifact(val remote: String, val local: String)
    private val markedWord = Regex("[A-Za-zА-Яа-яЁё0-9+]+(?:-[A-Za-zА-Яа-яЁё0-9+]+)*", RegexOption.IGNORE_CASE)

    private val artifacts = listOf(
        Artifact("nn_accent/model.onnx", "nn/nn_accent/model.onnx"),
        Artifact("nn_accent/vocab.txt", "nn/nn_accent/vocab.txt"),
        Artifact("nn_accent/config.json", "nn/nn_accent/config.json"),
        Artifact("nn_stress_usage/model.onnx", "nn/nn_stress_usage_predictor/model.onnx"),
        Artifact("nn_stress_usage/tokenizer.json", "nn/nn_stress_usage_predictor/tokenizer.json"),
        Artifact("nn_stress_usage/config.json", "nn/nn_stress_usage_predictor/config.json"),
        Artifact("nn_yo_homograph/model.onnx", "nn/nn_yo_homograph_resolver/model.onnx"),
        Artifact("nn_yo_homograph/tokenizer.json", "nn/nn_yo_homograph_resolver/tokenizer.json"),
        Artifact("nn_yo_homograph/config.json", "nn/nn_yo_homograph_resolver/config.json"),
        Artifact("nn_omograph/turbo3.1/model.onnx", "nn/nn_omograph/turbo3.1/model.onnx"),
        Artifact("nn_omograph/turbo3.1/tokenizer.json", "nn/nn_omograph/turbo3.1/tokenizer.json"),
        Artifact("dictionary/omographs.json.gz", "dictionary/omographs.json.gz"),
        Artifact("dictionary/yo_words.json.gz", "dictionary/yo_words.json.gz"),
        Artifact("dictionary/yo_homographs.json.gz", "dictionary/yo_homographs.json.gz"),
        Artifact("dictionary/accents_nn.json.gz", "dictionary/accents_nn.json.gz")
    )

    fun bootstrap(context: Context) {
        appContext = context.applicationContext
        if (ready || !loading.compareAndSet(false, true)) return
        executor.execute {
            val ctx = context.applicationContext
            val root = File(ctx.filesDir, "stressonnx-full")
            try {
                val complete = artifacts.all { File(root, it.local).let { f -> f.isFile && f.length() > 0L } }
                if (!complete) {
                    val available = StatFs(ctx.filesDir.absolutePath).availableBytes
                    check(available >= 750L * 1024L * 1024L) {
                        "Для StressONNX FULL нужно не менее 750 МБ свободного места"
                    }
                    status(ctx, "Загрузка StressONNX FULL FP32")
                    artifacts.forEachIndexed { index, artifact ->
                        val dest = File(root, artifact.local)
                        if (!dest.isFile || dest.length() == 0L) {
                            dest.parentFile?.mkdirs()
                            download(artifact.remote, dest)
                        }
                        status(ctx, "Загрузка StressONNX FULL FP32: ${index + 1}/${artifacts.size}")
                    }
                }
                // ruaccent-rs читает ключи словаря accents.json.gz; в StressONNX bundle тот же слой
                // опубликован под именем accents_nn.json.gz.
                val accents = File(root, "dictionary/accents_nn.json.gz")
                val legacyName = File(root, "dictionary/accents.json.gz")
                if (!legacyName.isFile || legacyName.length() != accents.length()) accents.copyTo(legacyName, overwrite = true)
                status(ctx, "Загрузка StressONNX FULL FP32: инициализация")
                loadNative(root)
                ready = true
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_STATUS, "StressONNX FULL FP32 готов · офлайн")
                    .remove(KEY_ERROR).apply()
                Log.i(TAG, "StressONNX FULL FP32 loaded")
            } catch (e: Exception) {
                Log.e(TAG, "StressONNX initialization failed", e)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_STATUS, "StressONNX FULL: ошибка загрузки")
                    .putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
            } finally {
                loading.set(false)
            }
        }
    }

    private fun preserveExplicitYo(source: String, prediction: String): String {
        val originalLetters = source.filter { it != '+' }
        val out = StringBuilder(prediction.length)
        var index = 0
        prediction.forEach { ch ->
            if (ch == '+') out.append(ch) else {
                val original = originalLetters.getOrNull(index++)
                out.append(if (original == 'ё' || original == 'Ё') original else ch)
            }
        }
        return out.toString()
    }

    /** Возвращает null до полной загрузки — вызывающий код тогда использует штатный RuVoice pipeline. */
    fun process(text: String): String? {
        if (!ready || text.isBlank()) return null
        return try {
            // Run on the complete sentence so every homograph keeps its surrounding context.
            val inferred = nativeStress(text)
            val sourceWords = markedWord.findAll(text).toList()
            val inferredWords = markedWord.findAll(inferred).toList()
            if (sourceWords.size != inferredWords.size) return null
            val replacements = ArrayList<String>(sourceWords.size)
            for (i in sourceWords.indices) {
                val source = sourceWords[i].value
                val prediction = inferredWords[i].value
                val sourceBase = source.replace("+", "").replace('ё', 'е').replace('Ё', 'Е')
                val predictionBase = prediction.replace("+", "").replace('ё', 'е').replace('Ё', 'Е')
                if (sourceBase != predictionBase) return null
                // Keep explicit book marks and non-Russian tokens verbatim; use model output for Russian words.
                val hasRussian = source.any { it in "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя" }
                replacements += if ('+' in source || !hasRussian) source else preserveExplicitYo(source, prediction)
            }
            // Rebuild from original spans, so every paragraph, unusual punctuation, emoji and whitespace
            // survives even when RUAccent's own normalizer omits characters it cannot feed to its models.
            val restored = StringBuilder(text.length + replacements.size)
            var cursor = 0
            for (i in sourceWords.indices) {
                val range = sourceWords[i].range
                restored.append(text, cursor, range.first)
                restored.append(replacements[i])
                cursor = range.last + 1
            }
            restored.append(text, cursor, text.length)
            restored.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "StressONNX inference failed; using built-in stress pipeline", e)
            null
        }
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "StressONNX FULL FP32: ожидает загрузки") ?: "StressONNX FULL FP32: ожидает загрузки"

    private fun loadNative(root: File) {
        // Preload ORT through Android before ort::init_from opens the same library path.
        System.loadLibrary("onnxruntime")
        System.loadLibrary("ruvoice_stress")
        val ctx = appContext ?: throw IllegalStateException("Application context unavailable")
        val ort = File(ctx.applicationInfo.nativeLibraryDir, "libonnxruntime.so")
        check(ort.isFile) { "ONNX Runtime library not found" }
        check(nativeInit(root.absolutePath, ort.absolutePath)) { "Could not initialize RUAccent FP32 models" }
    }

    private fun download(path: String, destination: File) {
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val connection = (URL(HF_ROOT + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RuVoice-Enhanced/1.1.3")
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}: $path" }
                    val expectedLength = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(part).buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    check(part.isFile && part.length() > 0L) { "Empty model file: $path" }
                    check(expectedLength <= 0L || part.length() == expectedLength) {
                        "Incomplete model file: $path (${part.length()}/$expectedLength)"
                    }
                    if (destination.exists()) destination.delete()
                    check(part.renameTo(destination)) { "Could not commit model file: $path" }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                last = e
                part.delete()
                if (attempt < 2) Thread.sleep(1200L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Download failed: $path")
    }

    private fun status(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
    }

    @JvmStatic private external fun nativeInit(modelDir: String, ortLibrary: String): Boolean
    @JvmStatic private external fun nativeStress(text: String): String
}
