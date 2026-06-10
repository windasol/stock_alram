plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.cdimascio:dotenv-java:3.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.example.dart.App")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
