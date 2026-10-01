# Change Log

This file contains all the notable changes done to the `bal discover` tool through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add the `bal discover <org>/<package> [bucket] [selector ...]` command, to learn a Ballerina package's API from
  Ballerina Central without a web search
- Add the `client`, `service`, `class`, `funcs` and `readme` buckets, classified by call-site grammar rather than
  Ballerina Central's metadata
- Pair each service type with the listener it binds to in the `service` bucket — the listener's `attach` target,
  or a type that includes it, read from the package's source when Central's docs cannot say — and list the
  service types no listener accepts apart
- Address resource functions by path and accessor, with path parameters spelled `:name` and keyword-escaped segments
  quoted in every printed command
- Group large resource listings by path segment, up to four levels below the top level
- Add a drill-down from a listing to a single method's or resource's signature, together with the types it names
- Add the `--module`/`-m` flag to target a submodule, across every bucket
- Add the `--filter` flag to narrow a bucket listing by keyword
- Cap every listing at 40 entries, with `--page` to page through any longer one
- Add a ready-to-run next command to every listing entry: `command`, or `commands` keyed by accessor on resource rows,
  plus a `next` command when a listing continues
- Add JSON output (one line per answer) and aligned human-readable text output, selected by `--output json|text` and
  defaulting to text on a terminal and JSON otherwise
- Report failures as a single-line JSON object on stderr, with exit code 1
- Cache Central responses under `$XDG_CACHE_HOME`, falling back to a live fetch on any cache failure
