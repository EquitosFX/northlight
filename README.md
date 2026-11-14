# TV player (working title: Northlight)

An IPTV player for Android TV, tuned for the NVIDIA Shield TV and its remote. The app ships with no content; users add their own playlists.

## Status

Two parts, at different levels of confidence:

- **`core/` (plain Kotlin): compiled and unit-tested.** Playlist parsing, TV guide parsing, Xtream Codes parsing, channel variant grouping and guide matching, channel line-up and reconnect timing, with 55 passing tests.
- **`app/` (the Android TV app): compiles in the automatic build, not yet tried on a Shield.** Expect adjustments once it has been used on a real device.

What is in place:

- Project and build files (Kotlin, Jetpack Compose, minSdk 26, Android TV launcher entry)
- Three colour themes (Lagoon, Orchid, Midnight), chosen in Settings and saved on the device
- `FocusSurface`, the one focusable building block for the remote
- Top navigation bar with a live clock
- Home screen: hero, rails, shortcut buttons, using invented sample content
- Settings › Appearance with the theme picker
- Settings › Playlists: add an Xtream Codes account or an M3U link, refresh, remove
- A database of playlists and channels, filled in batches as a playlist downloads
- TV guide: downloaded after each playlist import and refreshed when older than 12 hours; TV Guide tab with a programme grid; now and next in the player
- Films and Series (from the Home shortcuts): poster grid by group; series open their episodes; playback resumes where you left off; Continue watching rail on Home
- Search tab: channels, films and series by name
- Favourite channels: Right in the channel list adds or removes one; Favourites group in the group list
- Series: next episode starts after a 10-second countdown
- Live TV player: Up/Down changes channel, OK opens the channel list (Left for groups), Left/Right shows the info banner, Back shrinks the picture to the corner (hold Back to bring it back)

Everything else is listed in `FEATURES.md`.

## Building

The newest build is always at:

    https://github.com/EquitosFX/northlight/releases/latest/download/northlight.apk

- **GitHub:** `.github/workflows/build.yml` tests and builds every push. Pushes to `main` also publish the APK at the address above.
- **Android Studio:** open this folder, let it sync, then Build › Build APK.

## Where things live

| Path | What it holds |
| --- | --- |
| `core/src/main/kotlin/app/tvplayer/core/` | Playlist and guide parsers, variant grouping, guide matching |
| `core/src/test/kotlin/` | Their unit tests |
| `app/src/main/java/app/tvplayer/ui/theme/Theme.kt` | The three palettes, colour tokens and type scale |
| `core/src/main/kotlin/app/tvplayer/core/xtream/` | Xtream Codes addresses and answer parsing |
| `app/src/main/java/app/tvplayer/data/SettingsRepository.kt` | Saved settings (the chosen theme) |
| `app/src/main/java/app/tvplayer/data/PlaylistRepository.kt` | Adding, refreshing and removing playlists |
| `app/src/main/java/app/tvplayer/data/db/` | The database tables |
| `app/src/main/java/app/tvplayer/player/` | The live TV player and its screens |
| `app/src/main/java/app/tvplayer/ui/guide/` | The TV Guide screen |
| `app/src/main/java/app/tvplayer/ui/vod/` | Films and Series screens |
| `app/src/main/java/app/tvplayer/ui/search/` | Search screen |
| `app/src/main/java/app/tvplayer/data/ChannelsRepository.kt` | Channel line-up and programme look-ups |
| `core/src/main/kotlin/app/tvplayer/core/live/` | Channel line-up and reconnect timing |
| `app/src/main/java/app/tvplayer/ui/components/` | Shared pieces: focus surface, text, top navigation |
| `app/src/main/java/app/tvplayer/ui/home/` | Home screen and its sample content |
| `app/src/main/java/app/tvplayer/ui/settings/` | Settings: Playlists and Appearance |
| `app/src/main/res/values/strings.xml` | The app name (one line to change) |

## Decisions made so far

- **Text and focus are built on Compose Foundation**, not the TV Material components, so the focus look matches the design exactly. The TV Material library is included for later screens.
- **Room is the database. Hilt is not used:** the few shared objects are created by hand in `NorthlightApp.kt`, which is simpler at this size. Media3 arrives with the player.
- **Signing:** builds are signed with the fixed key in `app/debug.keystore` so each new APK updates the installed one. The key is public in this repository, which is fine for personal sideloading but not for publishing to a store.
- **Never commit playlist addresses, usernames or passwords.** This repository is public.
- **Fonts:** the design uses Inter and Space Grotesk. The font files are not bundled yet, so the system sans-serif is used.
- **Package name** is `app.tvplayer`, deliberately neutral so it does not depend on the final app name.
