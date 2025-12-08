package com.everlastingutils.config

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator

data class SimulationConfig(
    override var version: String = "1.0",
    override var configId: String = "everlasting_test_sim",
    var serverName: String = "Default Server",
    var maxPlayers: Int = 20
) : ConfigData

object ConfigTester {
    private val baseConfigDir: Path = Paths.get("config")
    private val activeTestDir: Path = baseConfigDir.resolve("everlasting_test_sim")
    private val expectedFile: Path = activeTestDir.resolve("config.jsonc")

    fun runAllTests(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        try {
            if (!Files.exists(baseConfigDir)) Files.createDirectories(baseConfigDir)
            cleanup()
            results["1. Init & File Creation"] = testInitialization()
            results["2. Load User Edits"] = testUserEditingFile()
            results["3. Migration"] = testMigration()
        } catch (e: Exception) {
            e.printStackTrace()
            results["CRITICAL FAILURE"] = false
        } finally {
            cleanup()
        }
        return results
    }

    private fun testInitialization(): Boolean = runBlocking {
        val manager = ConfigManager(
            currentVersion = "1.0",
            defaultConfig = SimulationConfig(),
            configClass = SimulationConfig::class,
            configDir = baseConfigDir,
            isTesting = true
        )
        val fileCreated = Files.exists(expectedFile)
        val correctValue = manager.getCurrentConfig().serverName == "Default Server"
        manager.cleanup()
        return@runBlocking fileCreated && correctValue
    }

    private fun testUserEditingFile(): Boolean = runBlocking {
        cleanup()
        Files.createDirectories(expectedFile.parent)
        Files.writeString(expectedFile, """
            {
                "version": "1.0",
                "configId": "everlasting_test_sim",
                "serverName": "My Awesome Server",
                "maxPlayers": 999
            }
        """.trimIndent())

        val manager = ConfigManager(
            currentVersion = "1.0",
            defaultConfig = SimulationConfig(),
            configClass = SimulationConfig::class,
            configDir = baseConfigDir,
            isTesting = true
        )
        val config = manager.getCurrentConfig()
        val success = config.serverName == "My Awesome Server" && config.maxPlayers == 999
        manager.cleanup()
        return@runBlocking success
    }

    private fun testMigration(): Boolean = runBlocking {
        cleanup()
        Files.createDirectories(expectedFile.parent)
        Files.writeString(expectedFile, """
            {
                "version": "0.5", 
                "configId": "everlasting_test_sim",
                "serverName": "Legacy Server",
                "maxPlayers": 50
            }
        """.trimIndent())

        val manager = ConfigManager(
            currentVersion = "1.0",
            defaultConfig = SimulationConfig(),
            configClass = SimulationConfig::class,
            configDir = baseConfigDir,
            isTesting = true
        )
        val config = manager.getCurrentConfig()
        val versionUpdated = config.version == "1.0"
        val valuesKept = config.serverName == "Legacy Server"
        manager.cleanup()
        return@runBlocking versionUpdated && valuesKept
    }

    private fun cleanup() {
        try {
            if (Files.exists(activeTestDir)) {
                Files.walk(activeTestDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (_: Exception) {}
    }
}