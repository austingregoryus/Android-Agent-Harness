# Adding a phone

Use a dedicated Android 10+ device. Follow [build and connect](development.md), pair a narrow client, grant only necessary permissions/roles and verify device reads and visible stop controls before enabling effects.

Normal app permissions provide supported SMS, contacts, notifications, accessibility and call control. Cellular audio capture/injection is a separate privileged capability; firmware support and permissions vary.

## Privileged install

`tools/privapp/build_module.py` packages an operator-supplied APK as a Magisk module with a permission allowlist. It does not unlock, root, sign an APK or guarantee device support.

```sh
python tools/privapp/build_module.py --apk path/to/signed-app.apk --out tmp/android-agent-harness-privapp.zip
```

Use your own signed APK and review the permission allowlist and archive before installation. Device-specific flashing procedures are outside this generic guide.

Separately verify permission grants, default dialer behavior, call state, downlink capture, uplink playback, microphone mute, hang-up and emergency stop using a controlled real call. A successful build cannot establish these behaviors.
