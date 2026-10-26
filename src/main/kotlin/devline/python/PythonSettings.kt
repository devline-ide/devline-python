package devline.python

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class PythonConfiguration(val interpreter: String = "", val directory: String = "",
    val arguments: List<String> = emptyList(), val environment: Map<String, String> = emptyMap())

/** Machine-local overrides, never written into the user's project or shared project metadata. */
internal class PythonSettings(private val file: Path) {
    private val gson = Gson()
    private fun document(): JsonObject = try {
        if (!Files.exists(file)) JsonObject().apply { add("projects", JsonObject()) }
        else {
            require(Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024)
            JsonParser.parseString(Files.readString(file)).asJsonObject.also { require(it["projects"].isJsonObject) }
        }
    } catch (_: Exception) { throw IdeExtensionException("PYTHON_SETTINGS_ERROR", "Could not read Python settings.") }
    private fun key(project: String) = MessageDigest.getInstance("SHA-256").digest(project.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun selected(document: JsonObject, project: String?) = project?.let { document.getAsJsonObject("projects")[key(it)] }
    private fun configuration(document: JsonObject, project: String?) = parse((selected(document, project) ?: document["global"])?.asJsonObject ?: JsonObject())
    fun read(project: String?): PythonConfiguration {
        val document = document()
        return configuration(document, project)
    }
    fun view(project: String?): Map<String, Any> {
        val document = document()
        return mapOf("settings" to configuration(document, project), "version" to digest(document), "inherited" to (project != null && selected(document, project) == null))
    }
    fun write(project: String?, value: JsonObject, version: String) = update(project, gson.toJsonTree(parse(value)).asJsonObject, version)
    fun reset(project: String?, version: String) = update(project, null, version)
    private fun update(project: String?, value: JsonObject?, version: String) {
        val document = document()
        if (digest(document) != version) throw IdeExtensionException("FILE_CONFLICT", "Python settings changed. Reload before saving.")
        val target = if (project == null) document else document.getAsJsonObject("projects")
        val key = project?.let(::key) ?: "global"
        if (value == null) target.remove(key) else target.add(key, value)
        require(document.getAsJsonObject("projects").size() <= 256)
        IdeExtensionFiles.writeAtomic(file, gson.toJson(document))
    }
    private fun digest(document: JsonObject) = MessageDigest.getInstance("SHA-256").digest(document.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun parse(value: JsonObject): PythonConfiguration {
        try {
            fun text(name: String) = value[name]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString } ?: ""
            val interpreter = text("interpreter"); val directory = text("directory")
            require(interpreter.length <= 4096 && directory.length <= 4096 && interpreter.none(Char::isISOControl) && '\u0000' !in directory)
            require(directory.isEmpty() || !Path.of(directory).isAbsolute && Path.of(directory).none { it.toString() == ".." })
            val arguments = value["arguments"]?.asJsonArray?.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.orEmpty()
            val environment = value["environment"]?.asJsonObject?.entrySet()?.associate { (key, item) ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && item.isJsonPrimitive && item.asJsonPrimitive.isString)
                key to item.asString
            }.orEmpty()
            require(arguments.size <= 64 && environment.size <= 64 && (arguments + environment.values).all { it.length <= 4096 && '\u0000' !in it })
            return PythonConfiguration(interpreter, directory, arguments, environment)
        } catch (_: Exception) { throw IdeExtensionException("PYTHON_INVALID_SETTINGS", "Check the interpreter, project directory, argument array and environment object.") }
    }
}
