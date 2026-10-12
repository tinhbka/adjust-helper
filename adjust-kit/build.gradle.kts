plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// Bump mỗi lần phát hành bản mới cho các app.
val publishVersion = "0.3.1"

android {
    namespace = "com.adjust.helper"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    publishing {
        singleVariant("release")
    }
}

// ./gradlew :adjust-kit:publishReleasePublicationToLocalRepoRepository
// -> build/adjust-repo (maven repo: aar + pom chứa transitive deps).
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.adjust.helper"
            artifactId = "adjust-helper"
            version = publishVersion
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "localRepo"
            url = uri(rootProject.layout.buildDirectory.dir("adjust-repo"))
        }
    }
}
dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.adjust.android)
    compileOnly(libs.flutter.embedding.debug.v100cf56914b326edb0ccb123ffdc60f00060bd513fa)
    implementation(libs.play.services.ads.identifier.v1801)
    implementation(libs.installreferrer)
}
// ./gradlew :adjust-kit:zipAdjustRepo
// -> dist/adjust-repo-<version>.zip, chỉ gồm .aar + .pom + .module
// (bỏ checksum và maven-metadata, không cần cho repo local).
tasks.register<Zip>("zipAdjustRepo") {
    dependsOn("publishReleasePublicationToLocalRepoRepository")
    from(rootProject.layout.buildDirectory.dir("adjust-repo")) {
        include("**/$publishVersion/*.aar", "**/$publishVersion/*.pom", "**/$publishVersion/*.module")
        into("adjust-repo")
    }
    archiveFileName.set("adjust-repo-$publishVersion.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
