plugins {
    id("java-library")
    id("maven-publish")
}

dependencies {
    api(project(":command-core"))
    api("org.incendo:cloud-velocity:${property("cloudMinecraftVersion")}")
    compileOnlyApi("com.github.anmvc.winter:velocity:${property("winterVersion")}")
    compileOnlyApi("com.velocitypowered:velocity-api:${property("velocityVersion")}")

    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    testImplementation("com.velocitypowered:velocity-api:${property("velocityVersion")}")
    testImplementation("com.github.anmvc.winter:velocity:${property("winterVersion")}")
}
