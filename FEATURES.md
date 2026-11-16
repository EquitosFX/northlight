# Feature checklist

Status key: **Tested** = compiled and unit-tested (64 tests in `core/`). **Built** = compiles in the automatic build, but has not been tried on a Shield yet. **Designed** = shown in the design previews, no code yet. **Not started** = neither.

## Foundation and design system

| Feature | Status |
| --- | --- |
| Project, build files, Android TV launcher entry | Built |
| Three colour themes, selectable in Settings, saved on device | Built |
| Colour tokens and type scale | Built |
| Focus component (fill, ring, scale) | Built |
| Side panel: clock, live picture, menu that previews sections, tucks away while browsing | Built |
| Bundled typeface (Figtree, SIL Open Font License) | Built |
| Skyfall branding: icon, TV banner, start-up screen, Skyfall theme | Built |
| In-app updates from GitHub (daily check, verified download, install over the top) | Built |
| Player controls on OK (rewind, pause, forward, go live, subtitles, audio, channels / next episode) | Built |
| Pause and rewind live TV (local rolling recording, 15 min to 2 hours) | Built; needs testing on real streams |
| Subtitle and audio track choice; OpenSubtitles for films and episodes | Built; needs testing on real streams |
| TV guide: full-screen listings after choosing a category; two-step Back | Built |
| Final app name, icon and home-screen banner | Not started (placeholders in place) |
| Automatic APK build recipe | Built (publishes `northlight.apk` as the latest release) |

## Screens

| Feature | Status |
| --- | --- |
| Home: welcome when empty; hero for last channel; shortcuts; Continue watching, Recent channels, Favourites rails | Built |
| Home: hero trailer on Up, reorder/hide rails per profile | Not started |
| Recents: recent channels and films to carry on with | Built |
| Settings › Appearance (theme picker) | Built |
| Settings › Playlists (add Xtream Codes or M3U, refresh, remove) | Built |
| Settings: other sections | Designed (list only) |
| TV Guide: info header, time ruler, programme grid, mini player over it | Built |
| TV Guide: category list (Favourites, All channels, groups) | Built |
| TV Guide: jump by day | Built |
| Programme actions panel | Designed |
| Live TV: full-screen player, channel list with groups, info banner | Built |
| Voice search overlay | Designed |
| Search: channels, films and series by name | Built |
| Voice search: Speak button and the remote's search/voice key, via Android TV's speech screen | Built (what the Shield mic button sends needs checking on the device) |
| Library and Recordings | Not started |
| Sport hub (from the TV guide) and team pages | Hub built |

## Sources and data

| Feature | Status |
| --- | --- |
| M3U playlist parsing (streaming, 100k entries in about half a second) | Tested |
| Xtream Codes login and list parsing | Tested |
| Importing Xtream Codes accounts and M3U links into the database | Built |
| Stalker/MAC portals, local M3U files | Not started |
| Multiple playlists | Built |
| Quick switcher, merged view | Not started |
| Per-playlist User-Agent, referrer, backup hosts, auto-refresh | Auto-refresh built |
| XMLTV guide parsing (streaming, gzip-aware) | Tested |
| Automatic guide-to-channel matching | Tested |
| Importing guides (several per playlist), automatic matching, refresh when older than 12 hours | Built |
| Manual matching, time offset, extra guide addresses, catch-up backfill | Not started |
| Channel variant grouping (SD/HD/FHD/4K/backup) | Tested |
| Check playlist tool (channel check, marks in the guide) | Built |
| TMDB metadata (viewer's own key): film pages, series details, cached look-ups | Built |
| People: search for actors and directors, selectable cast and crew on title pages, person page listing their titles in your playlists | Built |
| TMDB trailers (via YouTube), "more like this" | Trailers built |
| Plex / Jellyfin / Emby libraries | Not started |
| Backup and restore (on the Shield, Downloads, phone over Wi-Fi) | Built |
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
| Browse other channels while watching (Up/Down shows their now and next; OK switches) | Built |
| Now and next in the info banner and channel list | Built |
| Number entry | Not started |
| Catch-up: the guide goes back as far as the provider keeps programmes; OK on a past programme plays it from the start (Xtream Codes and M3U catch-up types) | Built |
| Timeshift (pause and rewind live, back to live) | Built |
| Recording: one-off, series, recurring, manual; USB/NAS; conflicts | Not started |
| Reminders (pop-up while Skyfall is open; listed in My list) | Built |
| Multiview | Not started |
| Live picture in the side panel while browsing (hold Back or select it to return) | Built |
| Android picture-in-picture outside the app | Not started |

## Films and series

| Feature | Status |
| --- | --- |
| Films and Series screens: groups, poster grid, series seasons and episodes | Built |
| Playback with pause and 10-second skip, resume where left off | Built |
| Film page with Play, Resume and Start over | Built |
| Watched ticks on films and episodes (and Mark as watched on a film page) | Built |
| New episode alerts for series in My list (checked every 4 hours) | Built |
| Christmas row on Home, 1 November to 6 January | Built |
| Sorting | Not started |
| Continue Watching rail on Home | Built |
| Autoplay next episode with countdown, next/previous episode media keys | Built |
| Watchlist (My list, Home rail) | Built |
| Remembered audio/subtitle tracks, OpenSubtitles, subtitle styling | OpenSubtitles and styling (Netflix style, box, outline; size, font, colour) built; remembering tracks not started |
| Skip intro / Skip recap and an early Up next over the credits (TheIntroDB timings, needs a TMDB key) | Built |
| "Because you watched…" suggestions on Home (TMDB recommendations matched to your playlists) | Built |
| Movie night: ten questions, then a shortlist from your playlists with ratings, running times and how the picks differ, and Show me others for the same answers (needs a TMDB key) | Built |
| Search results as poster, person and channel tiles | Built |
| Progress bar you can move along with Left/Right (faster when held); 30-second and 5-minute skips; catch-up moves by whole minutes | Built |

## Player

| Feature | Status |
| --- | --- |
| ExoPlayer playback (TS and HLS), per-channel User-Agent and referrer | Built |
| VLC backup player (automatic), external player hand-off | Backup player built |
| Frame-rate matching (50 Hz for UK channels, 24 Hz for films; measured from the stream when it doesn't say) | Built |
| Sound formats the box can't decode itself (Dolby Digital, DTS, MP2) through a built-in FFmpeg decoder | Built |
| HDR, audio passthrough | Built on the player's defaults; needs checking on a Shield |
| Lighter running on less powerful boxes (Chromecast with Google TV): still snow, paced guide scrolling | Built |
| Audio delay (lip sync, 50 ms steps, in the player and Settings › Player) | Built |
| Stream info, auto-reconnect, fall-through to backup copies, retry | Built |
| Sleep timer (30 min to 2 hours, warns a minute before) | Built |
| Black-screen detection: no picture after 7 s tries without rewind, then other copies, then the backup player | Built |
| Zoom modes, deinterlacing, buffer settings | Not started |

## Profiles, sport, voice, system

| Feature | Status |
| --- | --- |
| Profiles, kids profile, PINs, app lock | Not started |
| Sport: followed teams, fixtures, scores, channel finder, match centre | Not started |
| Voice: mic button and in-app recognition in Search | Built; natural requests and help screen not started |
| Android TV global search and home-screen rows | Home-screen rows built (Continue watching, recent channels) |
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
