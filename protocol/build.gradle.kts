// Shared, Minecraft/Forge/netty-free protocol module: the single source of truth for the voice
// crypto + wire format (VoiceProtocol, AesEncryption, the UDP PacketUdpCodec + packets, and the
// ClientHello/ServerHello HelloCodec body framing). Consumed by the mod NOT as a binary dependency
// (RetroFuturaGradle can't resolve a plain subproject variant onto the mod's obfuscation-stamped
// runtime/shade classpath) but by compiling this module's main sources in-tree via an extra srcDir
// (see the mod's build.gradle.kts), so the classes are shaded into the mod jar at their original
// fully-qualified names. The :exploit harness consumes it as a real implementation(project(":protocol"))
// dependency, so a protocol change breaks the harness at compile time instead of silently at runtime.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // These four are all provided by the Minecraft/Forge classpath at real mod runtime, so they are
    // compileOnly here: :protocol must NOT bundle its own copies (it is shaded into the mod jar with
    // relocateShadowedDependencies = false), which would put duplicate/conflicting classes on the game
    // classpath. Versions match the mod's dependencies.gradle so the compiled bytecode's method
    // references resolve against exactly what the game ships (log4j in particular is pinned to MC
    // 1.7.10's 2.0-beta9, whose error(String, Object...) varargs overload the shaded classes bind to).
    compileOnly("com.google.guava:guava:17.0")
    compileOnly("it.unimi.dsi:fastutil-core:8.5.16")
    compileOnly("org.apache.logging.log4j:log4j-api:2.0-beta9")
    compileOnly("org.jetbrains:annotations:24.1.0")

    // The plain-JVM test task has no Minecraft classpath to fall back on, so the moved protocol tests
    // need the runtime libraries explicitly (guava/fastutil for the codec, log4j for the loggers).
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.guava:guava:17.0")
    testImplementation("it.unimi.dsi:fastutil-core:8.5.16")
    testImplementation("org.apache.logging.log4j:log4j-api:2.0-beta9")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.0-beta9")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
}
