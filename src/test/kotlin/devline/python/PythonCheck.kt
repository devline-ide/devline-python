package devline.python

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.api.ProjectSummary
import devline.extensions.*
import devline.terminal.LocalTerminalService
import devline.terminal.TerminalOutput
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun main() {
    val gson = Gson()
    val root = Files.createTempDirectory("devline-python-check-юникод ").toRealPath()
    val projectRoot = Files.createDirectories(root.resolve("project with spaces"))
    val project = ProjectSummary("Python check", projectRoot.toString(), projectRoot.resolve("check.dlproject").toString())
    val profile = Files.createDirectories(root.resolve("profile"))
    val windows = System.getProperty("os.name").startsWith("Windows")
    val interpreter = System.getenv("PATH").split(java.io.File.pathSeparator).asSequence().flatMap { directory ->
        (if (windows) listOf("python.exe", "python3.exe") else listOf("python3", "python")).asSequence().map { Path.of(directory).resolve(it) }
    }.firstOrNull { Files.isRegularFile(it) && !it.toString().contains("WindowsApps") } ?: error("A real Python installation is required for pythonCheck")
    val marker = "literal & dollar\u0024() \"quoted\" привет"
    val argumentCases = listOf(marker, "", "trailing\\", "space trailing\\", "quote\"only", "double\\\\\"end", "\t")
    val venv = projectRoot.resolve(".venv")
    val createVenv = ProcessBuilder(interpreter.toString(), "-m", "venv", "--without-pip", venv.toString()).redirectErrorStream(true).start()
    try { check(createVenv.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && createVenv.exitValue() == 0) { "Could not create a real virtual environment" } }
    finally { if (createVenv.isAlive) createVenv.destroyForcibly() }
    Files.createDirectories(projectRoot.resolve("work"))
    val script = projectRoot.resolve("hello юникод.py")
    Files.writeString(script, """
        import os, sys, json
        assert sys.argv[1:] == ${gson.toJson(argumentCases)}, repr(sys.argv[1:])
        assert os.environ['DEVLINE_PY_TEST'] == 'значение'
        assert os.path.basename(os.getcwd()) == 'work'
        print('READY', flush=True)
        value = input()
        print('RESULT:' + json.dumps({'arg': sys.argv[1], 'env': os.environ['DEVLINE_PY_TEST'], 'cwd': os.getcwd(), 'input': value}, ensure_ascii=False), flush=True)
        sys.exit(7)
    """.trimIndent())
    LocalTerminalService().use { terminals ->
        val host = object : IdeExtensionContext {
            override val profileDirectory = profile
            override fun enabled() = true
            override fun currentProjectFile() = project.projectFile
            override fun projectRoot(projectFile: String): String {
                if (projectFile != project.projectFile) throw IdeExtensionException("PROJECT_CHANGED", "Changed project")
                return project.rootPath
            }
            override fun callProject(projectFile: String, method: String, arguments: JsonObject): com.google.gson.JsonElement {
                projectRoot(projectFile)
                return when (method) {
                    "terminal.launch" -> gson.toJsonTree(terminals.launch(project, projectFile, arguments["label"].asString,
                        arguments.getAsJsonArray("argv").map { it.asString }, arguments["directory"].asString,
                        arguments.getAsJsonObject("environment").entrySet().associate { it.key to it.value.asString }))
                    "terminal.stop" -> { terminals.stop(arguments["sessionId"].asString); com.google.gson.JsonNull.INSTANCE }
                    "terminal.close" -> { terminals.kill(arguments["sessionId"].asString); com.google.gson.JsonNull.INSTANCE }
                    else -> error("Unexpected host method $method")
                }
            }
        }
        val archive = Path.of(System.getProperty("devline.python.archive")).toAbsolutePath()
        val bundle = Files.createDirectories(root.resolve("bundle"))
        Files.copy(archive, bundle.resolve(archive.fileName))
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)).joinToString("") { "%02x".format(it) }
        Files.writeString(bundle.resolve("catalog.json"), """{"schemaVersion":1,"extensions":[{"id":"devline.python","archive":"${archive.fileName}","sha256":"$sha","enabled":true}]}""")
        BundledExtensions.prepare(profile, bundle)
        IdeExtensions(profile, host, emptyList()).use { extensions ->
            val extension = extensions.overview().extensions.single()
            check(extension.enabled && extension.installed && !extension.restartRequired)
            check(extension.manifest.commands.single().fileExtensions == listOf("py"))
            check(extension.webviews.single().location == "settings")
            fun preferences(scope: String) = JsonObject().apply { addProperty("scope", scope); addProperty("projectFile", project.projectFile) }
            fun settings(scope: String) = extensions.request("pythonRunner.settings", preferences(scope)).asJsonObject
            fun configure(scope: String, directory: String) {
                extensions.request("pythonRunner.configure", preferences(scope).apply {
                    addProperty("version", settings(scope)["version"].asString)
                    add("settings", gson.toJsonTree(mapOf("interpreter" to interpreter.toString(), "directory" to directory, "arguments" to argumentCases, "environment" to mapOf("DEVLINE_PY_TEST" to "значение"))))
                })
            }
            val virtualInterpreter = venv.resolve(if (windows) "Scripts/python.exe" else "bin/python")
            check(settings("global")["resolvedInterpreter"].asString == virtualInterpreter.toString())
            val probe = projectRoot.resolve("venv_probe.py")
            Files.writeString(probe, "import sys\nassert sys.prefix == ${gson.toJson(venv.toString())}\nprint('VENV_OK', flush=True)\n")
            val automatic = extensions.execute("devline.python", "run", project.projectFile, probe.fileName.toString()).asJsonObject["id"].asString
            var probeOutput = ""; var probeCursor = 0L
            val probeDeadline = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < probeDeadline) {
                val chunk = terminals.read(automatic, probeCursor); probeOutput += chunk.data; probeCursor = chunk.cursor
                if (chunk.exited) { check(chunk.exitCode == 0); break }
                Thread.sleep(30)
            }
            check(probeOutput.contains("VENV_OK")) { probeOutput }
            terminals.kill(automatic)
            configure("global", "")
            check(settings("project")["inherited"].asBoolean)
            val version = settings("global")["version"].asString
            configure("project", "work")
            try { extensions.request("pythonRunner.reset", preferences("global").apply { addProperty("version", version) }); error("Expected stale-settings guard") }
            catch (cause: IdeExtensionException) { check(cause.code == "FILE_CONFLICT") }
            check(settings("global")["settings"].asJsonObject["directory"].asString == "")
            check(settings("project")["settings"].asJsonObject["directory"].asString == "work")
            val result = extensions.execute("devline.python", "run", project.projectFile, script.fileName.toString(), "ru").asJsonObject
            val id = result["id"].asString
            check(result["program"].asBoolean)
            fun waitFor(id: String, predicate: (String, TerminalOutput) -> Boolean): Pair<String, TerminalOutput> {
                val output = StringBuilder(); var cursor = 0L
                val deadline = System.nanoTime() + 20_000_000_000
                var last = terminals.read(id, cursor)
                while (System.nanoTime() < deadline) {
                    last = terminals.read(id, cursor); output.append(last.data); cursor = last.cursor
                    if (predicate(output.toString(), last)) return output.toString() to last
                    Thread.sleep(30)
                }
                error("Timed out waiting for Python: $output ($last)")
            }
            waitFor(id) { text, _ -> text.contains("READY") }
            terminals.write(id, "ввод\r")
            val (output, exit) = waitFor(id) { _, chunk -> chunk.exited }
            check(exit.exitCode == 7 && output.contains("RESULT:") && output.contains("привет") && output.contains("значение") && output.contains("ввод")) { output }
            check(output.contains("work"))
            terminals.restart(project, project.projectFile, id)
            waitFor(id) { text, _ -> text.contains("READY") }
            terminals.write(id, "again\r")
            check(waitFor(id) { _, chunk -> chunk.exited }.second.exitCode == 7)
            terminals.kill(id)
            try { extensions.execute("devline.python", "run", "stale", script.fileName.toString()); error("Expected owner guard") }
            catch (cause: IdeExtensionException) { check(cause.code == "PROJECT_CHANGED") }
            try { extensions.execute("devline.python", "run", project.projectFile, "../escape.py"); error("Expected path guard") }
            catch (cause: IdeExtensionException) { check(cause.code == "INVALID_PATH") }
            Files.writeString(script, "import time,subprocess,sys,pathlib\np=subprocess.Popen([sys.executable,'-c','import time;time.sleep(120)'])\npathlib.Path('child.pid').write_text(str(p.pid))\nprint('WAITING',flush=True)\ntime.sleep(120)\n")
            val waiting = extensions.execute("devline.python", "run", project.projectFile, script.fileName.toString()).asJsonObject["id"].asString
            waitFor(waiting) { text, _ -> text.contains("WAITING") }
            val child = Files.readString(projectRoot.resolve("work/child.pid")).toLong()
            extensions.setEnabled("devline.python", false, extensions.overview().version)
            waitFor(waiting) { _, chunk -> chunk.exited }
            val deadline = System.nanoTime() + 5_000_000_000
            while (ProcessHandle.of(child).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(30)
            check(!ProcessHandle.of(child).map { it.isAlive }.orElse(false)) { "Child process survived disable" }
            check(terminals.list(project, project.projectFile).size == 1) // Output remains readable after stopping.
            check(!extensions.overview().extensions.single().enabled)
        }
    }
    // Remove only this test's temporary files after every process is closed.
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    println("Python Runner: real .venv, PTY, UTF-8, stdin, argv, cwd/env, exit 7, restart, bundled settings registration, stale guards and disable/child cleanup passed")
}
