# Contributing

## TL;DR

Fork the project, use JDK 17, run `./gradlew :app:testDebugUnitTest :app:lintDebug`, and submit a focused pull request. Do not commit credentials, notification content, proprietary sports data, or third-party marks without documented redistribution rights.

## Development rules

- Keep all notification matching and vault handling on-device.
- `schedule/` may make only bounded public GET requests. It must not import from `service/` or read notification or vault content.
- Add unit tests for matching, parsing, and privacy-sensitive behavior.
- Test the debug variant. Its application ID ends in `.opensource` so it stays isolated from the author's private install.
- Use synthetic fixtures unless a third-party data source is clearly licensed for redistribution.

## Pull requests

Explain the behavior change, privacy impact, and validation performed. Keep generated builds, local SDK paths, keystores, and signing files out of commits.
