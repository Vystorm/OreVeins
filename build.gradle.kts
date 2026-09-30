plugins {
    java
}

group = "com.icloud.kevinmendoza"
version = "2.6.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
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
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    manifest.attributes(
        "Implementation-Title" to "OreVeins",
        "Implementation-Version" to project.version
    )
}
