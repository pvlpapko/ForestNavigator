# Build notes

The project is intended to build in GitHub Actions.

No Gradle Wrapper binary is vendored in this archive. The workflow installs Gradle 9.6.0 with `gradle/actions/setup-gradle` and invokes `gradle :app:assembleDebug` directly.

Local Android build was not executed in the ChatGPT container because it does not contain the Android SDK/Gradle toolchain and outbound dependency downloads are unavailable there.
