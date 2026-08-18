package com.halo.moontone.log

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * Central logging for MoonTone.
 *
 * - Every line goes to logcat under tag "MoonTone".
 * - Every line is appended to a rotating file in files/logs/moontone.log (512KB x2).
 * - Last [RING_SIZE] lines are kept in memory for the debug CLI and the in-app log panel.
 * - Uncaught exceptions are captured and written to the same file.
 */
object MoonToneLog {

    const val TAG = "MoonTone"

    private const val RING_SIZE = 500
    private const val MAX_FILE_SIZE = 512 * 1024
    private const val KEEP_FILES = 2

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_SIZE)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var logFile: File? = null
        private set

    /** Optional callback so the Compose UI can show a live log tail. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(filesDir: File) {
        synchronized(lock) {
            if (logFile != null) return
            val dir = File(filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "moontone.log")
        }
    }

    fun v(tag: String, msg: String) = log('V', tag, msg, null)
    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    @JvmOverloads
    fun w(tag: String, msg: String, tr: Throwable? = null) = log('W', tag, msg, tr)
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null) = log('E', tag, msg, tr)

    fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        val line = "${dateFmt.format(Date())} $level/$tag: $msg" +
            (tr?.let { "\n" + stackTraceOf(it) } ?: "")

        val logcatMsg = "$tag: $msg"
        when (level) {
            'V' -> Log.v(TAG, logcatMsg)
            'D' -> Log.d(TAG, logcatMsg)
            'I' -> Log.i(TAG, logcatMsg)
            'W' -> if (tr != null) Log.w(TAG, logcatMsg, tr) else Log.w(TAG, logcatMsg)
            'E' -> if (tr != null) Log.e(TAG, logcatMsg, tr) else Log.e(TAG, logcatMsg)
            else -> Log.i(TAG, logcatMsg)
        }

        var tail: String? = null
        synchronized(lock) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(line)
            if (ring.size % 10 == 0) {
                tail = ring.joinToString("\n")
            }
        }
        appendToFile(line)
        val t = tail
        if (t != null) {
            mainHandler.post { listener?.invoke(t) }
        }
    }

    fun tail(count: Int = 100): String = synchronized(lock) {
        ring.takeLast(count.coerceIn(1, RING_SIZE)).joinToString("\n")
    }

    fun clear() = synchronized(lock) { ring.clear() }

    private fun stackTraceOf(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun appendToFile(line: String) {
        val f = logFile ?: return
        try {
            if (f.length() > MAX_FILE_SIZE) rotate(f)
            FileWriter(f, true).use { it.append(line).append('\n') }
        } catch (ignored: Exception) {
            // Never let logging take the app down.
        }
    }

    private fun rotate(f: File) {
        try {
            for (i in KEEP_FILES - 1 downTo 1) {
                val from = File(f.parentFile, "${f.name}.$i")
                val to = File(f.parentFile, "${f.name}.${i + 1}")
                if (from.exists()) from.renameTo(to)
            }
            File(f.parentFile, "${f.name}.1").let { if (it.exists()) it.delete() }
            f.renameTo(File(f.parentFile, "${f.name}.1"))
        } catch (ignored: Exception) {
        }
    }

    /** Install a JVM crash handler that logs the exception before dying. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
