import io.deepmedia.tools.deployer.DeployerExtension
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.process.CommandLineArgumentProvider

plugins {
    base
    id("io.deepmedia.tools.deployer") version "0.16.0" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "io.deepmedia.tools.deployer")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
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

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Winter command integration for Cloud on Paper and Velocity.")
                    url.set("https://github.com/thewinterframework/command")
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
                        url.set("https://github.com/thewinterframework/command")
                        connection.set("scm:git:git://github.com/thewinterframework/command.git")
                        developerConnection.set("scm:git:ssh://git@github.com/thewinterframework/command.git")
                    }
                }
            }
        }
    }

    extensions.configure<DeployerExtension> {
        signing {
            key.set(secret("winterSigningKey"))
            password.set(secret("winterSigningPassphrase"))
        }
        content {
            component {
                fromMavenPublication("maven", clone = true)
            }
        }
        projectInfo {
            description.set("Winter command integration for Cloud on Paper and Velocity.")
            url.set("https://github.com/thewinterframework/command")
            license(MIT)
            groupId.set(project.group.toString())
            artifactId.set(project.name)
            scm.fromGithub("thewinterframework", "command")
            developer("Diego Cardenas", "diego.cardenas.v06@gmail.com")
        }
        localSpec()
        centralPortalSpec {
            signing.key.set(secret("winterSigningKey"))
            signing.password.set(secret("winterSigningPassphrase"))
            auth.user.set(secret("winterRepositoryUsername"))
            auth.password.set(secret("winterRepositoryPassword"))
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

tasks.named("build") { dependsOn(subprojects.map { "${it.path}:build" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }
tasks.named("clean") { dependsOn(subprojects.map { "${it.path}:clean" }) }
