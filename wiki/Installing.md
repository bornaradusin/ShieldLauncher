# Installing

Android TV doesn't let you choose a home screen, so the stock one (Android TV Home) is switched off once, from a computer. After that you don't need the computer again, even for updates.

## 1. On the Shield

1. Go to **Settings › Device Preferences › About** and click **Build** 7 times, until it says you're a developer.
2. Go back to **Device Preferences › Developer options** and turn on **Network debugging**.
3. Note the Shield's IP address. It's under **Settings › Network & Internet**, on your network's page.

## 2. On a computer

1. Download Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools) and unzip it.
2. Download the newest `ShieldLauncher-….apk` from [Releases](https://github.com/bornaradusin/ShieldLauncher/releases) into the same folder.
3. Open a terminal in that folder. On Windows, type `cmd` in the folder's address bar and press Enter. In PowerShell, type `.\adb` instead of `adb`.
4. Connect to the Shield:
   ```
   adb connect 192.168.1.40
   ```
   On the TV, tick **Always allow from this computer** and press **Allow**. If the computer says "unauthorized", run the same command again.
5. Install the launcher and give it what it needs:
   ```
   adb install ShieldLauncher-1.1.1.apk
   adb shell appops set io.github.bornaradusin.launcher SYSTEM_ALERT_WINDOW allow
   adb shell pm grant io.github.bornaradusin.launcher android.permission.READ_TV_LISTINGS
   ```
   - The second line lets Home Assistant's notifications show over other apps.
   - The third lets the launcher read SmartTube's rows. The TV also asks for this the first time the launcher opens.
6. Switch off Android TV Home:
   ```
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```
   It should say `new state: disabled-user`. Copy and paste the name rather than typing it: a typo gives "Unknown package".
7. **Press Home on the remote.** Shield Launcher is now the only home screen, so Home always opens it.

## Undo it

```
adb shell pm enable com.google.android.tvlauncher
```

## Checking it worked

On the TV, open **Settings** (the cog, top right) › **Home button**. It shows these steps, and whether Shield Launcher is the home screen.

## Then

- [Set up from your phone](Phone-setup): sign in to Emby and Seerr, and arrange the rows.
- If **Notifications for Android TV** is installed, uninstall it. The launcher replaces it, and both can't listen for Home Assistant at once. See [Home Assistant notifications](Home-Assistant).
