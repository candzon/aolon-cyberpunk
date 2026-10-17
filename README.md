# Aolon Cyberpunk

My own Android app for the Aolon smartwatch. The official app works, but I wanted my data in my own Firebase, a dark UI I actually like looking at, and a gym log and cardio tracker in the same place as the watch. So I rebuilt it in Kotlin and Jetpack Compose.

It talks to the watch directly over Bluetooth. The watch uses the UTE protocol (the same family as GloryFit), and every command in `ble/WatchProtocol.kt` was worked out from how the official app talks to the watch, then checked against real packets from my own watch.

## What it does

Health shows steps by hour, heart rate and SpO2 measured on demand, and syncs the watch clock. My watch has no blood pressure sensor, so that row disappears whenever the watch reports it has none.

Sport has two tabs. Gym is a set-by-set log with routines, a rest timer that vibrates, personal records (estimated 1RM with the Epley formula), around 58 built-in exercises plus your own, and an import from Lyfta if you already log there. Any past workout can be repeated or saved as a routine.

Cardio records walks, runs and rides with the phone's GPS: a route on an OpenStreetMap map, distance, moving time, pace, per-km splits, and auto-pause when you stop. An indoor Workout mode records time and heart rate only. Heart rate comes from the watch during every activity.

Heart rate zones follow the common 220 minus age estimate with five bands (50 to 100 percent of max, as published by the Cleveland Clinic). Pick a target zone before you start and the app tells you, on the phone and on the watch, when you drift above or below it. After the activity you get time spent in each zone.

Body has a body fat calculator (the U.S. Navy method), weight and body fat tracking over time, and calories burned.

Watch notifications forward the phone notifications you choose, app by app, to the watch screen. Apps the watch knows (WhatsApp, Telegram, Gmail, Instagram and about 25 others) show with their own icon; everything else shows as Others with the app name in front.

Deleting anything is a soft delete with an Undo button, so a mis-tap never loses data.

## Things to know

- The watch only accepts one connection. Close the official Aolon app before using this one.
- Notifications reach the watch only while the app is running. If you swipe it away from recents, the Bluetooth link goes with it.
- The watch draws notification icons from its own built-in set. There is no way to send it a custom icon, which is why the app's own alerts show as Others.
- 220 minus age is an estimate. Your real max heart rate can be off by 10 bpm or more, so treat the zones as a guide.
- Emoji are removed from notifications before sending. The watch cannot draw them.

## Building it

You need Android Studio (or just the JDK 17+ and the Android SDK), a phone on Android 8.0 or newer, and your own Firebase project.

1. Create a Firebase project with Realtime Database and Google sign-in turned on.
2. Add an Android app with the package name `com.feri.aolon_cyberpunk` and register the SHA-1 of the key you sign with. For a debug build that is the one from `~/.android/debug.keystore`.
3. Download `google-services.json` into `app/`. The one in this repo points at my project, so replace it.
4. Build and install:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is signed with the debug key on purpose, because that is the key registered for Google sign-in in my Firebase project. If you plan to publish, create a proper release keystore and register its SHA-1 too.

Run the unit tests with `./gradlew testDebugUnitTest`. They cover the parts that are easy to get wrong: body fat maths, gym records, the Lyfta import, GPS track filtering and splits, heart rate zones, and the watch message packets.

## Where the data lives

Everything you record goes to Firebase Realtime Database under `aolon/users/{your uid}`, so only your Google account can read it (with the usual per-user security rules). GPS routes are stored apart from the activity list so the history screen stays light.

A few things never leave the phone: the Lyfta sign-in tokens, the list of apps allowed to notify the watch, and the content of the notifications themselves. The in-app Bluetooth log also hides message text and shows only the packet header and length.

## Project layout

```
app/src/main/java/com/feri/aolon_cyberpunk/
  ble/       Bluetooth link and the UTE watch protocol
  cardio/    GPS recording, auto-pause, splits, heart rate zones
  gym/       Gym log, exercise library, records
  lyfta/     Lyfta sign-in and workout import
  notify/    Notification forwarding to the watch
  ui/        Compose screens
```

## Credits

Maps from OpenStreetMap contributors, drawn with osmdroid. Gym import uses the Lyfta API. This is a personal project and is not affiliated with Aolon, UTE or Lyfta.
