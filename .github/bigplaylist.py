"""Makes a big test playlist (like a large provider's) for the speed check: an M3U list, its TV guide
(XMLTV) and a Skyfall backup file that adds them. Served to the emulator at http://10.0.2.2:8000/."""
import json, os, random, sys, time
from datetime import datetime, timezone

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
host = "http://10.0.2.2:8000"
random.seed(5)
lines = ["#EXTM3U"]
n = 0
# 3,000 live channels, two copies each (HD and FHD), 1,800 with guide listings.
for i in range(3000):
    for tag in ("HD", "FHD"):
        gid = f'tvg-id="ch{i}" ' if i % 5 < 3 else ""
        lines.append(f'#EXTINF:-1 {gid}tvg-name="UK: Channel {i} {tag}" group-title="Group {i % 60}",UK: Channel {i} {tag}')
        lines.append(f"{host}/live/u/p/{n}.ts"); n += 1
# 20,000 films in 200 groups.
words = ["Return", "Night", "Story", "Last", "Road", "Glass", "River", "Storm", "Garden", "Signal"]
for i in range(20000):
    name = f"The {random.choice(words)} {random.choice(words)} {i} ({1970 + i % 55})"
    lines.append(f'#EXTINF:-1 tvg-logo="{host}/img/m{i % 50}.png" group-title="Films {i % 200}",{name}')
    lines.append(f"{host}/movie/u/p/{i}.mkv")
# 2,000 shows of 10 episodes, listed episode by episode as M3U playlists do.
for s in range(2000):
    show = f"Series {s} {random.choice(words)}"
    for e in range(1, 11):
        lines.append(f'#EXTINF:-1 group-title="Series {s % 100}",{show} S01 E{e:02d}')
        lines.append(f"{host}/series/u/p/{s * 100 + e}.mkv")
open(os.path.join(out, "big.m3u"), "w").write("\n".join(lines) + "\n")

now = int(time.time())
start0 = now - now % 1800 - 24 * 3600
def ts(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y%m%d%H%M%S +0000")
with open(os.path.join(out, "guide.xml"), "w") as g:
    g.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n')
    for i in range(3000):
        if i % 5 < 3:
            g.write(f'<channel id="ch{i}"><display-name>Channel {i}</display-name></channel>\n')
    for i in range(3000):
        if i % 5 >= 3:
            continue
        t = start0
        while t < now + 24 * 3600:
            length = random.choice([15, 30, 30, 60, 60, 90, 120]) * 60
            g.write(f'<programme start="{ts(t)}" stop="{ts(t + length)}" channel="ch{i}"><title>Programme {i}-{t}</title>'
                    f'<desc>A programme on channel {i}.</desc></programme>\n')
            t += length
    g.write("</tv>\n")

json.dump({"format": "skyfall-backup", "version": 1, "createdAt": now * 1000, "playlists": [
    {"name": "Big test playlist", "type": "m3u", "address": f"{host}/big.m3u", "guideUrls": f"{host}/guide.xml"}]},
    open(os.path.join(out, "backup.json"), "w"))
print("big playlist:", n, "live entries, 20000 films, 20000 episodes;", os.path.getsize(os.path.join(out, "guide.xml")) // 1024, "KB guide")
