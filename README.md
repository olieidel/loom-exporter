# Loom Exporter

A JVM Clojure CLI for exporting Loom videos that are visible to the authenticated account into a filesystem archive.

## Status

This is a practical V1. Loom does not provide a stable open export API. The tool therefore uses:

- Loom's authenticated web GraphQL/API endpoints for inventory, metadata, transcripts, and signed media URLs.
- `ffmpeg` for HLS/DASH/direct media download and remuxing.

The browser-cookie fallback is opt-in. Only export videos you have permission to access and download.

## Usage

```sh
clojure -M:run --help
```

Discover via Loom's authenticated web API:

```sh
clojure -M:run inventory \
  --loom-web \
  --cookie-file cookies.txt \
  --loom-source ALL \
  --out exports/loom
```

`--cookie-file` accepts either Netscape `cookies.txt` format or a file containing the raw `Cookie:` request header value copied from a `POST https://www.loom.com/graphql` request in browser DevTools.

List visible videos without writing an archive:

```sh
clojure -M:run list \
  --loom-web \
  --cookie-file cookies.txt \
  --loom-source ALL
```

Use `--format json` or `--format edn` for machine-readable output.

Interactively select videos and download only those selections:

```sh
clojure -M:run select \
  --loom-web \
  --cookie-file cookies.txt \
  --jobs 2 \
  --loom-source ALL \
  --out exports/loom
```

The selector accepts comma-separated numbers and ranges, for example `1,3-5`, plus `all` to select everything shown.
On an interactive terminal, `select` opens a full-screen picker:

- `Up`/`Down` or `j`/`k` moves the cursor.
- `Space` toggles the highlighted video.
- `/` starts filtering; type to search titles, ids, URLs, owners, or visibility.
- `a` toggles all currently shown videos.
- `n` clears the selection.
- `Enter` starts downloading the selected videos.
- `q` cancels.

If the terminal does not support raw interactive input, the command falls back to the comma-separated prompt.

Downloads show terminal progress bars when running in an interactive terminal. Use `--no-progress` to disable them. Use `--jobs N` to export multiple selected videos in parallel; start conservatively because Loom/CDN throttling and local network bandwidth can make high values slower rather than faster.

Discover from explicit Loom URLs:

```sh
clojure -M:run inventory --out exports/loom --url https://www.loom.com/share/<id>
```

Export videos from an existing manifest:

```sh
clojure -M:run export --out exports/loom --cookie-file cookies.txt
```

Metadata-only export:

```sh
clojure -M:run export --out exports/loom --skip-video
```

Verify an archive:

```sh
clojure -M:run verify --archive exports/loom
```

## Archive Layout

```text
exports/loom/
  manifest.json
  videos/
    <loom-id>__<slug>/
      metadata.json
      README.md
      transcript.json
      captions.srt
      video.mp4
      video.<caption-or-thumbnail-files>
```

## Notes

- “Everything” means everything visible to the authenticated account. Private personal videos from other users cannot be exported unless Loom exposes them to that account.
- `--loom-source ALL` lists videos visible through the selected workspace/library context. `MINE` limits discovery to the current user's own library. Other observed source enums include `USER_SPACE`, `ALL_PUBLIC_SPACES`, `USER_PUBLIC_SPACES`, `USER_PROFILE_SPACES`, and `ARCHIVED`.
- Some Loom plans, workspace settings, or per-video permissions may prevent MP4 downloads. Those videos stay in `manifest.json` with a structured skip reason.
- `--cookie-file` can read Netscape `cookies.txt` or a raw Loom `Cookie:` header. Keep this file private; it is equivalent to a browser session.
- `ffmpeg` must be installed and on `PATH` for video downloads.
- Downloads are written to `video.part.<ext>` first and moved to `video.<ext>` only after `ffmpeg` exits successfully. On resume, existing files are checked with `ffprobe` when available; truncated files whose duration is much shorter than Loom metadata are redownloaded.
