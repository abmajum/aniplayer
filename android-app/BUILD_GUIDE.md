# Complete Build & Installation Guide

## Prerequisites

1. **Android Studio** - Download from https://developer.android.com/studio
2. **JDK 17** - Usually bundled with Android Studio
3. **Your FastAPI server** running and accessible

---

## Method 1: Build Using Android Studio (Recommended for First Time)

### Step 1: Open Project

1. Launch Android Studio
2. Click **"Open an Existing Project"**
3. Navigate to `/workspace/android-app`
4. Click **OK**

### Step 2: Wait for Gradle Sync

- Android Studio will automatically sync Gradle files
- Watch the progress bar at the bottom
- This may take 2-5 minutes on first open
- If sync fails: **File > Sync Project with Gradle Files**

### Step 3: Build APK

1. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**
2. Wait for build to complete (1-3 minutes)
3. Click **"locate"** in the notification popup
4. APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Step 4: Install via Android Studio

1. Connect your Android phone via USB
2. On phone: Enable **Developer Options**
   - Settings > About Phone > Tap "Build Number" 7 times
   - Settings > System > Developer Options > Enable "USB Debugging"
3. Accept USB debugging prompt on phone
4. In Android Studio, click green **Run** button (▶️)
5. Select your device
6. App installs and launches automatically

---

## Method 2: Build Using Command Line

### Linux/Mac

```bash
cd /workspace/android-app

# Make gradlew executable
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing)
./gradlew assembleRelease
```

### Windows

```cmd
cd \workspace\android-app

# Build debug APK
gradlew.bat assembleDebug

# Build release APK
gradlew.bat assembleRelease
```

### APK Locations

- **Debug**: `android-app/app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `android-app/app/build/outputs/apk/release/app-release.apk`

---

## Installation Methods

### Method A: USB Cable with ADB

```bash
# Verify device connection
adb devices

# Should show your device ID
# If not, check USB cable and enable USB debugging

# Install APK
adb install /workspace/android-app/app/build/outputs/apk/debug/app-debug.apk

# If app already exists, reinstall
adb install -r /workspace/android-app/app/build/outputs/apk/debug/app-debug.apk
```

### Method B: Transfer APK to Phone

1. **Find the APK file:**
   - `app/build/outputs/apk/debug/app-debug.apk`

2. **Transfer to phone:**
   - Email to yourself
   - Upload to Google Drive/Dropbox
   - Send via WhatsApp/Telegram
   - Copy via USB cable to phone's Downloads folder

3. **Install on phone:**
   - Open **File Manager** on phone
   - Navigate to downloaded APK
   - Tap to install
   - If prompted: Settings > Security > Enable "Unknown Sources"
   - Complete installation

### Method C: Quick Share/Nearby Share

1. Build APK using any method above
2. Use Android's **Nearby Share** or **Quick Share**
3. Share APK directly from computer to phone
4. Install on phone

---

## First-Time Setup

### 1. Grant Permissions

When you first open the app:
- Tap **"Allow"** when asked for storage permissions
- This enables scanning local videos

### 2. Configure Server URL

1. **Open Settings:**
   - Tap the **Settings icon (⚙️)** in top-right corner of home screen

2. **Enter Server URL:**
   - Format: `http://YOUR_IP:8000`
   - Example: `http://192.168.1.100:8000`
   - Must include `http://` or `https://`
   - Must include port number

3. **Find Your Computer's IP:**

   **Windows:**
   ```cmd
   ipconfig
   # Look for "IPv4 Address" under your network adapter
   # Usually starts with 192.168.x.x or 10.x.x.x
   ```

   **Mac:**
   ```bash
   ifconfig
   # Look for "inet" under en0 or en1
   ```

   **Linux:**
   ```bash
   ip addr
   # Look for "inet" under your network interface
   ```

4. **Save:**
   - Tap **"Save Server URL"**
   - You'll see a success message
   - Return to home screen

### 3. Test Connection

- Courses from your FastAPI server should appear
- Tap a course to browse content
- Tap a video to start streaming

---

## Troubleshooting

### Build Fails

**Error: "SDK not found"**
```bash
# Set ANDROID_HOME environment variable
export ANDROID_HOME=$HOME/Android/Sdk  # Linux
export ANDROID_HOME=$HOME/Library/Android/sdk  # Mac
```

**Error: "Java version mismatch"**
- Ensure JDK 17 is installed
- In Android Studio: **File > Project Structure > SDK Location**

**Error: "Gradle sync failed"**
- Click **File > Invalidate Caches / Restart**
- Delete `.gradle` folder and retry

### Installation Fails

**"App not installed"**
- Uninstall any previous version first
- Check available storage space
- Enable "Unknown Sources" in phone settings

**"Parse error"**
- APK may be corrupted - rebuild it
- Check Android version compatibility (min SDK 26)

### Can't Connect to Server

**No courses showing:**
1. Verify server is running: `curl http://YOUR_IP:8000`
2. Check phone and computer are on same WiFi
3. Try pinging computer from phone (use network utility app)
4. Check firewall settings on computer

**Connection timeout:**
- Computer's firewall may be blocking port 8000
- Windows: Windows Defender Firewall > Allow an app
- Mac: System Preferences > Security > Firewall
- Router: Ensure devices can communicate

**Wrong URL format:**
- ✅ `http://192.168.1.100:8000`
- ✅ `http://192.168.1.100:8000/`
- ❌ `localhost:8000` (won't work on phone)
- ❌ `192.168.1.100:8000` (missing http://)

### Videos Won't Play

**Local videos:**
- Check file format (mp4, mkv, webm, avi, mov supported)
- Ensure storage permission granted
- Try re-scanning: restart app

**Server videos:**
- Check WiFi connection
- Verify `/stream/{id}` endpoint works in browser
- Check server logs for errors

### Progress Not Saving

- Watch at least 5-10 seconds for progress to register
- For server videos: ensure stable connection
- Check app logs (Logcat) for database errors

---

## Advanced: Release Build for Distribution

### Create Keystore

```bash
keytool -genkey -v -keystore my-release-key.keystore \
  -alias video-lms -keyalg RSA -keysize 2048 -validity 10000
```

### Configure Signing

Create `android-app/app/release-signing.properties`:
```properties
storePassword=YOUR_PASSWORD
keyPassword=YOUR_PASSWORD
keyAlias=video-lms
storeFile=../my-release-key.keystore
```

### Build Release APK

```bash
./gradlew assembleRelease
```

The signed APK will be at:
`app/build/outputs/apk/release/app-release.apk`

---

## Quick Reference

| Task | Command/Action |
|------|----------------|
| Build Debug APK | `./gradlew assembleDebug` |
| Build Release APK | `./gradlew assembleRelease` |
| Install via ADB | `adb install app-debug.apk` |
| Find Computer IP | `ipconfig` (Win) / `ifconfig` (Mac/Linux) |
| Check Device | `adb devices` |
| Uninstall App | `adb uninstall com.videolms.android` |
| View Logs | `adb logcat \| grep VideoLMS` |

---

## Need Help?

1. Check Android Studio **Build Output** window
2. View phone logs: `adb logcat`
3. Check FastAPI server logs
4. Verify network connectivity
5. Review this guide's troubleshooting section
