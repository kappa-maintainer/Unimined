package xyz.wagyourtail.unimined.util

import net.fabricmc.tinyremapper.api.TrLogger
import net.fabricmc.tinyremapper.api.TrLogger.Level
import org.gradle.api.logging.Logger

/**
 * Forwards TinyRemapper log output into the Gradle logger so it can be controlled with the
 * regular `--warn` / `--info` / `--debug` switches instead of capturing the process stdout.
 *
 * The one known-benign warning is dropped to debug: `unknown invokedynamic bsm` fires for
 * bootstrap methods such as `scala/runtime/LambdaDeserialize/bootstrap` (tag=6 iif=false),
 * which reference methods that are never part of the mapping chain — TinyRemapper already
 * skips the indy by returning null, so the warning carries no actionable information.
 */
class TrLoggerFilter(private val logger: Logger) : TrLogger {
    override fun log(level: Level, message: String) {
        if (level == Level.WARN && message.contains("unknown invokedynamic bsm")) {
            logger.debug("[TinyRemapper] $message")
            return
        }
        when (level) {
            Level.DEBUG -> logger.debug("[TinyRemapper] $message")
            Level.INFO -> logger.info("[TinyRemapper] $message")
            Level.WARN -> logger.warn("[TinyRemapper] $message")
            Level.ERROR -> logger.error("[TinyRemapper] $message")
        }
    }
}
