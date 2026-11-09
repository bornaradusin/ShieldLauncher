# Troubleshooting

## The phone page is blank, or isn't the launcher's

- Use the address on the TV (Settings › Set up from your phone). The page is on port **8765** (or 8766, or 9876, if that one is taken): the TV shows the address, and whether the page is up.
- The phone must be on the same Wi-Fi as the Shield.
- The TV's set-up screen says whether the page is up, and counts the phone's visits. If the page is up but nothing arrives from the phone, the phone isn't reaching the Shield: check it's on the same network (not a guest Wi-Fi).

## Home Assistant's notifications don't show

- **Notifications for Android TV** may still be installed. It holds port 7676, so uninstall it. Settings › Notifications on the TV says when this is the problem.
- "Display over other apps" may not be allowed. See [Home Assistant notifications](Home-Assistant).
- Try Settings › **Send a test notification**.

## Home still opens Android TV Home

- Run the `pm disable-user` step in [Installing](Installing). Paste the package name rather than typing it: "Unknown package" means a typo.

## SmartTube's rows don't show

- Allow the launcher to read TV listings: Settings › App channels.
- Turn on SmartTube's home screen channels in its own settings.
- Turn the rows on in Settings › Rows on Home.

## A film doesn't start playing in Emby

- The launcher opens the film in the Emby app, then asks the app to play it, using Emby's remote control. If the app doesn't answer, the film's page is open: press Play.
- Make sure the Emby app allows remote control. In Emby for Android TV it's on unless you've turned it off.

## Games don't start

- Only the Shield's home screen can start Artemis games. Make Shield Launcher the home screen ([Installing](Installing)).

## A trailer doesn't play

- Some YouTube trailers can't play inside the launcher. They open in SmartTube instead.

## Requester says Seerr didn't take the API key

- Copy the key again from Seerr › Settings › General › API Key, and paste it on the phone page.

## Delete says Seerr doesn't have it

- Seerr only knows what its Emby library scan has seen. Run the scan in Seerr › Settings › Emby (or Jellyfin), then try again.
