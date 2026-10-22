# Aniplayer for Android

This is a native Android application that follows the Aniplayer mobile workflow. It uses Kotlin, Jetpack Compose, Media3 for playback, Android's Storage Access Framework (SAF) to access a user-selected course directory, and a private on-device SQLite database.

## Build and run

Open the `android/` directory in Android Studio, allow Gradle to sync, and run the `app` configuration on an Android device or emulator. The project requires JDK 17 and Android SDK Platform 35. From a terminal, build with `./gradlew assembleDebug` and run local unit tests with `./gradlew testDebugUnitTest`.

On first launch, choose a folder containing one subfolder per course. Grant access when Android asks. The app stores the folder permission and scans supported video formats (`mp4`, `mkv`, `webm`, `avi`, `mov`, and `m4v`) recursively. Video files remain in the selected folder. On later launches, the saved course index is loaded from SQLite without rescanning; use the refresh button when you want to scan for course changes.

## Local data and playback

The app stores its course index, lesson progress, imported subtitle metadata, selected root folder URI, and Pomodoro state in its private `aniplayer.db` SQLite database. Rescanning uses the persisted document URI as a stable identity and preserves watch progress for files that remain in place.

Course content highlights and automatically scrolls to the current or most recently played lesson, including completed lessons.

Media3 uses the Android device's available decoders. Codec/container support varies by device; the Android app does not transcode media. Playback errors explain that the file may use an unsupported codec or container.

Imported `.srt` and `.vtt` subtitles are copied into app-private storage and attached to the video as player tracks. Subtitle imports are limited to 10 MB.

Use the fullscreen control in the player to rotate into an immersive landscape view; use the exit control or system Back action to return. Deleting a course requires confirmation and permanently removes its course folder (videos, subtitles, and other files), its SQLite entries and progress, and its app-private imported subtitle copies.

The display stays awake during active video playback and returns to the device's normal screen timeout when playback pauses or the player closes.

The Pomodoro timer provides 25-minute focus and 5-minute break sessions. Its state survives app restarts. A WorkManager notification is scheduled for a running session; Android notification permission is requested when the user starts a timer.

## Data privacy

No account or network service is required. The Android database and subtitle copies are private to the app and are separate from the web app's database. Uninstalling the app removes app-private data. The source course videos are not copied, moved, or deleted.

## Tests

Run unit tests with `./gradlew test` and Android instrumentation tests with `./gradlew connectedAndroidTest` after opening/syncing the project in Android Studio and installing the required SDK and device/emulator.
