# Third-party notices

The root MIT license covers original project code. Dependencies and assets retain their own licenses.

| Component | Source | License / distribution |
| --- | --- | --- |
| droid-mcp 0.10.1 | [upstream source](https://github.com/stixez/droid-mcp/tree/v0.10.1) | Apache-2.0; resolved by Gradle; [license copy](licenses/droid-mcp-Apache-2.0.txt) |
| Smart Turn v3.2 CPU ONNX | [upstream model](https://huggingface.co/pipecat-ai/smart-turn-v3) | BSD-2-Clause; bundled; [license copy](licenses/smart-turn-BSD-2-Clause.txt) |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle) | Apache-2.0; wrapper JAR and scripts bundled |
| Android/Go/Python dependencies | Build manifests and lock/checksum files | Individual upstream licenses apply |

The bundled `smart-turn-v3.2-cpu.onnx` SHA-256 is `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f`, matched to the upstream model repository's LFS metadata during publication preparation. Smart Turn's model card identifies BSD-2-Clause licensing. The license copy credits Daily.

Golden turn-detection fixtures are generated from mathematical signals, not recordings of a person. See [fixture provenance](android-runtime/app/src/test/resources/README.md) and the generator. Optional speech servers, voices, models and carrier services are not included and must be obtained/configured separately.

This source notice is not a complete transitive dependency inventory for a distributed APK. Review dependency notices and license obligations for the exact binary you distribute.
