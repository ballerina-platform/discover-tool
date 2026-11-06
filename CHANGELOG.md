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
- Add the `type` bucket, listing every type the package declares by kind — records, enums, errors, type aliases,
  constants, module variables, annotations, listeners, classes, clients and service types — with `type <Name>`
  opening any of them: a declaration whole together with the declarations it names, the ones its size budget left
  out and the ones another package owns, each with the command that opens it; a listener's own declaration, `init`
  and its other methods included; the answer a class, client or service type gets in its own bucket; and, for an
  enum member, the enum that declares it
- Pair each service type with the listener it binds to in the `service` bucket — the listener's `attach` target,
  or a type that includes it, read from the package's source when Central's docs cannot say — and list the
  service types no listener accepts apart
- Address resource functions by path and accessor, with path parameters spelled `:name` and keyword-escaped segments
  quoted in every printed command
- Match every selector exactly as the listings print it — case-sensitive, a parameter only as `:name`, a path whole
  from its first segment, the container always named — and fail a miss as `symbol-not-found` (exit 1) that lists
  every name at the level the lookup reached as `candidates` when there are 40 or fewer, sorted as the listing
  sorts them, and otherwise carries that level's listing as its `command`, never a guessed near match; a name asked
  in a bucket that does not hold it fails with the command in the bucket that does, and a member named where its
  container goes with its owner's command, or the bucket's listing when several containers declare it; a `--filter`
  that matches nothing is still an answer
- Group large resource listings by path segment, up to four levels below the top level
- Add a drill-down from a listing to a single method's or resource's signature, together with the types it names
- Add the `--module` flag, with no short form, to target a submodule, across every bucket, read from the
  submodule's own Ballerina Central page; the bare package lists its submodules, and a submodule named as the
  package (e.g. `ballerinax/aws.auth`) fails with the `--module` command that reads it
- Add the `--filter` flag to narrow a bucket listing by keyword
- Read one exact version of a package from the coordinate, `<org>/<package>:<version>`, written as complete SemVer;
  it outranks the version a project locks, and is carried into every command the answer prints
- Name the version every answer was read at: a leading `version` key in JSON, and `org/name:version` in the text
  header, whether it was written, locked or Central's latest
- Lay out `--help` as the Ballerina CLI tool guide asks: a one-line description, the synopsis, the nouns, every
  option on one line with its default, and commented examples, within 80 columns; the exit codes are in the README
- Add `-V`/`--version`, printing the tool's version and exiting 0
- Read the version a project locks from the `Dependencies.toml` of the project in the current directory only,
  never a parent's
- Reject a switch given a value (`--refresh=true`) and a `--module` that is not a valid module name as usage
  errors, before anything is fetched
- Cap every listing at 40 entries, with `--page` to page through any longer one; a text listing that continues also
  ends with the `--filter <keyword>` command that narrows it
- List containers, service types, methods, resource paths and declarations alphabetically
- Add a ready-to-run next command to every listing entry, the bare package's buckets included: `command`, or
  `commands` keyed by accessor on resource rows, plus a `next` command when a listing continues
- Add JSON output (one line per answer) and aligned human-readable text output, selected by
  `--output-format json|text` and defaulting to text when stdout is a terminal and JSON otherwise, whatever stdin
  is
- Report failures on stderr in the run's output mode, as the Ballerina CLI tool guide asks: a single-line JSON
  object with `"severity":"error"`, or `bal discover: error: <message>` with the suggestion indented below it and
  `Try: <command>` below that; every `message` is one line, lowercase-first, with no closing period and no class
  name, and every JSON failure carries the package and version it was for
- Exit 2 on a usage error — a malformed command line, or a name that matches several things (`ambiguous`) — and,
  in text, follow it with the one-line synopsis and `Try 'bal discover <noun> --help' for more information.`; exit
  1 on every other failure, a package, version or module Central does not publish included; each `kind` falls under
  the core kind `usage` or `runtime`, listed in the README
- Write the stale-cache warning (Central could not be reached, so the cached copy was read) to stderr as its own
  report, `{"severity":"warning","kind":"stale-cache",…}` or `bal discover: warning: <message>`, never in the answer
- Carry the caller's whole command with the fix applied as `command` in a failure that has one fix, printed as the
  `Try:` line in text: a version given as a positional, a mistyped coordinate, a module path, a
  `--module` the package does not publish, a selector nothing reads, a name asked in the wrong bucket, `--page` on
  an answer that does not page
- Answer a version Central does not publish with the published versions nearest it, a misspelled package with or
  without a version with the same spelling hint, and a bad lock with the `Dependencies.toml` it was read from
- Send requests to Ballerina Central through the HTTP proxy `bal pull` uses, the `[proxy]` table of
  `~/.ballerina/Settings.toml`, with its `username` and `password` for proxy authentication, sent once per request
- Name what failed when a request to Central gets no answer — the host, or the proxy and the `Settings.toml` that
  sets it, and the cause — with `reached` in the JSON failure telling an answer from Central from none
- Cache Central responses under `$XDG_CACHE_HOME`, falling back to a live fetch on any cache failure
- Run on Ballerina 2201.14.0 and Java 25; the tool's bala is published for the `java25` platform
