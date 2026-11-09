# Home Assistant notifications

The launcher shows notifications over whatever is playing. It listens on port **7676** and takes the same messages as the *Notifications for Android TV* app. That means Home Assistant's built-in integration works unchanged, with no other app on the TV.

## Setting it up

1. On the TV, allow **Display over other apps**: Settings › Notifications on the TV. Or from a computer:
   ```
   adb shell appops set io.github.bornaradusin.launcher SYSTEM_ALERT_WINDOW allow
   ```
2. **Uninstall Notifications for Android TV**, if it's on the Shield. Only one app can listen on port 7676. If it's still there, Settings › Notifications on the TV tells you.
3. In Home Assistant, go to **Settings › Devices & services › Add integration › Notifications for Android TV / Fire TV**, and enter the Shield's IP address.

To check it works without Home Assistant, use Settings › **Send a test notification**. After Home Assistant sends one, Settings › Notifications on the TV shows when it came in and whether it was shown.

Notifications show over any app, from the moment the Shield starts. The launcher doesn't need to be open.

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

## A doorbell's live camera

A notification can carry a live camera. It plays in the notification over whatever is on, muted, for 30 seconds (or `duration`), and a new notification replaces it.

Home Assistant's built-in notification action only sends still pictures, so send this one with a `rest_command`. Add to `configuration.yaml`:

```yaml
rest_command:
  shield_camera:
    url: http://SHIELD-IP:7676
    method: POST
    content_type: application/json
    payload: >
      {"title": "{{ title }}", "msg": "{{ message }}", "stream": "{{ stream }}",
       "position": "top-right", "duration": 30}
```

Then, in the doorbell's automation:

```yaml
action: rest_command.shield_camera
data:
  title: Front door
  message: Someone is at the door
  stream: "http://HA-IP:8123/api/camera_proxy_stream/camera.front_door?token={{ state_attr('camera.front_door', 'access_token') }}"
```

`stream` can be:

| Address | Where it comes from |
|---|---|
| `http://…/api/camera_proxy_stream/camera.xyz?token=…` | Any camera in Home Assistant (MJPEG). The token is the camera's `access_token`. |
| `rtsp://user:password@camera-ip:554/…` | Straight from the camera, often the smoothest. |
| `http://…/….m3u8` | An HLS stream. |

`stream` also works next to the other fields (`title`, `msg`, `position`, `fontsize`, `interrupt`). With `interrupt: true` it stays until OK or Back.

