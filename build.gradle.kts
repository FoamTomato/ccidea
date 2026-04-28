import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "com.ccidea"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.lets-plot:lets-plot-batik:4.4.1")
    implementation("org.jetbrains.lets-plot:platf-awt-jvm:4.4.1")
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.4.1")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.7.3")
    // Batik's SAXDocumentFactory needs a JAXP SAXParserFactory provider on the same
    // classloader. Lets-Plot only ships xml-apis (interfaces); without an Impl jar,
    // ServiceLoader falls back to JDK-internal Xerces which JDK 21 forbids unnamed
    // modules to instantiate. Pulling Xerces here puts a usable Impl in the plugin CL.
    implementation("xerces:xercesImpl:2.12.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("242")
            untilBuild.set("999.*")
        }
        changeNotes.set(provider {
            // Pull the topmost ## section of CHANGELOG.md so the marketplace listing tracks releases.
            val changelog = file("CHANGELOG.md")
            if (!changelog.exists()) return@provider ""
            val text = changelog.readText()
            val firstStart = Regex("(?m)^## ").find(text)?.range?.first ?: return@provider ""
            val afterFirst = text.substring(firstStart)
            val secondMatch = Regex("(?m)^## ").findAll(afterFirst).drop(1).firstOrNull()
            val topRaw = if (secondMatch != null) afterFirst.substring(0, secondMatch.range.first) else afterFirst
            val lines = topRaw.lines()
            val title = lines.firstOrNull().orEmpty().removePrefix("## ").trim()
            buildString {
                append("<h3>").append(title).append("</h3>\n<ul>\n")
                for (line in lines.drop(1)) {
                    val item = line.trim()
                    if (item.startsWith("- ")) {
                        append("  <li>").append(item.removePrefix("- ")).append("</li>\n")
                    }
                }
                append("</ul>")
            }
        })
    }
    publishing {
        token.set(providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN"))
    }
    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }
    // To run JetBrains' Plugin Verifier, uncomment the following block. It needs
    // network access to plugins.jetbrains.com; day-to-day build / test does not.
    // pluginVerification { ides { recommended() } }
}

tasks.test {
    useJUnitPlatform()
}
