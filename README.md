# FloatShot
![](githubRepoContentDeleteIfYouWant/logo.jpg)
A small Android app that puts a floating camera button on top of every app.

- **Tap** the button: a screenshot is taken. The button hides itself first, so it is never in the picture.
- **Drag** the button anywhere. When you let go it snaps to the nearest side, and the position is remembered.
- **Long-press** the button (about one second) to close it. The notification also has a **Stop** button.

Screenshots are saved as PNG files in `Pictures/FloatShot` and show up in the Gallery / Google Photos.

## First run

1. Open FloatShot and tap **Allow display over other apps**, then switch FloatShot on in the settings page.
2. Go back and tap **Show the button**. Android asks to allow screen capture – choose **Entire screen**.
   (If you pick a single app, screenshots only show that app.)
3. The app steps aside and the button appears. Open any app and tap the button.

Android asks for screen-capture permission again every time the button is started. That is a system rule and cannot be turned off.

## How it works

- `BubbleService` is a foreground service of type `mediaProjection`. It mirrors the screen into an `ImageReader` and keeps only the newest frame.
- On tap the button is set to invisible, and the service waits for a frame drawn at least 60 ms later (fallback: the newest frame after hiding, within 700 ms). That frame is saved through MediaStore.
- Rotation is handled by resizing the virtual display.

## Build

Open the folder in Android Studio and press Run. Requires Android 10 (API 29) or newer.
AGP 9.4.1 (built-in Kotlin), Gradle 9.8.0, compileSdk/targetSdk 35. No third-party libraries.
