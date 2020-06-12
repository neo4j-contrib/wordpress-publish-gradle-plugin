plugins {
    id("com.gradle.plugin-publish") version "0.11.0"
    `java-gradle-plugin`
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    jcenter()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.6.0")
    implementation("org.yaml:snakeyaml:1.25")
    implementation("com.beust:klaxon:5.0.1")
    implementation(gradleApi())
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.6.0")
}

version = "0.5.0"

gradlePlugin {
    plugins {
        create("wordpressPlugin") {
            id = "com.neo4j.gradle.wordpress.WordPressPlugin"
            implementationClass = "com.neo4j.gradle.wordpress.WordPressPlugin"
        }
    }
}

pluginBundle {
    website = "https://neo4j.com/"
    vcsUrl = "https://github.com/neo4j-contrib/wordpress-publish-gradle-plugin"

    (plugins) {
        "wordpressPlugin" {
            id = "com.neo4j.gradle.wordpress.WordPressPlugin"
            displayName = "Publish posts and pages to WordPress"
            description = "A plugin to publish posts or pages to WordPress from an HTML file and a YAML file that contains metadata"
            tags = listOf("wordpress", "publish", "posts", "pages")
        }
    }
}

// Add a source set for the functional test suite
val functionalTestSourceSet = sourceSets.create("functionalTest") {
}

gradlePlugin.testSourceSets(functionalTestSourceSet)
configurations.getByName("functionalTestImplementation").extendsFrom(configurations.getByName("testImplementation"))

// Add a task to run the functional tests
val functionalTest by tasks.creating(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
}

val check by tasks.getting(Task::class) {
    // Run the functional tests as part of `check`
    dependsOn(functionalTest)
}
