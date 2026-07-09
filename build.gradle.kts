
plugins {
    id("com.gtnewhorizons.gtnhconvention")
    jacoco
}

// Compile the shared MC-free :protocol module's main sources in-tree instead of consuming :protocol
// as a binary project dependency. RetroFuturaGradle can't resolve a plain-Java subproject variant
// onto the mod's obfuscation-stamped runtime/shade classpath, so the shared crypto + wire-format
// classes are pulled in as an extra source directory: they compile alongside the mod's own classes
// and get shaded naturally into the mod jar at their original fully-qualified names. The .java still
// lives in exactly one place (:protocol), which the :exploit harness consumes as a real dependency.
// :protocol's own compileOnly libs (guava, fastutil, log4j, annotations) are already on the mod's
// compile classpath (see dependencies.gradle / Minecraft-Forge runtime).
sourceSets {
    main {
        java {
            srcDir(project(":protocol").file("src/main/java"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
