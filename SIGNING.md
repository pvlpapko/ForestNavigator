# Stable APK signing

ForestNavigator can build an updateable signed release APK when these GitHub Actions repository secrets are configured:

- FORESTNAV_KEYSTORE_B64
- FORESTNAV_STORE_PASSWORD
- FORESTNAV_KEY_ALIAS
- FORESTNAV_KEY_PASSWORD

Do not commit the signing keystore to this public repository.

Once the secrets are configured, GitHub Actions builds app-release.apk with the same signing certificate on every run. Keep applicationId unchanged and increase versionCode for every release.

Important: earlier APKs were signed with ephemeral GitHub debug keys. Moving to the stable signing key requires one final uninstall/reinstall. After the stable signed version is installed, future signed releases can update it in place.
