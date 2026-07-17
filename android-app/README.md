# Video LMS Android App

A native Android application for streaming and managing video courses. This app works with your existing FastAPI backend while also supporting local video playback from device storage.

## Features

### Local Video Support
- **Scan Device Storage**: Automatically scans Movies, Downloads, Videos, and Courses folders
- **Local Playback**: Play video files (mp4, mkv, webm, avi, mov) directly from device
- **SQLite Progress Tracking**: Stores watch progress locally using Room database
- **Offline Access**: Watch downloaded videos without internet connection

### Server Streaming
- **FastAPI Integration**: Connects to your existing FastAPI backend
- **Dual Progress Saving**: When streaming from server:
  - Saves progress to **local SQLite** database (shown in UI)
  - Simultaneously saves to **server** via `/api/progress` endpoint
  - Ensures progress is synced even if connection drops
- **Streaming**: Uses `/stream/{item_id}` endpoint for video playback

### Configurable Server Connection
- **Settings Screen**: Easy-to-use UI to configure server URL
- **Persistent Configuration**: Server URL saved in DataStore preferences
- **Dynamic Updates**: Change server without reinstalling the app
- **Connection Tips**: Built-in guidance for network setup

## Quick Start

### Build APK

```bash
cd /workspace/android-app

# On Linux/Mac
./gradlew assembleDebug

# On Windows
gradlew.bat assembleDebug
```

APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Install on Phone

**Option 1: USB Cable**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Option 2: Transfer APK**
1. Copy APK to phone (email, cloud storage, USB)
2. Open file manager on phone
3. Tap APK to install
4. Enable "Install from Unknown Sources" if prompted

### Configure Server

1. Open app on phone
2. Tap **Settings** icon (⚙️) in top-right
3. Enter server URL: `http://YOUR_COMPUTER_IP:8000`
4. Tap **Save**

**Important:**
- Use computer's IP address (not localhost)
- Phone and computer must be on same WiFi
- Find IP: `ipconfig` (Windows) or `ifconfig` (Mac/Linux)

## Project Structure

```
android-app/
├── app/src/main/java/com/videolms/android/
│   ├── MainActivity.kt
│   ├── VideoLMSApplication.kt
│   ├── data/
│   │   ├── local/       # Room Database
│   │   ├── remote/      # Retrofit API
│   │   ├── model/       # Data Classes
│   │   └── repository/  # Repository
│   └── ui/screens/
│       ├── HomeScreen.kt
│       ├── CourseContentScreen.kt
│       ├── VideoPlayerScreen.kt
│       ├── SettingsScreen.kt    # Server config UI
│       └── SettingsViewModel.kt
└── build.gradle.kts
```

## Detailed Documentation

See full documentation in [DOCS.md](DOCS.md) including:
- Complete build instructions
- Installation methods
- Troubleshooting guide
- API requirements
- Architecture details
