package com.dishplanner.data

import kotlinx.serialization.json.Json
import java.io.File

/** 读/写单个菜谱 JSON —— WorkManager 之外的地方也要用，避免复制粘贴 */
internal val sharedJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

internal fun File.readRecipeOrNull(): Recipe? = runCatching {
    sharedJson.decodeFromString(Recipe.serializer(), readText())
}.getOrNull()

internal fun File.writeRecipe(recipe: Recipe) {
    parentFile?.mkdirs()
    writeText(sharedJson.encodeToString(Recipe.serializer(), recipe))
}
