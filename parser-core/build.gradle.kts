// Bank SMS parsers copied from PennyWise (github.com/sarim2000/pennywiseai-tracker, AGPL-3.0).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir("src/commonMain/kotlin")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
