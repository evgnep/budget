package su.nepom.budget.utils

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

private val mapper = YAMLMapper().registerKotlinModule()

private class Dummy

fun <T> readObjectFromYaml(yaml: String, cls: Class<T>): T {
    return mapper.readValue(yaml, cls)
}

inline fun <reified T> readObjectFromYaml(yaml: String): T = readObjectFromYaml(yaml, T::class.java)

fun <T> readObjectFromYamlResourceFile(file: String, cls: Class<T>): T {
    val yaml = Dummy::class.java.getResourceAsStream(file)?.use {
        String(it.readAllBytes())
    } ?: throw IllegalArgumentException("Unknown file: $file")
    return readObjectFromYaml(yaml, cls)
}

inline fun <reified T> readObjectFromYamlResourceFile(file: String): T =
    readObjectFromYamlResourceFile(file, T::class.java)