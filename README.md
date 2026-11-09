# Shield Launcher

A fast, ad-free home screen for the NVIDIA Shield TV (any Android TV 8 or newer).

- **Apps** on top, in your order: hold OK on one to move it, take it off the row or see its info.
- **Continue watching, Movies and TV shows from Emby.** OK plays in the Emby app, from where you left off. Stay on a film for 2 seconds and its trailer plays behind the top, muted. Hold OK for the trailer full screen with sound.
- **Games from Artemis:** in Artemis, hold a game and choose *Create shortcut*.
- **Recommended and Subscriptions from YouTube**, opening in SmartTube.
- You choose which rows show and in what order.
- **Set up from your phone:** scan the code on the TV to arrange everything and sign in with the phone's keyboard.
- **Home Assistant notifications** over whatever is playing. They work with Home Assistant's own *Notifications for Android TV / Fire TV* integration.
- **Updates from GitHub:** a note on Home when there's a new version. OK downloads it and installs it, then deletes the download.

## Install it and make it the home screen

Android TV doesn't let you choose a home screen, so the stock one (Android TV Home) is switched off once from a computer. After that, updates need nothing.

1. **On the Shield:** Settings › Device Preferences › About, then click **Build** 7 times. In **Developer options**, turn on **Network debugging**. Note the Shield's IP address (Settings › Network & Internet).
2. **On a computer:** install Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools), then run:
   ```
   adb connect <shield-ip>
   ```
   On the TV, choose **Always allow**.
3. **Install the launcher** (the APK from the latest [release](https://github.com/bornaradusin/ShieldLauncher/releases)), and let it show notifications over other apps:
   ```
   adb install ShieldLauncher-1.0.0.apk
   adb shell appops set io.github.bornaradusin.launcher SYSTEM_ALERT_WINDOW allow
   ```
4. **Switch off Android TV Home:**
   ```
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```
5. **Press Home.** Shield Launcher is now the only home screen, so Home always opens it. If the TV asks you to pick one, choose it and **Always**.

To undo it: `adb shell pm enable com.google.android.tvlauncher`

*Settings › Home button* on the TV shows the same steps, and whether it worked.

## Set up from your phone

Open *Settings › Set up from your phone* on the TV and scan the code with your phone's camera. The phone and the Shield must be on the same Wi-Fi. If you'd rather not scan, open the address shown on the TV and type the 4-number code. The phone stays paired, so you can bookmark the page.

The phone page has three tabs:

- **Home:** rows (on or off, dragged into order), the apps row, and trailers behind films.
- **Accounts:**
  - **Emby:** server, user and password, and which Emby app plays films and episodes.
  - **YouTube:** sign in with Google.
  - **Games:** take games off Home.
- **Updates:** look for a new version and update the TV.

## Home Assistant notifications

The launcher listens on port **7676** for the same messages the *Notifications for Android TV* app takes, so Home Assistant's built-in integration works as it is:

1. In Home Assistant, go to **Settings › Devices & services › Add integration › Notifications for Android TV / Fire TV**, and enter the Shield's IP address.
2. Send notifications with `nfandroidtv.send_message` (or `notify.send_message` to its entity). You can set:
   - a title and message
   - `duration` (seconds)
   - `position` (`bottom-right`, `bottom-left`, `top-right`, `top-left`, `center`)
   - `fontsize` (`small`, `medium`, `large`, `max`)
   - `color` and `transparency`
   - `interrupt` (stays until OK or Back is pressed)
   - an `icon` and an `image`, for example a camera snapshot.

```yaml
action: nfandroidtv.send_message
data:
  entity_id: notify.shield
  title: Front door
  message: Someone is at the door
  data:
    duration: 8
    position: top-right
    image:
      url: http://homeassistant.local:8123/api/camera_proxy/camera.front_door
```

To try it without Home Assistant, use *Settings › Send a test notification* on the TV.

## Emby

Films and episodes play in the Emby app installed on the Shield (Emby for Android TV, or Emby). The launcher opens the item there, then uses Emby's remote control to start it from where you left off. If the app doesn't respond, the item's page is open in Emby and Play is one press away.

Trailers come from Emby: a trailer kept on the server with the film, or the trailer link Emby has for it from the movie database.

## Building

GitHub Actions builds every push. A push to `main` also puts the APK on a draft release for the version in `app/build.gradle.kts`, to check and publish by hand. The APK is signed with the key in `signing/release.jks.enc`, which the repository secret `ANDROID_KEYSTORE_PASSWORD` opens.
