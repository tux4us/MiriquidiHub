package io.github.tux4us.miriquidihub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Zustand der Shizuku-Anbindung. */
enum class ShizukuState { UNAVAILABLE, NOT_RUNNING, NEEDS_PERMISSION, READY }

/** Auf welchem Weg das Systemprotokoll gelesen wird. */
enum class LogAccessMode { OWN_ONLY, READ_LOGS, SHIZUKU }

/**
 * Zugriff auf das Logcat. Fremde Einträge sind nur lesbar, wenn die App `READ_LOGS` besitzt (per
 * ADB erteilt) oder Shizuku den Befehl mit Shell-Rechten ausführt. Ohne beides liefert Android nur
 * die Einträge der eigenen App.
 */
object AppLogAccess {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_REQUEST_CODE = 4711
    private const val MAX_LINES = 400_000

    private val LOGCAT_ARGS = arrayOf("logcat", "-d", "-v", "threadtime", "-v", "uid", "-b", "main,system,crash")

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"

    fun hasReadLogs(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun shizukuInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun shizukuState(context: Context): ShizukuState = try {
        when {
            !Shizuku.pingBinder() ->
                if (shizukuInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.UNAVAILABLE
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NEEDS_PERMISSION
        }
    } catch (e: Throwable) {
        ShizukuState.UNAVAILABLE
    }

    fun requestShizuku() {
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    /** Beste verfügbare Variante: READ_LOGS vor Shizuku vor „nur eigene Einträge“. */
    fun bestMode(context: Context): LogAccessMode = when {
        hasReadLogs(context) -> LogAccessMode.READ_LOGS
        shizukuState(context) == ShizukuState.READY -> LogAccessMode.SHIZUKU
        else -> LogAccessMode.OWN_ONLY
    }

    /** Liest den Logcat-Puffer (blockierend, im Hintergrund aufrufen). Fehler ergeben eine leere Liste. */
    fun readLines(mode: LogAccessMode): List<String> = try {
        val process = if (mode == LogAccessMode.SHIZUKU) shizukuProcess() else {
            ProcessBuilder(*LOGCAT_ARGS).redirectErrorStream(true).start()
        }
        val lines = ArrayDeque<String>()
        process.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) {
                lines.addLast(line)
                if (lines.size > MAX_LINES) lines.removeFirst()
            }
        }
        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        lines.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Shizuku.newProcess ist in neueren API-Versionen nicht öffentlich und wird per Reflection aufgerufen. */
    private fun shizukuProcess(): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, LOGCAT_ARGS, null, null) as Process
    }
}
