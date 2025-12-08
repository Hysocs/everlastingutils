package com.everlastingutils.scheduling

import org.slf4j.LoggerFactory

class SchedulerTester {
    private val logger = LoggerFactory.getLogger("EverlastingUtils-SchedulerTester")

    fun testBasicCreation(): Boolean {
        return try {
            val schedulerId = "everlastingutils-test-creation"
            SchedulerManager.createScheduler(schedulerId)
            try {
                SchedulerManager.shutdown(schedulerId)
            } catch (e: Exception) {
            }
            true
        } catch (e: Exception) {
            logger.error("Error in basic creation test: ${e.message}")
            false
        }
    }

    fun testBasicShutdown(): Boolean {
        return try {
            val schedulerId = "everlastingutils-test-shutdown"
            try {
                SchedulerManager.createScheduler(schedulerId)
            } catch (e: Exception) {
                return false
            }
            SchedulerManager.shutdown(schedulerId)
            true
        } catch (e: Exception) {
            logger.error("Error in basic shutdown test: ${e.message}")
            false
        }
    }

    fun testBasicShutdownAll(): Boolean {
        return try {
            SchedulerManager.shutdownAll()
            true
        } catch (e: Exception) {
            logger.error("Error in basic shutdownAll test: ${e.message}")
            false
        }
    }

    companion object {
        fun runAllTests(): Map<String, Boolean> {
            val tester = SchedulerTester()
            return mapOf(
                "basicCreation" to tester.testBasicCreation(),
                "basicShutdown" to tester.testBasicShutdown(),
                "basicShutdownAll" to tester.testBasicShutdownAll()
            )
        }
    }
}