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
- Add the `type` bucket for what is not callable — records, enums, errors, type aliases, constants, module variables
  and annotations — listed by kind, with `type <Name>` reading one whole together with the declarations it names, the
  ones its size budget left out and the ones another package owns, each with the command that opens it; a class,
  client, service type or listener named to `type`, and a record named to a container bucket, is answered by the
  bucket that holds it, and an enum member to the enum that declares it
- Pair each service type with the listener it binds to in the `service` bucket — the listener's `attach` target,
  or a type that includes it, read from the package's source when Central's docs cannot say — and list the
  service types no listener accepts apart
- Address resource functions by path and accessor, with path parameters spelled `:name` and keyword-escaped segments
  quoted in every printed command
- Group large resource listings by path segment, up to four levels below the top level
- Add a drill-down from a listing to a single method's or resource's signature, together with the types it names
- Add the `--module`/`-m` flag to target a submodule, across every bucket, read from the submodule's own Ballerina
  Central page; the bare package lists its submodules, and a submodule named as the package (e.g.
  `ballerinax/aws.auth`) fails with the `--module` command that reads it
- Add the `--filter` flag to narrow a bucket listing by keyword
- Read one exact version of a package from the coordinate, `<org>/<package>:<version>`; it outranks the version a
  project locks, and is carried into every command the answer prints
- Cap every listing at 40 entries, with `--page` to page through any longer one; a text listing that continues also
  ends with the `--filter <keyword>` command that narrows it
- List containers, service types, methods, resource paths and declarations alphabetically
- Add a ready-to-run next command to every listing entry: `command`, or `commands` keyed by accessor on resource rows,
  plus a `next` command when a listing continues
- Add JSON output (one line per answer) and aligned human-readable text output, selected by `--output json|text` and
  defaulting to text on a terminal and JSON otherwise
- Report failures on stderr with exit code 1, in the run's output mode: a single-line JSON object, or
  `error: <message>` with the suggestion indented below it
- Cache Central responses under `$XDG_CACHE_HOME`, falling back to a live fetch on any cache failure
