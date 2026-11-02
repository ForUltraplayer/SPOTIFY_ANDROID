// `extensions-pc`: the extension runner of the Windows PC product (ivLyrics Hangul). A headless
// loopback HTTP server that installs, keeps and asks Myspoti extensions exactly as the Android app
// does (:extensions is the engine, unchanged; the app layer's orchestration is ported here without
// Android). Started by the PC helper as
//   java -jar extensions-pc.jar --port 0 --token <token> --data <folder> --app-version <version>
// it prints `READY <port>` as its first line on stdout.
//
// Build the runnable jar (all dependencies inside) and run the tests, from base\:
//   .\gradlew.bat --no-daemon --no-configuration-cache :extensions-pc:test :extensions-pc:fatJar
// The jar: extensions-pc\build\libs\extensions-pc.jar
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":extensions"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation("junit:junit:4.13.2")
}

// The Myspoti version this engine is (the Hangul build's release.json, as :app reads it): what an
// extension's `minAppVersion` is compared with, and the runner's own version in /v1/health.
val releaseJson = rootProject.file("../release.json").takeIf { it.isFile }?.readText().orEmpty()
val engineVersion = (findProperty("appVersionName") as String?)
    ?: Regex("\"version\"\\s*:\\s*\"?([^\",}\\s]+)").find(releaseJson)?.groupValues?.get(1) ?: "2.3.0"
val generatedResources = layout.buildDirectory.dir("generated/runner-resources")
val runnerProperties = tasks.register<WriteProperties>("runnerProperties") {
    destinationFile.set(generatedResources.map { it.file("app/myspoti/extensions/pc/runner.properties") })
    property("version", engineVersion)
}
sourceSets.main {
    resources.srcDir(files(generatedResources).builtBy(runnerProperties))
}

// The plain jar (this module's classes only) is told apart, so it never overwrites extensions-pc.jar.
tasks.jar {
    archiveClassifier.set("plain")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "The runnable extensions-pc.jar, every dependency inside."
    archiveFileName.set("extensions-pc.jar")
    manifest { attributes("Main-Class" to "app.myspoti.extensions.pc.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "module-info.class", "META-INF/versions/*/module-info.class")
}
