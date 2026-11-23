package ru.kost.ruvoice

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Тема и установка дополнительного русского пака голосов. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(nightMode(this))
        installExtendedRussianVoices()
        StressOnnx.bootstrap(this)
    }

    /**
     * В полной сборке пять базовых голосов уже встроены. Дополнительный официальный пак содержит
     * все 22 голоса (5 штатных + 15 новых + 2 смеси). Скачиваем его один раз в фоне, проверяем
     * SHA-256 и устанавливаем тем же валидатором, что и пользовательские паки.
     * При отсутствии сети/ошибке повторяем при следующем запуске; повреждённый архив не сохраняется.
     */
    private fun installExtendedRussianVoices() {
        val prefs = getSharedPreferences("voice_pack_bootstrap", Context.MODE_PRIVATE)
        if (Packs.find(filesDir, EXTENDED_PACK_ID) != null) {
            prefs.edit().putBoolean("installed", true).putString("status", "22 русских голоса готовы").apply()
            return
        }
        if (prefs.getBoolean("installed", false)) prefs.edit().putBoolean("installed", false).apply()
        prefs.edit().putString("status", "Загрузка пакета 22 русских голосов").apply()
        bootstrap.execute {
            val archive = File(cacheDir, "ruvoice-pack-ru_voices.zip")
            try {
                val connection = (URL(VOICE_PACK_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        archive.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(actual == VOICE_PACK_SHA256) { "voice pack SHA-256 mismatch" }
                } finally {
                    connection.disconnect()
                }
                archive.inputStream().buffered().use { Packs.install(it, filesDir) }
                prefs.edit().putBoolean("installed", true).putString("status", "22 русских голоса готовы").apply()
                Log.i(TAG, "Installed official 22-voice Russian pack")
            } catch (e: Exception) {
                prefs.edit().putString("status", "Пакет голосов: ошибка загрузки, повтор при следующем запуске").apply()
                Log.w(TAG, "Could not install extended Russian voices; will retry next launch", e)
            } finally {
                archive.delete()
            }
        }
    }

    companion object {
        private const val TAG = "RuVoice"
        private const val EXTENDED_PACK_ID = "ru_voices"
        private const val VOICE_PACK_URL = "https://github.com/kost-t-human/ruvoice-tts/releases/download/packs/ruvoice-pack-ru_voices.zip"
        private const val VOICE_PACK_SHA256 = "6a1084e8693dbf581a9ee91b7376909aa9b5177efa9d760685d5315d430c1520"
        private val bootstrap = Executors.newSingleThreadExecutor()
        private fun ui(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        fun nightMode(ctx: Context) = ui(ctx).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        fun setNightMode(ctx: Context, mode: Int) {
            ui(ctx).edit().putInt("night", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
