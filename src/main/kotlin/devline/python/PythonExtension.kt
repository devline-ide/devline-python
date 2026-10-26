package devline.python

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import devline.extensions.*
import java.nio.file.Files
import java.nio.file.Path

class PythonExtension : IdeExtensionProvider {
    override val manifest: IdeExtensionManifest = javaClass.getResourceAsStream("/python-extension.json")!!.reader(Charsets.UTF_8).use {
        Gson().fromJson(Gson().fromJson(it, JsonObject::class.java)["extension"], IdeExtensionManifest::class.java)
    }
    override fun activate(context: IdeExtensionContext): IdeExtensionRuntime = PythonRuntime(context)
}

internal class PythonRuntime(private val context: IdeExtensionContext) : IdeExtensionRuntime {
    private val settings = PythonSettings(context.profileDirectory.resolve("python-runner/settings.json"))
    private val sessions = linkedSetOf<Pair<String, String>>()
    private val gson = Gson()
    private fun owner(params: JsonObject): String? = params["projectFile"]?.asString?.also { context.projectRoot(it) }
    override fun request(method: String, params: JsonObject): JsonElement {
        return when (method.substringAfterLast('.')) {
            "settings", "configure", "reset" -> {
                val project = if (params["scope"]?.asString == "project") (if (method.endsWith("settings")) context.currentProjectFile() else owner(params))
                    ?: throw IdeExtensionException("NO_PROJECT", "Open a project first.") else null
                if (method.endsWith("configure")) settings.write(project, params.getAsJsonObject("settings"), params["version"].asString)
                if (method.endsWith("reset")) settings.reset(project, params["version"].asString)
                val current = context.currentProjectFile()
                gson.toJsonTree(settings.view(project)).asJsonObject.apply {
                    current?.let { addProperty("projectFile", it) }
                    val root = (project ?: current)?.let { Path.of(context.projectRoot(it)) }
                    val interpreter = PythonInterpreter.find(settings.read(project).interpreter, root)
                    addProperty("resolvedInterpreter", interpreter?.firstOrNull().orEmpty())
                }
            }
            "run" -> {
                val project = params["expectedProjectFile"]?.asString ?: throw IdeExtensionException("NO_PROJECT", "Open a project first.")
                val root = Path.of(context.projectRoot(project)).toRealPath()
                val ru = params["locale"]?.asString?.startsWith("ru") == true
                fun fail(code: String, en: String, russian: String): Nothing = throw IdeExtensionException(code, if (ru) russian else en)
                val path = params["path"]?.asString.orEmpty()
                val relative = Path.of(path)
                if (relative.isAbsolute || !path.endsWith(".py", true)) fail("INVALID_PATH", "Choose a Python file in this project.", "Выберите Python-файл этого проекта.")
                val script = try { root.resolve(relative).normalize().toRealPath() }
                    catch (_: java.io.IOException) { fail("INVALID_PATH", "Python file was not found.", "Python-файл не найден.") }
                if (!script.startsWith(root) || !Files.isRegularFile(script)) fail("INVALID_PATH", "Choose a Python file in this project.", "Выберите Python-файл этого проекта.")
                val config = settings.read(project)
                val command = PythonInterpreter.find(config.interpreter, root)
                    ?: fail("PYTHON_NOT_FOUND", "Python was not found. Select an interpreter in the extension settings.", "Python не найден. Выберите интерпретатор в настройках расширения.")
                val arguments = JsonObject().apply {
                    addProperty("label", "Python: ${script.fileName}")
                    add("argv", gson.toJsonTree(command + listOf("-X", "utf8", "-u", script.toString()) + config.arguments))
                    addProperty("directory", config.directory)
                    add("environment", gson.toJsonTree(config.environment + mapOf("PYTHONUTF8" to "1", "PYTHONIOENCODING" to "utf-8")))
                }
                val result = context.callProject(project, "terminal.launch", arguments)
                sessions += project to result.asJsonObject["id"].asString
                result
            }
            else -> throw IdeExtensionException("UNKNOWN_METHOD", "Unknown Python action.")
        }
    }
    override fun projectClosed() = stop()
    override fun close() = stop()
    private fun stop() {
        sessions.forEach { (project, id) ->
            try { context.callProject(project, "terminal.stop", JsonObject().apply { addProperty("sessionId", id) }) }
            catch (_: Exception) { /* The project or terminal may already be closed. */ }
        }
        sessions.clear()
    }
}

internal object PythonInterpreter {
    fun find(configured: String, root: Path?): List<String>? {
        val windows = System.getProperty("os.name").startsWith("Windows")
        fun usable(path: Path) = Files.isRegularFile(path) && (windows || Files.isExecutable(path)) &&
            !path.toString().replace('\\', '/').contains("/Microsoft/WindowsApps/", true)
        fun executable(name: String): Path? {
            val path = try { Path.of(name) } catch (_: java.nio.file.InvalidPathException) { return null }
            if (path.isAbsolute) return path.takeIf(::usable)
            if (name.contains('/') || name.contains('\\')) return root?.resolve(path)?.normalize()?.takeIf(::usable)
            val names = if (windows && !name.endsWith(".exe", true)) listOf("$name.exe", name) else listOf(name)
            return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).asSequence()
                .flatMap { directory -> names.asSequence().map { Path.of(directory).resolve(it) } }.firstOrNull(::usable)
        }
        if (configured.isNotBlank()) return executable(configured)?.let { listOf(it.toString()) }
        if (root != null) for (directory in listOf(".venv", "venv")) {
            val path = root.resolve(directory).resolve(if (windows) "Scripts/python.exe" else "bin/python")
            if (usable(path)) return listOf(path.toString())
        }
        for (name in if (windows) listOf("python.exe", "python3.exe") else listOf("python3", "python")) {
            executable(name)?.let { return listOf(it.toString()) }
        }
        return if (windows) executable("py.exe")?.let { listOf(it.toString(), "-3") } else null
    }
}
