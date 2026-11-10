# Change Log

This file contains all the notable changes done to the `bal discover` tool through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add the `bal discover <noun> [<name>] -p <org>/<name>[:<version>] [options]` command, to learn a Ballerina
  package's API from Ballerina Central without a web search; the first word says what comes back, a name after it
  says which one, and options may come before or after the noun
- Add the nouns `package`, `search`, `client`, `service`, `class`, `function`, `type`, `method`, `resource` and
  `readme`; `client`, `service`, `class` and `function` are classified by call-site grammar rather than Ballerina
  Central's metadata
- Add `package <org>/<name>[:<version>]`, the package's overview: its clients, service types, classes and functions
  with their member counts, ordered by how often its readme uses each name, a line for its types by kind, one for
  its readme's sections, and its submodules, each with the command that opens it; a package with exactly one
  client opens that client in place; `bal discover <org>/<name>` is short for it
- Add `search <query>`, taking its keywords as one quoted query (`search "kafka send"`): across Central, its
  packages and symbols, each row with the `bal discover` command that opens it; with `-p`, every name in that one
  package holding each keyword, grouped by kind and searched locally; `package --filter <keyword>` runs the same
  search
- Add `method` and `resource`, naming their container with `--client`, `--class` or `--service`, which may be left
  out when exactly one container declares what is named; a method several containers declare is an `ambiguous`
  failure listing each one's command, and a container flag of the wrong kind fails with the command that has the
  right one
- Add `type`, listing every type the package declares by kind — records, enums, errors, type aliases, constants,
  module variables, annotations, listeners, classes, clients and service types — with `type <Name>` opening any of
  them: a declaration whole together with the declarations it names, the ones it leaves out and the ones another
  package owns, each with the command that opens it; a listener's own declaration, `init` and its other methods
  included; the answer a class, client or service type gets as its own noun; and, for an enum member, the enum
  that declares it
- Add `readme`, the module's README verbatim when it is at most 10 KB, else every one of its sections with the
  command that opens it; `readme <n>` or `readme "<title>"` reads one section
- Pair each service type with the listener it binds to in `service` — the listener's `attach` target, or a type
  that includes it, read from the package's source when Central's docs cannot say — and list the service types no
  listener accepts apart
- List the methods a service of a type must or may define, under *Methods to implement*, from the
  `metadata/trigger-metadata.json` the package's bala publishes, each marked `required`, `optional` or
  `any number`; a service type that has none answers with its documentation and the readme sections that declare
  such a service
- Address resource methods by path, with path parameters spelled `:name` and keyword-escaped segments printed
  plain (`gists/public`) and accepted in either spelling; a path is one argument matched whole as listed, so a
  dotted segment (`reactions.get`), the root path `.` and a rest parameter `:...path` need no splitting rule
- Select one method of a resource path with `--accessor <accessor>`, valid on `resource` alone (on another noun a
  usage error whose `command` drops it); a path with one accessor and no path under it answers with its signature
  without it, and an accessor the path does not declare fails with the path's accessors as `candidates`;
  `--filter <accessor>` narrows a resource listing to that accessor's methods
- Take at most one name per noun: more names than a noun takes is a usage error (exit 2) whose `command` is the fix
  when there is one — `search kafka send` is `search "kafka send"`, `method Producer send` is
  `method send --client Producer`, and `resource gists/public get` is `resource gists/public --accessor get` when
  `get` is one of the path's accessors; a package typed as a name is moved to `-p`, and two names that both read as
  a package get no command
- Answer the old package-first form (`bal discover ballerinax/kafka client Producer`), a plural or old noun
  (`funcs`, `methods`) and an unknown noun as usage errors whose `command` is the request in the current grammar,
  the noun meant, or a search for the word
- Match every name exactly as the listings print it — case-sensitive, a parameter only as `:name`, a path whole
  from its first segment — and fail a miss as `symbol-not-found` (exit 1) that lists every name at the level the
  lookup reached as `candidates` when there are 40 or fewer, sorted as the listing sorts them, and otherwise
  carries that level's listing as its `command`, never a guessed near match; a name asked as a noun that does not
  hold it fails with the command for the noun that does; a `--filter` that matches nothing is still an answer
- Group large resource listings by path segment, up to four levels below the top level
- Add a drill-down from a listing to a single method's or resource method's signature, together with the types it
  names, how to construct its container (*Create it*), and the readme sections whose code calls it
- Add the `--module` option, with no short form, to target a submodule on every noun, read from the submodule's
  own Ballerina Central page; the package overview lists its submodules, and a submodule named as the package
  (e.g. `ballerinax/aws.auth`) fails with the `--module` command that reads it
- Add the `--filter` option to narrow a listing by keyword, naming apart the entries it matched only in their
  documentation
- Read one exact version of a package from the coordinate, `<org>/<name>:<version>`, written as complete SemVer;
  it outranks the version a project locks, and is carried into every command the answer prints
- Read the version a project locks from the `Dependencies.toml` of the project in the current directory only,
  never a parent's; without either, read Central's latest
- Name the version every answer was read at: a leading `version` key in JSON, and `org/name:version` in the text
  header, whether it was written, locked or Central's latest
- Lay out `--help` as the Ballerina CLI tool guide asks: a one-line description, the synopsis, the nouns, every
  option on one line with its default, and commented examples, within 80 columns; each noun's `--help` lists
  every option that noun takes; the exit codes are in the README
- Add `-V`/`--version`, printing the tool's version and exiting 0; only on its own, so a noun or a bare coordinate
  rejects it, and a missing version stamp fails rather than printing nothing
- Reject a switch given a value (`--refresh=true`, whose `command` drops the value), `-m` (whose `command` spells
  it `--module`) and a `--module` that is not a valid module name as usage errors, before anything is fetched
- Cap every listing at 40 entries, with `--page` to page through any longer one; a text listing that continues also
  ends with the `--filter <keyword>` command that narrows it
- List containers, service types, methods, resource paths and declarations alphabetically, and path groups by the
  number of resource methods under each
- Add a ready-to-run next command to every listing entry, the package overview's sections included: `command`, or
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
  1 on every other failure, a package, version or module Central does not publish, a malformed lock
  (`invalid-lock`), a page past the end (`page-not-found`) and a readme section without the `--filter` keyword
  (`filter-no-match`) included; exit 130 when interrupted; each `kind` falls under the core kind `usage` or
  `runtime`, listed in the README
- Tell a retryable failure from a permanent one by `kind` alone: `unavailable` (no answer, or a 429 or 5xx) and
  `timeout` are worth running again later; `upstream` (any other error status, or a body that is not JSON) and
  `schema-drift` are not
- Write the stale-cache warning (Central could not be reached, so the cached copy was read) to stderr as its own
  report, `{"severity":"warning","kind":"stale-cache",…}` or `bal discover: warning: <message>`, never in the answer
- Carry the caller's whole command with the fix applied as `command` in a failure that has one fix, printed as the
  `Try:` line in text: a version given as a name, a mistyped coordinate, a module path, a `--module` of a package
  with no submodules, more names than a noun takes, a name asked as the wrong noun, `--page` on an answer that does
  not page
- Answer a version Central does not publish with the published versions nearest it, a misspelled package with or
  without a version with the same spelling hint, and a bad lock with the `Dependencies.toml` it was read from
- Send requests to Ballerina Central through the HTTP proxy `bal pull` uses, the `[proxy]` table of
  `~/.ballerina/Settings.toml`, with its `username` and `password` for proxy authentication, sent once per request
- Name what failed when a request to Central gets no answer — the host, or the proxy and the `Settings.toml` that
  sets it, and the cause — with `reached` in the JSON failure telling an answer from Central from none
- Cache Central responses under `$XDG_CACHE_HOME`, falling back to a live fetch on any cache failure
- Run on Ballerina 2201.14.0 and Java 25; the tool's bala is published for the `java25` platform
