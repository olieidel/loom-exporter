# Repository Guidelines

## Project Structure & Module Organization

This is a JVM Clojure CLI for exporting accessible Loom videos. Source code lives in `src/loom_exporter/`; tests mirror it in `test/loom_exporter/`.

- `cli.clj`: command parsing and command-specific help.
- `core.clj`: discovery, listing, export, and verification orchestration.
- `loom_web.clj` / `loom_media.clj`: Loom GraphQL/API and media download logic.
- `archive.clj` / `data.clj`: archive layout and EDN/JSON serialization.
- `tui.clj`: interactive video picker.
- `exports/`, `tmp/`, `.cpcache/`, and `target/` are generated/local data and should not be treated as source.

## Build, Test, and Development Commands

```sh
clojure -M:test
```
Runs the full test suite through `loom-exporter.test-runner`.

```sh
clojure -M:run --help
clojure -M:run list --cookie-file cookies.txt --limit 20
clojure -M:run export --out exports/loom --cookie-file cookies.txt
```
Runs the CLI locally.

```sh
clojure -T:uberjar uber
```
Builds `target/loom-exporter-0.1.0-standalone.jar`.

## Coding Style & Naming Conventions

Use idiomatic Clojure with two-space indentation and small, focused functions. Namespace filenames use underscores (`loom_media.clj`) while namespaces use hyphens (`loom-exporter.loom-media`). Prefer pure helpers for parsing, path construction, and data transformations; keep side effects near API, filesystem, and process boundaries.

Archive metadata defaults to EDN. Preserve JSON helpers for API payloads and explicit `--archive-format json` behavior.

Prefer simple local implementations and keep dependencies low. Add a library only when it clearly reduces risk or complexity; otherwise use JDK/Clojure facilities. For example, HTTP calls currently use `java.net.http`, and media/download behavior is implemented in Clojure instead of adding another CLI dependency where practical.

## Testing Guidelines

Tests use `clojure.test`. Add tests under `test/loom_exporter/*_test.clj` and include new namespaces in `test/loom_exporter/test_runner.clj`. Keep tests focused on behavior: CLI parsing, archive path/format handling, discovery validation, and pure transformations. Run `clojure -M:test` before committing.

## Commit & Pull Request Guidelines

Recent commits use short, imperative summaries, for example `Simplify CLI discovery options` and `Tidy archive data and CLI internals`. Keep commits scoped to one logical change and include tests/docs when behavior changes.

Pull requests should describe the user-visible change, mention any archive format or CLI compatibility impact, and include test results. Link related issues when available.

Agents should automatically create a git commit after making a completed change. Do not push unless the user explicitly asks.

## Security & Configuration Tips

Cookie files are equivalent to browser sessions. Do not commit `cookies.txt`, raw cookie headers, downloaded videos, or personal exports. Prefer examples with placeholder paths and Loom IDs. Verify changes do not print signed media URLs, cookies, or transcript contents in normal error output.
