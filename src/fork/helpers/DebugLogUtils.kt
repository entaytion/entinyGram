package desu.inugram.helpers

import desu.inugram.InuConfig
import org.telegram.messenger.BuildVars

object DebugLogUtils {
    private const val MAX_CALLER_FRAMES = 6

    @JvmStatic
    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED && InuConfig.EXTRA_DEBUG_LOGS.value

    @JvmStatic
    fun getCaller(): String {
        val sb = StringBuilder()
        var frames = 0
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            if (!name.startsWith("org.telegram") && !name.startsWith("desu.inugram")) continue
            if (name.startsWith(DebugLogUtils::class.java.name) || name.substringAfterLast('.').contains("DebugHelper")) continue
            if (sb.isNotEmpty()) sb.append(" < ")
            sb.append(name.substringAfterLast('.')).append('.').append(frame.methodName).append(':').append(frame.lineNumber)
            if (++frames == MAX_CALLER_FRAMES) break
        }
        return sb.toString()
    }

    @JvmStatic
    fun describeStack(thread: Thread): String =
        thread.stackTrace.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
