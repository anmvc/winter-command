plugins {
    id("java-library")
    id("maven-publish")
}

dependencies {
    api("com.github.anmvc.winter:core:${property("winterVersion")}")
    api("org.incendo:cloud-core:${property("cloudVersion")}")
    api("org.incendo:cloud-annotations:${property("cloudVersion")}")

    compileOnly("org.incendo:cloud-kotlin-coroutines:${property("cloudVersion")}")
    compileOnly("org.incendo:cloud-kotlin-coroutines-annotations:${property("cloudVersion")}")
    compileOnly("org.incendo:cloud-kotlin-extensions:${property("cloudVersion")}")

    testImplementation("org.incendo:cloud-kotlin-coroutines:${property("cloudVersion")}")
    testImplementation("org.incendo:cloud-kotlin-coroutines-annotations:${property("cloudVersion")}")
    testImplementation("org.incendo:cloud-kotlin-extensions:${property("cloudVersion")}")
}
