plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
group = "com.winsmediatama.photolocker.windows"
version = "0.1.0"
kotlin { jvmToolchain(17) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("net.java.dev.jna:jna:5.16.0")
    implementation("net.java.dev.jna:jna-platform:5.16.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("test"))
}
compose.desktop {
    application {
        mainClass = "com.winsmediatama.photolocker.windows.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "Photo-Locker-Windows"
            packageVersion = "0.1.0"
            description = "Private encrypted photo and video vault"
            vendor = "Wins Mediatama"
        }
    }
}