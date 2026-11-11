# Troubleshooting

## The phone page is blank, or isn't the launcher's

- Use the address on the TV (Settings › Set up from your phone). The page is on port **7677**.
- The phone must be on the same Wi-Fi as the Shield.
- If the TV says it couldn't listen on port 7677, restart the Shield.

## Home Assistant's notifications don't show

- **Notifications for Android TV** may still be installed. It holds port 7676, so uninstall it. Settings › Weather and notifications › Notifications on the TV says when this is the problem.
- "Display over other apps" may not be allowed. See [Home Assistant notifications](Home-Assistant).
- Try Settings › Weather and notifications › **Send a test notification**. It shows the overlay without going through the network, so if it works, look at what Home Assistant's notifications do.
- Settings › Weather and notifications › **Notifications on the TV** says when the last notification came in, from which address, and what happened to it. If it still says *nothing from Home Assistant yet* after you've sent one, Home Assistant is sending it somewhere else: check the IP address in its integration.

## Home still opens Android TV Home

- Run the `pm disable-user` step in [Installing](Installing). Paste the package name rather than typing it: "Unknown package" means a typo.

## SmartTube's rows don't show

- Allow the launcher to read TV listings: Settings › Home › App channels.
- Turn on SmartTube's home screen channels in its own settings.
- Turn the rows on in Settings › Home › Rows on Home.

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
