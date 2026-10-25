package dev.weq.qqhook.helper

fun Thread.sleepQuietly(time: Long) {
    kotlin.runCatching {
        Thread.sleep(time)
    }
}