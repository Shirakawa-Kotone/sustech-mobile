# Contributing

Issues and PRs are welcome — a bug report with what you saw and what you expected is worth more than anything.

## Ground rules

- English only: UI, code, docs, commit messages. Data from the servers (course, room and teacher names) stays Chinese as received.
- API clients never follow redirects; a write is confirmed by reading the list back, not by trusting the response body.
- Wire layers speak raw codes, never UI copy.
- Tests run without the campus network — see the Testing section of the [README](./README.md) for the mock print server and the UI scenarios.

## License

The project is under the PolyForm Noncommercial License 1.0.0. **The license may change in the future** — we are trying to solve the budget problems for iOS publishing. By contributing you accept that your contribution may be carried into such a future license.
