---
name: verify-mercurio
description: Prove a Mercurio change (the Android SSH and Herdr agent console, a ConnectBot fork) by building the head in a full clone, checking the APK is that head, installing it on an emulator and driving the changed screen through uiautomator, keeping screenshots and dumps. Use when proving a Mercurio PR or reproducing a console bug on an emulator.
---

# Verify Mercurio

The user surface is the Mercurio app on an Android device: the host list, then the console
(terminal, two-row compose bar and its tray, reader). A proof drives that surface on an
emulator over adb: `uiautomator dump` to read the screen, `input tap` to act, `screencap`
for screenshots. Green tests alone are not proof.

## Build the head

Use a full clone, never a git worktree: the app-versioning Gradle plugin fails in worktrees.
Run one Gradle build per clone at a time.

```bash
git clone https://github.com/Ormus-Solutions/mercurio.git mercurio-verify
cd mercurio-verify
git fetch origin pull/<N>/head && git checkout --detach FETCH_HEAD
./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug
./gradlew :app:lintOssDebug    # on its own: lint can crash when it shares a Gradle run
```

`spotlessCheck` ratchets against `origin/main`, so keep that ref at GitHub's main.

The build writes its versionName to
`app/build/outputs/app_versioning/ossDebug/version_name.txt` as `git-<tag>-<n>-g<abbrev>`.
`<abbrev>` must be a prefix of the head SHA under review, with a clean tree. That is the proof
that the APK at `app/build/outputs/apk/oss/debug/app-oss-debug.apk` is this head.

## Install and launch

```bash
adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk
adb shell dumpsys package solutions.ormus.logos.debug | grep versionName   # must equal the built one
adb shell am start -n solutions.ormus.logos.debug/org.connectbot.ui.MainActivity
```

The app opens on the host list (title `Mercurio`). Installing an older head over a newer one
needs `adb uninstall solutions.ormus.logos.debug` first, which wipes the app's hosts and known
host keys on that device.

## Drive

```bash
adb shell uiautomator dump /sdcard/mercurio.xml && adb exec-out cat /sdcard/mercurio.xml
adb shell input tap <x> <y>          # centre of the node's bounds from the dump
adb exec-out screencap -p > shot.png
```

Find nodes by their text or content-desc, never by remembered coordinates. The labels are the
English string resources in `app/src/main/res/values/strings.xml`. Drive every entry point the
change touches: for example a compose bar key from row 1 and from the tray (`More keys`), or a
host from the host list and from the `Sessions` drawer. `uiautomator dump` fails while the
screen animates; retry it.

Use a host you own, or a local test server, for anything past the login prompt. Never type a
password, accept a host key or send keys into a session you do not own from a verify run.

## Evidence

- The head SHA, the built versionName and the installed versionName.
- A screenshot and a dump before and after each user action.
- A dump also lists nodes under a dialog scrim, so check the screenshot for what the user can
  actually see.
- An entry point you could not reach is reported with the command tried and the unmet
  precondition, never as verified through a different one.
- Screenshots can show host addresses and key fingerprints. Crop or blur them before posting
  them anywhere public.
