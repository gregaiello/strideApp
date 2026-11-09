import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing details come from local.properties, which git ignores, rather than
// from constants in the source, so a key never reaches anyone who clones it.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.stride.hud"
    compileSdk = 34

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "dev.stride.hud"
        minSdk = 28
        targetSdk = 28
        // Bumped per release, because the About screen is the only place
        // anyone can see which build is on a console, and "0.1-spike · build 1"
        // was still being shown four months and one published repo later.
        versionCode = 4
        versionName = "0.8.0"
    }
    /*
     * A release build is what belongs on a treadmill.
     *
     * A debug build carries `android:debuggable="true"`, which lets anything
     * else on the console attach to this process and read its store — the
     * NAS key included — and it is what turns on the WebView DevTools
     * socket in MainActivity. Neither is a risk worth taking for a machine
     * that sits in a hallway for years.
     *
     * The signing key is yours and is never in this repo. Generate one:
     *
     *     keytool -genkey -v -keystore ~/stride-release.jks -alias stride \
     *             -keyalg RSA -keysize 4096 -validity 10000
     *
     * then name it in local.properties:
     *
     *     stride.keystore=/Users/you/stride-release.jks
     *     stride.keystore.password=...
     *     stride.key.alias=stride
     *     stride.key.password=...
     *
     * Without those the release variant gets no signing config at all, and
     * Gradle produces an unsigned APK that adb refuses to install. That is the
     * correct failure: it names what is missing, where a build that quietly
     * fell back to the debug key would ship the thing we are avoiding.
     */
    val keystorePath = localProps.getProperty("stride.keystore", "")
    signingConfigs {
        if (keystorePath.isNotEmpty() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("stride.keystore.password", "")
                keyAlias = localProps.getProperty("stride.key.alias", "stride")
                keyPassword = localProps.getProperty("stride.key.password", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        /*
         * One check, off, with a reason.
         *
         * ExpiredTargetSdkVersion is a Google Play policy: apps published there
         * must target API 33 or higher. This one is installed with adb onto a
         * treadmill console running Android 9 and can never be a Play app, so
         * the policy does not apply — and targetSdk 28 is load-bearing rather
         * than neglect. BootReceiver starts an activity from a background
         * broadcast, which API 29 closed; the Bluetooth permissions in the
         * manifest are the pre-31 set. Raising it would break the console.
         *
         * Everything else lint considers fatal still fails the release build,
         * which is the point of leaving the rest on.
         */
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Pure logic (laps, the recording) is tested on the PC, not the console.
    // Default return values keep a stray Log.i in that code from failing a
    // test with "Method ... not mocked" — the log line is not what is tested.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Fork-only: SFTP for copying each .fit to a NAS (NasCopy). Pure Java, no
    // dependencies of its own; the maintained fork of the original JSch.
    implementation("com.github.mwiede:jsch:0.2.20")

    // Fork-only: draws the QR code a phone scans to download a walk
    // (PhoneShare). Pure Java, no dependencies, a few hundred lines. The
    // console has no internet, so a page could not fetch one, and a QR
    // encoder written here would be Reed-Solomon by hand for no gain.
    implementation("io.nayuki:qrcodegen:1.8.0")

    // Test-only, so the APK still ships with only the two above.
    // org.json is here because android.jar's copy is stubs: on the JVM every
    // method returns null or 0, and a JSON round-trip test would be testing
    // nothing. The real library is the same API the console runs.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
