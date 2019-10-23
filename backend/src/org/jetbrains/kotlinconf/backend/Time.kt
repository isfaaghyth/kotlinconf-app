package org.jetbrains.kotlinconf.backend

import io.ktor.util.date.*
import java.util.concurrent.atomic.*

private var simulatedTime = AtomicReference<GMTDate?>(null)
private var updatedTime = AtomicReference<GMTDate>(GMTDate())

internal fun updateTime(time: GMTDate?) {
    simulatedTime.set(time)
    updatedTime.set(GMTDate())
}

internal fun now(): GMTDate {
    val start = simulatedTime.get()

    return if (start == null) {
        GMTDate()
    } else {
        val offset = GMTDate().timestamp - updatedTime.get().timestamp
        start + offset
    }
}
