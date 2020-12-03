package io.pleo.antaeus.core.scheduler

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Scheduler(corePoolSize: Int = 1) {
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(corePoolSize)
    private var scheduledJob: ScheduledFuture<*>? = null

    fun scheduleJob(job: () -> Unit, scheduledAt: LocalDateTime) {
        val now = LocalDateTime.now()

        val delay = Duration.between(
            now,
            scheduledAt
        ).toSeconds()

        // Schedule at fixed rate of 60 seconds for testing purposes
        // TODO schedule for each 1st of the Month
        scheduledJob = executor.scheduleAtFixedRate(
            job,
            delay,
            60,
            TimeUnit.SECONDS
        )
    }
}