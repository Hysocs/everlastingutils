package com.everlastingutils.scheduling

import com.everlastingutils.utils.logDebug
import com.google.common.util.concurrent.ThreadFactoryBuilder
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min


object SchedulerManager {
    private val logger = LoggerFactory.getLogger("EverlastingUtils-SchedulerManager")

    private val SHARED_POOL_SIZE = min(Runtime.getRuntime().availableProcessors().coerceAtLeast(4), 8)

    // Use AtomicBoolean for thread-safe operations
    private val isShuttingDown = AtomicBoolean(false)

    // Track if we need to recreate the pool
    @Volatile
    private var needsRecreation = false

    private var sharedScheduler: ScheduledExecutorService = createSchedulerPool()

    private val activeTasks = ConcurrentHashMap<String, MutableSet<ScheduledFuture<*>>>()
    private val taskGroups = ConcurrentHashMap<String, String>()

    // Lock for scheduler recreation
    private val recreationLock = Any()

    private fun createSchedulerPool(): ScheduledExecutorService {
        return (Executors.newScheduledThreadPool(
            SHARED_POOL_SIZE,
            ThreadFactoryBuilder()
                .setNameFormat("EverlastingUtils-AsyncWorker-%d")
                .setDaemon(true)
                .build()
        ) as ScheduledThreadPoolExecutor).apply {
            removeOnCancelPolicy = true
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                if (isShuttingDown.compareAndSet(false, true)) {
                    logger.info("JVM shutdown detected - cancelling tasks...")
                    cancelAllTasksInternal()
                }
            } catch (_: Exception) {
            }
        }, "EverlastingUtils-ShutdownHook"))
    }

    /** Call this from your mod's server stopping event */
    @JvmStatic
    fun onServerStopping(server: MinecraftServer) {
        logger.info("Server stopping → cancelling all scheduled tasks...")
        shutdownAll()
    }

    /** Call this from your mod's server starting event to reset state */
    @JvmStatic
    fun onServerStarting(server: MinecraftServer) {
        synchronized(recreationLock) {
            logger.info("Server starting - resetting scheduler state")

            // Always reset shutdown flag first
            isShuttingDown.set(false)

            if (needsRecreation || sharedScheduler.isShutdown || sharedScheduler.isTerminated) {
                logger.info("Recreating scheduler pool for new server instance...")
                sharedScheduler = createSchedulerPool()
                needsRecreation = false
            }

            logDebug("[SCHEDULER] Ready for server start", "everlastingutils")
        }
    }

    @JvmStatic
    fun createScheduler(id: String): ScheduledExecutorService {
        ensureSchedulerAvailable()
        logDebug("[SCHEDULER] Created scheduler: $id", "everlastingutils")
        return SafeDelegatingScheduler(id, sharedScheduler)
    }

    @JvmStatic
    fun scheduleAtFixedRate(
        id: String,
        server: MinecraftServer,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: () -> Unit
    ): ScheduledFuture<*> {
        require(initialDelay >= 0) { "initialDelay must be non-negative" }
        require(period > 0) { "period must be positive" }

        // Check shutdown state and ensure scheduler is available
        if (!ensureSchedulerAvailable()) {
            logger.warn("Cannot schedule task '$id' - scheduler is shutting down")
            return CancelledFuture()
        }

        val serverRef = WeakReference(server)
        val futureHolder = arrayOfNulls<ScheduledFuture<*>>(1)

        val future = try {
            sharedScheduler.scheduleWithFixedDelay({
                val srv = serverRef.get()

                // Check all conditions before executing
                if (srv == null || !srv.isRunning || srv.isStopped || isShuttingDown.get()) {
                    futureHolder[0]?.let { untrackAndCancel(id, it) }
                    return@scheduleWithFixedDelay
                }

                try {
                    srv.execute {
                        if (srv.isRunning && !srv.isStopped && !isShuttingDown.get()) {
                            try {
                                task()
                            } catch (t: Throwable) {
                                logger.error("Task '$id' threw exception - cancelling", t)
                                futureHolder[0]?.let { untrackAndCancel(id, it) }
                            }
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    futureHolder[0]?.let { untrackAndCancel(id, it) }
                }
            }, initialDelay, period, unit)
        } catch (e: RejectedExecutionException) {
            logger.error("Failed to schedule task '$id' - executor rejected", e)
            return CancelledFuture()
        }

        // Double-check shutdown state after scheduling
        if (isShuttingDown.get()) {
            future.cancel(false)
            return CancelledFuture()
        }

        futureHolder[0] = future
        trackTask(id, future)
        return future
    }

    @JvmStatic
    fun schedule(
        id: String,
        server: MinecraftServer,
        delay: Long,
        unit: TimeUnit,
        task: () -> Unit
    ): ScheduledFuture<*> {
        require(delay >= 0) { "delay must be non-negative" }

        if (!ensureSchedulerAvailable()) {
            logger.warn("Cannot schedule task '$id' - scheduler is shutting down")
            return CancelledFuture()
        }

        val serverRef = WeakReference(server)
        val futureHolder = arrayOfNulls<ScheduledFuture<*>>(1)

        val future = try {
            sharedScheduler.schedule({
                val srv = serverRef.get()

                if (srv == null || !srv.isRunning || srv.isStopped || isShuttingDown.get()) {
                    futureHolder[0]?.let { untrackTask(id, it) }
                    return@schedule
                }

                try {
                    srv.execute {
                        if (srv.isRunning && !srv.isStopped && !isShuttingDown.get()) {
                            try {
                                task()
                            } catch (t: Throwable) {
                                logger.error("Task '$id' threw exception", t)
                            } finally {
                                futureHolder[0]?.let { untrackTask(id, it) }
                            }
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    futureHolder[0]?.let { untrackTask(id, it) }
                }
            }, delay, unit)
        } catch (e: RejectedExecutionException) {
            logger.error("Failed to schedule task '$id' - executor rejected", e)
            return CancelledFuture()
        }

        if (isShuttingDown.get()) {
            future.cancel(false)
            return CancelledFuture()
        }

        futureHolder[0] = future
        trackTask(id, future)
        return future
    }

    @JvmStatic
    fun shutdown(id: String) {
        cancelTasks(id, true)
        activeTasks.remove(id)
        taskGroups.entries.removeIf { it.value == id }
        logDebug("[SCHEDULER] Shutdown scheduler: $id", "everlastingutils")
    }

    @JvmStatic
    fun shutdownAll() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            // Already shutting down
            return
        }

        cancelAllTasksInternal()

        // Shutdown the pool
        sharedScheduler.shutdown()
        try {
            if (!sharedScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                sharedScheduler.shutdownNow()
                if (!sharedScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.warn("Some tasks did not terminate")
                }
            }
        } catch (e: InterruptedException) {
            sharedScheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }

        needsRecreation = true
        logDebug("[SCHEDULER] Global shutdown complete", "everlastingutils")
    }

    @JvmStatic
    fun cancelTasks(id: String, mayInterruptIfRunning: Boolean = false) {
        activeTasks.remove(id)?.forEach { future ->
            try {
                future.cancel(mayInterruptIfRunning)
            } catch (_: Exception) {}
        }

        taskGroups.entries.removeIf { it.key == id }
    }

    @JvmStatic
    fun getStats(): Map<String, Int> {
        return activeTasks.mapValues { (_, taskSet) ->
            try {
                taskSet.toList().count { !it.isDone && !it.isCancelled }
            } catch (e: ConcurrentModificationException) {
                0
            }
        }
    }

    @JvmStatic
    fun getSkippedExecutionCount(id: String): Long {
        return 0L
    }

    @JvmStatic
    fun assignTaskGroup(taskId: String, groupId: String) {
        taskGroups[taskId] = groupId
    }

    @JvmStatic
    fun cancelTaskGroup(groupId: String) {
        val tasksInGroup = taskGroups.entries
            .filter { it.value == groupId }
            .map { it.key }
            .toList()

        tasksInGroup.forEach { taskId ->
            cancelTasks(taskId, true)
        }
    }

    private fun ensureSchedulerAvailable(): Boolean {
        synchronized(recreationLock) {
            if (sharedScheduler.isShutdown || sharedScheduler.isTerminated) {
                if (!isShuttingDown.get()) {
                    logger.warn("Scheduler was shutdown but not in shutdown state - recreating...")
                    sharedScheduler = createSchedulerPool()
                    needsRecreation = false
                    return true
                }
                return false
            }
            return !isShuttingDown.get()
        }
    }

    private fun cancelAllTasksInternal() {
        activeTasks.values.forEach { taskSet ->
            taskSet.forEach { future ->
                try {
                    future.cancel(false)
                } catch (_: Exception) {}
            }
        }

        activeTasks.clear()
        taskGroups.clear()
    }

    private fun trackTask(id: String, future: ScheduledFuture<*>) {
        activeTasks.compute(id) { _, existing ->
            val taskSet = existing ?: ConcurrentHashMap.newKeySet()
            taskSet.add(future)
            taskSet
        }
    }

    private fun untrackTask(id: String, future: ScheduledFuture<*>?) {
        if (future != null) {
            activeTasks.computeIfPresent(id) { _, taskSet ->
                taskSet.remove(future)
                if (taskSet.isEmpty()) null else taskSet
            }
        } else {
            activeTasks.computeIfPresent(id) { _, taskSet ->
                taskSet.removeIf { it.isDone || it.isCancelled }
                if (taskSet.isEmpty()) null else taskSet
            }
        }
    }

    private fun untrackAndCancel(id: String, future: ScheduledFuture<*>) {
        try {
            future.cancel(false)
        } catch (_: Exception) {}
        untrackTask(id, future)
    }

    private class CancelledFuture : ScheduledFuture<Any?> {
        override fun cancel(mayInterruptIfRunning: Boolean) = true
        override fun isCancelled() = true
        override fun isDone() = true
        override fun get(): Any? = throw CancellationException()
        override fun get(timeout: Long, unit: TimeUnit): Any? = throw CancellationException()
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: Delayed) = -1
    }

    private class SafeDelegatingScheduler(
        private val id: String,
        private val delegate: ScheduledExecutorService
    ) : ScheduledExecutorService by delegate {
        override fun shutdown() = SchedulerManager.shutdown(id)

        override fun shutdownNow(): MutableList<Runnable> {
            SchedulerManager.cancelTasks(id, true)
            SchedulerManager.shutdown(id)
            return mutableListOf()
        }
    }
}