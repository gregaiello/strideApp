# STRIDE

## Fork-related info

**Original project from keranm/strideApp and slightly adapted for the x22i, tested and currently working**

Step-by-step guide for this fork, from a factory reset to a .fit on Strava: [docs/stride-fit-test-guide.html](docs/stride-fit-test-guide.html) (download it and open it in a browser; GitHub shows HTML as source).

The software infrastructure/architecture is presented below for information:
<img width="1763" height="930" alt="image" src="https://github.com/user-attachments/assets/6e44d891-9944-4b02-8342-51481d98fe7f" />

**Replace the iFit software on a NordicTrack treadmill. Keep the belt, lose the subscription.**

**Please note**: This is a hobby project by one person and Claude Code, please don't get upset at that. Claude Code is enabling me to be able to do something I've dreamed of doing for a few years but didn't have the ability. Now I'm able to walk/run on my own physical hardware that I bought without it having someone else's subscription software on it. If you don't like that 90% of this was coded with AI tools like Claude Code - then this isn't the project for you. If you want to fork this, put your fingerprints all over it ... please do, I'd welcome that!

> ### 🚧 Early days and factory reset has saved me more than once
>
> It works, and I walk on it most days, but it works on *my* machine. Nobody
> else has installed it yet and I've no idea how it behaves on other hardware.
>
> Nothing here is supported, so use it at your own risk. If it goes wrong a
> factory reset puts the original software back, and I've done that plenty of
> times while building this.
>
> Read the code, take the [protocol notes](protocol/FITPRO_PROTOCOL.md), and
> tell me what board your treadmill has.

> ⚠️ **This drives a motorised treadmill.** Read [SAFETY.md](SAFETY.md) before
> you install anything. Your physical safety key is still the stop that matters
> and it cuts the motor directly. STRIDE drives the incline, but speed is only
> ever suggested. You decide when to go faster.
> The console's own STOP and START keys pause and resume the walk in STRIDE
> as well, so one press is enough. Closing STRIDE stops the belt.

> **Gen 7 / iFit 2.0.** Second-hand reports say this update closes the
> privileged-mode route everything here depends on. If you know how to do this on Gen 7
> let us know the steps and we can update this


I got tired of iFit on my NordicTrack. Manual mode, a 400 m loop, and an
interface that kept asking me to subscribe. The console turns out to be an
Android tablet talking to a separate motor board, so I replaced what runs on
the tablet and left the motor board alone.

The belt and the deck now work with no login, no subscription and no internet.
Every walk is saved as a .fit file, and can copy itself to a NAS on your own
network. I built it with Claude Code alongside me, and there's a
[full write-up of how the jailbreak works](https://theidea.works/blog/jailbreak-nordictrack-treadmill/)
if you'd rather read the story than the steps.

**[v0.8](https://github.com/keranm/strideApp/releases/latest)**, in daily use,
and it now pairs with Zwift over Bluetooth FTMS.

![STRIDE on a NordicTrack console](docs/screenshots/oval-original.png)

**[More screenshots →](docs/GALLERY.md)**

---

## What's in here

| | |
|---|---|
| **`console/`** | The Android app that replaces iFit on the treadmill's own screen. Talks to the motor board over USB, drives the incline, runs guided walks, and gives you five completely different interfaces to pick from. |
| **`protocol/`** | The [FitPro serial protocol](protocol/FITPRO_PROTOCOL.md), decoded and written down. If you're working on a different machine, start here. |

---

## Will it work on my treadmill?

Honestly, maybe. It's proven on one machine, mine:

| | |
|---|---|
| Model | NordicTrack C 1750 |
| Generation | Gen 6, the embedded "CLASSIC" console |
| Motor board | FitPro, device id `0x04` |
| Reported ranges | incline −3 to +12 %, speed 1.6 to 20 km/h |

Other Gen 6 NordicTrack and ProForm consoles on the same board are probably
fine, because the protocol looks like a family rather than a single model, but
nobody has tried. You also need a console you can reach with `adb`, and that's
the part that varies most by model and year.

[`console/usbprobe`](console/usbprobe) asks your board what it is and changes
nothing. It's an app you install on the console, so it needs ADB and the iFit
software out of the way first, which makes it step 6 below rather than
something you can run today.

**Please open an issue with whatever it tells you,** working or not. A list of
known-good boards would be the most useful thing this project could have and it
doesn't exist yet. I'm not buying more treadmills to build one.

---

## Installing

**[docs/INSTALL.md](docs/INSTALL.md)** has the full step by step, but read the
banner at the top of this page first. Nobody who didn't already know the
answers has followed that guide yet, and it marks its own weak spots.

In outline:

1. **Factory reset the console.** Stay offline afterwards.
2. **Enable privileged mode.** Tap ten times, wait seven seconds, tap ten more,
   then answer the challenge code the console gives you.
3. **Force stop iFit Admin** (`com.ifit.eru`) and take away its two
   permissions, then force stop and uninstall iFit (`com.ifit.standalone`).
4. **Enable ADB over Wi-Fi.** Tap Build number seven times, turn on USB
   debugging, join your network.
5. **Run [`tools/unchain.sh`](tools/unchain.sh).** Disables the iFit packages
   and installs the launcher.
6. **Identify your board** with [`console/usbprobe`](console/usbprobe), which
   reads and changes nothing. Report what it says.
7. **Install the console app**, then set it up on its own screen. Either grab
   the signed APK from [the latest release](https://github.com/keranm/strideApp/releases/latest)
   and `adb install -r` it, or build your own with `console/stride/run.sh`.
   Pick one and stay on it — they're signed with different keys, so swapping
   later means uninstalling and losing your settings.

After step 7 you've got a treadmill with no subscription.

If you'd rather not drive it by hand, point Claude Code at this repo, give
it the treadmill's IP address, and ask it to follow the install guide over ADB.
That's how most of my own installs have gone.

---

## Configuration

**The console configures itself.** Who walks, units, warm-up, heart rate,
display, the NAS: all of it lives in Settings on the treadmill's own
screen. No config files, no rebuild.

**Speed or pace.** The Cluster interface shows pace in min/km by default; tap
the number to switch to km/h. Every interface scales its speed and incline
gauges to the range your board reports, so a 40 % deck fills at 40 %. During
a workout Cluster gives the lap in progress its own row: time, distance,
average heart rate, climb and VAM.

**Walking under a film.** Settings → Display → Switch button puts a round
button at the right edge, over every app, that flips between STRIDE and one
streaming app you pick, so LAP and END stay one tap away. Drag it up or down
to move it. It is off until you pick an app, and Android has to allow STRIDE
to draw over other apps: tap ALLOW there, or over adb
`adb shell appops set dev.stride.hud SYSTEM_ALERT_WINDOW allow`.
The console has no Play Store; the [fork guide](docs/stride-fit-test-guide.html)
(step 11) covers installing SmartTube (YouTube), Netflix and Prime Video over
adb. None of them needs Google Play services. The official YouTube app does,
and on this uncertified console Google blocks it within days, so it isn't
offered. Only video apps are offered for the button.

**Headphones.** Settings → Headphones pairs Bluetooth headphones from the
console itself, whose own Bluetooth screen is out of reach. Put them in
pairing mode, press SEARCH, then PAIR. Once connected, every sound the console
makes plays in them: YouTube, Netflix, Prime Video. They reconnect on their own
when switched on near the treadmill, and FORGET removes them completely.
Headphones use classic Bluetooth (A2DP), not BLE. Connecting relies on a
hidden Android 9 call; if a ROM ever refuses it, switching the headphones off
and on still connects them.

**Copy walks to a NAS.** Settings → Treadmill → Copy to NAS sends every saved
.fit over SFTP to `<home>/<walker>/` on your NAS. Enter its IP or network name
(not a `.local` name), port and user, press CHECK to see that it answers, then
type the password once and press SET UP. STRIDE makes its own key, installs
it on the NAS and never keeps the password; from then on it logs in with the
key and only trusts the NAS it first met. A walk the NAS misses waits and is
sent after the next one, or with SEND NOW. On a Synology, enable SSH, SFTP and
the user home service first.

**Routes from the NAS.** Once the NAS is set up, STRIDE makes a `routes`
folder next to the walkers' folders. Every `.gpx` you put there becomes a
route under My routes, and the deck follows its hills by distance walked. It is
fetched at start-up and with FETCH in My routes. The file needs elevation:
Strava (⋯ → Export GPX), Garmin and Komoot exports have it, and a file without
it is listed as skipped rather than walked flat. Heights are smoothed so GPS
noise does not become ramps, and the board's own incline limits still apply.
Delete the file on the NAS to remove the route.

**Resetting settings** (About → Reset every setting) asks for the PIN
**1235**. It's the same everywhere and only there so a stray tap can't wipe the
setup; people, totals and history are kept either way.

---

## How it's put together

A few decisions that will save you reading the code to find them.

- **Kotlin owns the treadmill, HTML owns the screen.** The five interfaces are
  HTML in a WebView, so you can design them in a browser. Tap SPEED+ and the
  belt speeds up, but the request goes through Kotlin first, which clamps it to
  whatever the board says it can do. A bad layout can't outrun the machine.
- **Recording is per person, not per console.** Guests are never recorded.
  They're summarised at the end of a walk and then forgotten.

---

## Fork it

Please do. STRIDE is [Apache-2.0](LICENSE), so you can change it, ship it, sell
it and build something else out of it without asking me. There's no CLA and you
keep the copyright on whatever you write.

The thing I'd most like back is **what board your treadmill has**, working or
not. That list is one machine long today and it can only grow from other
people's treadmills. There's an
[issue template](https://github.com/keranm/strideApp/issues/new/choose) for it.

[CONTRIBUTING.md](CONTRIBUTING.md) covers getting set up, where things live,
how to redesign an interface in a browser without touching a treadmill, and the
rules I'd ask you to keep if you're changing anything that moves the belt.

---

## Thanks

**[nordictrack-ftms-bridge](https://github.com/ciarancoffey/nordictrack-ftms-bridge)**
by @[ciarancoffey](https://github.com/ciarancoffey). A couple of folks on reddit asked whether STRIDE could speak FTMS, which is the
standard Bluetooth service Zwift and friends use to talk to a treadmill, and
I took a look at Ciaran's project. This showed that YES an iFit Android console can be a Bluetooth LE *peripheral*, not just a
central. Using that as inspiration I coded up a working FTMS implementation

That project is AGPL-3.0 and STRIDE is Apache-2.0, so no code moves between
them. FTMS is a published Bluetooth SIG specification, and anything built here
gets built from the spec.

The install guide's [own credit](docs/INSTALL.md#credit) covers the community
Gen 6 modding guide that steps 1.1 to 1.4 follow.

---

## Licence

[Apache-2.0](LICENSE). No warranty, and see [SAFETY.md](SAFETY.md).

STRIDE is an independent project, built for compatibility with hardware its
authors own. Not affiliated with or endorsed by iFIT, ICON Health & Fitness,
NordicTrack, ProForm or Apple. Trademarks belong to their
owners and are used here only to say what this works with.

This repository contains no code from any of those companies.
