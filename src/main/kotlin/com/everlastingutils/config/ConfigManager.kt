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
import java.util.concurrent.atomic.AtomicLong

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

    fun extractConfigSection(content: String): String? {
        val CONFIG_SECTION = """\/\*\s*CONFIG_SECTION\s*\*\/([\s\S]*?)(?:\/\*\s*END_CONFIG_SECTION\s*\*\/|$)""".toRegex()
        return CONFIG_SECTION.find(content)?.groupValues?.get(1)
    }
}

data class WatcherSettings(
    val enabled: Boolean = false,
    val debounceMs: Long = 1000,
    val autoSaveEnabled: Boolean = false,
    val autoSaveIntervalMs: Long = 30_000
)

private data class ConfigContainer<T : ConfigData>(
    val fileName: String,
    val filePath: Path,
    val configClass: KClass<T>,
    var currentData: T,
    var lastValidData: T,
    val metadata: ConfigMetadata,
    val currentComments: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    val lastModifiedTime: AtomicLong = AtomicLong(0),
    val lastFileSize: AtomicLong = AtomicLong(0),
    var lastSavedHash: Int
)

class ConfigManager<T : ConfigData>(
    private val currentVersion: String,
    private val defaultConfig: T,
    private val configClass: KClass<T>,
    private val configDir: Path = Paths.get("config"),
    private val metadata: ConfigMetadata = ConfigMetadata.default(defaultConfig.configId),
    private val isTesting: Boolean = false
) {
    private val logger = LoggerFactory.getLogger("ConfigManager-${defaultConfig.configId}")
    private val backupDir = configDir.resolve("${defaultConfig.configId}/backups")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .setLenient()
        .serializeNulls()
        .create()

    private val parser = JsoncParser()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    private val configs = ConcurrentHashMap<String, ConfigContainer<*>>()
    private val mainConfigFileName = "${defaultConfig.configId}/config.jsonc"

    private var watcherJob: Job? = null
    private var autoSaveJob: Job? = null

    init {
        runBlocking {
            Files.createDirectories(configDir.resolve(defaultConfig.configId))
            Files.createDirectories(backupDir)

            registerConfigInternal(
                fileName = mainConfigFileName,
                configClass = configClass,
                defaultConfig = defaultConfig,
                meta = metadata
            )

            if (metadata.watcherSettings.enabled) {
                setupWatcher()
            }
            if (metadata.watcherSettings.autoSaveEnabled) {
                startAutoSave()
            }
        }
    }

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

    suspend fun <S : ConfigData> registerSecondaryConfig(
        fileName: String,
        configClass: KClass<S>,
        defaultConfig: S,
        fileMetadata: ConfigMetadata
    ) {
        registerConfigInternal(fileName, configClass, defaultConfig, fileMetadata)
    }

    fun getCurrentConfig(): T {
        @Suppress("UNCHECKED_CAST")
        return configs[mainConfigFileName]?.currentData as? T ?: defaultConfig
    }

    fun <S : ConfigData> getSecondaryConfig(fileName: String): S? {
        @Suppress("UNCHECKED_CAST")
        return configs[fileName]?.currentData as? S
    }

    private suspend fun <S : ConfigData> registerConfigInternal(
        fileName: String,
        configClass: KClass<S>,
        defaultConfig: S,
        meta: ConfigMetadata
    ) {
        val file = configDir.resolve("${defaultConfig.configId}/$fileName")
        if (file.parent != null) Files.createDirectories(file.parent)

        val container = ConfigContainer(
            fileName = fileName,
            filePath = file,
            configClass = configClass,
            currentData = defaultConfig,
            lastValidData = defaultConfig,
            metadata = meta,
            lastSavedHash = defaultConfig.hashCode()
        )

        configs[fileName] = container

        if (!file.exists()) {
            saveConfigContainer(container, defaultConfig)
        } else {
            reloadSingleConfig(container)
        }
    }

    private suspend fun reloadSingleConfig(container: ConfigContainer<*>) = withContext(Dispatchers.IO) {
        if (!container.filePath.exists()) return@withContext

        try {
            val attrs = Files.readAttributes(container.filePath, BasicFileAttributes::class.java)
            val currentMod = attrs.lastModifiedTime().toMillis()
            val currentSize = attrs.size()

            if (currentMod <= container.lastModifiedTime.get() && currentSize == container.lastFileSize.get()) {
                return@withContext
            }

            container.lastModifiedTime.set(currentMod)
            container.lastFileSize.set(currentSize)
        } catch (e: Exception) {
            logIfNotTesting("error", "Error reading file attributes for ${container.fileName}: ${e.message}")
            return@withContext
        }

        try {
            val content = Files.readString(container.filePath, Charsets.UTF_8)
            if (content.isBlank()) {
                logIfNotTesting("warn", "File ${container.fileName} is empty. Ignoring.")
                return@withContext
            }

            @Suppress("UNCHECKED_CAST")
            val typedContainer = container as ConfigContainer<ConfigData>
            processReload(typedContainer, content)

        } catch (e: Exception) {
            logIfNotTesting("error", "Unexpected error reloading ${container.fileName}: ${e.message}")
        }
    }

    private suspend fun <S : ConfigData> processReload(container: ConfigContainer<S>, content: String) {
        val (jsonContent, comments) = parser.parseWithComments(content)
        try {
            val parsed = gson.fromJson(jsonContent, container.configClass.java)

            container.currentComments.clear()
            container.currentComments.putAll(comments)

            if (parsed.version != currentVersion) {
                logIfNotTesting("info", "Version mismatch in ${container.fileName}. Migrating...")
                handleVersionMismatch(container, parsed)
            } else {
                updateContainerData(container, parsed)
            }
        } catch (e: JsonSyntaxException) {
            logIfNotTesting("error", "SYNTAX ERROR in ${container.fileName}: ${e.message}")
            logIfNotTesting("error", "The configuration was NOT updated. Please fix the file syntax.")
        }
    }

    private fun <S : ConfigData> updateContainerData(container: ConfigContainer<S>, newData: S) {
        container.currentData = newData
        container.lastValidData = newData
        container.lastSavedHash = newData.hashCode()
    }

    private suspend fun <S : ConfigData> handleVersionMismatch(container: ConfigContainer<S>, oldData: S) {
        createBackup(container, "pre_migration")

        val merged = mergeConfigs(oldData, container.currentData, container.configClass.java)

        updateContainerData(container, merged)
        saveConfigContainer(container, merged)
    }

    private fun <S : ConfigData> mergeConfigs(oldConfig: S, newConfig: S, clazz: Class<S>): S {
        val oldJson = gson.toJsonTree(oldConfig).asJsonObject
        val newJson = gson.toJsonTree(newConfig).asJsonObject

        deepMerge(oldJson, newJson)

        newJson.addProperty("version", currentVersion)
        return gson.fromJson(newJson, clazz)
    }

    private fun deepMerge(source: JsonObject, target: JsonObject) {
        for ((key, sourceElement) in source.entrySet()) {
            if (key == "version") continue

            if (target.has(key)) {
                val targetElement = target.get(key)
                if (sourceElement.isJsonObject && targetElement.isJsonObject) {
                    deepMerge(sourceElement.asJsonObject, targetElement.asJsonObject)
                } else {
                    target.add(key, sourceElement)
                }
            }
        }
    }

    private suspend fun createBackup(container: ConfigContainer<*>, reason: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val safeName = container.fileName.replace("/", "_").replace("\\", "_")
            val backupFile = backupDir.resolve("${safeName}_${reason}_$timestamp.jsonc")

            if (container.filePath.exists()) {
                Files.copy(container.filePath, backupFile, StandardCopyOption.REPLACE_EXISTING)
            }

            Files.list(backupDir).use { stream ->
                stream.filter { it.toString().endsWith(".jsonc") }
                    .sorted(Comparator.reverseOrder())
                    .skip(50)
                    .forEach { Files.delete(it) }
            }
        } catch (e: Exception) {
            logIfNotTesting("error", "Backup failed for ${container.fileName}: ${e.message}")
        }
    }

    private fun setupWatcher() {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            try {
                val watcher = FileSystems.getDefault().newWatchService()
                val mainDir = configDir.resolve(defaultConfig.configId)
                val watchedKeys = mutableMapOf<WatchKey, Path>()

                if (mainDir.exists()) {
                    watchedKeys[mainDir.register(watcher, ENTRY_MODIFY)] = mainDir
                }

                configs.values.forEach { container ->
                    val parent = container.filePath.parent
                    if (parent != null && parent.exists()) {
                        if (watchedKeys.values.none { it == parent }) {
                            watchedKeys[parent.register(watcher, ENTRY_MODIFY)] = parent
                        }
                    }
                }

                while (isActive) {
                    val key = withContext(Dispatchers.IO) { watcher.take() }
                    val dirPath = watchedKeys[key]

                    if (dirPath != null) {
                        for (event in key.pollEvents()) {
                            val changedPath = dirPath.resolve(event.context() as Path)

                            val matchedContainer = configs.values.find {
                                it.filePath.toAbsolutePath() == changedPath.toAbsolutePath()
                            }

                            if (matchedContainer != null) {
                                delay(metadata.watcherSettings.debounceMs)
                                reloadSingleConfig(matchedContainer)
                            }
                        }
                    }
                    key.reset()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) logIfNotTesting("error", "Watcher stopped: ${e.message}")
            }
        }
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            while (isActive) {
                delay(metadata.watcherSettings.autoSaveIntervalMs)
                configs.values.forEach { container ->
                    if (container.currentData.hashCode() != container.lastSavedHash) {
                        @Suppress("UNCHECKED_CAST")
                        val typedContainer = container as ConfigContainer<ConfigData>
                        saveConfigContainer(typedContainer, typedContainer.currentData)
                    }
                }
            }
        }
    }

    suspend fun saveConfig(config: T) {
        @Suppress("UNCHECKED_CAST")
        val container = configs[mainConfigFileName] as? ConfigContainer<T>
        if (container != null) {
            saveConfigContainer(container, config)
        }
    }

    private suspend fun <S : ConfigData> saveConfigContainer(container: ConfigContainer<S>, data: S) = withContext(Dispatchers.IO) {
        try {
            val content = buildConfigContent(data, container.metadata, container.currentComments)
            Files.writeString(container.filePath, content, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            container.lastSavedHash = data.hashCode()

            val attrs = Files.readAttributes(container.filePath, BasicFileAttributes::class.java)
            container.lastModifiedTime.set(attrs.lastModifiedTime().toMillis())
            container.lastFileSize.set(attrs.size())

        } catch (e: Exception) {
            logIfNotTesting("error", "Failed to save ${container.fileName}: ${e.message}")
        }
    }

    private fun buildConfigContent(
        config: Any,
        meta: ConfigMetadata,
        comments: Map<String, String>
    ): String = buildString {
        append("/* CONFIG_SECTION\n")
        meta.headerComments.forEach { append(" * $it\n") }
        if (meta.includeVersion) append(" * Version: ${currentVersion}\n")
        if (meta.includeTimestamp) append(" * Last updated: ${LocalDateTime.now()}\n")
        append(" */\n")

        val jsonElement = JsonParser().parse(gson.toJson(config))
        val jsonContent = gson.toJson(jsonElement)
        val lines = jsonContent.lines()

        lines.forEachIndexed { index, line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty()) {
                val propertyName = trimmedLine.substringBefore(":").trim().removeSurrounding("\"")

                meta.sectionComments[propertyName]?.let {
                    append(line.substringBefore(trimmedLine))
                    append("// $it\n")
                }
                comments[propertyName]?.let {
                    append(line.substringBefore(trimmedLine))
                    append("// $it\n")
                }

                append(line)
                if (index < lines.size - 1) append("\n")
            }
        }

        append("\n/*\n")
        meta.footerComments.forEach { append(" * $it\n") }
        append(" * END_CONFIG_SECTION\n */")
    }

    suspend fun reloadManually() {
        configs.values.forEach { reloadSingleConfig(it) }
    }

    suspend fun reloadConfig() {
        reloadManually()
    }

    fun enableWatcher() {
        if (!metadata.watcherSettings.enabled) {
            setupWatcher()
        }
    }

    fun disableWatcher() {
        watcherJob?.cancel()
        watcherJob = null
    }

    fun enableAutoSave() {
        if (!metadata.watcherSettings.autoSaveEnabled) {
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