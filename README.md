# TV player (working title: Northlight)

An IPTV player for Android TV, tuned for the NVIDIA Shield TV and its remote. The app ships with no content; users add their own playlists.

## Status

Two parts, at different levels of confidence:

- **`core/` (plain Kotlin): compiled and unit-tested.** Playlist parsing, TV guide parsing, channel variant grouping and guide matching, with 31 passing tests.
- **`app/` (the Android TV app): not compiled or run yet.** It was written in a workspace that cannot download the Android build tools. Expect a round of small fixes on the first real build.

What is in place:

- Project and build files (Kotlin, Jetpack Compose, minSdk 26, Android TV launcher entry)
- Three colour themes (Lagoon, Orchid, Midnight), chosen in Settings and saved on the device
- `FocusSurface`, the one focusable building block for the remote
- Top navigation bar with a live clock
- Home screen: hero, rails, shortcut buttons, using invented sample content
- Settings › Appearance with the theme picker

Everything else is listed in `FEATURES.md`.

## Building

Either route produces `app/build/outputs/apk/debug/app-debug.apk`, which can be sideloaded onto the Shield.

- **GitHub:** push this folder to a repository. `.github/workflows/build.yml` builds the APK on every push to `main` and attaches it to the run as a download.
- **Android Studio:** open this folder, let it sync, then Build › Build APK.

## Where things live

| Path | What it holds |
| --- | --- |
| `core/src/main/kotlin/app/tvplayer/core/` | Playlist and guide parsers, variant grouping, guide matching |
| `core/src/test/kotlin/` | Their unit tests |
| `app/src/main/java/app/tvplayer/ui/theme/Theme.kt` | The three palettes, colour tokens and type scale |
| `app/src/main/java/app/tvplayer/data/SettingsRepository.kt` | Saved settings (the chosen theme) |
| `app/src/main/java/app/tvplayer/ui/components/` | Shared pieces: focus surface, text, top navigation |
| `app/src/main/java/app/tvplayer/ui/home/` | Home screen and its sample content |
| `app/src/main/java/app/tvplayer/ui/settings/` | Settings › Appearance |
| `app/src/main/res/values/strings.xml` | The app name (one line to change) |

## Decisions made so far

- **Text and focus are built on Compose Foundation**, not the TV Material components, so the focus look matches the design exactly. The TV Material library is included for later screens.
- **Hilt, Room and Media3 are not added yet.** They come in with the data layer and the player, where they are first needed.
- **Fonts:** the design uses Inter and Space Grotesk. The font files are not bundled yet, so the system sans-serif is used.
- **Package name** is `app.tvplayer`, deliberately neutral so it does not depend on the final app name.
