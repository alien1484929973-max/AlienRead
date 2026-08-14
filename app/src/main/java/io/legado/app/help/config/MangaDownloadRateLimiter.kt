package io.legado.app.help.config

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

object MangaDownloadRateLimiter {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    suspend fun await() {
        val minSeconds = AppConfig.mangaDownloadDelayMin.coerceAtLeast(0)
        val maxSeconds = AppConfig.mangaDownloadDelayMax.coerceAtLeast(minSeconds)
        if (maxSeconds == 0) return
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val waitForPrevious = (nextAllowedAt - now).coerceAtLeast(0L)
            if (waitForPrevious > 0) delay(waitForPrevious)
            val randomSeconds = Random.nextInt(minSeconds, maxSeconds + 1)
            nextAllowedAt = SystemClock.elapsedRealtime() + randomSeconds * 1000L
        }
    }
}
