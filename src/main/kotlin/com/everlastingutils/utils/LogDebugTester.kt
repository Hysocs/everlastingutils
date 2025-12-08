package com.everlastingutils.utils

import kotlinx.coroutines.runBlocking

class LogDebugTester {
    companion object {
        private const val TEST_MOD_ID = "test_mod"

        private fun testInitialization(): Boolean = runBlocking {
            try {
                LogDebug.init(TEST_MOD_ID, true)
                val isEnabled = LogDebug.isDebugEnabledForMod(TEST_MOD_ID)
                if (!isEnabled) return@runBlocking false
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun testDebugToggle(): Boolean = runBlocking {
            try {
                LogDebug.setDebugEnabledForMod(TEST_MOD_ID, false)
                if (LogDebug.isDebugEnabledForMod(TEST_MOD_ID)) return@runBlocking false

                LogDebug.setDebugEnabledForMod(TEST_MOD_ID, true)
                if (!LogDebug.isDebugEnabledForMod(TEST_MOD_ID)) return@runBlocking false
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun testDebugOutput(): Boolean = runBlocking {
            try {
                LogDebug.init(TEST_MOD_ID, true)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun testMultipleMods(): Boolean = runBlocking {
            try {
                val secondModId = "second_test_mod"
                LogDebug.init(TEST_MOD_ID, true)
                LogDebug.init(secondModId, false)

                if (!LogDebug.isDebugEnabledForMod(TEST_MOD_ID)) return@runBlocking false
                if (LogDebug.isDebugEnabledForMod(secondModId)) return@runBlocking false

                true
            } catch (e: Exception) {
                false
            }
        }

        fun runAllTests(): Map<String, Boolean> = mapOf(
            "initialization" to testInitialization(),
            "debug_toggle" to testDebugToggle(),
            "debug_output" to testDebugOutput(),
            "multiple_mods" to testMultipleMods()
        )
    }
}