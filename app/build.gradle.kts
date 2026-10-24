import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
}

android {
    namespace = "com.fardin.floatshot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fardin.floatshot"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Kotlin is built in since AGP 9 and uses the same JVM target as Java.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ------------------------------------------------------------------------
// Every APK build also gets a copy named "FloatShot YYYYMMDD HHMM.apk"
// in the APK folder at the project root.

abstract class NameApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:Internal
    abstract val outFolder: DirectoryProperty

    @get:Input
    abstract val suffix: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun copyApk() {
        val built = loader.get().load(apkFolder.get())
            ?: throw GradleException("No APK found in ${apkFolder.get()}")
        val stamp = SimpleDateFormat("yyyyMMdd HHmm").format(Date())
        val dir = outFolder.get().asFile.apply { mkdirs() }
        built.elements.forEach { element ->
            val target = File(dir, "FloatShot $stamp${suffix.get()}.apk")
            File(element.outputFile).copyTo(target, overwrite = true)
            logger.lifecycle("APK saved: ${target.absolutePath}")
        }
    }
}

val apkOutDir = rootProject.layout.projectDirectory.dir("APK")

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<NameApkTask>("nameApk$name") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            outFolder.set(apkOutDir)
            suffix.set(if (variant.buildType == "release") " release" else "")
        }
    }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("nameApk" + name.removePrefix("assemble"))
}
