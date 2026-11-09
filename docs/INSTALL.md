# Installing STRIDE

> 🚧 **DRAFT.** Nobody has followed this page end to end on a factory-reset
> machine. [Report anything that does not match](https://github.com/keranm/strideApp/issues).

**Read [SAFETY.md](../SAFETY.md) before you start.** This software drives a
motorised treadmill.

> **Gen 7 / iFit 2.0.** Second-hand reports say this update closes the
> privileged-mode route used below. Nobody here has seen it happen, and it is
> one passing comment on another site rather than anything tested. Declining
> the update costs nothing, so decline it until somebody knows either way.

To undo everything: factory reset the console.

---

## What you need

| | |
|---|---|
| A treadmill | NordicTrack or ProForm, FitPro motor board, Android console. Check [Step 0](#step-0-check-your-treadmill). |
| A computer | macOS or Linux, with `adb` and Python 3.9+. |
| JDK 17 + Android SDK | Only if you build it yourself. There is a [signed APK](https://github.com/keranm/strideApp/releases/latest) if you would rather not — see [1.7](#17-install-stride). |
| A NAS | Optional. Any SSH/SFTP server, for copying walks off the console and fetching GPX routes. |

Allow about an hour. Once Part 1 is done the treadmill works with no
subscription.

---

## Step 0. Check your treadmill

STRIDE is confirmed on one machine:

| | |
|---|---|
| Model | NordicTrack C 1750 |
| Generation | Gen 6, the "CLASSIC" embedded console |
| GlassOS | 8.51.7.1070 |
| System version | EKA2_20221110 |
| Firmware | 84.121 |
| Motor board | FitPro, device id `0x04` |
| Ranges reported | incline -3 to +12 %, speed 1.6 to 20 km/h |

- **Likely to work:** other Gen 6 NordicTrack and ProForm consoles on a FitPro board.
- **Unknown:** Gen 7 / iFit 2.0. Reported not to work, untested here.
- **Required:** an Android console you can reach with `adb`.

What you can check right now, on the console's own screen:

1. Go to **Settings → About tablet**.
2. Read **Build number**. This machine reports `EKA2_20221110`.
3. Read **Android version**. This machine is on Android 9.

You cannot identify the motor board yet. That needs ADB and the iFit software
out of the way, so it happens in [step 1.6](#16-identify-the-motor-board).

[Open an issue](https://github.com/keranm/strideApp/issues) with what you find,
working or not.

---

## Part 1. The console

### 1.1 Factory reset

1. Find the pinhole reset button. Look for a single small hole on the side
   panel or the top of the console.
2. Hold the pinhole in while you switch the treadmill on at the power switch.
3. Wait for *System recovery* in blue text.
4. Let it finish. Takes five to ten minutes.
5. Do not join Wi-Fi yet. Stay offline until iFit is disabled in step 1.3.

### 1.2 Enable privileged mode

On the iFit welcome screen:

1. Tap a blank area of the screen ten times.
2. Wait seven seconds. Nothing appears during the pause.
3. Tap the same spot ten more times.
4. The console shows a challenge code. Get the matching response code, either
   by ringing NordicTrack support and asking for one, or from a third-party
   calculator such as <https://getresponsecode.com> (no connection to this
   project).
5. Enter the response code.
6. The console opens the default Android apps screen. Tap **Settings**.

### 1.3 Stop iFit locking you out again

Do this before anything else. Skipping it undoes every later step at the next
reboot.

Open **Settings → Apps**. Deal with two apps.

**iFit Admin** (`com.ifit.eru`):

1. Tap **Force stop**.
2. Go to **Settings → Apps → Special access → Display over other apps**.
3. Set iFit Admin to **Not allowed**.
4. Go to **Settings → Apps → Special access → Modify system settings**.
5. Set iFit Admin to off.

![iFit Admin app info](screenshots/install-ifit-admin.png)

![Display over other apps](screenshots/install-display-over-other-apps.png)

iFit Admin is a system app and cannot be uninstalled. Force stop plus those two
permissions is as far as it goes.

**iFit** (`com.ifit.standalone`):

1. Tap **Force stop**.
2. Tap **Uninstall**.

![iFit app info](screenshots/install-ifit-app.png)

### 1.4 Enable ADB over Wi-Fi

1. Go to **Settings → About tablet**.
2. Tap **Build number** seven times.

   ![Build number](screenshots/install-build-number.png)

   Tapping Build number seven times is what turns on Developer Mode. After
   about four taps the console starts counting down how many taps are left,
   and confirms that Developer Mode is enabled on the seventh.

3. Go to **Settings → Developer options**.

   ![Developer options](screenshots/install-developer-options.png)

4. Turn on **USB debugging**.

   ![USB debugging](screenshots/install-usb-debugging.png)

5. Pull down the notification panel, long-press **Wi-Fi**, and join your network.
6. Go to **Settings → About tablet** and read the **IP address**.

   ![IP address](screenshots/install-ip-address.png)

On your computer:

```sh
adb connect <console-ip>:5555
adb devices
```

✅ The console lists as `device`, not `unauthorized` or `offline`.

Do not turn USB debugging off afterwards.

### 1.5 Read unchain.sh before running it

[`tools/unchain.sh`](../tools/unchain.sh) does five things in order:

1. Disables `com.ifit.eru` and denies it `SYSTEM_ALERT_WINDOW` and `WRITE_SETTINGS`.
2. Disables the remaining iFit packages.
3. Turns on developer settings and keeps the screen awake.
4. Installs a launcher.
5. Installs STRIDE if you have built it, and sets it as the home app.

Run it with `STRIDE_KIOSK=0` to skip step 5. The script prints the command that
undoes the home-app change.

```sh
./tools/unchain.sh
```

> The console's USB port is the link to the motor board, so Wi-Fi ADB is
> usually your only way in. Whether it survives a power cycle varies by
> console. Test yours before relying on it. STRIDE itself needs no ADB to run.

### 1.6 Identify the motor board

`console/usbprobe` asks the board what it is. It reads and writes nothing.

```sh
cd console/usbprobe
./run.sh
```

Read the result on the console's screen.

✅ It names a board and a device id. This machine reports FitPro, device `0x04`,
incline -3 to +12 %, speed 1.6 to 20 km/h.

If it says the device is claimed, `com.ifit.eru` is running again. Re-run
`tools/unchain.sh`.

**[Open an issue](https://github.com/keranm/strideApp/issues) with what it
says,** working or not. A list of known-good boards is the most useful thing
this project could have and it does not exist yet.

### 1.7 Install STRIDE

Two ways in. They end at the same screen, so take whichever suits — but read
the last paragraph before picking, because moving between them later costs you
your settings.

#### Option A — install the released APK

Nothing to build. You need `adb`, which you already have from step 1.4.

Download the APK from
[the latest release](https://github.com/keranm/strideApp/releases/latest), then:

```sh
adb install -r stride-*.apk
adb shell am start -n dev.stride.hud/.MainActivity
```

To check what you downloaded, use the certificate rather than a file hash. The
hash changes every release; the certificate does not. Every build published
here is signed by `CN=STRIDE, O=The Idea Works, C=AU`, SHA-256
`274a6f2e308df50e1e4219f5bd1f4aca5fda8ccd5feccc7c52579c0c8dd302e8`:

```sh
apksigner verify --print-certs stride-*.apk
```

Anything else did not come from here.

It ships with nobody's credentials in it. Everything is set on the console in
step 1.8.

#### Option B — build it yourself

You need JDK 17 and the Android SDK.

```sh
cd console/stride
./run.sh
```

`run.sh` builds, installs, launches, and tails the log. It finds the Android
SDK via `ANDROID_HOME` or `~/Library/Android/sdk`.

**Read what it prints.** Without a signing key in `local.properties` it builds
a *debug* APK and says so. A debug build is `debuggable`, which lets anything
else on the console read this app's stored settings — the NAS key
included — and it opens the WebView DevTools socket. That is fine on a bench
and is not what you want on a machine that sits in a hallway for years. To
build the same thing the release is, generate a key and use `./run.sh release`;
the four properties are documented at the top of
`console/stride/app/build.gradle.kts`.

#### Pick one and stay on it

Android identifies an app by its signing key, so a build of yours and the
released APK are different apps as far as the console is concerned. Installing
one over the other fails with a signature mismatch, and the only way across is
`adb uninstall dev.stride.hud` first — which takes your settings, your people
and your workout history with it.

✅ Either way: the console shows the STRIDE welcome screen, and the log prints
`limits: <min>..<max> km/h, <min>..<max> %`.

### 1.8 Set up STRIDE

Everything is on the treadmill's own screen. There are no config files.

- **Settings → Who walks.** Add yourself. Nobody is pre-configured.
- **Settings → Treadmill.** Units, warm-up, cool-down, guided-walk speed limit.
- **Settings → Interface.** Pick one of five. *Original* is the most tested.
- **Settings → Treadmill → Copy to NAS.** Optional. Copies every walk's .fit to
  the NAS over SFTP and fetches GPX routes from its `routes` folder. The
  [README](../README.md#configuration) walks through it.

✅ Clip the safety key, choose a workout, and check the belt starts and the
distance climbs.

---

## If it goes wrong

1. Pull the safety key.
2. Switch the treadmill off at the wall.
3. To restore the manufacturer's software, factory reset from the console's own
   recovery.

---

## Credit

Steps 1.1 to 1.4 follow a community *NordicTrack Gen 6 modding guide*. That
guide heads towards QZ Companion. STRIDE goes a different way after 1.4.

If you know who wrote it, [tell us](https://github.com/keranm/strideApp/issues).

---

## Testing this guide

Most likely to be wrong:

- **Steps 1.1 to 1.4.** Written up after the fact. Nobody has followed this page
  on a freshly reset machine.
- **Every ✅ check.** If one does not happen, that is the bug.
- **Anything you had to work out that is not written down.**

Open an issue. "Stuck at 1.1 on a 2019 Commercial 1750" is useful.
