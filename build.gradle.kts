import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.CommandLineArgumentProvider

plugins {
    base
    id("maven-publish")
}

val javaVersion = 21

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            quiet()
            addBooleanOption("Xdoclint:none", true)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val testClasspath = sourceSets["test"].runtimeClasspath
        val mainClasspath = sourceSets["main"].runtimeClasspath
        inputs.files(testClasspath).withNormalizer(ClasspathNormalizer::class.java)
        inputs.files(mainClasspath).withNormalizer(ClasspathNormalizer::class.java)
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-Dcommand.test.classpath=${testClasspath.asPath}",
                "-Dcommand.test.processorpath=${mainClasspath.asPath}",
            )
        })
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testImplementation"("org.mockito:mockito-core:5.20.0")
        "testImplementation"("net.kyori:adventure-api:${property("adventureVersion")}")
        "testImplementation"("org.slf4j:slf4j-api:2.0.16")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                artifactId = project.name
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Winter command integration for Cloud on Paper and Velocity.")
                    url.set("https://github.com/anmvc/winter-command")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            name.set("Diego Cardenas")
                            email.set("diego.cardenas.v06@gmail.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/anmvc/winter-command")
                        connection.set("scm:git:git://github.com/anmvc/winter-command.git")
                        developerConnection.set("scm:git:ssh://git@github.com/anmvc/winter-command.git")
                    }
                }
            }
        }
    }
}

configure(subprojects.filter { it.name != "command-core" }) {
    extensions.configure<SourceSetContainer> {
        named("test") { java.srcDir(rootProject.file("test-support/src/main/java")) }
    }
    tasks.withType<Test>().configureEach {
        systemProperty("command.test.platform", project.name.removePrefix("command-"))
    }
}

tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }
tasks.named("build") { dependsOn(subprojects.map { "${it.path}:build" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }
tasks.named("clean") { dependsOn(subprojects.map { "${it.path}:clean" }) }
tasks.named("publishToMavenLocal") { dependsOn(subprojects.map { "${it.path}:publishToMavenLocal" }) }
