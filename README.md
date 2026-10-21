# Lita — private encrypted video vault (native Android)

Kotlin · Jetpack Compose (Material 3) · AndroidX Media3 ExoPlayer · Storage Access Framework · BiometricPrompt · Android Keystore.
Package/application ID: `com.lita.app` · version 1.0.0 · minSdk 24 (Android 7.0) · target/compile SDK 36 (Android 16) · AGP 8.13 · Gradle 8.13.

## Honest build status (read this first)

This project was generated in an environment **without** an Android SDK, Gradle, a Kotlin compiler, or network access.
Therefore:

| Step | Status |
|---|---|
| Source generation (all files below) | Done |
| Static checks | Only crude ones: bracket balance in every `.kt` file, and that every `vm.xxx` member used by the UI exists in `LitaViewModel` |
| Gradle sync | **Not run** |
| Kotlin compilation / debug APK build | **Not run — no APK was produced** |
| Unit tests (`app/src/test`) | **Written, never executed** (no results exist) |
| Instrumented tests (`app/src/androidTest`) | Written, never executed |
| On-device behaviour (SAF, ExoPlayer, biometrics, pull-to-refresh, layout) | **Unverified** |

Expect to fix some compile errors on first build (typos, missing imports, API drift). Nothing here should be treated as tested.
No security audit has been performed.

`gradle/wrapper/gradle-wrapper.jar` is **not included** (it could not be downloaded). `gradlew`/`gradlew.bat` are small launchers that
explain this. Android Studio does not need the JAR to sync (it reads `gradle-wrapper.properties`, Gradle 8.13). To get a working
command-line wrapper once: open the project in Android Studio, then run `gradle wrapper --gradle-version 8.13` in the Terminal
(or use any local Gradle 8.10+).

## Building on GitHub Actions
`.github/workflows/build.yml` installs JDK 17, the Android SDK and Gradle 8.13, runs the unit tests, builds the debug APK and
uploads it as the artifact **Lita-debug-apk** (Actions tab → run → Artifacts). It does not need `gradle-wrapper.jar`.
The first run is the first real compile of this code, so expect to fix errors from the log.

## Splash screen credit emoji
Replace `app/src/main/res/drawable/vendetta_emoji.png` with your own emoji image (same file name, PNG, square, e.g. 128×128) **before building**.
It is shown after "Created by Vendetta IRN from" at the bottom of the start screen. The file in the project is an empty transparent placeholder.

## Languages and fonts
English is the default; Persian (فارسی) is built in. Switch in Settings → Language, or with the small language menu on the first-run screen.
Strings stay in English in the code and are translated at display time (`i18n/FaStrings.kt`); to add a language add an `AppLanguage` entry and a table.
Persian uses a right-to-left layout (video pages and the PIN pad stay left-to-right).
Custom fonts: see `app/FONTS_README.txt` (put `lita_font_en.ttf` / `lita_font_fa.ttf` in `app/src/main/res/font/`).

## Requirements
- Android Studio Ladybug (2024.2) or newer, with Android SDK Platform 35 and Build-Tools installed
- JDK 17 (Android Studio's bundled JBR 17+ is fine; Gradle runs on 17–21)

## Open, sync, build
1. *File → Open* the `Lita` folder, accept the Gradle sync (it downloads dependencies; needs internet).
2. Build a debug APK: *Build → Build APK(s)*, or `./gradlew :app:assembleDebug` after creating the wrapper JAR.
3. Output path: `app/build/outputs/apk/debug/app-debug.apk`
4. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy the APK to the phone and open it.
5. Unit tests: `./gradlew :app:testDebugUnitTest` · instrumented: `./gradlew :app:connectedDebugAndroidTest`

## Architecture
```
crypto/    VaultCrypto (AES-256-GCM, PBKDF2), ChunkedCipher/ChunkedReader (streaming, random access), RecoveryFile
vault/     VaultStorage (interface), IndexCodec, VaultRepository (import/verify/export/scan/duplicates/recovery)
data/      SafVaultStorage (SAF), AndroidIo (picker, folder walk, MediaStore export), UserState, KeyValueStore
playback/  VaultDataSource (decrypting Media3 DataSource), PlayerController (one ExoPlayer), ShuffleCycle, thumbnail probe
security/  PinHasher, AttemptLimiter, AppPrefs, DeviceKeyStore (Android Keystore)
ui/        theme, Explore, Showcase, Saved, History, Cleaner, Settings, Lock, Setup, root scaffold
```
The vault logic depends only on the `VaultStorage` interface, so it is unit-tested on the JVM with an in-memory store.

## Vault format
All vault files live in one flat folder you choose with the system folder picker:
- `lita-recovery-a|b` — master key wrapped with AES-256-GCM under a PBKDF2-HMAC-SHA-256 key (600 000 iterations, random 16-byte salt, random nonce, versioned header authenticated as AAD). Two slots; a password change writes the new slot, verifies it, then removes the old one.
- `lita-index-a|b` — AES-256-GCM encrypted index: original file names, extensions, folder names/structure, hashes, durations. Two alternating slots with a generation counter, so a crash while writing never destroys the last good index.
- `<32 hex chars>` — encrypted videos and encrypted thumbnails: random names, no extension.
- Video objects: 256 KiB chunks, AES-256-GCM per chunk, nonce = random 64-bit prefix ‖ chunk counter, AAD = header ‖ chunk index ‖ final-flag. This detects flipped bits, re-ordering, truncation and header edits. Chunking gives random access so playback decrypts on the fly; **no plaintext temp files are used for playback, import or thumbnails.**
- The master key is a random 256-bit value; it is also wrapped by a non-exportable Android Keystore key so Lita opens without asking for the password each time. The Keystore key disappears on uninstall; use **Restore vault** with the recovery password afterwards.

**If you lose the recovery password, the vault may be impossible to recover.** There is no reset and no cloud copy.

## Storage and permission limits (Android reality)
- Vault location uses SAF. Android 11+ blocks choosing the storage root, `Download`, and other apps' `Android/data` folders; pick a folder you create (e.g. `Documents/LitaVault`).
- Some providers (certain cloud drives) do not support random access or exact file names; Lita detects this and refuses rather than corrupting data.
- The vault folder survives uninstall only if you don't delete it and the folder is outside app-private storage.
- Deleting originals after import uses `DocumentsContract.deleteDocument` and only works if the provider and the granted permissions allow it; failures are reported and the original is left in place.
- Export writes a **decrypted** copy to `Movies/Lita Export` through MediaStore (no permission needed on Android 10+). The system resolves name collisions. The copy is no longer protected.
- Biometric availability depends on enrolled biometrics and device configuration. The app lock is an interface gate; vault encryption does not depend on the PIN.
- Volume keys are intercepted on the Explore screen to show Lita's overlay (stream volume is changed without the system UI flag). Some devices/OEM skins may still show the system panel; this cannot be guaranteed.
- Screenshots and the recents preview are blocked by default (Settings → Block screenshots).

## Features and where they live
Explore (vertical pager, one bounded ExoPlayer, right-hand rail Save/Mute/Export/Delete, custom seek bar, error card with Retry), Showcase (staggered 2-column grid, deterministic large/medium/small tiles, optional single muted preview), Saved, History, Duplicate Cleaner (re-hashes candidates, one verified copy always kept), Settings (import video/folder, restore, change location by verified copy, change recovery password, PIN, biometrics, relock timing, filters with OFF = visible / ON = hidden), pull-to-refresh in Explore and Showcase (no Refresh/Shuffle buttons), fair persistent shuffle cycle, splash with black background and the real icon.

## Tests written (none executed)
`CryptoTest` (GCM round trips, tag failure, truncation, reorder, random-access reader, recovery metadata validation, wrong password), `VaultRepositoryTest` (import/export, import failure preserving source, silent-corruption detection, restore, wrong password, index slot recovery, scan statuses, orphan recovery, duplicate retention, password change), `ShuffleCycleTest` (fairness, boundary rule, persistence, import/delete adaptation, failure isolation), `UserStateTest` (hide semantics, persistence, cleanup), `PinTest`. `DeviceCryptoInstrumentedTest` covers the Keystore wrapper on a device.

## Known gaps / unverified
- Everything above under "Honest build status"; no compile, no test run, no device run.
- Bookmarks, history, filters and the shuffle queue live in app-private storage, so they are **not** restored on a new device (only the vault is).
- Export location is fixed to `Movies/Lita Export` (no alternate-location picker).
- Playback position inside a video is not resumed across navigation (the video, not the timestamp, is).
- Showcase layout was reasoned about, not tested on multiple screen sizes.
- Folder import skips non-video files and reports counts; individual skipped file names are not listed.
- Vault files copied or edited by other apps are detected only by size/existence during cheap scans; full verification happens on export, duplicate scan and import.

## Release build, signing key, Play Protect
`assembleRelease` (R8 + resource shrinking) starts noticeably faster than the debug APK. Without a signing secret the release APK is
signed with the CI's throw-away debug key, so every build has a **different signature** and Android refuses to install it over the previous
one (uninstall first; your vault survives, restore it with the recovery password). Create one stable key once and store it as GitHub
secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; `build.yml` then signs both APKs with it.
Google Play Protect may still show a warning for any app installed outside the Play Store; this cannot be removed from the app itself.

## In-app file browser
Vault location and imports use Lita's own file browser and need Android's "All files access". A vault in a normal folder (e.g. `/storage/emulated/0/LitaVault`)
survives uninstalling Lita. Older SAF-based vaults still open.
