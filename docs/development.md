# Build and connect

Before connecting a device with real accounts or cellular service, read the [disclaimer and operator responsibilities](disclaimer.md). You control the permissions, supervision and potential costs of your deployment.

## Prerequisites

- A JDK compatible with the Gradle wrapper (JDK 17+), Android SDK platform 36 and platform-tools.
- Go 1.24+ and Python 3.10+.
- A dedicated Android 10+ phone or emulator for device work.

Android dependencies resolve from Google, Maven Central and JitPack. First builds need network access. Set `ANDROID_HOME` or create ignored `android-runtime/local.properties` with your SDK location. Set `JAVA_HOME` if necessary.

## Build and offline verification

```sh
cd android-runtime
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease
```

On Windows use `gradlew.bat`. Release output is unsigned: use your own signing workflow before installation/distribution. Never commit a signing key or password. Debug builds expose diagnostic receivers and belong on development devices.

```sh
cd gateway
go test ./...
go vet ./...
go build -o bin/gateway ./cmd/gateway
```

Use `bin/gateway.exe` on Windows. From the repository root:

```sh
python -m venv .venv
# Activate .venv for your shell, then:
python -m pip install -r tools/requirements.txt
python tools/test_inbound.py
python tools/test_converse_sim.py
python -m unittest discover -s tools -p 'test_*.py'
```

The first two Python suites have standalone runners; unittest discovery alone does not run them. Simulations take several minutes. ONNX inference tests may skip if the host native library cannot load; inspect the test report.

## Connect over USB

```sh
adb install -r android-runtime/app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:18765 tcp:8765
curl http://127.0.0.1:18765/health
```

Open the app and press Start before checking health. Grant only the permissions and Android roles you intend to exercise. Notification access and accessibility are separate settings. Full call control requires the default dialer role.

In `gateway/`, copy `config.example.yaml` to ignored `config.local.yaml`. Keep the USB URL at `http://127.0.0.1:18765`; choose provider/model settings for your account. Set `device.token_file: ../secrets/device-token` so pairing writes the repository-root file that the Python drivers also use. For TLS, set `device.tls_fingerprint_file: ../secrets/device-tls-fingerprint` as well. The example's unchanged `secrets/device-token` instead resolves inside `gateway/` and is not discovered automatically by Python.

Generate a **Standard agent** code in the phone UI. Start with a battery/device-read-only peer:

```sh
bin/gateway pair --config config.local.yaml --code XXXX-XXXX --label development --scopes "device.get_*" --max-action-class READ_ONLY
bin/gateway health --config config.local.yaml
bin/gateway run --config config.local.yaml "Read the battery level"
```

Pairing writes the token to `device.token_file`. Relative paths resolve against the config file's directory. Enable observe-only while exploring reads. Set provider keys in the environment variables named in the config; the `run` command contacts that provider. For an MCP host, configure it to launch `bin/gateway mcp --config config.local.yaml` using absolute executable/config paths. Health alone does not verify authenticated tools.

Calls and SMS require a separate deliberate grant: follow the scoped pairing commands in [spoken calls](conversation-mode.md) or [inbound SMS](inbound.md). The gateway's default pairing ceiling is only `COMMUNICATION`; `telephony.place_call` and `messaging.send_sms` require `EXTERNAL_COMMITMENT`. Each pairing needs a new code, writes a new token, and does not revoke older peers; revoke unused peers in the phone UI. Neither a wider ceiling nor turning off observe-only replaces user authorization for a target or task.

For network access, enable LAN TLS on the phone, use `https://PHONE_HOST:8766` and configure `device.tls_fingerprint_sha256` from the phone's displayed fingerprint. Do not substitute cleartext LAN HTTP.

The phone's `ai.ask` endpoint/model are separate from Python settings. First-time builders must configure `AiConfig` in `policy/PolicyModel.kt` or provision persisted policy. There is no endpoint editor in the UI. Defaults are loopback and `local-model`; use HTTPS for a network service or deliberately add a narrow Android network-security exception for your endpoint.

Local service defaults changed during publication preparation. Existing Android persisted settings retain their
values; Python installations should set the `SYMBIOSIS_*` endpoint variables documented in the call guide.

## Device checks

`verify_device.py` uses ignored `secrets/device-url`, `device-token` and `device-tls-fingerprint`. Read its options: some probes write an app-local file; explicit write/SMS/call options add effects. `sms_mesh.py` sends real SMS. Neither belongs in offline CI. See [adding a phone](adding-a-phone.md) for privileged cellular audio.

For the SMS mesh, create ignored `secrets/fleet.json` with at least two entries of the form
`"test-phone": {"serial": "YOUR_ADB_SERIAL", "credentials_dir": "devices/test-phone", "debug": false}`.
Credential directories resolve relative to the fleet JSON. Use `--fleet` for another location. The script
sends one SMS for each ordered pair of configured phones; it never uses a built-in personal device list.

## Compatibility identifiers

The public product name is Android Agent Harness. Existing installations and clients retain the Android package `dev.symbiosis.runtime`, `SYMBIOSIS_*` environment variables, `_meta.symbiosis` protocol keys, and existing database, lock, download and privileged-install paths. These technical identifiers are kept to preserve app data, permissions and interoperability; they are not the product display name.
