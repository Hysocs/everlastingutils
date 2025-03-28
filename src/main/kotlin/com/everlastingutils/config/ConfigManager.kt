package com.everlastingutils.config

import com.google.gson.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.reflect.KClass
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface ConfigData {
    val version: String
    val configId: String
}

data class ConfigMetadata(
    val headerComments: List<String> = emptyList(),
    val footerComments: List<String> = emptyList(),
    val sectionComments: Map<String, String> = emptyMap(),
    val includeTimestamp: Boolean = true,
    val includeVersion: Boolean = true,
    val watcherSettings: WatcherSettings = WatcherSettings()
) {
    companion object {
        fun default(configId: String) = ConfigMetadata(
            headerComments = listOf(
                "Configuration file for $configId",
                "This file is automatically managed - custom comments will be preserved"
            )
        )
    }
}

class ConfigMigrationResult<T : ConfigData>(
    val migratedConfig: T,
    val migratedFields: Set<String>,
    val skippedFields: Set<String>
)

class JsoncParser {
    companion object {
        private val TRAILING_COMMA = """,(\s*[}\]])""".toRegex()
    }

    fun parseWithComments(content: String): Pair<String, Map<String, String>> {
        val comments = mutableMapOf<String, String>()
        val jsonContent = removeComments(content, comments)
        val processedContent = jsonContent.replace(TRAILING_COMMA, "$1")
        return processedContent.trim() to comments
    }

    private fun removeComments(content: String, comments: MutableMap<String, String>): String {
        val result = StringBuilder()
        var i = 0
        var inString = false
        var commentStart = -1
        var propertyBeforeComment = ""

        while (i < content.length) {
            val c = content[i]

            if (inString) {
                result.append(c)
                if (c == '"' && !isEscaped(content, i)) {
                    inString = false
                }
                i++
            } else {
                when {
                    c == '"' && !isEscaped(content, i) -> {
                        inString = true
                        result.append(c)
                        i++
                    }
                    c == '/' && i + 1 < content.length && content[i + 1] == '/' -> {
                        // Start of single-line comment
                        commentStart = i
                        val commentEnd = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                        val commentText = content.substring(i + 2, commentEnd).trim()
                        if (propertyBeforeComment.isNotBlank()) {
                            comments[propertyBeforeComment] = commentText
                        }
                        i = commentEnd
                        if (i < content.length) {
                            result.append('\n')
                            i++
                        }
                    }
                    c == '/' && i + 1 < content.length && content[i + 1] == '*' -> {
                        // Start of multi-line comment
                        commentStart = i
                        val commentEnd = content.indexOf("*/", i + 2)
                        if (commentEnd != -1) {
                            i = commentEnd + 2
                        } else {
                            i = content.length
                        }
                    }
                    else -> {
                        result.append(c)
                        if (c == ':' && commentStart == -1) {
                            // Extract property name before this colon
                            val lineSoFar = result.toString().trim()
                            val lastProperty = lineSoFar.substringAfterLast('"', "")
                                .substringBeforeLast(':', "")
                                .trim().removeSurrounding("\"")
                            if (lastProperty.isNotBlank()) {
                                propertyBeforeComment = lastProperty
                            }
                        }
                        i++
                    }
                }
            }
        }
        return result.toString()
    }

    private fun isEscaped(content: String, index: Int): Boolean {
        var count = 0
        var j = index - 1
        while (j >= 0 && content[j] == '\\') {
            count++
            j--
        }
        return count % 2 != 0
    }

    // Keep extractConfigSection as is if still needed
    fun extractConfigSection(content: String): String? {
        val CONFIG_SECTION = """\/\*\s*CONFIG_SECTION\s*\*\/([\s\S]*?)(?:\/\*\s*END_CONFIG_SECTION\s*\*\/|$)""".toRegex()
        return CONFIG_SECTION.find(content)?.groupValues?.get(1)
    }
}

data class WatcherSettings(
    val enabled: Boolean = false,            // Whether the file watcher is enabled
    val debounceMs: Long = 1000,            // Debounce time in milliseconds
    val autoSaveEnabled: Boolean = false,    // Whether auto-save is enabled - now false by default
    val autoSaveIntervalMs: Long = 30_000   // Auto-save interval in milliseconds
)

class ConfigManager<T : ConfigData>(
    private val currentVersion: String,
    private val defaultConfig: T,
    private val configClass: KClass<T>,
    private val configDir: Path = Paths.get("config"),
    private val metadata: ConfigMetadata = ConfigMetadata.default(defaultConfig.configId),
    private val isTesting: Boolean = false  // Add testing flag
) {
    private val logger = LoggerFactory.getLogger("ConfigManager-${defaultConfig.configId}")
    private val configFile = configDir.resolve("${defaultConfig.configId}/config.jsonc")
    private val backupDir = configDir.resolve("${defaultConfig.configId}/backups")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Initialize all state-related properties
    private val configData = AtomicReference(defaultConfig)
    private val lastValidConfig = AtomicReference(defaultConfig)
    private val lastSavedHash = AtomicInteger(defaultConfig.hashCode())
    private val currentComments = ConcurrentHashMap<String, String>()
    private val lastModifiedTime = AtomicLong(0)
    private val lastFileSize = AtomicLong(0)

    private fun logIfNotTesting(level: String, message: String) {
        if (!isTesting) {
            when (level) {
                "error" -> logger.error(message)
                "warn" -> logger.warn(message)
                "info" -> logger.info(message)
                else -> logger.debug(message)
            }
        }
    }

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .setLenient()
        .serializeNulls()
        .create()

    private val parser = JsoncParser()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    private var watcherJob: Job? = null
    private var autoSaveJob: Job? = null

    init {
        runBlocking {
            initializeConfig()
            if (metadata.watcherSettings.enabled) {
                setupWatcher()
            }
            if (metadata.watcherSettings.autoSaveEnabled) {
                startAutoSave()
            }
        }
    }

    private suspend fun initializeConfig() = withContext(Dispatchers.IO) {
        Files.createDirectories(configFile.parent)
        Files.createDirectories(backupDir)

        if (!configFile.exists()) {
            saveConfig(defaultConfig)
        } else {
            reloadConfig()
        }
    }

    private fun hasFileChanged(): Boolean {
        return try {
            val attrs = Files.readAttributes(configFile, BasicFileAttributes::class.java)
            val currentModTime = attrs.lastModifiedTime().toMillis()
            val currentSize = attrs.size()

            val changed = currentModTime > lastModifiedTime.get() ||
                    currentSize != lastFileSize.get()

            if (changed) {
                lastModifiedTime.set(currentModTime)
                lastFileSize.set(currentSize)
            }

            changed
        } catch (e: Exception) {
            logger.error("Error checking file changes: ${e.message}")
            false
        }
    }

    private fun setupWatcher() {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            try {
                val watcher = FileSystems.getDefault().newWatchService()
                configFile.parent.register(watcher, ENTRY_MODIFY)

                while (isActive) {
                    val key = withContext(Dispatchers.IO) { watcher.take() }
                    for (event in key.pollEvents()) {
                        if (event.context() == configFile.fileName) {
                            delay(metadata.watcherSettings.debounceMs)
                            if (hasFileChanged()) {
                                reloadConfig()
                            }
                        }
                    }
                    key.reset()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Config watcher stopped: ${e.message}")
                }
            }
        }
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            while (isActive) {
                delay(metadata.watcherSettings.autoSaveIntervalMs)
                if (hasChanges()) {
                    saveConfig(configData.get())
                }
            }
        }
    }

    private suspend fun readConfigFile(): String = withContext(Dispatchers.IO) {
        Files.newBufferedReader(configFile, Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }

    private suspend fun writeConfigFile(content: String) = withContext(Dispatchers.IO) {
        Files.newBufferedWriter(configFile, Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { writer ->
            writer.write(content)
            writer.flush()
        }
    }

    private fun hasChanges(): Boolean =
        configData.get().hashCode() != lastSavedHash.get()

    suspend fun reloadConfig() = withContext(Dispatchers.IO) {
        if (!configFile.exists()) {
            saveConfig(defaultConfig)
            return@withContext
        }

        if (!hasFileChanged()) {
            return@withContext
        }

        try {
            val content = readConfigFile()
            if (content.isBlank()) {
                handleConfigError("empty_file")
                return@withContext
            }

            val (jsonContent, comments) = parser.parseWithComments(content)
            if (jsonContent.isBlank()) {
                handleConfigError("parse_error")
                return@withContext
            }

            try {
                val parsedConfig = gson.fromJson(jsonContent, configClass.java)
                currentComments.clear()
                currentComments.putAll(comments)

                if (parsedConfig.version != currentVersion) {
                    handleVersionMismatch(parsedConfig)
                } else {
                    configData.set(parsedConfig)
                    lastValidConfig.set(parsedConfig)
                    lastSavedHash.set(parsedConfig.hashCode())
                }
            } catch (e: JsonSyntaxException) {
                handleConfigError("json_error")
            }
        } catch (e: Exception) {
            if (configFile.exists()) {
                handleConfigError("reload_error")
            }
        }
    }

    private suspend fun handleConfigError(reason: String) {
        createBackup(reason)

        restoreFromBackup()?.let { restoredConfig ->
            configData.set(restoredConfig)
            lastValidConfig.set(restoredConfig)
            saveConfig(restoredConfig)
            return
        }

        val lastKnownGood = lastValidConfig.get()
        if (lastKnownGood != defaultConfig) {
            configData.set(lastKnownGood)
            saveConfig(lastKnownGood)
        } else {
            configData.set(defaultConfig)
            saveConfig(defaultConfig)
        }
    }


    private suspend fun handleVersionMismatch(oldConfig: T) {
        createBackup("pre_migration")
        val currentConfig = configData.get()
        val mergedConfig = mergeConfigs(
            mergeConfigs(oldConfig, currentConfig),
            defaultConfig
        )

        configData.set(mergedConfig)
        lastValidConfig.set(mergedConfig)
        saveConfig(mergedConfig)
    }

    private fun mergeConfigs(oldConfig: T, newConfig: T): T {
        val oldConfigJson = gson.toJsonTree(oldConfig).asJsonObject
        val newConfigJson = gson.toJsonTree(newConfig).asJsonObject

        oldConfigJson.entrySet().forEach { (key, oldValue) ->
            if (key != "version" && newConfigJson.has(key)) {
                newConfigJson.add(key, oldValue)
            }
        }

        newConfigJson.addProperty("version", currentVersion)
        return gson.fromJson(newConfigJson, configClass.java)
    }

    private suspend fun createBackup(reason: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val backupFile = backupDir.resolve("${defaultConfig.configId}_${reason}_$timestamp.jsonc")
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING)

            Files.list(backupDir).use { stream ->
                stream.filter { it.toString().endsWith(".jsonc") }
                    .sorted(Comparator.reverseOrder())
                    .skip(50)
                    .forEach { Files.delete(it) }
            }
        } catch (e: Exception) {
            logger.error("Backup failed: ${e.message}")
        }
    }

    private suspend fun restoreFromBackup(): T? = withContext(Dispatchers.IO) {
        try {
            if (!Files.exists(backupDir) || Files.list(backupDir).use { it.count() } == 0L) {
                logIfNotTesting("info", "No backups found in directory: ${backupDir}")
                return@withContext null
            }

            Files.list(backupDir).use { stream ->
                val latestBackup = stream
                    .filter { it.toString().endsWith(".jsonc") }
                    .max { a, b ->
                        a.fileName.toString().compareTo(b.fileName.toString())
                    }
                    .orElse(null)

                latestBackup?.let {
                    try {
                        val content = Files.newBufferedReader(it, Charsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                        val (jsonContent, _) = parser.parseWithComments(content)
                        gson.fromJson(jsonContent, configClass.java)
                    } catch (e: Exception) {
                        logIfNotTesting("warn", "Failed to restore from backup: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logIfNotTesting("error", "Failed to restore from backup: ${e.message}")
            null
        }
    }

    suspend fun saveConfig(config: T) = withContext(Dispatchers.IO) {
        try {
            val content = buildConfigContent(config)
            writeConfigFile(content)
            lastSavedHash.set(config.hashCode())
        } catch (e: Exception) {
            logger.error("Save failed: ${e.message}")
        }
    }

    private fun buildConfigContent(config: T): String = buildString {
        append(buildHeaderComment())

        // Let Gson handle the JSON formatting
        val jsonElement = JsonParser().parse(gson.toJson(config))
        val jsonContent = gson.toJson(jsonElement)

        // Split the formatted JSON into lines
        val lines = jsonContent.lines()

        // Process each line and add comments where needed
        lines.forEachIndexed { index, line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty()) {
                // Extract the property name if this line contains one
                val propertyName = trimmedLine.substringBefore(":").trim().removeSurrounding("\"")
                val currentPath = propertyName // You might need to build the full path here

                // Add section comments if they exist
                metadata.sectionComments[currentPath]?.let { comment ->
                    append(line.substringBefore(trimmedLine))  // Preserve indentation
                    append("// $comment\n")
                }

                // Add inline comments if they exist
                currentComments[currentPath]?.let { comment ->
                    append(line.substringBefore(trimmedLine))  // Preserve indentation
                    append("// $comment\n")
                }

                append(line)
                if (index < lines.size - 1) append("\n")
            }
        }

        append(buildFooterComment())
    }


    private fun buildHeaderComment(): String = buildString {
        append("/* CONFIG_SECTION\n")
        metadata.headerComments.forEach { append(" * $it\n") }
        if (metadata.includeVersion) append(" * Version: $currentVersion\n")
        if (metadata.includeTimestamp) append(" * Last updated: ${LocalDateTime.now()}\n")
        append(" */\n")
    }

    private fun buildFooterComment(): String = buildString {
        append("\n/*\n")
        metadata.footerComments.forEach { append(" * $it\n") }
        append(" * END_CONFIG_SECTION\n */")
    }

    fun getCurrentConfig(): T = configData.get()

    suspend fun reloadManually() {
        reloadConfig()
    }

    fun enableWatcher() {
        if (!metadata.watcherSettings.enabled) {
            metadata.copy(watcherSettings = metadata.watcherSettings.copy(enabled = true))
            setupWatcher()
        }
    }

    fun disableWatcher() {
        watcherJob?.cancel()
        watcherJob = null
    }

    fun enableAutoSave() {
        if (!metadata.watcherSettings.autoSaveEnabled) {
            metadata.copy(watcherSettings = metadata.watcherSettings.copy(autoSaveEnabled = true))
            startAutoSave()
        }
    }

    fun disableAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    fun cleanup() {
        watcherJob?.cancel()
        autoSaveJob?.cancel()
        scope.cancel()
    }
}