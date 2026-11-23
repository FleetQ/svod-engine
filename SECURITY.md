# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Svod, please report it privately.
**Do not open a public issue, pull request, or discussion for security reports.**

Email **security@svod.local** with:

- A description of the vulnerability and its impact.
- Steps to reproduce (proof-of-concept, affected version/commit, configuration).
- Any suggested remediation, if you have one.

You will receive an acknowledgement within a few business days. We will work
with you on a coordinated disclosure timeline and credit you in the release
notes unless you prefer to remain anonymous.

## Supported Versions

Security fixes target the latest released version and the `main` branch.
Older versions are not patched; please upgrade before reporting.

## Security Model

Svod's threat model assumes the local machine is trusted and the network is not.

- **App API is loopback by default.** The HTTP App API binds to `127.0.0.1` by
  default, where local processes on the same machine are the only clients. A
  shared engine may bind another address only with TLS on both ports
  (`appApiTls` and `mcpTls`), at least one person with a personal API key
  (`users`) and `localAdmin` set to false; the engine refuses to start otherwise
  (ADR-0019).
- **Remote agents use the MCP server over TLS.** Agents on other hosts reach the
  engine through the MCP server, which is served over TLS and authenticated with
  a **per-agent bearer token**. Each agent has its own token so access can be
  revoked individually. Tokens are never logged.
- **Secret-scanning can block credentials pre-commit (opt-in).** The scan is off
  by default; enable it with `secretScanning` in the config. When enabled, it
  rejects writes that contain high-confidence credentials (API keys, tokens,
  private keys), preventing accidental disclosure in the repository history.

For the full data-handling summary (data location, authentication, audit trail,
deletion, outbound connections and known gaps), see
[docs/security-overview.md](docs/security-overview.md).

### Out of Scope

- Vulnerabilities requiring an attacker who already has local code execution or
  root on the host (the local machine is in the trusted boundary).
- Denial of service caused by intentionally malformed local configuration.
- Issues in third-party models or runtimes downloaded on first run, unless Svod
  mishandles them.

## Handling of Secrets

Never commit API keys, bearer tokens, signing identities, or notarization
credentials. Signing and notarization are driven entirely by environment
variables and the macOS keychain (see `dist/notarize.sh`); credentials must
never appear in source, config samples, or CI logs.
