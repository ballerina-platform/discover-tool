# bal-discover-tool — System Design & Architecture

## Context

`bal discover` reads a Ballerina package off **Ballerina Central's docs API** and answers what its API is:
which buckets it has, what each bucket holds, and the exact signature of any one callable. It talks to
Central over HTTPS and to nothing else: no language server, no compiler invocation, no search index, and no
model of any kind.

The tool jar contains only our own classes (~370KB). Everything it needs at run time (`gson`, `picocli`, the
CLI launcher) is already on the Ballerina distribution's classpath, so those dependencies are `compileOnly`
and no third-party code is redistributed.

---

## Grammar

```
bal discover <org>/<package> [bucket] [selector ...]
             [--output json|text] [--filter <keyword>] [--page <n>] [-m|--module <name>] [--refresh]
```

**Package first, then drill down.** There is one picocli command. The package is resolved, then the bucket,
then the selectors — each positional narrows the one before it, so none of them is a mode switch. A package
with no bucket answers with its bucket list (and its submodules, when it publishes any).

**Five buckets.** `client`, `service`, `class` and `funcs` partition the callable surface by how a symbol is
CALLED: `client` is what is reached with `->` (remote methods and resource functions), `service` is a
service object type together with the listener it attaches to, `class` is a plain object reached with `.`,
and `funcs` needs no receiver. The split is DERIVED, not read off the payload: Central publishes no
`isClient` key and its `clients` array is not the callable surface — `ballerina/http` declares ten clients,
two of which Central files as ordinary declarations, and for `ballerina/sql` the array is empty while both of
its clients live there. `symbols/Surface` is the one place the partition is computed. A listener is never
addressable on its own; it appears as the `listener` a service pairs with. `readme` is the fifth bucket and is
not part of the callable surface at all.

**A wrong guess costs a line, not a call.** A bucket given a symbol of another kind still answers, with a
`note` naming the canonical bucket; a name that is a member rather than a container resolves and names its
owner; a member declared on several containers lists the owners rather than picking one. Without that, a
kind-specific bucket would make every guess a wasted round trip.

**Versions are internal.** There is no version syntax. `DiscoverTool` walks up from the process's directory
for a `Ballerina.toml`, and the `Dependencies.toml` beside it pins the version; outside a project the tool
reads Central's latest. A version-shaped argument is rejected with that rule as the suggestion.

**`--module` is resolved once**, by `Loader`, against the payload Central already returned (it carries every
module of the package), and every command the tool prints afterwards carries it forward through
`LoadedPackage.pkgArgument()`. The `<org>/<package>` argument is always one literal package coordinate; a
dotted name is never split into package and module by guessing.

---

## Architecture

```
                       argv
                        │
        ┌───────────────▼────────────────┐
        │  cli/DiscoverTool              │  BLauncherCmd. THE ONLY class that reads the
        │  (the process wrapper)         │  environment, the TTY, or exits the process.
        └───────────────┬────────────────┘
                        │  argv + streams + cache + interactive, all injected
        ┌───────────────▼────────────────┐
        │  cli/Cli + cli/Commands        │  picocli grammar → one bucket → one DiscoverResult
        │  cli/Usage                     │  → one renderer. Returns an exit code.
        └───────────────┬────────────────┘
                        │
        ┌───────────────▼────────────────┐
        │  Loader.loadPackage            │  resolve a version, then read it ONCE.
        └───────┬────────────────┬───────┘
                │                │
   ┌────────────▼──────────────┐ │
   │ central/PackageRepository │ │   the provider seam; CentralRepository is the
   │  └ CentralRepository      │ │   only implementation today
   └────────────┬──────────────┘ │
   ┌────────────▼─────────┐   ┌──▼──────────────────────────┐
   │ central/CentralClient│◄──┤ cache/DiskCache             │
   │ retry · Retry-After  │   │ raw payload, keyed by       │
   │ budget · 400-vs-404  │   │ coordinates. Never throws,  │
   │ offline fallback     │   │ never reports.              │
   └────────────┬─────────┘   └─────────────────────────────┘
                │  untyped JSON
   ┌────────────▼─────────────────┐
   │ central/schema/{CentralDocs, │  the ONE description of what Central sends.
   │                 Schema}      │  Read fields required; unknown keys stripped.
   └────────────┬─────────────────┘
                │  typed
   ┌────────────▼──────────────────────────────┐
   │ model/{FromCentral, Patches} → Library    │  the IR: sealed hierarchies, no flag bags.
   └────────────┬──────────────────────────────┘
                │
   ┌────────────▼────────────┐   ┌─────────────────────────┐
   │ symbols/{Declarations,  │   │ render/{Signatures,     │
   │  Names, PathTree,       │   │  TypeDefs} — the SHARED │
   │  Surface, Filter}       │   │  declaration renderer   │
   └────────────┬────────────┘   └───────────┬─────────────┘
                └──────────┬─────────────────┘
   ┌───────────────────────▼──────────────────────────────┐
   │ views/Containers  client · service · class · funcs   │
   │ views/Readme      readme                             │
   │ views/Closure     the type walk under one signature  │
   └───────────────────────┬──────────────────────────────┘
                           │  render/DiscoverResult
   ┌───────────────────────▼──────────────────────────────┐
   │ render/JsonRenderer (via CompactJson) │ TextRenderer │
   └──────────────────────────────────────────────────────┘
```

Every arrow points one way and no stage mutates its input.

---

## One result, two renderers

Every answer is a value of the sealed `render/DiscoverResult`, and `JsonRenderer` and `TextRenderer` are the
only two things that turn one into bytes — so the two renderings cannot drift into describing different data.
`--output json|text` picks one; without it, `DiscoverTool` passes whether stdout is an interactive terminal
(`System.console() != null`) and `Cli` picks text for a terminal and JSON otherwise. Pre-JDK 22, that check
also reports "not interactive" when only stdin is redirected; that errs toward JSON, which a person can
override and a parser could not.

| Shape             | Answers                                                                         |
| ----------------- | ------------------------------------------------------------------------------- |
| `BucketList`      | a bare package: its buckets and its submodules                                  |
| `ContainerRoster` | several containers in a bucket                                                  |
| `PathGroups`      | resource paths grouped by segment, plus those ending at the grouped prefix      |
| `ResourceList`    | resource paths, one entry per path with every accessor it answers to, paged     |
| `MethodList`      | remote or normal methods, alphabetical, paged over the ceiling                   |
| `MixedListing`    | a container with both resource functions and named methods, split by call form, paged |
| `Signature`       | exactly one callable, with the declarations its signature names                 |
| `NoMatch`         | a selector or `--filter` that matched nothing, with what is there instead       |
| `Owners`          | one member declared on several containers                                       |
| `EmptyBucket`     | a bucket the package declares nothing in, and the buckets that do               |
| `Readme`          | the readme verbatim, or one section of it                                       |
| `ReadmeChunks`    | the readme sections a selector or `--filter` matched                            |

The text rendering is laid out for a person at a terminal: a header naming where the answer is (package,
`--module`, bucket, container, selectors, `--filter` — from a `TextRenderer.Context` `Cli` builds out of the
argument list, since the JSON never needs to echo the request), a count line, one entry per line in columns
padded to the longest name (`TextTable`), plain section headings, and a footer of notes, the truncation line
and `Next:` commands. Where every row of a large listing has a command — a `call`, or a resource row's
`calls`, every accessor's command counting toward the shape — the footer prints the shape they share once, with
a placeholder (`Next: bal discover ballerinax/github client Client <group>`, or `<path> <accessor>` for
resources), and only a row its name cannot be substituted into — one needing shell quoting, a group opened
without the listing's accessor — prints its own (a quoted resource path with several accessors prints one
command with an `<accessor>` slot, which the row's own accessor list fills); owners, alternatives, `Elsewhere` and submodules keep a command on every row, since those
commands are the answer. No colour and no wrapping, so it pipes to `grep` and `less` intact; the whole readme
stays verbatim.

JSON field names follow the RFC's worked examples where one exists (`buckets`, `groups`, `resources`,
`path`, `accessors`, `calls`, `methods`, `shown`, `total`, `next`, `call`). `CompactJson` writes object fields inline
and one array element per line: valid JSON either way, and far fewer lines than indented JSON, which is the
measure an agent's tooling truncates on.

**Every row carries its exact next command.** A group, a container, a submodule and a readme section each open
one thing and carry a `call`. A resource row carries `calls` instead — an object keyed by accessor, in
`accessors` order, one command per accessor — whether it has one accessor or several: a flat `call` on a
multi-accessor path would have to guess which accessor, and one shape for every resource row means a caller
never branches on the accessor count. A `call`, `calls` value or `next` re-types the selection it came from: a
path selection canonically (path, then accessor), anything else exactly as given, one shell word per argument
(`Texts.shellWord`), with any `--filter` kept. `PointersTest` runs every `call`, `calls` value and `next` that
the first answers of every bucket and container print — bare, filtered, by name substring and by path plus accessor —
against the recorded payloads, then follows what those answers print two levels further on a bounded sample;
each must exit 0 with something other than a `NoMatch`, and none may print the command that produced it.
Commands with a `<slot>` are templates and are checked for shape, not run. It also runs every resource row's
`calls`, accessor by accessor, and requires each to open exactly that path and accessor's `Signature`.

Every answer about one container names it in `container` (in the header line, in text). A `Signature`'s `form`
is `->`, `.` or, for a constructor, `new`.

**A `Signature` quotes; it never re-spells.** Its `declaration` and each of its `types` come from
`Signatures`/`TypeDefs` byte for byte — the same functions that render the `.bal` API snapshots —
so `ViewsAgreeTest` can require every quoted line to appear in the API snapshot verbatim. The structured
`params` and `returns` beside it are derived from the same model, for a consumer that would rather not parse
Ballerina. The types are a depth-1 closure under a 6,000-byte budget, with anything left out named in
`omitted`: github's caches DELETE takes `*ActionsDeleteActionsCacheByKeyQueries`, whose fields ARE the call's
named arguments, so including it is what makes the call writable in one lookup.

---

## Output size: an entry ceiling, not a byte budget

No listing holds more than **40 entries** (`Containers.MAX_ENTRIES`). The earlier design bounded documents by
bytes and degraded through tiers without ever paginating, with a hidden `--all` to escape it; that is gone.
Over the ceiling:

- **Resource paths group** by their next literal segment — only when the selection is anchored at a path (or
  is the bare container), and counting the SELECTED operations, so an accessor narrows each `count` and rides
  into each group's `call`. Path parameters are transparent to grouping but spelled out in a group's name
  (`repos/:owner/:repo/actions`), since the literal segments alone can name two places. Operations that end
  exactly at the grouped prefix are listed beside the groups as resources, never as a group whose `call` would
  be the command that produced it. A group subdivides only while it is still over the ceiling, to at most four
  LITERAL levels below the top — surveyed against real connectors, three were always enough
  (`ballerinax/jira` needed the most), and the fourth is margin.
- **Remote and normal methods page**, alphabetically, with `--page <n>` (`Containers.Page`, shared with the
  readme's section listing and the mixed listing below). So do resource paths that cannot be grouped: a name-substring or `--filter`
  selection (there is no path prefix a group name could extend without inventing one) and one already past
  the depth cap. Grouping methods by a verb prefix was rejected: there is no fixed verb vocabulary across
  connectors to split on reliably (`ballerinax/twilio` alone has 199 remote methods on one client). A page
  outside the listing is a `validation` failure naming the range, never an empty page.
- **A mixed listing pages as one sequence**: resource paths, then remote methods, then normal ones, windowed
  by the same `Containers.Page` (`Page.slice` cuts each section to the window), so a page can end partway
  through one section and start the next. Each page keeps its section split and omits a section it holds
  nothing of; `next` is the `--page N+1` command carrying the selector, `--filter` and `--module`.
- **A roster of containers is cut** at 40 and points at `--filter`.

A cut answer always says so — `shown`/`total` and the `next` command in JSON (plus `page`/`pages` when
paged), a `... N more, narrow further` line and a `Next: <command>` line in text (`... N more (page P of
Q)` when paged, `N` counting what follows this page) — so a partial list is never mistaken for a complete
one.

`--filter` narrows within the bucket already selected, client-side, over the payload already in memory; it
is never sent to Central. It is a case-insensitive substring of an entry's surface text (name, path, parameter
and type names). An entry that matches only in its documentation is not listed but named in `documented`,
because rendering both buries the first set and dropping the second loses the caller who knows the capability
but not the vocabulary.

---

## Paths

Path parameters render `:name`, type-free (`repos/:owner/:repo`): the bracket form `[string owner]` breaks
under zsh's globbing and `{owner}` was not confirmed safe outside bash and zsh. A segment that collides with a
Ballerina keyword keeps its escape (`gists/'public`) — dropping it would describe a call that does not compile
— and every command the tool prints double-quotes such a path for the shell, so the caller never has to.

Selectors are tolerant on the way in: a parameter answers to `:owner`, `[string owner]`, `[owner]`, `{owner}`
and `owner`; an escaped segment answers to `code\-scanning` and `code-scanning`; an accessor may come before
the path (as its own argument or in the same one) or after it; and `new` addresses the constructor Ballerina spells `init`
— except on a container with resource paths, where a path selector is tried first, so a segment called `new`
(github's `codespaces/'new`) wins. These are input aliases only — output always
prints what the package declares.

A path and an accessor name one operation when the path itself declares that accessor; otherwise the
accessor filters everything beneath the path. Paths match from the first segment — an unanchored match for
`repos/{owner}/{repo}` on github would return nine operations rather than three. The one relaxation is a
trailing segment, which is looked for beneath the prefix that matched: answered (with a `note`) when it occurs
once, listed as `NoMatch.paths` when it occurs in several places. A wildcard `*` names every branch it also
matched in the `note`, because it takes the busiest one.

---

## What each module hides

| Module | Depth |
|---|---|
| `central/CentralClient` | **The adapter.** The only module that can fail for reasons outside the process: retry policy, `Retry-After` in both legal forms, jittered backoff, a wall-clock budget, Central answering an unpublished package with 400 rather than 404, and the offline fallback. |
| `central/PackageRepository` | The provider seam a second source (a local cache of Central, Artifactory) will implement. `Loader` holds an ordered list and tries each until one answers; `CentralRepository` is the only implementation so far. |
| `central/HttpTransport` | The seam every retry test drives. Transport trouble is a value, not an exception, so "503 then 200" is two records and no socket. |
| `central/schema/Schema` | The one place untyped JSON is touched. Collects EVERY mismatch before failing, because the person reading a drift failure is about to extend the schema. |
| `cache/DocsCache` | **The interface is the test surface.** `DocsCache.NULL` keeps every other test hermetic — no test can reach a developer's real `$HOME`. Nothing here may throw or report. |
| `model/FromCentral` | Central's flag-bag encoding is decided **once**, and nothing downstream ever sees `isResource`, `isAnonymousUnionType` or `inclusionType`. Also where a module is selected: the default module by exact id, a submodule only when `--module` names it. |
| `model/Patches` | A few per-package corrections, for names Central omits. |
| `model/ModuleRef` | A foreign reference's three derivations: the import path (keyword segments quoted), the CLI coordinate, and whether an import is needed at all. The pre-declared langlib set is measured with the compiler, not inferred from `lang.*`. |
| `render/Signatures`, `render/TypeDefs` | The **shared** declaration renderer. The views and the API snapshots agree because both call it; `ViewsAgreeTest` holds the line. |
| `render/DiscoverResult` | The result IR — see "One result, two renderers". |
| `symbols/PathTree` | Anchored, tolerant path matching, `locate`'s trailing-segment relaxation, and the per-node operations that grouping counts. |
| `symbols/Surface` | **The partition the four callable buckets address**, by derived role. Exhaustive and disjoint over every object a package declares, which `SurfaceTest` asserts in both directions. |
| `symbols/Filter` | `--filter`, as a linear scan over the package in memory, returning surface matches and documentation-only matches separately. |
| `views/Containers` | One implementation behind `client`, `service`, `class` and `funcs`. Holds the resolution order (exact container → exact member or path in scope → another bucket → substring member), the entry ceiling, grouping and paging. The selector grammar is read off the resolved CONTAINER, not the bucket: a client IS a class, so `class ballerina/http Client get ...` has to parse an accessor too. |
| `views/Closure` | The type walk under a signature: breadth-first, bounded, naming what it dropped. |
| `views/Readme` | The readme bucket: the whole readme, a section by number or title, or the sections `--filter` matches. A section counts only if it carries a fenced code block. |
| `Loader` | `loadPackage` is the only load, so no bucket is cheap because it skipped work another does. |
| `cli/Cli` | argv → exit code, with streams, transport, cache, project directory and interactivity injected so tests drive the real command. |
| `cli/DiscoverTool` | The process wrapper — the only place that reads the environment or exits. |

`views/TypeView` is not reachable from the CLI; it remains only because a set of `Closure` tests still drive
through it, and is due to be removed once they are ported.

---

## The contract

| | |
|---|---|
| stdout | the requested answer, and nothing else — including the usage text, when `--help` is what was asked |
| stderr | on failure, one JSON object matching `Failure`, and nothing else |
| exit 0 | success, and stdout is **complete** |
| exit 1 | every failure, whatever went wrong |

**One failure code, and the `kind` is the branch.** `upstream` and `timeout` are worth re-running
unchanged; `validation`, `package-not-found` and `symbol-not-found` need a different command, which the
`suggestion` names; `schema-drift` is for a maintainer. A selector that matches nothing inside a container
is not a failure: it is a `NoMatch` at exit 0, because an empty selection is a fact about the container and
the next move is in the answer.

---

## The cache

```
<root>/v2/docs/<repository>/<org>/<name>/<version>.json    mode 0600, no TTL
<root>/v2/latest/<repository>/<org>/<name>.json            {"version":"6.0.0","atMs":…}
```

What is cached is the **raw payload**, not the IR and not a rendered answer — the payload is not derived from
our code, so the coordinates are the whole key, and `--filter`/`--page` cost nothing extra once it is warm.

Location is a pure function of the environment (`cache/CacheLocation`), tried in order:

1. `BAL_DISCOVER_CACHE=off` — explicit opt-out
2. `BAL_DISCOVER_CACHE_DIR=<dir>` — explicit location, no fallback
3. `$XDG_CACHE_HOME/bal-discover` when absolute
4. `~/.cache/bal-discover` — the default
5. `<tmpdir>/bal-discover-<user>`, mode 0700
6. disabled

**Any** problem with an entry is a miss, never a failure: missing, unreadable, truncated, not JSON, rejected
by the schema, or coordinates that do not match its own path. Each drops the entry and uses the network, so a
corrupt entry cannot produce a wrong answer and heals on the next fetch.

Writes go to a per-process temp file and are then moved atomically. No lock and no single-flight: two
processes that miss the same package both fetch and both move, the content is equivalent, and no third
process can observe a partial file.

---

## Project structure

```
discover-tool/
├── build.gradle, settings.gradle, gradle.properties   ← root build; all versions
├── config/checkstyle/                                  ← :checkstyle
├── config/resources/{ToolBallerina,BalTool}.toml       ← templates for the bala
├── bal-tool/                                           ← :bal-tool, packs :native into ballerina/tool_discover
└── native/                                             ← :native, the tool itself
    └── src/
        ├── main/java/io/ballerina/tools/discover/
        │   ├── Result, Failure, QualifiedName, Version, Texts, Loader, LoadedPackage
        │   ├── cache/{DocsCache,DiskCache,CacheLocation,Versions}
        │   ├── central/{PackageRepository,CentralRepository,CentralClient,HttpTransport,
        │   │            JdkHttpTransport,HttpOptions,Coordinates,DependenciesToml,Json}
        │   ├── central/schema/{CentralDocs,Schema}
        │   ├── model/{Library,TypeDef,Fn,Service,TypeRef,Param,ReturnDef,RecordField,
        │   │          ClientClass,FromCentral,Patches,Defaults,Pipeline,ModuleRef}
        │   ├── render/{DiscoverResult,JsonRenderer,TextRenderer,TextTable,CompactJson,
        │   │           Signatures,TypeDefs,Documents,Identifiers}
        │   ├── symbols/{Declarations,Names,PathTree,Surface,Filter}
        │   ├── views/{Containers,Readme,Readmes,Closure,TypeView}
        │   └── cli/{DiscoverTool,Cli,Commands,Usage,UsageRenderer}
        └── test/
            ├── java/io/ballerina/tools/discover/   ← the suites below, plus constructs/ and render/
            └── resources/
                ├── fixtures/*.json.gz         ← 13 recorded Central payloads
                ├── snapshots/                 ← per fixture: the .bal API document, and every bucket's
                │                                 bare listing as .buckets.txt and .buckets.json
                └── command-outputs/unix/      ← usage and failure golden files
```

---

## Dependencies

Every dependency is `compileOnly` — all three are on the Ballerina distribution's runtime classpath
(`bre/lib`), so the jar bundles nothing:

| Dependency | Purpose |
|---|---|
| `org.ballerinalang:ballerina-cli` | the `BLauncherCmd` interface `bal` discovers the tool through |
| `info.picocli:picocli` | argument parsing |
| `com.google.code.gson:gson` | JSON parsing and emission |

HTTP is `java.net.http` from the JDK. The cost is version coupling to the distribution (`picocli 4.0.1`), so
nothing here may rely on a feature newer than the version in `bre/lib`. `ballerina-cli` is published only to
ballerina-platform's GitHub Packages, so building needs a `read:packages` token (see `README.md`).

---

## Tests

`./gradlew :native:test` — offline, no network and no `$HOME` access.

| Suite | What it holds the line on |
|---|---|
| `CorpusTest` | Thirteen recorded payloads render byte-for-byte to thirteen committed `.bal` API snapshots — the oracle every quoted declaration is checked against. |
| `ViewsAgreeTest` | **What makes the drill-down safe.** Every exact member name and every exact path plus accessor resolves to one `Signature` (over a corpus-wide floor), and every line it quotes appears in the API snapshot verbatim; every path the tree offers is reachable and nothing unoffered is; closures terminate, do not repeat and stay bounded. |
| `PointersTest` | Every `call`/`calls`/`next` command the first answers print (bare, filtered, per container, by name substring, by path plus accessor) is RUN through the real CLI against the recorded payload, then what those print is followed two levels further on a bounded sample; each must exit 0 with something other than "nothing matched", and none may point back at the command that printed it. Every resource row's `calls`, one per accessor, must open exactly that path and accessor's signature. |
| `SelectionCarryTest` | A page, a group and a canonical command re-select exactly what the listing was a window onto: the selector, the accessor and the `--filter` ride along, a substring never becomes a path, the depth cap counts literal segments, and an out-of-range `--page` fails. A mixed listing over the ceiling (an edited http payload, since no recorded container is one) pages across its section boundaries without repeating or skipping an entry. |
| `ViewsTest` | The `.buckets.txt`/`.buckets.json` snapshots, the entry ceiling over every fixture (listed sizes, `shown` against what is listed, a `next` on every cut listing, nested `NoMatch.available` listings included), and the resolution and tolerance rules. Also where path ordering is pinned: a locale collator, not `String::compareTo`, which disagree on real github segments. |
| `RegisterTest` | Over every fixture and a broad set of queries: every answer renders as one JSON object, no text rendering carries Markdown report furniture, no `note` carries a Markdown backtick, every text listing puts one entry per line with its columns lined up and every JSON `call`/`calls`/`next` reachable from the text, and quoted Ballerina carries no fences of the tool's own. |
| `render/DiscoverResultRenderingTest` | Every result shape, in both renderers, driven directly. |
| `CliTest` | Parsing, streams, exit codes and `--output` together, in-process against a recorded payload, including following every resource row's `calls`, accessor by accessor, to the signatures they name. |
| `SurfaceTest` | That the bucket partition is exhaustive and disjoint, and that a `client object` type Central files as an ordinary declaration is still a `client`. |
| `ReadmeTest` | That the resolved module's readme is there and passed through untouched. |
| `FromCentralTest`, `PackageRepositoryTest` | Module selection (`--module`, exact default-module match) and the provider seam. |
| `CacheTest` | Every corruption mode falls through to the network silently; TTL boundaries; concurrency; the offline fallback. |
| `ClientTest` | Which failures are worth retrying, which are answers, and what each costs the caller. |
| `SymbolsTest` | The discovery corpus — real lookups recorded from agent runs, pinned with hit counts. |
| `KeySpaceTest` | The payload's whole key space per fixture: the drift detector for fields the reader does not read yet. |
| `PatchesTest` | Each correction pinned in both directions — what it must change and what it must leave alone. |
| `constructs/ConstructTest` | One synthetic payload per Ballerina syntax dimension, so a rendering change fails by construct name rather than as a corpus-wide diff. |
| `DiscoverToolTest` | The usage text as a golden file, and that `bal` hands the whole argument list through unparsed. |

Snapshot escape hatches, both narrow and deliberate:

```bash
UPDATE_SNAPSHOTS=1 ./gradlew :native:test              # after an intentional rendering change — review the diff
BAL_DISCOVER_UPDATE_KEYSPACE=1 ./gradlew :native:test   # after re-recording fixtures
```

The suite does **not** prove the tool is installed, that `bal` routes to it, that arguments survive the
launcher, or that exit codes reach the shell — see "Verifying a build against live Central" in `README.md`.
