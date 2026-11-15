# Feature checklist

Status key: **Tested** = compiled and unit-tested (64 tests in `core/`). **Built** = compiles in the automatic build, but has not been tried on a Shield yet. **Designed** = shown in the design previews, no code yet. **Not started** = neither.

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
| TV Guide: info header, time ruler, programme grid, mini player over it | Built |
| TV Guide: group filter chips, jump by day | Not started |
| Programme actions panel | Designed |
| Live TV: full-screen player, channel list with groups, info banner | Built |
| Voice search overlay | Designed |
| Search: channels, films and series by name | Built |
| Voice search | Not started |
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
| Importing guides (several per playlist), automatic matching, refresh when older than 12 hours | Built |
| Manual matching, time offset, extra guide addresses, catch-up backfill | Not started |
| Channel variant grouping (SD/HD/FHD/4K/backup) | Tested |
| Check playlist tool | Not started |
| TMDB metadata (viewer's own key): film pages, series details, cached look-ups | Built |
| People: search for actors and directors, selectable cast and crew on title pages, person page listing their titles in your playlists | Built |
| TMDB trailers, "more like this" | Not started |
| Plex / Jellyfin / Emby libraries | Not started |
| Backup and restore | Not started |
| Encrypted credentials | Not started. Xtream stream addresses contain the username and password, so encrypting the password field alone would not help; it needs stream addresses built at play time instead |
| Local database: playlists and channels | Built |
| Local database: guide tables, search index, timing with 100k+ channels on a Shield | Not started |

## Live TV

| Feature | Status |
| --- | --- |
| Guide grid behaviour (day jump, on-now filter, genre colours) | Not started |
| Favourite channels (Right in the channel list) and a Favourites group | Built |
| Custom groups, hide/rename/renumber, PIN-locked groups | Not started |
| Channel up/down with wrap-around, last channel remembered | Built |
| Now and next in the info banner and channel list | Built |
| Number entry | Not started |
| Catch-up: watch from start, go live, seek | Not started |
| Timeshift (pause and rewind live) | Not started |
| Recording: one-off, series, recurring, manual; USB/NAS; conflicts | Not started |
| Reminders | Not started |
| Multiview | Not started |
| Bottom-right mini player while browsing (hold Back to return) | Built |
| Android picture-in-picture outside the app | Not started |

## Films and series

| Feature | Status |
| --- | --- |
| Films and Series screens: groups, poster grid, series seasons and episodes | Built |
| Playback with pause and 10-second skip, resume where left off | Built |
| Film page with Play, Resume and Start over | Built |
| Watched marks, sorting | Not started |
| Continue Watching rail on Home | Built |
| Autoplay next episode with countdown, next/previous episode media keys | Built |
| Watchlist | Not started |
| Remembered audio/subtitle tracks, OpenSubtitles, subtitle styling | Not started |

## Player

| Feature | Status |
| --- | --- |
| ExoPlayer playback (TS and HLS), per-channel User-Agent and referrer | Built |
| VLC fallback, FFmpeg software decoders, external player hand-off | Not started |
| Frame-rate matching, HDR, audio passthrough | Built on the player's defaults; needs checking on a Shield |
| Audio delay | Not started |
| Stream info, auto-reconnect, fall-through to backup copies, retry | Built |
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
| Unit tests: parsers, variant grouping, guide matching, line-up, reconnect timing | Tested |
| Unit tests: recording scheduler | Not started |
| Focus-navigation UI tests | Not started |
| Performance targets (cold start under 2s, zap under 1s) | Not started |
| Accessibility pass (TalkBack, text scaling) | Not started |
