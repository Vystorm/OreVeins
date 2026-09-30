plugins {
    java
}

group = "com.icloud.kevinmendoza"
version = "2.7.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Optional web page for Vystorm Core (see README, "Web page (optional)"). The
// integration in src/vystorm/ is compiled into the jar only when a Vystorm Core
// jar (0.24.0 or newer) is available: -PvystormCoreJar=<path> (or the same key in
// ~/.gradle/gradle.properties), else the first Vystorm_Core*.jar in libs/.
// Without it the jar is built without the web page and works exactly the same.
val vystormCoreJar: File? = (findProperty("vystormCoreJar") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?: file("libs").listFiles { f -> f.isFile && f.name.startsWith("Vystorm_Core") && f.name.endsWith(".jar") }
        ?.sortedBy { it.name }
        ?.lastOrNull()
val withVystormWeb = vystormCoreJar != null && vystormCoreJar.isFile

val vystorm: SourceSet = sourceSets.create("vystorm") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    if (withVystormWeb) {
        "vystormCompileOnly"(files(vystormCoreJar))
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.named("compileVystormJava") {
    onlyIf("a Vystorm Core jar is configured") { withVystormWeb }
}

tasks.named<ProcessResources>("processVystormResources") {
    onlyIf("a Vystorm Core jar is configured") { withVystormWeb }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName = "OreVeins-${project.version}.jar"
    if (withVystormWeb) {
        from(vystorm.output)
    }
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    manifest.attributes(
        "Implementation-Title" to "OreVeins",
        "Implementation-Version" to project.version
    )
    doFirst {
        logger.lifecycle(if (withVystormWeb) "OreVeins: web page for Vystorm Core included (${vystormCoreJar!!.name})"
            else "OreVeins: no Vystorm Core jar configured, building without the optional web page")
    }
}
