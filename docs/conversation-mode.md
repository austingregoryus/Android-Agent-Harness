# Spoken calls

The phone captures call audio, transcribes speech and plays audio; `tools/converse.py` listens for events, asks a model what to say and sends speech back. Smart Turn optionally detects completed turns. The driver handles interruption, reconnect replay, bounded generation/playback waits and hang-up.

Cellular downlink capture and uplink injection require compatible privileged device provisioning. Ordinary sideloading cannot grant this. See [adding a phone](adding-a-phone.md). Calls contact real people and can incur charges.

## Pair and configure

Follow [build and connect](development.md), including the shared repository-root token path. Mint a new **Standard agent** pairing code and, from `gateway/`, grant the call driver:

```sh
bin/gateway pair --config config.local.yaml --code XXXX-XXXX --label calls --scopes "telephony.*,events.*,device.get_*" --max-action-class EXTERNAL_COMMITMENT
```

This replaces the configured token file; revoke any unused older peer in the phone UI. Enable **Ask me first** for supervised calls and turn off observe-only only when ready for the authorized call. The default policy does not require a fresh approval tap.

Keep URL, token and TLS fingerprint in ignored repository-root `secrets/device-url`, `secrets/device-token` and `secrets/device-tls-fingerprint`. Python also accepts `SYMBIOSIS_URL`, `SYMBIOSIS_TOKEN` and `SYMBIOSIS_TLS_PIN`. An existing gateway-local token can instead be selected for this driver with `--token-file gateway/secrets/device-token`. Configure Python's URL/pin separately from gateway YAML. Pass private briefs through `--system-file`; command-line brief text is visible in process listings.

## Minimal call configuration

Install the core Python requirements from the development guide. On the provisioned phone, install/enable a working Android TTS engine and speech recognition service and verify both with controlled audio. Their availability, network use and cellular audio routing depend on the device; the following configuration has no external Python speech server requirement but is not proof of device compatibility.

Run from the repository root after replacing `MODEL_ID` with a model served by your OpenAI-compatible endpoint, and `AUTHORIZED_NUMBER` with the authorized target. Create the private task brief at `secrets/call-brief.txt` first:

```sh
python tools/converse.py --dial AUTHORIZED_NUMBER --system-file secrets/call-brief.txt --brain custom --local-base http://127.0.0.1:11430/v1 --local-model MODEL_ID --fallback-brain none --outcome-brain same --tts android --stt device
```

This command places a real call. The model endpoint must support `/chat/completions`, streamed replies and tool calls. Hosting a model server is outside this repository. For a remote endpoint use HTTPS and set `LLM_API_KEY` if it requires a bearer key. The driver also reads `KEY=VALUE` lines from ignored `secrets/api_keys`, using `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `CEREBRAS_API_KEY`, `GROQ_API_KEY`, `GEMINI_API_KEY` or `LIVEKIT_API_KEY` for recognized service hosts. These are literal key/value lines, not shell `export` statements. Do not put credentials in command arguments.

Use the matching phone runtime from this snapshot. The driver marks new calls `driver_managed`: an approval that times out is cancelled, and cannot execute after a later tap or app restart. If dialing succeeds but its response is lost, the phone attempts to end the call when conversation mode has not started within 90 seconds (checked every 10 seconds). Once conversation mode starts, the existing owner-presence lease applies. Hang-up is best effort and depends on Android permissions/roles; an unknown transport outcome is not confirmation that no call occurred. An explicit conversation stop after successful startup does not restart the startup timer.

`--brain custom` preserves `--local-base`, `--local-model` and `--llm-reasoning`. `SYMBIOSIS_LLM_BASE` supplies the default custom endpoint. Named presets override these flags; the default `luna` selects a hosted endpoint. `--brain ornith` selects the configured local endpoint with the built-in local model name. Preset names do not guarantee provider/model availability. `--fallback-brain none --outcome-brain same` keeps fallback and outcome generation on the selected primary model. Inspect `python tools/converse.py --help` before changing these choices.

## Optional external speech services

The driver defaults to Chatterbox TTS and Whisper-assisted STT, which require separately provisioned services. This export includes neither their servers nor their voices/models. The service interfaces below describe what the driver calls; they are not installation recipes or tested server-version recommendations.

| Selection | Required interface/assets |
| --- | --- |
| `--tts chatterbox` | `SYMBIOSIS_CHATTERBOX_URL` or `--tts-server`: `GET /health` JSON with `ready: true`; `POST /synthesize` JSON `{text, voice}` returning mono 16-bit WAV. Voice choices are currently `joi`, `channel_a`, `channel_b`; provision a permitted voice matching the configured name. |
| Chatterbox helper | `SYMBIOSIS_VOICE_HELPER_URL` or `--tts-helper`: `POST /v1/audio/speech`, fields `input`, `voice: clone`, `engine: chatterbox`, `response_format: pcm`; returns raw 24 kHz mono 16-bit PCM. Pass `--tts-helper ""` to disable it. |
| `--tts kokoro-server` | `--tts-server` or `KOKORO_SERVER`: `GET /v1/models`; `POST /v1/audio/speech` with `model: kokoro`, input, voice, speed and PCM output; returns raw 24 kHz mono 16-bit PCM. |
| `--tts kokoro` | Additional `kokoro-onnx` package and compatible `~/.kokoro/kokoro-v1.0.onnx` and `voices-v1.0.bin`, obtained separately under their own terms. Not installed by core requirements; no pinned compatible asset/package combination is qualified here. |
| `--stt whisper` | `SYMBIOSIS_WHISPER_URL` or `--whisper-server`: multipart `POST /v1/audio/transcriptions` with WAV `file`, `response_format: verbose_json`, language, temperature and optional prompt; JSON text/segments with confidence fields. The phone recognizer still drives transcription. |

An unavailable Chatterbox service can fall back to the helper, then the default Kokoro server, then in-process Kokoro. Those fallbacks need their own dependencies; configuring the Chatterbox URL alone does not provision them. Use the explicit Android/device combination above when external speech services are not configured.

Set `agent.converse_script` to the driver path in gateway config to expose `gateway.converse_call`. That wrapper exposes only a subset of CLI settings; inspect its tool schema before choosing it for a custom configuration. Gateway provider selection does not configure the Python model/speech services. The returned outcome is model output, not independent proof of an external commitment. Unknown termination outcomes must stay unknown.

`test_converse_sim.py` uses a fake phone/model/TTS. `dry_call.py` and `judgement_eval.py` contact actual configured model services. Microphone, carrier audio, speech quality and physical stop controls need separate device testing.
