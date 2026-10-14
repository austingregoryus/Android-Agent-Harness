# Contributing

Start with the [architecture](docs/architecture.md) and [development guide](docs/development.md). Keep device effects behind runtime dispatch/policy. Add focused regression coverage for bugs. Preserve unknown outcomes when a request may have executed before a connection failed.

Run Go tests and vet, Python standalone offline suites and unittest discovery, and Android unit tests for affected components. Document device/provider checks separately: a simulated call is not evidence of carrier audio quality. Do not put real phone numbers, account details, transcripts, tokens, local network topology or device identifiers in fixtures or issues.

Use synthetic data and reserved example numbers such as `+12025550100`. Keep configuration and credentials in ignored files. Before proposing changes, run `python tools/publication.py check` and inspect the diff. A scanner cannot determine whether arbitrary prose reveals something private.
