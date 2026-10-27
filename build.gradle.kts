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
val panelInstall = tasks.register<Exec>("panelInstall") {
    workingDir(projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "extension", projectDir.absolutePath)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/extension-devkit"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/ui-web"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/animgraph"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/game-render-web"))
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall)
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "panel", projectDir.absolutePath)
    inputs.dir("web")
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
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
