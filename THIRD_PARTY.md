# Third-party code

Only permissive licences (Apache 2.0, MIT, BSD) are allowed in this project. No GPL code.

| Component | Used for | Licence |
| --- | --- | --- |
| Kotlin standard library | App language | Apache 2.0 |
| kotlinx.coroutines | Background camera work | Apache 2.0 |
| AndroidX Core, Activity, Lifecycle | App framework, FileProvider | Apache 2.0 |
| Jetpack Compose (UI, Material 3) | User interface | Apache 2.0 |
| Gradle wrapper | Build | Apache 2.0 |
| Android Gradle Plugin, Android SDK, NDK | Build toolchain (not shipped in the APK except platform stubs) | Apache 2.0 / Android SDK License |
| GitHub Actions: actions/checkout, actions/setup-java, actions/upload-artifact, gradle/actions | CI only, not shipped | MIT |
| JUnit 4 (+ Hamcrest) | Unit tests only; never shipped in the APK | EPL 1.0 (Hamcrest: BSD) |
