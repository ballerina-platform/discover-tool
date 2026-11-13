# Proposal: `bal discover`, a Deterministic Library Discovery Subcommand for Ballerina

_Owners_: @ThisaruGuruge  
_Reviewers_:  
_Created_: 2026/09/03  
_Updated_: 2026/11/12  
_Status_: Submitted  

## Summary

This proposal introduces `bal discover`, a new `bal` subcommand that gives AI agents and developers a deterministic, local way to learn a Ballerina library or connector's real API — its clients/services, methods, parameters, and return types — instead of falling back to web search, which frequently surfaces stale or hallucinated examples. `bal discover` is targeted at SwanLake, Ballerina's current runtime. Its contract is designed to be version-agnostic, so the same flags, commands, and output shape can carry forward to Ballerina's next major version, NutCracker, when that becomes relevant — though no NutCracker work is delivered here. `bal discover` is the near-term flagship deliverable of a wider Ballerina CLI improvement initiative aimed at making `bal` more agent-friendly across all of its subcommands; that broader initiative provides context for this proposal but is not itself the subject of this proposal.

The command is noun-first, in the shape of `docker` and `occ`: the first word says what comes back (`client`, `method`, `resource`, `type`, `readme`, ...), a name after it says which one, and `-p <org>/<package>` says which package. A bare package coordinate, `bal discover <org>/<package>`, is the package's overview, the one place the noun may be left out.

**Grammar.** Two grammars were considered: grammar A, a package-first drill-down (`bal discover <org>/<package> [bucket] [name]`), and grammar B, the noun-first one in this proposal. This proposal adopts B plus the bare package coordinate from A (see Alternatives Considered).

## Background & Motivation

Ballerina is more than a language — it is an integration platform, and the `bal` command-line tool is its primary interface. Developers use `bal` to create, build, test, and run Ballerina projects, and the tool is deliberately extensible: subcommands such as `bal openapi`, `bal graphql`, and `bal asyncapi` are built and shipped independently of the core tool. That extensibility is a defining strength of the ecosystem.

`bal` today has two distinct classes of user: the human Ballerina developer working interactively in a terminal, and AI agents — most notably WSO2 Integrator's Integration Intelligence agent (formerly WSO2 Integrator Copilot), which generates Ballerina code from natural-language input, as well as general-purpose coding agents operating on Ballerina codebases. Both go through the same CLI today, but the CLI was designed with only the first in mind.

That mismatch is most costly around library and connector usage. When an agent needs to know how to call a Ballerina library — what clients or services it exposes, what methods are available, what a method's parameters and return type look like — there is no reliable, local way to ask `bal` that question today. Agents fall back to web search instead, which often surfaces outdated blog posts or scraped code samples that no longer match the library's current API. The result is code that looks correct but fails to compile or doesn't behave as expected.

This proposal introduces `bal discover`: a subcommand that answers this question directly, deterministically, and from the same structured source of truth every time, removing an entire class of avoidable failure. It targets SwanLake, Ballerina's current runtime.

`bal discover` is the near-term flagship deliverable of a wider initiative to make the Ballerina CLI more broadly agent-friendly — covering topics such as structured output across all subcommands, POSIX-compliant behavior, and an extensible plugin architecture for Ballerina's next major version, NutCracker. That wider initiative is why `bal discover`'s own contract is designed to be version-agnostic (see Design Principle, below), but the wider initiative's own goals, design, and delivery are out of scope for this proposal.

This proposal also does not address a related but separate problem: that AI models are not extensively trained on Ballerina and can generate incorrect code for that reason alone. That is a model-knowledge gap, not a tooling gap.

## Goals

- Give agents and developers a deterministic, local discovery path into a library or connector's real API surface — client/service shape, methods, parameters, return types — eliminating the need to fall back on web search.
- Reduce the token cost and iteration count of learning a library's API by returning structured, purpose-built output instead of prose.
- Increase the correctness, determinism, and one-shot reliability of AI-generated Ballerina code that depends on library/connector usage, particularly for the Integration Intelligence agent.
- Design `bal discover`'s contract — command, flags, output shape — independent of the underlying Ballerina runtime, so it applies identically to SwanLake today and to NutCracker in the future, even though only SwanLake is implemented here.
- Ship `bal discover` as a real, working SwanLake subcommand — not just a design document.

## Non-Goals

- **Broader `bal` CLI/subcommand redesign.** Structured output, POSIX compliance, and a full behavioral contract (fail-fast validation, exit-code taxonomy, discovery self-description) for other subcommands (`bal openapi`, `bal graphql`, `bal run`, etc.) are part of the wider Ballerina CLI improvement initiative this proposal sits within, not this proposal itself.
- **MCP support.** Exposing `bal discover`'s data over MCP is a plausible future enhancement, not part of this proposal — the CLI itself remains the entry point for both human developers and Integration Intelligence.
- **Changing `bal search`.** The existing keyword-search command is left as-is, and whether it is later deprecated in favour of `bal discover search` is not decided here. `discover`'s `search` noun answers "which package, or which name in this package" (see Searching and Relationship to `bal search`, below); it is not a recommendation engine.
- **NutCracker implementation.** `bal discover`'s contract is designed to be NutCracker-compatible, but no NutCracker code, plugin mechanism, or Cobra-specific work is delivered by this proposal — that work is paced behind NutCracker's own compiler-modularization effort.
- **Best-practices guidance.** Surfacing library-specific best-practices content through `discover` is not part of this proposal — it's a future task.

## Proposed Design

Today, an agent writing Ballerina code that needs to use an unfamiliar library or connector has no reliable way to learn that library's real API from inside the toolchain. It falls back to web search, which surfaces stale blog posts, scraped GitHub snippets, or outdated examples — a direct path to hallucinated or non-compiling code. `bal discover` closes that gap: a deterministic, local discovery path that lets an agent learn a library's client/service shape, its methods, and their parameters and return types, entirely from tooling already on the machine. The target depth is modeled directly on `go doc` — a Go-writing agent reaching for `go doc` against the standard library mid-session, to answer exactly this kind of "how do I call this" question, is observed, everyday behavior; Ballerina-writing agents have no equivalent today.

Rather than building this from scratch, `bal discover` is implemented directly on top of `bal-library-tool`, an existing implementation of this same idea maintained by the AI team. That codebase is the implementation basis for `bal discover` — a running start on symbol-level introspection, output contract design, and caching, rather than re-deriving all of it independently.

**About the examples.** Every example in this document is real output of the implementation run against live Ballerina Central. The examples were captured at different times, so the version in one example can differ from the version in another for the same package. The header of each answer names the package version it was read at, since Central moves on and counts drift with it. A line holding only `...`, or a `"..."` element inside JSON, marks output this document elides.

### Design Principle: A Version-Agnostic Contract, SwanLake-First

`bal discover`'s contract — its command name, flags, and output shape — is designed independently of either Ballerina runtime's current CLI foundation (SwanLake's picocli, or the forthcoming NutCracker's Cobra), as the contract a well-designed, agent-facing discovery tool would have if built from a blank slate. Implementation and delivery under this proposal are SwanLake-only: `bal discover` ships as a real, working subcommand against Ballerina's current runtime, constrained by picocli's extension model. The contract itself, however, must hold verbatim on a future NutCracker build — the same flags, the same commands, the same output shape — even though no NutCracker code, testing, or Cobra-specific accommodation is part of this proposal. This discipline exists so that `bal discover` remains the same tool an agent already knows how to call, regardless of which Ballerina runtime eventually answers it.

### Command Grammar

```
bal discover <noun> [<name>] -p <org>/<package>[:<version>] [--client|--class|--service <name>] [--accessor <accessor>] [flags]
bal discover package <org>/<package>[:<version>]
bal discover <org>/<package>[:<version>]
bal discover search "<query>" [-p <org>/<package>[:<version>]]
```

The first word says what comes back; a name after it says which one. A name means one thing, and no name means a listing. The grammar is verb-less on purpose: `discover` is read-only and will never have write actions, so there is no `list`/`get` verb to type, and no clash with a method that happens to be called `list` or `get`.

**One positional per noun.** Every noun takes at most one positional, as the Ballerina CLI conventions ask. The command words — `discover` and the noun after it — are not positionals. The positional is the value the answer is about: the package coordinate after `package`, a client's name, a method's name, a resource path, a type's name, a readme section, a search query. Everything else is a flag, even when it is required: the scope (`-p`, `--module`, and the container, `--client`, `--class` or `--service`) and the modifiers (`--accessor`, `--filter`, `--page`, ...). So a resource method is a path plus `--accessor`, and a search of several keywords is one quoted query. More names than a noun takes is a usage error (exit 2) whose suggestion is the command that reads them, when there is one.

| Noun | What it answers |
| --- | --- |
| `package <org>/<package>` | The package's overview: its clients, service types, classes and functions with member counts, one line for its types by kind, one for its readme's sections, and its submodules. |
| `search "<query>"` | Central's packages and symbols; with `-p`, every name in one package that holds each keyword of the query (see Searching, below). |
| `client [<name>]` | Objects reached with `->`: the clients, or one client's resource paths, remote methods and methods. |
| `service [<name>]` | Service object types, under the listener that accepts each; the rest are listed apart. |
| `class [<name>]` | Plain objects reached with `.`. |
| `function [<name>]` | Module-level functions, or one function's signature. |
| `method [<name>]` | A container's remote and plain methods, or one method's signature; `init` is the constructor. |
| `resource [<path>]` | A container's resource paths, the paths under one, or one resource method's signature (`client->path.accessor(...)`): a path with one accessor and no path under it answers directly, and `--accessor <accessor>` picks one of several. |
| `type [<name>]` | Every type the module declares, or one declaration. |
| `readme [<n>\|"<title>"]` | The module's README, verbatim, or one section of it. |

Every noun but `package` takes the package with `-p`, in the form `bal pull` takes (`<org>/<package>[:<version>]`); `search` takes it only to search one package. A flag may come before the noun or after it. Every command the tool itself prints is written in one order — the noun, its name, `-p`, the container flag, `--accessor`, then the other flags — so a printed command is also a model for the next one an agent writes.

**The bare package coordinate.** `bal discover <org>/<package>[:<version>]` is the same command as `bal discover package <org>/<package>[:<version>]`: the same answer, the same exit code, the same flags. It is the one place a noun may be left out, because the package is where every session starts and the first thing an agent types is the package. Agents type the package first even after reading a noun-first help page; accepting that form removes the failure instead of teaching around it (see Alternatives Considered). Longer package-first commands (`bal discover ballerinax/kafka client Producer`) are not accepted: they exit 2 with the same request written in the right order, as the example under Errors and Exit Codes shows.

`method` and `resource` name their container with `--client`, `--class` or `--service`. The flag can be left out when exactly one container declares what is named (`method send -p ballerinax/kafka` answers as `--client Producer`); when several do, the failure lists each one's command. Every command the tool prints names the container.

Each noun takes the flags below that apply to it (`bal discover <noun> --help` lists exactly those): `-V, --version` is root only, `--accessor` is `resource` only, and `search` takes no `--filter` or container flag:

| Flag | Meaning |
| --- | --- |
| `-p, --package <org>/<package>[:<version>]` | The package to read, on every noun but `package`; on `search` only to search one package. |
| `--client`, `--class`, `--service <name>` | The container a `method` or `resource` is declared by; optional when exactly one declares it. |
| `--accessor <accessor>` | On `resource` only: the method of the path, by its accessor (`get`, `post`, ...); optional when the path has one accessor and no path under it. On any other noun it is a usage error, "--accessor applies only to resource" (see Resource Functions). |
| `--output-format json\|text` | Override the TTY-detected default renderer. |
| `--filter <keyword>` | Narrow a listing (see Filtering Large Result Sets); on a resource listing, `--filter get` narrows it to the `get` methods (and to any entry whose path, parameter or type holds `get`). On `package` it runs the in-package `search`. |
| `--page <n>` | Turn the page of any listing over the ceiling (see Pagination). |
| `--module <name>` | Target a submodule, on every noun including `readme` (see Module Addressing). It has no short form; a name Ballerina would not accept as a module name is a usage error, before anything is fetched. |
| `--refresh` | Ignore the cached copy and fetch again (see Caching). A switch takes no value: `--refresh=true` is a usage error. |
| `-V, --version` | Print the tool's own version and exit 0. Root only: a noun, or a bare coordinate standing for `package`, rejects it. |

`bal discover --help` and `bal discover <noun> --help` follow the Ballerina CLI conventions for help: a one-line description, `Usage`, `Commands` (the nouns, on the root page), `Options` (every option the noun takes, one line each, with its default) and commented `Examples`, within 80 columns. Exit codes are not in help; they are under Errors and Exit Codes, below, and in the README.

### One Source, Multiple Renderers

`bal discover` produces one structured, canonical representation of its result for any given query. That structure is rendered in exactly one of two ways, and which one applies **by default** depends on how the command is being invoked:

- **Human-oriented text**, by default when output is going to an interactive terminal — formatted for readability, consistent with `bal`'s existing conventions.
- **Structured JSON**, by default when output is piped, redirected, or otherwise consumed non-interactively — detected via standard TTY detection, with no flag required to opt in.

Either default can always be overridden explicitly with `--output-format json` or `--output-format text`, regardless of how the command is being invoked. The same query renders differently depending on which applies — nothing about the underlying data changes, only its shape on the way out:

```
$ bal discover client -p ballerinax/kafka   # on an interactive terminal: human text
ballerinax/kafka:4.6.7 · client
3 clients

  Caller     3 remote methods
  Consumer  24 remote methods
  Producer   5 remote methods

Next: bal discover client <name> -p ballerinax/kafka

$ bal discover client -p ballerinax/kafka | cat   # piped or redirected: JSON, no flag needed
{"qualified":"ballerinax/kafka","version":"4.6.7","containers":[{"name":"Caller","remote":3,"command":"bal discover client Caller -p ballerinax/kafka"},{"name":"Consumer","remote":24,"command":"bal discover client Consumer -p ballerinax/kafka"},"..."],"shown":3,"total":3}
```

TTY detection sets the default, not a hard rule — a caller can always override it explicitly in either direction. What TTY detection actually removes is the need for `--output-format json` in the common, agent-facing case specifically: an agent's tool-call harness runs `bal discover` as a subprocess and reads its stdout through a pipe, which is never a real TTY, so it gets JSON by default without ever needing to know the flag exists. This rendering pattern is expected to extend to other `bal` subcommands as the wider CLI improvement initiative progresses, but `bal discover` is where it's implemented first.

### Bounding Output Size

Coding agents commonly reach for shell idioms like `head`/`tail` when working with command-line tools. Against a `bal discover` response, that can silently truncate a large result — the agent then reasons from a partial answer without knowing anything was cut off, and reaches a wrong conclusion with full confidence. This is a specific, real failure mode the design has to guard against directly, not a general performance nicety.

Four things follow from that:

- **Every listing has a hard ceiling of 40 entries, and targets around 50 lines of output overall.** The 40-entry limit is firm; the ~50-line figure is a target for listings, not a guarantee, because a leaf — a single method or resource signature, or a single type's declaration — is deliberately never cut: it is printed whole, however many fields its types carry, since a field left off could be one the caller has to set. (The bare `readme` is bounded differently; see The `readme` Noun, below.) A query that would naturally return more than that — a connector with hundreds of resource functions, a client with dozens of remote methods — is expected to be narrowed before it's returned in full, rather than silently emitting a long response an agent's own tooling might truncate out from under it. Path grouping (see Resource Functions, below) narrows resource-heavy connectors; `--filter` (see Filtering Large Result Sets, below) narrows either kind; and `--page` pages every listing that still exceeds the ceiling — remote methods are where real connectors need it first, since they have no path hierarchy to subdivide by (see Remote Functions, below). The package overview holds to the same ceiling: a section shows at most 10 rows, and when the whole still passes 40 rows the longest section gives up rows until it fits.
- **A single answer's supporting detail is bounded too.** A method's signature carries the declarations of the types it uses, one level deep: its parameter types always, in full, since they are what the call has to be written against; its return type and everything beneath only within a 6,000 bytes limit; and a class or object it uses directly never, since its body is a drill-down of its own. Every type used but not printed is listed by name with the command that prints it — all of them, never cut to a count (see The `type` Noun, below).
- **A response cut off by that ceiling says so explicitly, rather than silently stopping.** A truncated listing carries its own count alongside what's shown — `shown`/`total`, `page`/`pages`, `remaining` (how many entries are left after this page, documentation-only matches included) and `next` (the one command that turns the page) in JSON, or a trailing `... N more (page P of Q)` line in text, followed by `Next:` lines carrying the ready-to-run `--page` and `--filter <keyword>` commands (see the `repos` grouping example in Resource Functions, below, for what this looks like against a real connector) — so an agent that can't see the whole thing knows to keep drilling down rather than treating a partial list as complete. A response an agent reads as final has to actually be final; anything less has to say so.
- **Every JSON answer is exactly one line.** No line breaks inside it, however long. A `head`/`tail` cut — the very failure this section guards against — can then only drop a whole answer, never leave half of one behind that an agent might read as complete; line count stops mattering for JSON altogether, and its byte size is bounded by the entry ceiling instead. A `--pretty` flag to fully indent JSON for direct human reading is a plausible small addition, but isn't part of the initial design — piping to `jq` already covers that need without `discover` reimplementing a JSON formatter.

### Discovery Flow & UX

`bal discover` exposes a full drill-down chain: a package resolves to the clients, services, classes and functions it exposes, and to the types its API is written in; each of those resolves to its methods; each method resolves to its parameters and return type, and each type to its declaration. Every step is answerable from local tooling alone, with no external documentation or web lookup required to go from "I want to use this library" to "I know exactly what to call."

**The first answer is the package overview.** `bal discover <org>/<package>` lists, per noun, what the package has and how big each thing is, so one call replaces the several a bucket-by-bucket drill-down needs. Rows in each section are ordered by how often the module's own readme uses each name (a whole identifier in its code blocks or inline code; prose words never count), most used first, then alphabetically; every other listing is alphabetical. A package with exactly one client is most often used through it, so the overview opens that client in place, under its row, with page 1 of what `client <name>` would answer.

An example walkthrough (`ballerina/http`):

```
$ bal discover ballerina/http
ballerina/http:2.18.1

Clients (10)
  Client                                 7 resource methods  15 remote methods  4 methods
  Caller                                                      5 remote methods  1 method
  ClientOAuth2Handler                                         1 remote method   2 methods
  ClientObject                           7 resource methods  15 remote methods
  FailoverClient                         7 resource methods  15 remote methods  1 method
  ListenerLdapUserStoreBasicAuthHandler                       2 remote methods
  ListenerOAuth2Handler                                       1 remote method
  LoadBalanceClient                      7 resource methods  15 remote methods
  StatusCodeClient                       7 resource methods  15 remote methods  4 methods
  StatusCodeClientObject                 7 resource methods  15 remote methods
Service types (7)
  Service
  InterceptableService      1 method
  RequestErrorInterceptor
  RequestInterceptor
  ResponseErrorInterceptor
  ResponseInterceptor
  ServiceContract
Classes (83)
  Headers                          4 methods
  Request                         34 methods
  Response                        35 methods
  ClientBasicAuthHandler           3 methods
  ClientBearerTokenAuthHandler     3 methods
  ClientSelfSignedJwtAuthHandler   3 methods
  Cookie                           3 methods
  CookieStore                     10 methods
  CsvPersistentCookieHandler       4 methods
  DefaultStatus
  ... 73 more: bal discover class -p ballerina/http
Functions (7)
  authenticateResource
  createHttpCachingClient
  createHttpSecureClient
  getDefaultListener
  getHeaderMap
  getQueryMap
  parseHeader
Types   bal discover type -p ballerina/http    135 records, 6 enums, 65 errors, 32 aliases, 144 constants, 61 variables, 7 annotations, 1 listener
Readme  bal discover readme -p ballerina/http  8 sections

Submodules
  httpscerr  bal discover package ballerina/http --module httpscerr  This module provides status code error types.

Next: bal discover <noun> <name> -p ballerina/http
Next: bal discover search <query> -p ballerina/http

$ bal discover client -p ballerina/http
ballerina/http:2.18.1 · client
10 clients

  Caller                                                      5 remote methods  1 method
  Client                                 7 resource methods  15 remote methods  4 methods
  ClientOAuth2Handler                                         1 remote method   2 methods
  ClientObject                           7 resource methods  15 remote methods
  FailoverClient                         7 resource methods  15 remote methods  1 method
  ListenerLdapUserStoreBasicAuthHandler                       2 remote methods
  ListenerOAuth2Handler                                       1 remote method
  LoadBalanceClient                      7 resource methods  15 remote methods
  StatusCodeClient                       7 resource methods  15 remote methods  4 methods
  StatusCodeClientObject                 7 resource methods  15 remote methods

Next: bal discover client <name> -p ballerina/http

$ bal discover method get -p ballerina/http --client Client
ballerina/http:2.18.1 · method · Client · get

# Retrieve a representation of a specified resource from an HTTP endpoint.
# + path - Request path
# + headers - The entity headers
# + targetType - Expected return type (to be used for automatic data binding).
# Supported types:
# - Built-in subtypes of `anydata` (`string`, `byte[]`, `json|xml`, etc.)
# - Custom types (e.g., `User`, `Student?`, `Person[]`, etc.)
# - Full HTTP response with headers and status (`http:Response`)
# - Stream of Server-Sent Events (`stream<http:SseEvent, error?>`)
# + return - The response or the payload (if the `targetType` is configured) or an `http:ClientError` if failed to
# establish the communication with the upstream server or a data binding failure
isolated remote function get(string path, map<string|string[]>? headers = (), TargetType targetType = <>) returns targetType|ClientError;

Create it
  http:Client httpClient = check new (url, config);
  init(string url, *http:ClientConfiguration config)
  bal discover method init -p ballerina/http --client Client

Types it uses (4)
  # The types of data values that are expected by the HTTP `client` to return after the data binding operation.
...
```

The end of a drill-down — one callable — is deliberately more than a one-line signature. It is the declaration exactly as the library's source states it, doc comment included; then *Create it*, the line that constructs the client the call belongs to, with the `init` signature and the command that opens it; then the declarations of the types it uses, one level deep, under the rule in Bounding Output Size, above (*Types it uses*); every type it uses but doesn't print is named with the command that opens it (*Other types it uses, not printed*); and, when the package's own readme calls the method in a code example, the readme sections that do (*Readme sections that call it*). In JSON the same answer carries the `declaration` text plus structured parts: `params` (name, type, default, kind, description), `returns`, the call `form` (`->`, `.` or `new`), `deprecated`, `create`, `callers`, the closure (`types`), and the `omitted` and `foreign` rows.

The walkthrough above only shows `ballerina/http`'s remote method `get`, but just as much of the target UX is drilling into a connector's **resource** methods — a client addressed by path, not by method name — which many connectors, especially generated ones, expose as their primary calling convention. `ballerinax/github` has one client, so its overview opens it in place (`ballerinax/github`):

```
$ bal discover ballerinax/github
ballerinax/github:6.1.0

Clients (1)
  Client  903 resource methods
    Path groups (resource methods in each)
      repos                421
      orgs                 200
      user                  93
      teams                 34
      users                 34
      gists                 19
      projects              19
      app                   13
      repositories          11
      notifications          7
      search                 7
      marketplace_listing    6
      applications           5
      assignments            3
      classrooms             3
      advisories             2
      codes_of_conduct       2
      enterprises            2
      gitignore              2
...
      zen                    1
Types   bal discover type -p ballerinax/github    1102 records, 126 aliases
Readme  bal discover readme -p ballerinax/github  24 sections

Next: bal discover resource <group> -p ballerinax/github --client Client
Next: bal discover search <query> -p ballerinax/github

$ bal discover resource gists/:gistId -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · gists/:gistId
7 resource paths

  gists/:gistId                      get, delete, patch
  gists/:gistId/:sha                 get
  gists/:gistId/comments             get, post
  gists/:gistId/comments/:commentId  get, delete, patch
  gists/:gistId/commits              get
  gists/:gistId/forks                get, post
  gists/:gistId/star                 get, put, delete

Next: bal discover resource <path> -p ballerinax/github --client Client --accessor <accessor>
```

The full mechanism — grouping, the size ceiling, and drilling all the way down to one resource's accessors — is covered in Resource Functions, below.

The target covers packages resolved from a registry — starting with Ballerina Central — as well as local, workspace-only packages that have never been published anywhere public; both need to be discoverable through the same command. 0.1.0 reads Ballerina Central only (see Multi-Source Repository Interface, below).

A package version is part of the package coordinate, never a flag: `bal discover package ballerina/http:2.15.7`, the same `<org>/<package>[:<version>]` form `bal pull` takes, accepted wherever `-p` is. `--version` is deliberately not used for it — by convention that flag prints a program's own version (`bal --version`), and in `discover` it does: `bal discover -V` or `--version` prints the tool's version. Without a version, `discover` reads the one the current project locks, and otherwise Central's latest:

1. A version written in the coordinate is read exactly, even inside a project that locks a different one.
2. Otherwise, when the current directory is a Ballerina project (it holds `Ballerina.toml`), the version its `Dependencies.toml` locks for that package, so a lookup sees what `bal build` compiles against. No parent directory is searched.
3. Otherwise Central's latest: outside a project, in a project that has never been built (no `Dependencies.toml`), or for a package the project doesn't depend on yet — where latest is what adding the import would resolve to.

The version must be complete (`ballerina/http:2.15` is a usage error), and a version no repository has is a failure naming the version and the published versions nearest it, never a silent fallback to another. `--module` composes with it unchanged (`bal discover package ballerina/http:2.15.7 --module httpscerr`): the version is the package's, not the module's. A version written in the coordinate is carried into every command an answer prints, so drilling in stays on it; without one, printed commands are unpinned (inside a project, following one resolves the same locked version again). `--version` after a noun is an unknown option (exit 2).

One assumption worth stating plainly here: a uniform drill-down experience across every library depends on those libraries' documentation and specs being structured consistently enough to introspect the same way. That isn't true of every Ballerina library today, and is treated as a real dependency of this design (see Risks and Assumptions), not a solved problem.

### Nouns and Roles

Four of the nouns are the callable API surface, derived from call-site grammar:

- **client** — types called with `->`, exposing both `remote` and `resource` methods (see Resource Functions and Remote Functions, below). Resource methods are not a special case restricted to HTTP-style connectors — they're a first-class, widely-used calling convention in their own right, on equal footing with remote methods.
- **service** — service types, each listed under the listener it attaches to; the ones no listener accepts are listed apart. A listener itself is not a service and isn't opened here: its declaration — `init` and the rest of its methods, which is what `new http:Listener(9090, config)` is written against — is listed and opened under `type`.
- **class** — a genuinely rare catch-all for plain classes that are neither clients nor part of a service/listener pair.
- **function** — module-level functions.

`method` and `resource` are how one callable inside a container is opened. Methods are named the way Ballerina names them: a **resource method** (`client->path.accessor()`), a **remote method** (`client->name()`), or a plain **method** (`obj.name()`); module-level ones are **functions**. Counts are of methods, never of paths — `ballerina/http`'s `Client`, with one wildcard path and seven accessors on it, counts `7 resource methods  15 remote methods  4 methods`.

The other two are not part of the callable surface. **type** holds the types that surface is written in — the records, enums, errors and aliases an agent has to construct or match on to make a call at all — and is covered in The `type` Noun, below. **readme** is documentation, not something an agent calls, and is covered on its own terms in The `readme` Noun, below.

A symbol's noun is derived from its call-site grammar — whether it's invoked with `->`, with `.`, or with no receiver at all — never from Ballerina Central's own descriptive metadata. That metadata is not reliable enough to drive this: Central has no `isClient` field at all, its `isService` flag is present but consistently reads `false`, and `ballerina/sql`'s own `clients` array is empty even though its real `Client` and `SchemaClient` types exist — 5 and 4 real, arrow-called remote methods respectively (`query`, `queryRow`, `execute`, `batchExecute`, `call` on `Client`; `listTables`, `getTableInfo`, `listRoutines`, `getRoutineInfo` on `SchemaClient`), verified directly against Central's own docs API (`ballerina/sql` 1.19.0) — Central simply files both under a separate `objectTypes` category instead, since they're abstract client object types that concrete driver packages like `ballerinax/mysql` implement, not `client class` declarations themselves. Grammar-derived classification sidesteps all of this by construction.

Asking a noun a package has nothing in is not a failure: it says the noun is empty and lists, under *Elsewhere in the package* (`elsewhere` in JSON), the nouns that do hold something, each with its count and command:

```
$ bal discover function -p ballerinax/googleapis.gmail
ballerinax/googleapis.gmail:4.2.1 · function
ballerinax/googleapis.gmail has no functions.

Elsewhere in the package
  client   1  bal discover client -p ballerinax/googleapis.gmail
  type    39  bal discover type -p ballerinax/googleapis.gmail
```

Service and listener are deliberately paired into one noun rather than folded into the generic `class` noun: a service cannot run without attaching to a listener, and a listener with no service attached does not do anything, so treating them as two independently-useful things and burying the pairing inside a large catch-all would actively hide the thing an agent most needs to find. `ballerina/http` is a representative case: 83 plain classes against exactly 1 real listener and 7 real service types. Under a plain two-way client/class split, those 8 entries — the entire reason most agents reach for this library — would be lost inside 91 near-identical-looking classes. As a design rule, every service type the noun lists is either shown under the listener it binds to or explicitly listed as attachable to none — never left for the caller to work out.

**How the binding is derived.** Ballerina has no declaration that states which listener a service type is for, so `discover` derives the binding structurally, in the same grammar-derived spirit as the nouns themselves: a service type binds to a listener when it is the type the listener's `attach()` takes as its first parameter (resolved through unions and type aliases), or includes that type (`*Service;`). Not every `service object` type is attachable, and `ballerina/http` shows why that matters: `RequestInterceptor`, `ResponseInterceptor`, `RequestErrorInterceptor` and `ResponseErrorInterceptor` are `distinct service object` types of the same shape as `http:Service`, but the listener's `attach()` accepts none of them — they are used through `createInterceptors()`, never with `service ... on new http:Listener(...)`. They stay under `service`, addressable by name, but under a separate heading (`notAttachable` in JSON) rather than under a listener they cannot run on; opening one says it is not attachable to a listener and, when a method signature in the package names it, how it is used instead:

```
$ bal discover service -p ballerina/http
ballerina/http:2.18.1 · service
7 service types

http:Listener
  InterceptableService  1 method
  Service
  ServiceContract

Not attachable to a listener
  RequestErrorInterceptor
  RequestInterceptor
  ResponseErrorInterceptor
  ResponseInterceptor

Next: bal discover service <name> -p ballerina/http
```

Central's docs payload does not publish a service type's inclusions, so when some service type is not itself an `attach()` target, `discover` reads the package's own source to settle it — once per version, caching the small derived answer (see Caching, below). It looks first in the local Ballerina home repository, where `bal pull` and `bal build` put packages, then in the running distribution's own `repo/bala` (the standard library it ships), and only then downloads the bala Central publishes. When none of these is reachable, a pairing it could not settle is still listed under the candidate listener but marked not confirmed (`"confirmed":false` in JSON, with the reason in `unconfirmedReason`), and opening that service type says why: a degraded answer that says so, never a silent guess.

**Methods to implement.** Most listener packages give their service type no methods at all — `ballerinax/kafka`'s is `public type Service distinct service object {};` — because the methods a service must define (`onConsumerRecord`, an optional `onError`) are enforced by the package's compiler plugin, not declared in Ballerina; Central's docs payload has nothing to show for them. Since about 2026-09-07, standard library and connector packages ship that contract as data inside the package itself: `metadata/trigger-metadata.json`, written to the Ballerina Trigger Construct Spec v1.0 and already read by the WSO2 Integrator language server. For each service type it lists the methods a service defines — name, `remote` or `resource`, required or optional, parameters in order with their types, docs and presence, return type — and the rules across them ("exactly one of `onMessage` and `onRequest`"). `discover` reads it from the resolved version's bala with the same lookup the pairing source uses (home repository, then the distribution's `repo/bala`, then the bala Central publishes, downloaded once per run for both purposes), caches the small file per version — and, for a version that publishes none, that fact — and lists the methods under *Methods to implement* when the service type is opened (`bal discover service Service -p ballerinax/kafka`, or `type Service`): each as the declaration a service would write (`remote function onError(kafka:Error kafkaError) returns error?`), marked `required`, `optional` or `any number` (a shape the service repeats under names of its own, as `ballerina/graphql` and `ballerina/http` describe theirs), with its documentation and one line per parameter. Type names are spelled as source: the package's own types carry its module prefix, another module's carry that module's. It reads `v1.x` files, ignores fields it doesn't know, and refuses `v2` and later; a service type whose types it cannot write down yields no methods rather than a wrong list. Only the default module's service types are described this way: the file belongs to the package, not to a submodule. A service type that declares its methods itself (`trigger.*`, `salesforce`, `ballerina/ai`) keeps using the docs payload. The file's own listener→service list is incomplete (`trigger.github` names 10 of about 75 service types), so pairing stays with the `attach()` rule above; the file is read for methods only. A version published before its package shipped the file, or a bala that cannot be reached, keeps today's answer for the service type (no methods listed) — nothing is invented.

```
$ bal discover service Service -p ballerinax/kafka
ballerinax/kafka:4.6.7 · service · Service
Methods to implement (2)

remote function onConsumerRecord(kafka:Caller caller, kafka:AnydataConsumerRecord[]|kafka:BytesConsumerRecord[] records) returns error?
  required
  Invoked with each batch of records polled from the subscribed topics. The listener polls on its configured interval and dispatches everything it received as one batch.
  caller (optional): Handle for committing offsets explicitly. Needed only when auto-commit is disabled.
  records (required): The polled batch. Declare a narrower element type to project each record's value, see the consumerRecordPayload data-binding rule.

remote function onError(kafka:Error kafkaError) returns error?
  optional
  Invoked when polling fails, or when a polled batch cannot be dispatched, most commonly because a record's value fails to bind to the declared type.
  kafkaError (required): The failure that prevented the batch from being delivered.

Note: attach it to kafka:Listener
```

### Resource Functions

`ballerinax/github`'s client (6.1.0) exposes 903 resource methods across 595 distinct resource paths.

A resource is addressed by a **path** plus an **accessor** — the part that in Ballerina source looks like `client->path.accessor(...)`, with a bare `client->path(...)` as shorthand for `.get()`:

```ballerina
github->gists();
github->gists.post();
github->gists/'public();
```

**Terminology: `accessor`.** This matches both Ballerina's own grammar (`resource function <accessor> <path>`) and Central's docs schema, which already names this field `accessor` — no invented terminology, and no real conflict with HTTP/REST usage either, since `accessor` values are exactly the familiar verbs (`get`, `post`, `patch`, ...).

**i. One entry per resource path, not one per accessor.** A path with several accessors — `get`/`delete`/`patch` on the same resource — is one entry, not three. Scoped to GitHub's `gists` group for a printable example (the JSON is the real single line, with its rows cut to the first two):

```
$ bal discover resource gists -p ballerinax/github | cat
{"qualified":"ballerinax/github","version":"6.1.0","container":"Client","resources":[{"path":"gists","accessors":["get","post"],"commands":{"get":"bal discover resource gists -p ballerinax/github --client Client --accessor get","post":"bal discover resource gists -p ballerinax/github --client Client --accessor post"}},{"path":"gists/:gistId","accessors":["get","delete","..."],"commands":{"get":"bal discover resource gists/:gistId -p ballerinax/github --client Client --accessor get","delete":"bal discover resource gists/:gistId -p ballerinax/github --client Client --accessor delete","patch":"bal discover resource gists/:gistId -p ballerinax/github --client Client --accessor patch"}},"..."],"shown":10,"total":10}
```

Every entry is followable, and every resource entry has the same shape: a `commands` object keyed by accessor, one ready-to-run command per accessor — `{"get": ...}` for a single-accessor path, `{"get": ..., "delete": ..., "patch": ...}` for `gists/:gistId`. A single flat `command` would have to arbitrarily pick one accessor on multi-accessor paths, which would silently mislead rather than help; and a `command` on some entries but `commands` on others would make an agent branch on which field is present. Keying by accessor everywhere avoids both. (Entries that open exactly one thing — path groups, containers, submodules, methods, types — carry a single `command`.) A resource's container is named explicitly in every printed command (`--client Client`), since a package can expose more than one client; the flag may be left out when only one does. An exact resource path is itself a selector, not only a group name: `bal discover resource gists/:gistId -p ballerinax/github` lists `gists/:gistId` together with every resource beneath it (7 paths), the same way a group name would. Group counts count **resource methods** — one per path and accessor — not distinct paths: `gists` holds 19 resource methods, but its listing shows 10 path rows, because several paths carry more than one accessor.

Path parameters render with a `:name` prefix, type-free — the form already established for this exact purpose (a path this design shows has to be safely re-typeable as a shell argument for further drill-down; the literal Ballerina bracket syntax, `[string gistId]`, is not — it fails outright in zsh's default glob-matching behavior; brace-wrapped `{gistId}` was considered next but wasn't confirmed safe outside bash/zsh, so a colon prefix was chosen instead, verified inert across a wider set of shells).

A segment Ballerina has to escape prints **plain**: the path `gists/'public` is printed and typed `gists/public`, and `code\-scanning` is printed `code-scanning`. The apostrophe is unsafe unquoted in every shell tested (bash and zsh both), so printing it would leave a quoting burden on the caller. The escaped spelling is still accepted as input (`gists/'public`), and the code an answer quotes keeps Ballerina's own spelling (`isolated resource function get gists/'public(...)`), so what an agent goes on to write still compiles.

**ii. Large connectors group resources by path, to a bounded depth.** `bal discover resource -p ballerinax/github` groups first by top-level segment, and only subdivides a group further — by its next literal path segment — if that group would still exceed the size ceiling on its own. Path parameters are *not* transparent in a group's name: below the top level, a group's name is the real path prefix, parameters included — `repos/:owner/:repo/actions`, not `repos/actions` — so the name is exactly what gets typed back and exactly what appears in the resource paths underneath it; a shorthand that leaves the parameters out (`repos/actions`) is not a path and fails (see Names and Selectors, below). Every count in a group listing is a number of **resource methods**, not of distinct paths. A level's own paths are listed under *Paths at this level*, and the groups beneath it under *Path groups (resource methods in each)*. Groups are listed by resource-method count, largest first (ties alphabetical); every other listing — resource paths, methods, containers, types — is alphabetical. A response a group's subdivision doesn't fully resolve reports what's cut off and how to continue, in both renderers — never a silent stop. Full walkthrough (`ballerinax/github`), starting from nothing and ending at a concrete, callable resource list:

**Step 1 — the whole client.** 903 resource methods fall under just 36 top-level path segments; all 36 fit under the ceiling, so nothing is cut off here (the first rows are shown):

```
$ bal discover resource -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client
36 path groups

Path groups (resource methods in each)
  repos                421
  orgs                 200
  user                  93
  teams                 34
  users                 34
  gists                 19
  projects              19
  app                   13
  repositories          11
  notifications          7
...

Next: bal discover resource <group> -p ballerinax/github --client Client
```

**Step 2 — drilling into `repos`, the largest group.** `repos` holds 421 resource methods: one path directly under it (`repos/:owner/:repo`, shown as a resource row) and 64 groups beneath, subdivided by their next literal segment. That's 65 entries against a ceiling of 40, so *this* response is genuinely truncated — it is page 1 of 2, and says so, with the actual next commands to run:

```
$ bal discover resource repos -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · repos
1 resource path here, 64 path groups

Paths at this level
  repos/:owner/:repo  get, delete, patch

Path groups (resource methods in each)
  repos/:owner/:repo/actions                   72
  repos/:owner/:repo/branches                  36
  repos/:owner/:repo/pulls                     31
  repos/:owner/:repo/issues                    30
  repos/:owner/:repo/releases                  16
  repos/:owner/:repo/environments              14
  repos/:owner/:repo/code-scanning             13
...

... 25 more (page 1 of 2)
Next: bal discover resource <path> -p ballerinax/github --client Client --accessor <accessor>
Next: bal discover resource <group> -p ballerinax/github --client Client
Next: bal discover resource repos -p ballerinax/github --client Client --page 2
Next: bal discover resource repos -p ballerinax/github --client Client --filter <keyword>
```

**Step 3 — drilling into `repos/:owner/:repo/actions`.** Its 72 resource methods still exceed the ceiling, so they subdivide once more — but this time all next-level groups fit, so nothing is cut off.

```
$ bal discover resource repos/:owner/:repo/actions -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · repos/:owner/:repo/actions
13 path groups

Path groups (resource methods in each)
  repos/:owner/:repo/actions/runs                    20
  repos/:owner/:repo/actions/runners                 12
  repos/:owner/:repo/actions/permissions              8
  repos/:owner/:repo/actions/workflows                7
  repos/:owner/:repo/actions/secrets                  5
  repos/:owner/:repo/actions/variables                5
  repos/:owner/:repo/actions/artifacts                4
  repos/:owner/:repo/actions/caches                   3
  repos/:owner/:repo/actions/jobs                     3
  repos/:owner/:repo/actions/oidc                     2
  repos/:owner/:repo/actions/cache                    1
  repos/:owner/:repo/actions/organization-secrets     1
  repos/:owner/:repo/actions/organization-variables   1

Next: bal discover resource <group> -p ballerinax/github --client Client
```

**Step 4 — drilling into `repos/:owner/:repo/actions/runs`.** 17 resource paths, comfortably under the ceiling: this is the concrete, callable answer, not another grouped listing:

```
$ bal discover resource repos/:owner/:repo/actions/runs -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · repos/:owner/:repo/actions/runs
17 resource paths

  repos/:owner/:repo/actions/runs                                      get
  repos/:owner/:repo/actions/runs/:runId                               get, delete
  repos/:owner/:repo/actions/runs/:runId/approvals                     get
  repos/:owner/:repo/actions/runs/:runId/approve                       post
  repos/:owner/:repo/actions/runs/:runId/artifacts                     get
  repos/:owner/:repo/actions/runs/:runId/attempts/:attemptNumber       get
  repos/:owner/:repo/actions/runs/:runId/attempts/:attemptNumber/jobs  get
  repos/:owner/:repo/actions/runs/:runId/attempts/:attemptNumber/logs  get
  repos/:owner/:repo/actions/runs/:runId/cancel                        post
  repos/:owner/:repo/actions/runs/:runId/deployment_protection_rule    post
  repos/:owner/:repo/actions/runs/:runId/force-cancel                  post
  repos/:owner/:repo/actions/runs/:runId/jobs                          get
  repos/:owner/:repo/actions/runs/:runId/logs                          get, delete
  repos/:owner/:repo/actions/runs/:runId/pending_deployments           get, post
  repos/:owner/:repo/actions/runs/:runId/rerun                         post
  repos/:owner/:repo/actions/runs/:runId/rerun-failed-jobs             post
  repos/:owner/:repo/actions/runs/:runId/timing                        get

Next: bal discover resource <path> -p ballerinax/github --client Client --accessor <accessor>
```

**iii. One more level down: a resource's own parameter/return-type signature.** Path plus accessors is the terminal answer for *which* resources exist, but not yet for *how to call* one — the same gap `method` already closes for remote methods. The path stays the one positional, and the accessor is the `--accessor` flag. A path with one accessor and no path under it answers directly with its signature, since there is nothing to choose between: 65.7% of the 5,739 resource paths on Central (every package's latest version, surveyed 2026-11-07) have exactly one accessor, so most resource lookups need no flag at all, the way a method needs no container flag when one container declares it. Any other path — several accessors, or paths under it — lists, and each row carries the full `--accessor` command for each of its accessors. Central's docs API carries the same fully-expanded parameter/return-type data for resource methods that it does for remote methods, so this is a real, already-available drill-down, not a new data source. The answer is a header naming the package, noun, container, path and accessor (when one was given), then the declaration as the library's own source states it — doc comments and the full `isolated resource function ...` line — then *Create it* and the declarations of the types it names (see The `type` Noun, below). In JSON the same answer carries the declaration plus a structured `params` list (name, type, default, description) and `returns`:

```
$ bal discover resource gists/public -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · gists/public

# List public gists
# + headers - Headers to be sent with the request
# + queries - Queries to be sent with the request
# + return - Response
isolated resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries queries) returns BaseGist[]|error?;

Create it
  github:Client githubClient = check new (config);
  init(github:ConnectionConfig config, string serviceUrl = "https://api.github.com")
  bal discover method init -p ballerinax/github --client Client

Types it uses (5)
  # Represents the Queries record for the operation: gists/list-public
  public type GistsListPublicQueries record {
      # The number of results per page (max 100)
      int perPage = 30;
      # Page number of the results to fetch
      int page = 1;
      # Only show results that were last updated after the given time. This is a timestamp in [ISO 8601](https://en.wikipedia.org/wiki/ISO_8601) format: YYYY-MM-DDTHH:MM:SSZ
      string since?;
  };

  # Base Gist
  public type BaseGist record {
...
```

A path with several accessors lists instead, and `--accessor` opens one of them:

```
$ bal discover resource gists/:gistId/star -p ballerinax/github
ballerinax/github:6.1.0 · resource · Client · gists/:gistId/star
1 resource path

  gists/:gistId/star  get, put, delete

Next: bal discover resource <path> -p ballerinax/github --client Client --accessor <accessor>

$ bal discover resource gists/:gistId/star -p ballerinax/github --accessor put
ballerinax/github:6.1.0 · resource · Client · gists/:gistId/star · put

# Star a gist
# + headers - Headers to be sent with the request
# + return - Response
isolated resource function put gists/[string gistId]/star(map<string|string[]> headers = {}) returns error?;

Create it
  github:Client githubClient = check new (config);
  init(github:ConnectionConfig config, string serviceUrl = "https://api.github.com")
  bal discover method init -p ballerinax/github --client Client
```

`--accessor` is strict: it names one method of one path, so it needs a path (`resource --accessor get` with no path is a usage error), and an accessor the path does not declare fails as `symbol-not-found` with the path's accessors as `candidates`. It never filters a listing; to see one accessor's methods across a group, `--filter get` narrows the listing (see Filtering Large Result Sets, below). On any other noun it is a usage error, "--accessor applies only to resource", whose `command` drops it.

**The path is matched exactly as listed, with no dot-splitting.** Dots are real inside resource paths, so a dot is never read as a separator: across Central (every package's latest version, surveyed 2026-11-07) there are 184 resource methods on dotted paths, 174 of them `ballerinax/slack`'s (`reactions.get`, `chat.delete`, `users.profile.get`), plus `guilds/:guildId/widget.json` and `Users/.search`; 60 methods sit on the root path, printed `.`; and 77 on a rest parameter, printed `:...path`. `bal discover resource reactions.get -p ballerinax/slack` is slack's `reactions\.get` path, whose one accessor is `get`:

```
$ bal discover resource reactions.get -p ballerinax/slack
ballerinax/slack:5.0.1 · resource · Client · reactions.get

# Gets reactions for an item.
# + headers - Headers to be sent with the request
# + queries - Queries to be sent with the request
# + return - Typical success response
isolated resource function get reactions\.get(map<string|string[]> headers = {}, *ReactionsGetQueries queries) returns InlineResponseItems2001[]|error;
...
```

The alternative considered was one token in Ballerina's own call syntax, `<path>.<accessor>` (`gists/public.get`). It was rejected: reading it needs three parsing rules (split on the last dot, but not a dot inside `:...path`, and a lone `.` is the root path), and it gives tokens like `reactions.get.get`, which an agent cannot read at a glance.

This is what the `commands` entries in the `gists` listing above resolve to: `bal discover resource gists/public -p ballerinax/github --client Client --accessor get` is exactly the command `gists/public`'s `commands.get` carries; it opens the same signature as the command without `--accessor` above, and every row's commands name the accessor whether the path has one or several. The field is JSON only (not human text, which already has a person reading it who can construct the next command themselves): the value is removing an entire class of command-building mistakes, at a bounded cost — one extra string per shown entry, capped by the same ≤40-entry ceiling everything else in this design is already bounded by. A single flat `command` on every entry regardless of accessor count was considered and rejected: a multi-accessor resource (`gists/:gistId/star` has three) has no single unambiguous "next call" to offer without arbitrarily picking one accessor over the others, which risks quietly steering an agent toward the wrong one. The `commands` object avoids this: every accessor gets its own exact command, none is preferred, and single- and multi-accessor entries share one shape.

`--filter` composes with any of these steps directly — `bal discover resource repos --filter runner -p ballerinax/github` narrows within `repos` to matching paths in one step (8 paths), without needing the intermediate `repos/:owner/:repo/actions` and `.../runners` drill-down at all. It matches accessors too, so `bal discover resource gists --filter get -p ballerinax/github` lists the 10 paths under `gists` with only their `get` methods.

Subdivision is capped at a hard maximum of **4 additional levels beyond the top-level segment** (three were used above), not left unbounded, so a group can never fragment into an unnavigable tree of one-entry leaves. A listing still over the ceiling at the cap stops grouping and pages instead, with the same `--page` every other listing uses (see Pagination, below), so the cap never leaves a path unreachable. This is checked against real data, not picked arbitrarily: across a survey of seven real connectors — including the two most resource-heavy surveyed, `ballerinax/github` and `ballerinax/jira` (2.0.2: 590 resource methods, one dominant top-level group, `api`, of 577 resource methods over 389 paths) — three additional levels was always enough to bring every group under the ceiling, and most connectors needed none at all. Four is one level of margin above everything observed, covering the one real edge case the survey surfaced: a connector whose paths all share a redundant, non-discriminating segment (`api/<version>/...`, in Jira's case) that consumes a full grouping level without narrowing anything.

### Remote Functions

Remote methods can exceed the output ceiling too, and by a wider margin than resource methods do — surveyed directly against real Central data (re-checked 2026-10-06): `ballerinax/twilio` 5.0.2's `Client` exposes 199 remote methods, `ballerinax/microsoft.outlook.mail` 3.1.0 48, `ballerinax/googleapis.sheets` 4.0.0 43, `ballerinax/salesforce` 8.7.2 46 (the connector `--filter report` is demonstrated against, below), `ballerinax/zoom` 1.7.2 32, `ballerinax/aws.s3` 4.0.1 19 — several already past the 40-entry ceiling on their own, with no drill-down step in between.

Resource methods have a narrowing mechanism because a path is inherently hierarchical — `repos/actions/runs` is structurally a subset of `repos/actions`, independent of what any particular connector chose to name things. A remote method's name has no equivalent structural hierarchy to subdivide by. It was tempting to invent one: across every connector surveyed above, remote method names consistently follow a verb-plus-noun convention (`createMessage`, `fetchMessage`, `deleteMessage`, `listMessage` in `twilio`; `createMailFolder`, `deleteMailFolder` in `microsoft.outlook.mail`; `addSheet`, `deleteRows` in `googleapis.sheets`), which could plausibly be split into groups the same way path segments are, grouping by the trailing noun. This was deliberately **not** adopted as a mechanism: unlike a path segment, the "noun" isn't a structural property of the language or the spec — it's a naming convention with no fixed verb vocabulary across connectors (`create`/`fetch`/`list`/`delete` in `twilio`, `add`/`copy`/`create` in `googleapis.sheets`, `describe`/`apexRestExecute` in `salesforce`), so reliably splitting an arbitrary method name into "verb" and "noun" would mean guessing at a boundary that isn't guaranteed to exist — the same category of unreliable, non-deterministic parsing this design already rejects for other data sources (Central's descriptive flags, `--help` prose).

Given that, this proposal adopts straightforward **pagination** as the mechanism for remote methods that exceed the ceiling — brought into this initial design rather than left as future work, since real connectors (`twilio` at 199) already need it on day one, not hypothetically. Methods are listed in a stable, deterministic order (alphabetical by name), paged by a `--page <n>` flag (1-indexed), with the same `shown`/`total` and next-step-command shape used everywhere else in this design (`ballerinax/twilio` 5.0.2; the lists are cut for space):

```
$ bal discover client -p ballerinax/twilio
ballerinax/twilio:5.0.2 · client · Client
199 remote methods

  createAccount
  createAddress
  createApplication
  createCall
  createCallFeedbackSummary
...
... 159 more (page 1 of 5)
Next: bal discover method <name> -p ballerinax/twilio --client Client
Next: bal discover client Client -p ballerinax/twilio --page 2
Next: bal discover client Client -p ballerinax/twilio --filter <keyword>

$ bal discover client -p ballerinax/twilio --page 2
ballerinax/twilio:5.0.2 · client · Client
199 remote methods

  deleteConferenceRecording
  deleteConnectApp
  deleteIncomingPhoneNumber
  deleteIncomingPhoneNumberAssignedAddOn
  deleteKey
...
... 119 more (page 2 of 5)
Next: bal discover method <name> -p ballerinax/twilio --client Client
Next: bal discover client Client -p ballerinax/twilio --page 3
Next: bal discover client Client -p ballerinax/twilio --filter <keyword>

$ bal discover client -p ballerinax/twilio | cat   # piped
{"qualified":"ballerinax/twilio","version":"5.0.2","container":"Client","methods":[{"name":"createAccount","command":"bal discover method createAccount -p ballerinax/twilio --client Client"},{"name":"createAddress","command":"bal discover method createAddress -p ballerinax/twilio --client Client"},"..."],"shown":40,"total":199,"page":1,"pages":5,"remaining":159,"next":"bal discover client Client -p ballerinax/twilio --page 2"}
```

`--filter` (see Filtering Large Result Sets, below) composes with `--page` the same way it composes with resource-path grouping — narrowing first with `--filter message` cuts `twilio`'s 199 down to a page-sized set directly, without needing pagination at all for most real queries. Pagination exists for the residual case where a caller genuinely wants the full unfiltered list.

### Pagination

`--page` is not specific to remote methods: every listing that exceeds the ceiling pages the same way — a roster of containers, a level of path groups, resource paths that cannot be grouped (selected by `--filter` or already at the grouping depth cap), a container whose members come in more than one call form (paged as one sequence: resource paths, then remote methods, then normal ones, with `counts` giving every form's size across all pages, so a form that only appears on a later page is never hidden), `type` listings, documentation-only `--filter` matches, and readme sections. Pages are 1-indexed; every page keeps the names, `--filter` and `--module`. In JSON, `next` is always exactly one runnable command — the one that turns the page — and is absent when nothing follows; placeholder commands (`<name>`, `<group>`) appear only in text `Next:` lines, never in JSON. A page past the end is a failure that names the last page (exit 1), and `--page 0`, or `--page` against an answer that never pages, is a usage error (exit 2):

```
$ bal discover client --page 9 -p ballerinax/twilio --output-format text
bal discover: error: page 9 is out of range: this listing has 199 entries on 5 pages
  Pass a page from 1 to 5.
  Try: bal discover client Client -p ballerinax/twilio --page 5
# exit 1
```

### Module Addressing

A dotted name after the org — `<org>/<name>.<segment>` — is genuinely ambiguous in Ballerina's package model:

- **A hierarchical package name.** `ballerinax/googleapis.gmail` is not a submodule of a `googleapis` package — no such package exists. `googleapis.gmail` is itself a complete, independently-published package name; Central lists it, versions it, and resolves it exactly like any other package.
- **A submodule of a shorter package.** `ballerina/graphql.dataloader` is not its own package — that exact name resolves to nothing on Central. It's the `dataloader` submodule published as part of the `ballerina/graphql` package. `ballerinax/candid.premier` is the same shape: a `premier` submodule of `ballerinax/candid`.

`bal discover` doesn't try to resolve that ambiguity from the string itself. The `<org>/<package>` coordinate is always resolved as one literal, complete package coordinate — dots and all, since `googleapis.gmail` really is the package's whole name — with no fallback interpretation attempted. Targeting a submodule instead is a separate, explicit step: a `--module <name>` flag, naming the submodule within the already-resolved package. This is the flag the Ballerina CLI conventions give every tool for "target a submodule instead of the default", reused here rather than inventing a second convention for the same concept. It has no short form: the Ballerina CLI conventions reserve `-m`, so `-m` is a usage error whose fix is the same command spelled `--module`, and a value that is not a valid module name is a usage error before anything is fetched.

```
$ bal discover ballerinax/googleapis.gmail
ballerinax/googleapis.gmail:4.2.1

Clients (1)
  Client  32 resource methods
    users/:userId/drafts                               get, post
    users/:userId/drafts/:id                           get, put, delete
...
Types   bal discover type -p ballerinax/googleapis.gmail    31 records, 3 errors, 2 aliases, 2 constants
Readme  bal discover readme -p ballerinax/googleapis.gmail  18 sections

Next: bal discover resource <path> -p ballerinax/googleapis.gmail --client Client --accessor <accessor>
Next: bal discover search <query> -p ballerinax/googleapis.gmail

$ bal discover ballerina/graphql
ballerina/graphql:1.19.1

Clients (2)
  Client             6 remote methods
  PingMessageCaller  1 remote method
Service types (2)
  Service
  Interceptor  1 remote method
Classes (2)
  Context  8 methods
  Field    7 methods
Functions (2)
  __addError
  getSdlString
Types   bal discover type -p ballerina/graphql    52 records, 1 enum, 10 errors, 7 aliases, 5 constants, 3 variables, 4 annotations, 1 listener
Readme  bal discover readme -p ballerina/graphql  24 sections

Submodules
  dataloader  bal discover package ballerina/graphql --module dataloader  This module provides a way to load data from a data source with batching and caching capabilities.
  subgraph    bal discover package ballerina/graphql --module subgraph    This module provides a way to create a subgraphs for a federated GraphQL service

Next: bal discover <noun> <name> -p ballerina/graphql
Next: bal discover search <query> -p ballerina/graphql

$ bal discover package ballerina/graphql --module dataloader
ballerina/graphql:1.19.1 · module dataloader

Classes (2)
  DataLoader         4 methods
  DefaultDataLoader  4 methods
Types  bal discover type -p ballerina/graphql --module dataloader  1 alias

Next: bal discover class <name> -p ballerina/graphql --module dataloader
Next: bal discover search <query> -p ballerina/graphql --module dataloader

$ bal discover class -p ballerina/graphql --module dataloader
ballerina/graphql:1.19.1 · module dataloader · class
2 classes

  DataLoader         4 methods
  DefaultDataLoader  4 methods

Next: bal discover class <name> -p ballerina/graphql --module dataloader

$ bal discover ballerina/graphql.dataloader --output-format text
bal discover: error: package not found on Central: ballerina/graphql.dataloader
  'ballerina/graphql.dataloader' is not a package: it is the 'dataloader' module of the ballerina/graphql package. Read it with --module.
  Try: bal discover package ballerina/graphql --module dataloader
# exit 1
```

`ballerinax/googleapis.gmail` has no top-level functions — its default module is a `Client` plus supporting records — so asking it for `function` is the empty-noun answer shown earlier rather than a failure; a package's actual noun list depends entirely on what it publishes and isn't safe to guess by analogy with other packages.

`bal discover <org>/<package>` with no further drill-down always shows the default module's own overview, exactly as a single-module package would — most packages publish only one module, so this is the common case and needs no special handling. When a package publishes submodules, they're listed separately under **Submodules**, each with its bare name (exactly what `--module` takes), a one-line summary, and — the same "always the actual ready-to-run command, never just a description of one" treatment used for groups in Resource Functions, above — a `command` field in JSON pointing straight at drilling into that submodule. The default module's own sections are already shown right above the submodule list, so there's no redundant `(default)` entry alongside them. Under `--module <name>`, the list names only that submodule's own children (`<name>.<child>`), never its siblings, which belong to the package's listing. This costs nothing extra to compute: Central's docs response for a package carries the full API surface of its default module only, but names every module the package publishes — id and one-line summary — in that module's `relatedModules`, so the submodule list comes from the same lookup the package overview already makes, not a second round trip. A submodule's own API surface is not in that response (verified against `ballerina/graphql` 1.18.2), so `--module <name>` reads the submodule's own docs page instead — `graphql.dataloader` at the package's resolved version — accepted only if Central marks it a non-default module of that package, never a guess at the package boundary.

A submodule's own overview isn't pre-fetched or inferred from the parent's, since submodules can differ completely from the default module and from each other: `graphql.dataloader` has no client, service, or top-level function at all — only two classes, `DataLoader` and `DefaultDataLoader`. A package coordinate that doesn't resolve at all fails fast as "package not found," the same as any other bad precondition; for one that's still unclear even after the submodule listing, `bal discover search` (see Searching, below) or `bal search` is the way to look Central up directly.

`--module` applies uniformly across every noun, including `type` and `readme` — `bal discover class -p ballerina/graphql --module dataloader` and `bal discover readme -p ballerina/graphql --module dataloader` both target the `dataloader` submodule the same way. There is no separate mechanism on some nouns and not others; one flag applies consistently everywhere.

### The `readme` Noun

`bal discover readme -p <org>/<package>` returns that module's README, verbatim — the same content Ballerina Central already serves as the module's `description`, byte-identical to the package's own `docs/README.md`. Like every other noun, it defaults to the package's default module; `--module <name>` retrieves a specific submodule's README instead, using the same `--module` flag described above.

Unlike client/service/class/function, `readme` isn't derived from call-site grammar — it's a documentation artifact, not a piece of the callable surface. It gets its own noun rather than a flag on the others because it answers a different kind of question ("what does this module's documentation say") than the callable nouns ("what can I call, and how").

A bare `readme` of up to 10 KB is printed whole, verbatim (in text, under the one header line every answer has) — 83% of the readmes surveyed (88 modules across the 68 most-pulled packages, 2026-10-08) are. A larger one prints its list of sections instead — number, title, line count and the command that opens it, every section and never cut off at 40 — since the largest run to 20–33 KB (`ballerinax/oracledb`, `postgresql`, `mysql`, `mssql`, `ballerina/graphql`); there is no flag to force the whole text, the same as no listing has one. Every heading-delimited section is a section, prose-only ones included, so nothing in a large readme becomes unreachable. `readme <n>` or `readme "<title>"` prints one section, and `readme --filter <keyword>` lists the sections whose text contains the keyword, each with the command that opens it, paged like any listing (`ballerinax/kafka`):

```
$ bal discover readme --filter producer -p ballerinax/kafka
ballerinax/kafka:4.6.7 · readme · --filter producer
4 matching sections

  3  Consumer and producer   1 line
  4  Kafka producer         18 lines
  7  Data serialization     26 lines
  9  Examples                9 lines

Next: bal discover readme <n> -p ballerinax/kafka

$ bal discover readme --filter producer -p ballerinax/kafka | cat
{"qualified":"ballerinax/kafka","version":"4.6.7","sections":[{"number":3,"title":"Consumer and producer","lines":1,"command":"bal discover readme 3 -p ballerinax/kafka"},{"number":4,"title":"Kafka producer","lines":18,"command":"bal discover readme 4 -p ballerinax/kafka"},{"number":7,"title":"Data serialization","lines":26,"command":"bal discover readme 7 -p ballerinax/kafka"},"..."],"matching":true,"shown":4,"total":4}
```

In JSON, the readme or one section is `{"readme": <markdown>, "lines": <n>}`, plus `section`, `of` and `title` for a section. A module that publishes no readme at all (`ballerina/graphql`'s `dataloader`, for one) is a `symbol-not-found` failure, not an empty answer.

### The `type` Noun

`bal discover type -p <org>/<package>` lists every type a package publishes: records, enums, error types, type aliases (unions, intersections, function types, and the like), constants, module-level variables, annotations, listeners, and the classes, clients, and service types the callable nouns also list — in Ballerina they are all types, so this noun names them all. A listener is opened nowhere else: `type <Listener>` prints its declaration, `init` and the rest of its methods included, with the types it uses. Without this noun, a type is only visible as one of the types a method's signature happens to name, so a record that no shown signature reaches, an enum member a field's type names (`http:HTTP_1_1`), or an annotation a service author needs (`@http:ServiceConfig`) has no address at all.

The listing is grouped into sections by kind, alphabetical within each, and held to the same ceiling as every other listing — real packages need it (as of 2026-10-06): `ballerinax/github` 6.1.0 publishes 1,228 types (1,102 of them records), `ballerinax/slack` 5.0.1 506, `ballerina/http` 2.17.3 449 (144 of them constants). Grouping by kind can't bring a 1,102-record section under 40 on its own, so the narrowing mechanism is the same one remote methods use — `--page` and `--filter` — with each section's total carried across every page (`ballerinax/kafka`; the first sections are shown):

```
$ bal discover type -p ballerinax/kafka
ballerinax/kafka:4.6.7 · type
14 records, 3 enums, 3 errors, 8 aliases, 29 constants, 1 annotation, 1 listener, 4 classes, 3 clients, 1 service type

Records
  AnydataConsumerRecord
  AnydataProducerRecord
  AuthenticationConfiguration
  BytesConsumerRecord
  BytesProducerRecord
  CertKey
  ConsumerConfiguration
  KafkaPayload
  OffsetAndTimestamp
  PartitionOffset
  ProducerConfiguration
  RecordMetadata
  SecureSocket
  TopicPartition

Enums
  DeserializerType
  Protocol
  SerializerType

...
  DEFAULT_URL

... 27 more (page 1 of 2)
Next: bal discover type <name> -p ballerinax/kafka
Next: bal discover type -p ballerinax/kafka --page 2
Next: bal discover type -p ballerinax/kafka --filter <keyword>

$ bal discover type -p ballerinax/kafka | cat   # piped
{"qualified":"ballerinax/kafka","version":"4.6.7","sections":{"records":[{"name":"AnydataConsumerRecord","command":"bal discover type AnydataConsumerRecord -p ballerinax/kafka"},{"name":"AnydataProducerRecord","command":"bal discover type AnydataProducerRecord -p ballerinax/kafka"},"..."],"enums":[{"name":"DeserializerType","command":"bal discover type DeserializerType -p ballerinax/kafka"},{"name":"Protocol","command":"bal discover type Protocol -p ballerinax/kafka"},"..."],"errors":[{"name":"Error","command":"bal discover type Error -p ballerinax/kafka"},{"name":"PayloadBindingError","command":"bal discover type PayloadBindingError -p ballerinax/kafka"},"..."],"aliases":[{"name":"AuthenticationMechanism","command":"bal discover type AuthenticationMechanism -p ballerinax/kafka"},{"name":"CompressionType","command":"bal discover type CompressionType -p ballerinax/kafka"},"..."],"constants":[{"name":"ACKS_ALL","command":"bal discover type ACKS_ALL -p ballerinax/kafka"},{"name":"ACKS_NONE","command":"bal discover type ACKS_NONE -p ballerinax/kafka"},"..."]},"counts":{"records":14,"enums":3,"errors":3,"aliases":8,"constants":29,"annotations":1,"listeners":1,"classes":4,"clients":3,"serviceTypes":1},"shown":40,"total":67,"page":1,"pages":2,"remaining":27,"next":"bal discover type -p ballerinax/kafka --page 2"}
```

`bal discover type <Name> -p <org>/<package>` answers with that one type's declaration, verbatim, plus the declarations of the types it uses one level deep, within the same 6,000 bytes limit a signature's return type is held to, with a `*T` inclusion not counting against that depth (otherwise `http:ClientConfiguration`, which is little more than `*CommonClientConfiguration`, would show none of its real fields). The declaration itself is never cut. Every type it uses but doesn't print is named, all of them, each with its own `type <Name>` command, and so is every type it uses from another package (`ballerinax/github`, built against `ballerina/http`):

```
$ bal discover type ConnectionConfig -p ballerinax/github | cat
{"qualified":"ballerinax/github","version":"6.1.0","name":"ConnectionConfig","kind":"record","declaration":"# Provides a set of configurations for controlling the behaviours when communicating with a remote H...","types":[{"name":"OAuth2RefreshTokenGrantConfig","declaration":"# OAuth2 Refresh Token Grant Configs\npublic type OAuth2RefreshTokenGrantConfig record {|\n    *http:O..."}],"foreign":[{"name":"BearerTokenConfig","module":"ballerina/http","version":"2.15.7","command":"bal discover type BearerTokenConfig -p ballerina/http:2.15.7"},"..."]}
```

Inside a declaration, a foreign type is always module-qualified (`http:BearerTokenConfig`, `http:HttpVersion`) — no comment is appended to say where it comes from. The answer's `foreign` list names each one (unqualified, as the module's own `type` noun does) together with its module, its version and the command that opens it.

A foreign type's command follows the rule in Module Addressing, above: it is printed only when the package that declares the type is known exactly — the type's module is the default module of a package, or a submodule that package lists — and otherwise the row names the module and version with no command, rather than printing a coordinate `discover` would have to guess the package boundary of. Its `version` is the one the signature was generated against, and the command carries it in the coordinate (`-p ballerina/http:2.15.7`), so following it reads the very declaration the signature named rather than whatever a project locks or Central's latest has since become. Every other command `discover` prints is unpinned — inside a project, following it resolves the same locked version again — unless the caller wrote a version in the coordinate, which then pins the whole drill-down, so a chain of next steps never silently moves to a different version (`ballerina/http:2.15.7`):

```
$ bal discover client Client -p ballerina/http:2.15.7
ballerina/http:2.15.7 · client · Client
7 resource methods, 15 remote methods, 4 methods

...

Next: bal discover resource <path> -p ballerina/http:2.15.7 --client Client --accessor <accessor>
Next: bal discover method <name> -p ballerina/http:2.15.7 --client Client
```

A few rules keep this noun composable with the others:

- **Any type, by name.** `type <Name>` opens whatever type the package declares under that name. A class, client, or service type gives the same answer its own noun gives (`type Response` answers as `class Response` does), so one thing never has two shapes of answer. An enum member is a type too — a constant, and so a singleton type — and `type SSL` shows the enum declaring it, with a note naming that enum (`ballerinax/kafka`):

```
$ bal discover type SSL -p ballerinax/kafka
ballerinax/kafka:4.6.7 · type · SSL

# Represents protocol options.
public enum Protocol {
    SSL,
    TLS,
    DTLS
}

Note: 'SSL' is a member of the enum Protocol.
```

  The callable nouns stay narrow: `client`, `class`, and `service` open only their own kind, so `client Response` fails (see Names and Selectors, below). Because `type <Name>` opens any type, a foreign type's command is always `type <Name>` in its own package, with no need to know which noun that package files it under.
- **Narrowing.** `--filter` narrows the listing exactly as it does elsewhere: name matches first, documentation-only matches after them (see Filtering Large Result Sets, below). It doesn't apply to a single named type, and combining the two fails as a usage error rather than being silently ignored. No `--kind` flag is added: `--filter` already covers it, and a type named `Error` would make a kind keyword ambiguous.
- **Misses.** A name that resolves to nothing fails as `symbol-not-found`, the same as an unknown client or class: it lists the module's types as `candidates` when there are 40 or fewer, and otherwise carries the `type` listing command, never a guessed near match (see Errors and Exit Codes, below).
- **Followable everywhere.** The `omitted` and `foreign` lists a method's signature carries use the same `{name, command}` rows as a type leaf, never cut to a count, so every type any answer mentions is reachable in one command.

### Filtering Large Result Sets

Some Ballerina connectors expose very large surfaces — a single client can have well over a hundred remote methods. Returning all of them on every drill-down query would work against the whole point of a low-token discovery path. `bal discover` supports a `--filter <keyword>` flag that narrows results within an already-selected listing (`ballerinax/salesforce`):

```
$ bal discover client --filter report -p ballerinax/salesforce
ballerinax/salesforce:8.7.3 · client · Client · --filter report
6 remote methods

  deleteReport
  getReportInstanceResult
  listAsyncRunsOfReport
  listReports
  runReportAsync
  runReportSync

Next: bal discover method <name> -p ballerinax/salesforce --client Client
```

`--filter` only narrows the results within whatever the command's noun and names already selected — it never changes what kind of thing is being addressed, the same way `grep` narrows a listing without changing what's being listed. The rule is deliberately plain, with no query syntax: one keyword, matched case-insensitively as a substring of an entry's name, resource path or accessor, parameter names or types, so `--filter get` on a resource listing is how to see one accessor across many paths (`--accessor` opens one path's method and never filters). Entries whose only match is in their documentation are not dropped but listed after the others, in their own section headed *Mentioned only in their documentation* (`documented` in JSON, with `documentedTotal`), held to the same ceiling and paged with the same `--page`. On a roster, a container whose own name matches is kept whole, and one with a matching member is kept narrowed. A keyword that matches nothing is still an answer (exit 0) — `--filter` is a search the caller asked for, not a lookup by name, and an empty result is its answer. `--filter` with a single named declaration (`type <Name> --filter ...`) is a usage error rather than being silently ignored. On `package`, `--filter <keyword>` runs the in-package `search` (see Searching, below).

Filtering happens client-side, inside `discover` itself, after the package's full data has already been retrieved — not as a query parameter sent to Central or any other repository provider. This does mean the full set is fetched even when only a filtered subset is shown, but that cost is already paid once and absorbed by `discover`'s own disk cache (see Caching, below), not repeated per query. The alternative — pushing `--filter` down as a server-side query — was not pursued: it isn't available today (none of the repository-provider APIs behind `discover`'s interface support this kind of keyword-within-a-package filtering), and the Central-wide lookup is the separate `search` noun (see Searching, below).

### Searching

`search` is the one noun that is not about a package already chosen. It has two forms:

- **In one package, locally.** `bal discover search "<query>" -p <org>/<package>` finds names inside one package, with nothing sent to Central. Each keyword of the query is matched as a case-insensitive substring, and a name matches when every keyword does. The names are a container's, a listener's, a function's or a type's own name, a method's own name (not its container's), a resource method's path and accessor, a readme section's title and a submodule's name. It reads names only, never parameters or documentation (that is `--filter`, on a listing). Matches are grouped by kind and paged as one sequence under the 40-entry ceiling. No match is an answer (exit 0) pointing at the package's overview.
- **Across Central.** `bal discover search "<query>"` asks Central: its package search (what `bal search` asks) and its symbol search across every package, 10 packages and 25 symbols a page, each row with the `bal discover` command that opens it, pinned to the version Central matched. Symbols must match every keyword; packages are ranked on the keywords as typed. The ranking is Central's, so the same keywords can rank differently as Central changes. It is not cached.

The query is the noun's one positional: a single keyword needs no quotes (`search send`), and several are quoted as one (`search "kafka send"`). Typing them unquoted, `search kafka send`, is a usage error (exit 2) whose `Try:` line is the quoted query.

```
$ bal discover search send -p ballerinax/kafka
ballerinax/kafka:4.6.8 · search · send
2 remote methods

Remote methods
  Producer  send
  Producer  sendWithMetadata

Next: bal discover method <name> -p ballerinax/kafka --client <container>

$ bal discover search "kafka send"
search · kafka send
8 packages, 33 symbols

Packages (8)
  package ballerinax/kafka:4.6.8            Apache Kafka is a distributed event streaming platform used for high-performance data pipelines, streaming analytics, data integration, and mission-critical applications.
  package wso2/choreo.sendemail:0.6.1       Demo connector to send an email.
  package wso2/choreo.sendsms:0.6.1         Demo connector to send an SMS.
  package ballerinax/sendgrid:1.5.2         Connects to Sendgrid API from Ballerina.
  package ballerinax/isendpro:1.5.2         Connects to iSendPro from Ballerina.
  package wso2/choreo.sendwhatsapp:0.3.0    Demo connector to send a WhatsApp message.
  package thambaru/debezium_to_kafka:0.2.1  Ballerina library — `thambaru/debezium_to_kafka` v0.2.0
  package kanishka/sendgrid:0.1.4           Connects to Sendgrid API from Ballerina.

Symbols (25 of 33)
  remote method  method send -p ballerinax/kafka:4.6.8 --client Producer
  remote method  method sendWithMetadata -p ballerinax/kafka:4.6.8 --client Producer
  error          type KafkaPublishError -p thambaru/debezium_to_kafka:0.2.1
  remote method  method getMsgVpnKafkaSenders -p xlibb/solace.semp:0.2.0 --client Client
  remote method  method createMsgVpnKafkaSender -p xlibb/solace.semp:0.2.0 --client Client
  remote method  method getMsgVpnKafkaSender -p xlibb/solace.semp:0.2.0 --client Client
  remote method  method replaceMsgVpnKafkaSender -p xlibb/solace.semp:0.2.0 --client Client
  remote method  method deleteMsgVpnKafkaSender -p xlibb/solace.semp:0.2.0 --client Client
...
```

### Names and Selectors

The noun fixes what a name means: after `client`, a name is a client; after `method`, a method; after `resource`, a path, whose accessor is `--accessor`. There is no precedence between "a member or a path or a container", and no selector counting, because the noun already says which. The rules are part of the contract:

- **A miss fails** (exit 1, `symbol-not-found`): a container, member, path or declaration that doesn't exist exactly as typed. When the level the lookup reached holds 40 names or fewer, the failure lists all of them as `candidates`, sorted as the listing sorts them; past that it carries the command that lists that level. Either way it is the full set at that level, never a guessed near match.
- **A name asked of the wrong noun fails** the same way, its suggestion the command in the noun that holds it (`client Response` → `bal discover class Response -p ballerina/http`).
- **A name several containers declare is ambiguous** (exit 2, `ambiguous`, a usage error: the command must name one): the failure lists each owner with its command, and JSON carries them as `owners`. Naming the container with `--client`, `--class` or `--service` resolves it; the flag is optional when exactly one container declares the name. A container flag naming a container of another kind fails with the command that has the right one.
- **Exact spelling.** Names are case-sensitive, as in Ballerina. A path parameter is typed `:name`, exactly as printed; an escaped segment is typed plain (`gists/public`) or in Ballerina's escaped spelling (`gists/'public`). A path is typed whole, from its first segment, as one argument joined by `/`, and a dot is part of a segment, never a separator (`reactions.get`; the root path is `.`, a rest parameter `:...path`): one that leaves out its parameter segments (`repos/actions`), names only a segment deep inside it (`caches`), or is split across two positionals (`repos/:owner/:repo issues`) fails.
- **`init` addresses the constructor** (`method init -p ballerinax/kafka --client Producer`).
- **More names than a noun takes are a usage error** (exit 2) naming the command that reads them, never silently dropped: `method Producer send` becomes `method send --client Producer`, `resource gists/public get` becomes `resource gists/public --accessor get` (when `get` is one of the path's accessors; otherwise it fails as a miss listing them), and `search kafka send` becomes `search "kafka send"`. When no one command reads them (`resource gists public get`), the error says how to write it and has no `command`.
- **`--accessor` belongs to `resource`.** It needs a path, and on another noun it is a usage error, "--accessor applies only to resource", whose `command` drops it.
- **A rest-parameter resource** such as `http:Client`'s `get [PathParamType ...path]` is a calling shorthand (`client->/users/[id]`), not a set of addressable paths: it lists as `:...path` with its accessors, and `bal discover method get -p ballerina/http --client Client` opens the remote method `get`.

Real output of these rules (`ballerinax/kafka`, `ballerina/http`):

```
$ bal discover method sendd -p ballerinax/kafka --client Producer --output-format text
bal discover: error: 'sendd' is not a method of Producer in ballerinax/kafka:4.6.8
  Name one of the methods of Producer below.
  candidates:
    close
    flush
    getTopicPartitions
    send
    sendWithMetadata
# exit 1

$ bal discover method commit -p ballerinax/kafka --output-format text
bal discover: error: 'commit' is declared by Caller and Consumer in ballerinax/kafka:4.6.8
  Name the one you mean with --client, --class or --service.
    Caller    bal discover method commit -p ballerinax/kafka --client Caller
    Consumer  bal discover method commit -p ballerinax/kafka --client Consumer
Usage: bal discover method [<name>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] [options]
Try 'bal discover method --help' for more information.
# exit 2

$ bal discover client Response -p ballerina/http --output-format text
bal discover: error: 'Response' is not a client in ballerina/http:2.18.2
  It is a class.
  Try: bal discover class Response -p ballerina/http
# exit 1
```

### Output Shapes

Every answer is one structured result with one JSON shape per kind of answer. The normative list of those shapes is the Output table in the implementation's README ([discover-tool README, "Output"](https://github.com/ballerina-platform/discover-tool#output)); this proposal summarizes it rather than keeping a second copy that can drift:

| Answer | Key JSON fields |
| --- | --- |
| package overview | `clients`, `serviceTypes`, `classes`, `functions` (each `entries`, `shown`, `total`, `command`; `clients` also `members` when there is exactly one client), `types` (`counts`, `command`), `readme` (`sections`, `command`), `submodules` |
| several containers (a roster) | `containers`, and in `service` `notAttachable` |
| resource groups | `container`, `resources` ending at this prefix, `groups` (`name`, `count`, `command`), `counts` |
| resource paths | `container`, `resources` (`path`, `accessors`, `commands`) |
| methods | `container`, `methods` (`name`, `command`), or `resources`/`remote`/`normal` plus `counts` when call forms mix |
| one callable | `declaration`, `params`, `returns`, `form`, `types`, `omitted`, `foreign`, `create`, `callers` |
| `type` listing / one declaration | `sections` + `counts` / `name`, `kind`, `declaration`, `types`, `omitted`, `foreign` |
| empty noun | `bucket`, `elsewhere` |
| one service type | `container`, `note`; when its package describes the methods a service defines, `handlers` and `rules` |
| readme / readme sections | `readme`, `lines` / `sections` (also a bare readme over 10 KB), with `matching` true when narrowed |
| search in a package / of Central | `keywords` plus the matched kinds, `counts` / `packages`, `symbols` |

Shared across shapes: every answer leads with `qualified` (`org/name`) and `version`, the package version it was read at (written, locked, or Central's latest), and the text header names it too (`ballerinax/kafka:4.6.7 · client · Producer`); every row that opens one thing carries `command`, and every resource row carries `commands` keyed by accessor; a listing carries `shown`/`total`, plus `page`/`pages`/`remaining`/`next` when paged, and `documented`/`documentedTotal` under `--filter`; `next` is exactly one runnable command, absent when nothing follows; `omitted` is never cut, so it has no overflow keys; any answer may carry `note` (listener attachment, a service type's use when not attachable, an enum member's enum). A warning is never part of an answer; it goes to stderr (see Errors and Exit Codes, below).

Text output follows fixed layout rules, since a caller that asks for `--output-format text` may parse it too: a header naming where the answer is (`org/package:version · noun · container · name`), a count line, one entry per line in aligned columns under section headings, then a footer — notes, a `... N more (page P of Q)` line when the listing is cut, and `Next:` lines. Where every row's command shares one shape, a single `Next:` line prints it with a placeholder (`<name>`, `<group>`, `<path>`, `<accessor>`, `<query>`).

### Errors and Exit Codes

The wider CLI initiative's behavioral contract is out of scope for this proposal (see Non-Goals), but `discover` implements its own part of it first, since an agent's next step depends on it, and follows the Ballerina CLI conventions for errors and exit codes. Stdout holds the answer and nothing else; on failure stdout is empty and stderr holds exactly one failure, whose `kind` and `suggestion` say what to do next. The exit code is one of a closed table: 0 for an answer, 2 for a usage error, 1 for every other failure (130 when interrupted). `discover` never exits 3 or 4: it writes nothing, so nothing is overwritten (4), and its target is a coordinate on Central, not local sources, so a package, version or module Central doesn't have is a runtime `package-not-found` (1), not an invalid target (3). Which failure it is, and what to do about it, is carried by the `kind`; each `kind` falls under one of the guide's core kinds, which decides the exit code:

| `kind` | core kind | exit | retry helps | means |
| --- | --- | --- | --- | --- |
| `usage` | `usage` | 2 | no | the command is malformed, or asks for something it cannot combine |
| `ambiguous` | `usage` | 2 | no | the name matches more than one thing, each listed with its command |
| `package-not-found` | `runtime` | 1 | no | Central publishes no such package, version or module |
| `symbol-not-found` | `runtime` | 1 | no | no name in the package matches what was asked |
| `page-not-found` | `runtime` | 1 | no | a page past the end of a paged listing |
| `filter-no-match` | `runtime` | 1 | no | a readme section asked with a `--filter` keyword it does not mention |
| `invalid-lock` | `runtime` | 1 | no | the project's `Dependencies.toml` locks the package at something that is not a complete version |
| `unavailable` | `runtime` | 1 | yes | no answer came back (network, DNS, TLS, the proxy), or Central or the proxy was throttling or overloaded (429, 500, 502, 503, 504; the proxy's 502, 503, 504) |
| `timeout` | `runtime` | 1 | yes | Central did not answer in time |
| `upstream` | `runtime` | 1 | no | Central or the proxy refused the request (any other error status) or answered with a body that is not JSON |
| `schema-drift` | `runtime` | 1 | no | Central answered with a shape this tool does not read |
| `internal` | `runtime` | 1 | no | a defect in `bal discover` |

"Retry helps" means the same command may succeed run again later, unchanged; the `kind` alone says so, so an agent never needs the status codes behind it.

A failure is rendered in the run's own output mode, the same one the answer would have used: JSON when stdout is not a terminal — including `bal discover ... | jq` typed at one — and text when it is, either overridden by `--output-format`. One mode per run, chosen once. In JSON it is one object on one line carrying `"severity":"error"`, `kind`, `message` and `suggestion`; in text, `bal discover: error: <message>`, the suggestion indented beneath it, and `Try: <command>` beneath that when one command fixes it. A `message` says what went wrong in one line, lowercase-first, with no closing period and no class name. A usage error — an unknown option (`-m` among them), an option with a missing or invalid value (`--refresh=true`, `--module 'bad!name'`), no noun, a malformed package name or incomplete version, `--version` after a noun, an unknown noun, more names than a noun takes (the keywords of `search` unquoted among them), more than one container flag, `--filter` with a named type, an `--accessor` with no path or on a noun other than `resource`, the old package-first form — is found before anything is fetched, and in text it is followed by a one-line usage synopsis and `Try 'bal discover <noun> --help' for more information.`; the JSON object is unchanged. Some usage errors can only be found once the package is read (more names than a `method` or `resource` takes, read against it — the old `resource <path> <accessor>` form among them — or a name several containers declare); they are still exit 2, name the command to run or the choices, and in text end with that noun's synopsis. `qualified` in a JSON failure is always `org/name`, with the version, when one is known, in its own `version` key, and the module under `--module` in `module`. When one fix applies, JSON carries the caller's whole command with it applied as `command`, and text prints it as the `Try:` line. A miss lists the names at the level it reached as `candidates` when there are 40 or fewer, and otherwise carries that level's listing as `command` (see Names and Selectors, above). A `--module` the package does not publish is `package-not-found` with `module`, listing the submodules it does publish as `candidates`.

A miss is a failure, never an exit-0 answer: an agent that misreads a near miss as an answer reasons from the wrong thing, and an exit code is the one signal every caller checks. `unavailable` and `timeout` are worth re-running unchanged later, and only they say so; a network failure's message names the host, or the proxy and the `Settings.toml` that sets it, and its suggestion points at the connection and the `[proxy]` settings in `~/.ballerina/Settings.toml`, which `discover` reads as `bal pull` does. `schema-drift` means Central's payload changed shape and is for a maintainer.

A warning never changes the exit code and is never part of the answer: it is its own report on stderr, `{"severity":"warning","kind":"stale-cache","message":…}` in JSON and `bal discover: warning: <message>` in text. The one warning is `stale-cache`: Central could not be reached to confirm the version, so the cached copy was read.

The old package-first form is a usage error, and its `Try:` line is the same request in the new order:

```
$ bal discover ballerinax/kafka client Producer --output-format text
bal discover: error: 'ballerinax/kafka' is not a noun; name the package with -p
  Name the noun first and the package with -p.
  Try: bal discover client Producer -p ballerinax/kafka
Usage: bal discover client [<name>] -p <org>/<name>[:<version>] [options]
Try 'bal discover client --help' for more information.
# exit 2

$ bal discover ballerina/graphql.dataloader | cat
{"severity":"error","kind":"package-not-found","message":"package not found on Central: ballerina/graphql.dataloader","qualified":"ballerina/graphql.dataloader","suggestion":"'ballerina/graphql.dataloader' is not a package: it is the 'dataloader' module of the ballerina/graphql package. Read it with --module.","command":"bal discover package ballerina/graphql --module dataloader"}
# exit 1
```

The two forms the one-positional rule retired are usage errors the same way, each `Try:` line the form that replaced it, and so is `--accessor` on a noun that has no accessor:

```
$ bal discover resource gists/public get -p ballerinax/github --output-format text
bal discover: error: resource takes one path, and its accessor as --accessor; got 2
  Put the accessor in --accessor.
  Try: bal discover resource gists/public -p ballerinax/github --client Client --accessor get
Usage: bal discover resource [<path>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] [--accessor <accessor>] [options]
Try 'bal discover resource --help' for more information.
# exit 2

$ bal discover search kafka send --output-format text
bal discover: error: search takes one query, in quotes; got 2
  Quote the keywords as one query.
  Try: bal discover search "kafka send"
Usage: bal discover search <query> [-p <org>/<name>[:<version>]] [options]
Try 'bal discover search --help' for more information.
# exit 2

$ bal discover method send -p ballerinax/kafka --accessor get --output-format text
bal discover: error: --accessor applies only to resource
  Drop --accessor.
  Try: bal discover method send -p ballerinax/kafka
Usage: bal discover method [<name>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] [options]
Try 'bal discover method --help' for more information.
# exit 2
```

### Multi-Source Repository Interface

`bal discover` resolves packages through a single repository-provider interface, so that nothing in `discover`'s core logic special-cases any one repository — the same way a local Maven `~/.m2` cache sits alongside Maven Central without special handling. The interface exists in the 0.1.0 design and code; **Ballerina Central is its only implementation in 0.1.0.** Further providers — a local "Local Central" cache, Artifactory, and other private repositories — are planned for a later release, each as one more fetcher behind the same interface rather than a bespoke code path inside `discover` itself.

Selecting a repository and authenticating to it through the existing `~/.ballerina/Settings.toml` file — the same file `bal pull` and the rest of the toolchain already read for exactly this purpose (Central access tokens, custom repository definitions) — is likewise planned for that later release, and does not work in 0.1.0 (only its `[proxy]` table is read today). The intent is that `discover` won't parse or resolve this itself: package resolution would reuse `bal pull`'s existing repository-resolution machinery directly, so a repository already configured and working for `bal pull` would work for `bal discover` with no separate setup.

### Determinism: No AI/LLM Inside the Tool

Every response `bal discover` produces is built from structured data — package and module introspection, provider metadata, a library's own documentation — and never from a model call inside `bal` itself. The same input always produces the same output. This is a hard constraint, not an optimization: agents need a source of truth they can call repeatedly and cache safely, and introducing a second, non-deterministic model into that path would undermine the entire premise of the tool.

One input is network-dependent beyond Central's docs API: settling a service type's listener binding can need the package's own source (see Nouns and Roles, above), read from the local Ballerina home repository, then the distribution's `repo/bala`, then a bala download from Central. When none is reachable and nothing is cached, the same query answers with those pairings marked `"confirmed":false` rather than settled — the answer stays honest about what it couldn't check, but it can differ from an online run until the source is reachable once.

### Caching

`bal discover` uses a disk-based cache carried over from `bal-library-tool`, rather than a new cache design: cache files live in an XDG-compliant location, writes are atomic (written to a temp file, then moved into place, so a crash never leaves a corrupt cache entry), and any cache miss or cache-layer failure falls back silently to a live fetch — never a hard error. Five things are cached, keyed by package coordinates and version: the raw docs payload Central serves for a package and for a submodule, the latest version resolved for a package (for 10 minutes), the small answer derived from a package's source about its service types' inclusions, and the package's `metadata/trigger-metadata.json` (never the bala itself). `--refresh` ignores all of them and fetches again, and only a fetch that succeeds replaces the cached copy — worth passing only when a name should exist and doesn't. Central-wide `search` is not cached. The location is `$XDG_CACHE_HOME/bal-discover` (`~/.cache/bal-discover` by default), overridable or disabled through environment variables documented in the README.

### Relationship to `bal search`

`bal search <keyword>` already exists in the Ballerina CLI as a keyword-based lookup against Ballerina Central, and this proposal leaves it unchanged — it is not re-implemented, extended, or absorbed.

Agents need the first step of the flow ("which package, which method") inside the same tool, and a Central lookup costs one command instead of a context switch to another one, so the design includes a `search` noun (see Searching, above). It asks Central for the same package search `bal search` asks and adds Central's symbol search, and it never ranks or recommends beyond what Central returns. The two commands still compose into a two-step workflow: `bal search <keyword>` or `bal discover search "<query>"` answers "which library should I use", typically informed by domain knowledge the agent already has; `bal discover package <org>/<package>` then answers "how do I use this library". Whether `bal search` is later deprecated in favour of `bal discover search` is a separate decision, not made by this proposal.

## Alternatives Considered

**Grammar A: the package-first drill-down.** The first draft used `bal discover <org>/<package> [bucket] [name]` (`bal discover ballerinax/kafka client Producer send`). Both grammars were built on identical answers, so only the command shape, the printed next commands and the help differ. Package-first has real strengths: shorter printed commands and one fewer way to fail (no missing `-p`). Noun-first was chosen for its structure: each word has one role (noun, name, flag), so it needs no selector precedence rules, and every noun has its own help. Its one weakness, agents typing the package first and getting a usage error, is removed by accepting the bare package coordinate, which is the form A taught.

**MCP as the interface.** Exposing `bal discover`'s functionality through the Model Context Protocol, either instead of or alongside the CLI, was considered and set aside for now. `bal discover` remains a CLI subcommand serving both human developers and the Integration Intelligence agent, with each consumer getting a different rendering — human-readable text or structured JSON — from one underlying source of truth. MCP remains a plausible future addition (see Future Work) once `bal discover`'s own contract has proven out, rather than a second surface maintained in parallel from the start.

**A new or enhanced `bal search` command.** Considered and rejected: the existing `bal search <keyword>` command is left as-is. `discover search` (see Searching, above) is deliberately a lookup — names in a package, or Central's own package and symbol search — and never ranks or recommends beyond what Central returns.

**Natural-language-driven search.** Accepting free-text intent directly (e.g., `bal search <natural-language query>`) instead of an exact keyword or package name was considered and dropped. Matching free text to a package inherently involves ranking and interpretation — exactly the kind of non-deterministic step this proposal exists to eliminate. An agent is expected to arrive at a keyword or package name using its own domain knowledge, not a fuzzy-matching layer inside the CLI.

**A two-bucket role model.** An earlier design, based on existing prior-art tooling, split results into a client bucket and a catch-all bucket that folded service types in alongside plain classes. This was rejected in favor of the four-noun callable model — client, service, class, function (plus the separate, non-callable `type` and `readme` nouns) — that gives the service-and-listener pairing its own noun. A service cannot run without attaching to a listener, and a listener with no service attached does not do anything, so folding that pairing into a large generic bucket buries exactly what agents need to find fastest. Concretely, `ballerina/http` has 83 plain classes against 1 listener and 7 service types, so under the two-bucket approach, 8 load-bearing entries would be lost inside a set of 91.

**Re-deriving `bal discover` from scratch.** Building an entirely new implementation, rather than extending existing prior art, was rejected. `bal-library-tool` already exists, with symbol-level API depth, a proven disk-cache design, and a validated stdout/stderr contract — parked for an unrelated release-process reason, not a design flaw. Reusing and extending it is faster and lower-risk than a ground-up rebuild, while still leaving room for deliberate design corrections, such as the four-noun change above, where real usage data justified deviating from it.

## Risks and Assumptions

**Documentation and specification consistency across libraries.** Ballerina's own GraphQL and HTTP library specifications already diverge in structure from one another, and libraries' documentation isn't structured consistently across the ecosystem more broadly. For the feature set this proposal actually implements, that divergence doesn't change `discover`'s output — everything `discover` reports comes from Central's own docs API (introspected package/module structure: clients, services, methods, parameters, return types), which is uniform across libraries regardless of how differently their specs read. The one exception is service↔listener pairing, which may read the package's own source (see Determinism, above); when that source can't be reached, pairings degrade to `"confirmed":false` rather than failing. Where this risk is real is API *documentation* quality — a library with thinner or inconsistent doc comments produces a thinner `discover` result for that library specifically — and it would become a real design dependency if `discover` ever extracted data directly from libraries' specs rather than from Central's introspected model, which is not part of this proposal's plan.

**Reliability of registry metadata.** Ballerina Central's package metadata includes flags intended to signal a symbol's role (`isClient`, `isService`, a `clients` array), but these cannot be trusted as-is — they are demonstrably incomplete or misleading for real packages. `discover` mitigates this by deriving role from call-site grammar through direct introspection of package artifacts rather than reading these flags. This is a sound mitigation, but it carries real implementation complexity and performance cost compared to a simple metadata lookup, and it depends on packages' compiled artifacts being introspectable in the first place.

**A young, hand-written service-methods format.** *Methods to implement* reads `metadata/trigger-metadata.json` (see Nouns and Roles, above), whose spec is still marked unreviewed and lives in a personal GitHub repository; about 40 published packages already carry `v1.0` and can't change, so `discover` reads `v1.x` leniently and refuses `v2`. The file is written by hand next to the compiler plugin that actually enforces the rules, so the two can drift; and versions published before about 2026-09-07 have no file at all, so `discover` lists no methods for those.

**The grammar is judged on how agents use it.** The noun-first grammar was chosen on its structure, and its effect on how coding agents use the tool has not been measured beyond that choice. Whether people prefer it to the package-first form has not been measured either.

**Continued access to Ballerina Central's current interfaces.** The design assumes ongoing access to Ballerina Central's existing public API surface for package documentation, symbol search and metadata. Central is the only repository `discover` reads in 0.1.0, so should that access mechanism change, the Central-backed implementation behind `discover`'s repository interface would need to be updated accordingly. Once other providers exist behind the same interface (planned for a later release), they would be unaffected by such a change, since they implement the interface independently.

## Dependencies

**Service-authoring metadata.** Listener binding is derived from each listener's `attach()` signature and the package's own source (see Nouns and Roles, above), not from the structured trigger-authoring metadata owned by a separate team. *Methods to implement* does depend on that metadata, as published in each package's `metadata/trigger-metadata.json`; without it, a service type with no declared methods and no such file lists none rather than inventing any.

**`bal pull`'s repository-resolution machinery and the existing Ballerina settings file.** In the later release that adds further providers, provider selection and authentication are planned to be handled by reusing this existing machinery rather than reimplementing resolution logic specifically for `discover`.

**`bal-library-tool`.** The implementation basis for `discover` — including its symbol-level introspection approach and disk-cache design — builds directly on this existing implementation rather than a clean-slate build.

**Docerina's potential evolution into a structural validator.** Not a hard prerequisite for this proposal — `discover`'s output already comes from a uniformly-structured source (Central's introspected docs model, not each library's own spec prose; see Risks and Assumptions, above) — but doc-*quality* still varies by library, and validating that consistently across the ecosystem would be a real, separate improvement. Building that validation capability into Docerina (`bal doc`) would matter more if `discover` later extracted data directly from specs, which isn't part of this proposal's plan; noted here as a low-priority, longer-term item rather than a blocking dependency.

## Future Work

Extensions that follow naturally from `bal discover`'s design but are out of scope for this proposal's initial delivery:

- **`bal discover` on NutCracker.** Once NutCracker has a subcommand/plugin mechanism — being designed separately, as part of the wider CLI improvement initiative — porting `bal discover` to it, with its contract unchanged, is natural follow-on work.
- **Tool discovery, not just library discovery.** `bal discover` currently covers library and connector discovery only. Extending it to also describe `bal`-integrated tools themselves — e.g., letting `bal discover` expose `bal openapi`'s own flags and usage as structured data instead of parsed `--help` text — is a natural extension, not yet designed.
- **Best-practices guidance.** Surfacing library-specific best-practices content through `discover` — for example, a `--best-practices` flag returning package- or method-level guidance — is a future task, not part of this proposal.
- **MCP exposure.** Once `bal discover`'s structured data model is proven out, exposing the same data over MCP as an additional renderer is a natural next surface — not required for agents to benefit today, since the CLI is already the shared entry point.
- **Measuring impact.** Measuring `bal discover`'s effect on token usage and one-shot correctness of generated code, using an agent-benchmarking harness, is the natural follow-up once the tool has shipped.

`bal search`'s own output-quality issues (e.g., column truncation) are real but separate, standalone work — not part of `bal discover` or this proposal. Broader ecosystem-wide work — a full subcommand behavioral contract, NutCracker's general plugin architecture, structured export of every tool's command model — belongs to the wider Ballerina CLI improvement initiative this proposal sits within, not to `bal discover` specifically.

## References

- `bal-library-tool` — the existing library-discovery tool this proposal builds `bal discover` on: [https://github.com/wso2/labs-agentic-engineer/tree/main/packages/bal-library-tool](https://github.com/wso2/labs-agentic-engineer/tree/main/packages/bal-library-tool)
- Ballerina Central — the primary package registry `bal discover` and `bal search` query for library and connector metadata: [https://central.ballerina.io](https://central.ballerina.io)
