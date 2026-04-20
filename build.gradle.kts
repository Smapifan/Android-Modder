plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.smapifan.androidmodder.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
