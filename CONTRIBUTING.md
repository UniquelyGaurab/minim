# Building and releasing Minim

Minim is built and tested on GitHub's machines with GitHub Actions. Nothing needs a local build server.

## Test builds

1. Changes go to the `dev` branch.
2. Every push to `dev` runs [Test build](.github/workflows/test-build.yml): Minim Fermata's tests, Minim's tests and lint (errors fail the run, warnings don't), then a debug APK signed with Minim's key.
3. The APK goes to the rolling **[test-build](https://github.com/UniquelyGaurab/minim/releases/tag/test-build)** pre-release as `Minim-test.apk`, replaced on every run. Its notes give the commit and versionCode. It's also kept as a workflow artifact for 14 days.
4. Install it over Minim on your phone. It's signed with the same key as the releases, so your library stays.

The test-build pre-release is never offered as an update inside Minim: the update checker reads `/releases/latest`, which skips pre-releases, `test-build` isn't a version number, and pre-releases are refused anyway. `TestBuildChannelTest` and a check at the end of every run make sure of it.

## Releases

1. Once a test build is approved, `dev` is merged into `main`, with the version's entry in `CHANGELOG.md` (`## 7.1.0 (date)`).
2. Pushing the tag (`v7.1.0`) runs [Release](.github/workflows/release.yml): the same tests and signed build, then a GitHub release marked latest, named "Minim 7.1.0", with `Minim-v7.1.0.apk` attached and the notes taken from the changelog entry. It can also be run by hand from the Actions tab with an existing tag.
3. Phones on older versions see the update prompt through `/releases/latest`. There's no other update file to change.

The tag must match `versionName` in `app/build.gradle.kts`, and a release that already exists is never replaced.

## Version codes

Test builds use the `versionCode` in `app/build.gradle.kts` as it is. Each release raises it by one, so a test build never outranks the next release and always installs over the current one.

## Signing

The key is stored as encrypted repository secrets (`MINIM_KEYSTORE_BASE64`, `MINIM_KEYSTORE_PASSWORD`, `MINIM_KEY_ALIAS`, `MINIM_KEY_PASSWORD`). It's written to the runner's temp folder only for the build and deleted afterwards. Each run checks that the APK's certificate matches the releases (SHA-256 `38a79e9e…9c83`). Never commit a keystore, a password, a Firebase file or a backup.
