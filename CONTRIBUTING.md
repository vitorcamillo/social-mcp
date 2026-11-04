# Contributing

1. Fork the repository and create a focused branch.
2. Keep changes scoped to the public server and its synthetic tests.
3. Run `mvn -B -ntp test` and `mvn -B -ntp package` before opening a pull request.
4. Run a redacted secret scan such as `gitleaks dir --redact --no-banner .`.
5. Describe the behavior changed, the tests run, and any platform-specific limitation.

Never submit cookies, browser profiles, screenshots containing account data, proxy URLs with credentials, access tokens, or private operating instructions. Reports about a possible security issue should follow [SECURITY.md](SECURITY.md).
