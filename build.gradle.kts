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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.cdimascio:dotenv-java:3.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // 아키텍처 규칙(docs/ARCHITECTURE.md §3·§4) 기계 검증 — 테스트 전용, 런타임 무프레임워크 유지
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.example.dart.App")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
