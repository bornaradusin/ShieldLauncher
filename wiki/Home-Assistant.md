# Home Assistant notifications

The launcher shows notifications over whatever is playing. It listens on port **7676** and takes the same messages as the *Notifications for Android TV* app. That means Home Assistant's built-in integration works unchanged, with no other app on the TV.

## Setting it up

1. On the TV, allow **Display over other apps**: Settings › Notifications on the TV. Or from a computer:
   ```
   adb shell appops set io.github.bornaradusin.launcher SYSTEM_ALERT_WINDOW allow
   ```
2. **Uninstall Notifications for Android TV**, if it's on the Shield. Only one app can listen on port 7676. If it's still there, Settings › Notifications on the TV tells you.
3. In Home Assistant, go to **Settings › Devices & services › Add integration › Notifications for Android TV / Fire TV**, and enter the Shield's IP address.

To check it works without Home Assistant, use Settings › **Send a test notification**.

## Sending one

```yaml
action: nfandroidtv.send_message
data:
  entity_id: notify.shield
  title: Front door
  message: Someone is at the door
  data:
    duration: 8
    position: top-right
    fontsize: large
    color: black
    transparency: 25%
    interrupt: false
    image:
      url: http://homeassistant.local:8123/api/camera_proxy/camera.front_door
```

| Option | Values |
|---|---|
| `duration` | Seconds on screen (5 if not given) |
| `position` | `bottom-right` (the default), `bottom-left`, `top-right`, `top-left`, `center` |
| `fontsize` | `small`, `medium`, `large`, `max` |
| `color` | `grey` (the launcher's own dark look), `black`, `indigo`, `green`, `red`, `cyan`, `teal`, `amber`, `pink` |
| `transparency` | `0%`, `25%`, `50%`, `75%`, `100%` |
| `interrupt` | `true`: stays until OK or Back is pressed (or the time runs out) |
| `icon`, `image` | A picture, by `url` or `path`, the same as for the original app |

A new notification takes the place of the one showing.
