# Social MCP

Social MCP is a Java 21 MCP server for operating social-network sessions in a real desktop Chrome through [javaDriveless](https://github.com/vitorcamillo/javaDriveless). It keeps one browser profile per network and separates read-only browsing from public actions.

The server is deliberately conservative: public actions are drafted into a batch, checked against per-network quotas and tripwires, and require an explicit terminal approval before execution. A kill switch and an append-only journal provide local operational control. This project does not bypass CAPTCHAs, anti-spam systems, account restrictions, or platform rules. Use it only with accounts you control and follow each platform's terms.

## Requirements

- Java 21 and Maven 3.9+
- Google Chrome with a usable desktop display (`DISPLAY` on Linux)
- A separately installed or locally built `javaDriveless` 1.12.2

Build the browser library first:

```bash
git clone https://github.com/vitorcamillo/javaDriveless.git
cd javaDriveless
mvn -B -ntp install
```

Then build this server:

```bash
git clone https://github.com/vitorcamillo/social-mcp.git
cd social-mcp
mvn -B -ntp package
./bin/social-mcp --cli status
```

The launcher stores state in `~/.social-mcp-public` by default. Set `SOCIAL_MCP_HOME` to an isolated directory when running tests or more than one installation. The directory contains configuration, journal entries, batches, screenshots, and browser profiles; never share it or commit it.

## Gate configuration

The public defaults intentionally have no gate command. Approval fails closed until you configure a local gate in `config.json` under `defaults.gate.command`. The command receives `pre --batch <path> --config <path> --json` and must write a JSON object such as `{"resultado":"PASS"}` (or `WARN`/`FAIL`) and return a compatible exit status. Keep the gate executable local and review its source before use. After reviewing the complete draft, approve it explicitly with `./bin/social-mcp --cli approve <batch-id> humano "reviewed"`; there is no example gate that approves everything.

The CLI approval records a role supplied by the caller (`humano` or `orquestrador`); it is not an identity provider. Treat access to the host and the `SOCIAL_MCP_HOME` directory as trusted. An MCP client or agent with shell access can invoke the CLI, so use operating-system permissions and human review for real accounts.

## MCP tools

The server communicates over newline-delimited JSON-RPC on stdio. It exposes status, browsing, reading, screenshots, draft batches, execution of approved batches, state controls, and journal inspection. Session cookies are never part of this repository: log in interactively in the profile owned by the user, or import a session file supplied by that user, then protect the runtime directory.

The public source contains synthetic tests only. It does not contain an account, a browser profile, a cookie export, a proxy credential, a marketing plan, or a hosted service.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please include a focused test for behavior changes and never include runtime data, screenshots, cookies, access tokens, or account identifiers in an issue or pull request.

## License

This project is released under the MIT License. See [LICENSE](LICENSE).
