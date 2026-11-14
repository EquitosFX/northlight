# Feature checklist

Status key: **Tested** = compiled and unit-tested (42 tests in `core/`). **Built** = compiles in the automatic build, but has not been tried on a Shield yet. **Designed** = shown in the design previews, no code yet. **Not started** = neither.

## Foundation and design system

| Feature | Status |
| --- | --- |
| Project, build files, Android TV launcher entry | Built |
| Three colour themes, selectable in Settings, saved on device | Built |
| Colour tokens and type scale | Built |
| Focus component (fill, ring, scale) | Built |
| Top navigation bar with clock | Built |
| Bundled fonts (Inter, Space Grotesk) | Not started |
| Final app name, icon and home-screen banner | Not started (placeholders in place) |
| Automatic APK build recipe | Built (publishes `northlight.apk` as the latest release) |

## Screens

| Feature | Status |
| --- | --- |
| Home: hero, rails, shortcuts (sample content) | Built |
| Home: hero trailer on Up, reorder/hide rails per profile | Not started |
| Settings › Appearance (theme picker) | Built |
| Settings › Playlists (add Xtream Codes or M3U, refresh, remove) | Built |
| Home: rail of the user's imported channels | Built |
| Settings: other sections | Designed (list only) |
| TV Guide with info header, filters and mini player | Designed |
| Programme actions panel | Designed |
| Live TV channel list and info banner | Designed |
| Voice search overlay | Designed |
| Recents | Not started |
| Library and Recordings | Not started |
| Sport hub and team pages | Not started |

## Sources and data

| Feature | Status |
| --- | --- |
| M3U playlist parsing (streaming, 100k entries in about half a second) | Tested |
| Xtream Codes login and list parsing | Tested |
| Importing Xtream Codes accounts and M3U links into the database | Built |
| Stalker/MAC portals, local M3U files | Not started |
| Multiple playlists | Built |
| Quick switcher, merged view | Not started |
| Per-playlist User-Agent, referrer, backup hosts, auto-refresh | Not started |
| XMLTV guide parsing (streaming, gzip-aware) | Tested |
| Automatic guide-to-channel matching | Tested |
| Multiple guide sources, manual matching, time offset, catch-up backfill | Not started |
| Channel variant grouping (SD/HD/FHD/4K/backup) | Tested |
| Check playlist tool | Not started |
| TMDB metadata | Not started |
| Plex / Jellyfin / Emby libraries | Not started |
| Backup and restore | Not started |
| Encrypted credentials | Not started (passwords are currently stored unencrypted in the app's private storage) |
| Local database: playlists and channels | Built |
| Local database: guide tables, search index, timing with 100k+ channels on a Shield | Not started |

## Live TV

| Feature | Status |
| --- | --- |
| Guide grid behaviour (day jump, on-now filter, genre colours) | Not started |
| Favourites, custom groups, hide/rename/renumber, PIN-locked groups | Not started |
| Number entry, last channel, zap overlay, mini-guide | Not started |
| Catch-up: watch from start, go live, seek | Not started |
| Timeshift (pause and rewind live) | Not started |
| Recording: one-off, series, recurring, manual; USB/NAS; conflicts | Not started |
| Reminders | Not started |
| Multiview | Not started |
| Picture-in-picture and the bottom-right mini player | Not started |

## Films and series

| Feature | Status |
| --- | --- |
| Browsing, metadata, resume, watched states, sorting | Not started |
| Watchlist, Continue Watching, autoplay next, episode buttons | Not started |
| Remembered audio/subtitle tracks, OpenSubtitles, subtitle styling | Not started |

## Player

| Feature | Status |
| --- | --- |
| ExoPlayer default, VLC fallback, external player hand-off | Not started |
| Frame-rate matching, HDR, audio passthrough, audio delay | Not started |
| Stream info badge, restart channel, auto-reconnect | Not started |
| Sleep timer, zoom modes, deinterlacing, buffer settings | Not started |

## Profiles, sport, voice, system

| Feature | Status |
| --- | --- |
| Profiles, kids profile, PINs, app lock | Not started |
| Sport: followed teams, fixtures, scores, channel finder, match centre | Not started |
| Voice: mic button, in-app recognition, natural requests, help screen | Not started |
| Android TV global search and home-screen rows | Not started |
| Remappable Shield menu button | Not started |

## Quality

| Feature | Status |
| --- | --- |
| First successful build on real tooling | Done |
| Tested on a Shield TV | Not started |
| Unit tests: parsers, variant grouping, guide matching | Tested |
| Unit tests: recording scheduler | Not started |
| Focus-navigation UI tests | Not started |
| Performance targets (cold start under 2s, zap under 1s) | Not started |
| Accessibility pass (TalkBack, text scaling) | Not started |
