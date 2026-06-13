# Contributing to Svod

Thanks for your interest in contributing. This guide covers how to build, test,
and submit changes.

## Building and Testing

The engine is a Kotlin/JVM project built with Gradle (wrapper committed). It
requires **JDK 20**.

Run the full test suite from the repository root:

```bash
cd engine && JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test
```

The suite is self-contained. Tests that depend on optional local services
(ONNX embedding models, a running Ollama instance) auto-skip when the model or
service is absent, so a clean checkout runs green without extra setup.

CI runs this same command on `macos-14` for every push and pull request to
`main`.

## Commits and Pull Requests

- Keep commits **small and reviewable** — one logical change per commit, with a
  clear message describing the *why*, not just the *what*.
- Open a pull request against `main`. Make sure the test suite passes locally
  before requesting review; CI must be green to merge.
- Don't bundle unrelated refactors with a feature or fix. Separate concerns into
  separate PRs.

## Architecture Decision Records (ADRs)

Significant architectural decisions are recorded as ADRs under
[`docs/adr/`](docs/adr/), numbered sequentially (e.g. `0013-my-decision.md`).
If your change introduces or alters an architectural decision — a new
subsystem, a protocol, a cross-cutting constraint — add an ADR in the same PR.
Follow the format of the existing records.

## The Contract Is the Source of Truth

The API contract in [`contract/openapi.yaml`](contract/openapi.yaml) is the
**versioned source of truth** for the App API. Code is expected to conform to
the contract, not the other way around.

- **Additive changes only** without a major version bump. Adding endpoints,
  optional fields, or new response variants is fine within a major version.
- **Breaking changes** — removing or renaming fields/endpoints, changing types,
  making optional fields required — require a **major version bump** and should
  be called out explicitly in the PR description and an ADR.

When you change behavior visible at the API boundary, update the contract in the
same PR.

## Security

Never commit credentials. See [`SECURITY.md`](SECURITY.md) for the security
model and how to report vulnerabilities. A pre-commit secret scan will reject
commits containing credentials.
