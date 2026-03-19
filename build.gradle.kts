plugins {
    java
    application
}

group = "engine"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val disruptorVersion = "4.0.0"
val nettyVersion = "4.1.115.Final"
val kafkaVersion = "3.7.0"
val micrometerVersion = "1.13.0"
val hdrHistogramVersion = "2.2.2"

dependencies {
    implementation("com.lmax:disruptor:$disruptorVersion")
    implementation("io.netty:netty-all:$nettyVersion")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("io.micrometer:micrometer-core:$micrometerVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")
    implementation("org.hdrhistogram:HdrHistogram:$hdrHistogramVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testRuntimeOnly("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

application {
    mainClass.set("engine.MatchingEngineApp")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
