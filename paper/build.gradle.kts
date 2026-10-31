plugins {
    id("java-library")
    id("maven-publish")
}

dependencies {
    api(project(":command-core"))
    api("org.incendo:cloud-paper:${property("cloudMinecraftVersion")}")
    compileOnlyApi("com.github.anmvc.winter:paper:${property("winterVersion")}")
    compileOnlyApi("io.papermc.paper:paper-api:${property("paperVersion")}")

    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    testImplementation("com.github.anmvc.winter:paper:${property("winterVersion")}")
    testImplementation("io.papermc.paper:paper-api:${property("paperVersion")}")
}
