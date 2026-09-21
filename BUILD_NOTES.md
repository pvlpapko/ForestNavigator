# Build notes

The project builds in GitHub Actions.

## Verified build

- Workflow: `.github/workflows/android-build.yml`
- Successful run: #6
- Commit with the verified source/configuration: `36aeef09d011031d76fbbb156feec76e8e89bcd6`
- Task: `:app:assembleDebug`
- Artifact: `ForestNavigator-debug-apk`
- JDK: 17
- Gradle: 9.6.0
- compileSdk: 37
- targetSdk: 36
- SDK package: `platforms;android-37.0`
- Build Tools: 37.0.0

No Gradle Wrapper binary is vendored. CI provisions Gradle with `gradle/actions/setup-gradle` and invokes Gradle directly.

A local Android build was not executed in the ChatGPT container because that runtime does not provide a complete Android SDK build environment. The GitHub Actions build is the verified build.
