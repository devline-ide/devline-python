plugins { kotlin("jvm") }

dependencies {
    compileOnly(project(":devline-ide-extensions"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    testImplementation(project(":devline-ide-extensions"))
    testImplementation(project(":devline-terminal"))
    testImplementation(project(":devline-api"))
    testImplementation("com.google.code.gson:gson:2.13.2")
}

tasks.processResources {
    from("src/extension/extension.json") { rename { "python-extension.json" } }
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(":devline-desktop:webInstall")
    workingDir(rootProject.projectDir)
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", "node_modules\\.bin\\vite.cmd", "build", "--config", project.file("web/vite.config.ts").absolutePath)
    else commandLine("node_modules/.bin/vite", "build", "--config", project.file("web/vite.config.ts").absolutePath)
    inputs.dir("web")
    inputs.dir(rootProject.file("devline-ui-web/src"))
    outputs.dir(layout.buildDirectory.dir("panel"))
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild)
    workingDir(rootProject.projectDir)
    commandLine("node", project.file("web/pack.mjs").absolutePath)
    inputs.dir(layout.buildDirectory.dir("panel"))
    outputs.dir(layout.buildDirectory.dir("extension-web"))
}
val extensionArchive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    archiveFileName.set("devline-python-0.1.1.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from("src/extension/extension.json")
    from(packPanel) { into("web") }
    into("lib") { from(tasks.jar) }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
configurations.create("bundledExtension") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts.add("bundledExtension", extensionArchive)

tasks.register<JavaExec>("pythonCheck") {
    group = "verification"
    dependsOn(tasks.testClasses, extensionArchive)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get()
    mainClass.set("devline.python.PythonCheckKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
    systemProperty("devline.python.archive", extensionArchive.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
