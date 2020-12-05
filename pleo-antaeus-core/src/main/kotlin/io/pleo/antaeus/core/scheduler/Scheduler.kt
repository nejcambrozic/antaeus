package io.pleo.antaeus.core.scheduler

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Scheduler(corePoolSize: Int = 1) {
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(corePoolSize)
    private var scheduledJob: ScheduledFuture<*>? = null

    fun scheduleJob(job: () -> Unit, scheduledAt: LocalDateTime, period: Long = 24, unit: TimeUnit = TimeUnit.HOURS) {
        val now = LocalDateTime.now()

        val delay = Duration.between(
            now,
            scheduledAt
        ).toHours()

        scheduledJob = executor.scheduleAtFixedRate(
            job,
            delay,
            period,
            unit
        )
    }
}