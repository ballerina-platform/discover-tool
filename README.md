# Ballerina Discover Tool

`bal discover` reads a Ballerina package off **Ballerina Central** and answers what its API actually is:
the clients, services, classes and functions it declares, how each is called, and the exact signature of
any one of them. It exists so an AI agent (or a human) can learn a package's real API instead of guessing it
from a web search or memory. It is deterministic — no model runs inside it.

```bash
bal discover --help
```

## Usage

```
bal discover <org>/<package> [bucket] [selector ...] [flags]
```

The package comes first and every further positional drills one level down. With no bucket, the answer is
the list of buckets the package has.

| Bucket    | What it holds                                                                                            |
| --------- | -------------------------------------------------------------------------------------------------------- |
| `client`  | Objects reached with `->`: remote methods and resource functions (`client->path.accessor(...)`).         |
| `service` | Service object types, each paired with the listener it attaches to.                                      |
| `class`   | Plain objects reached with `.`.                                                                          |
| `funcs`   | Module-level functions.                                                                                  |
| `readme`  | The module's README, verbatim. `readme <n>` or `readme "<title>"` opens one code-carrying section.       |

The first four are derived from how a symbol is called, not from Central's `isClient`-style flags:
`ballerina/http` declares ten clients, two of which Central files as ordinary declarations.

Selectors after the bucket name a container, then a member: a method name, or a resource path followed by
its accessor (`client "gists/'public" get`). With one container in the bucket, the container name can be
left out.

| Flag                   | Meaning                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------ |
| `--output json\|text`  | Override the default: text when stdout is a terminal, JSON otherwise.                                  |
| `--filter <keyword>`   | Narrow the selected bucket to entries whose name, path, parameter or type contains the keyword. Applied client-side to the fetched payload, never sent to Central. |
| `--page <n>`           | Turn the page of a listing that pages over the entry ceiling: methods, resource paths that cannot be grouped, and readme sections narrowed by `--filter`. A page outside the listing is a `validation` failure. |
| `-m, --module <name>`  | Target a submodule instead of the default module, in every bucket including `readme`.                 |
| `--refresh`            | Ignore the cached payload and fetch it again.                                                          |

## Walkthrough

The output below is real, produced against the Central payloads recorded under
`native/src/test/resources/fixtures/`; live Central may have moved on since they were recorded.

What a package has:

```
$ bal discover ballerinax/kafka
client, service, class, readme

$ bal discover ballerinax/kafka | cat
{"buckets":[
"client",
"service",
"class",
"readme"
]}
```

A bucket with too many resource paths to list is grouped by path segment. Every listing about one container
names it first:

```
$ bal discover ballerinax/github client
Container: Client
Groups: repos (421), orgs (200), user (93), teams (34), users (34), gists (19), projects (19), app (13), repositories (11), notifications (7), search (7), marketplace_listing (6), applications (5), assignments (3), classrooms (3), advisories (2), codes_of_conduct (2), enterprises (2), gitignore (2), installation (2), licenses (2), markdown (2), . (1), app-manifests (1), apps (1), emojis (1), events (1), feeds (1), issues (1), meta (1), networks (1), octocat (1), organizations (1), rate_limit (1), versions (1), zen (1)
```

A group small enough to list shows one entry per path, with every accessor it answers to:

```
$ bal discover ballerinax/github client gists
Container: Client
gists — get, post
gists/:gistId — get, delete, patch
gists/:gistId/comments — get, post
gists/:gistId/comments/:commentId — get, delete, patch
gists/:gistId/star — get, put, delete
gists/:gistId/forks — get, post
gists/:gistId/:sha — get
gists/:gistId/commits — get
gists/'public — get
gists/starred — get
```

Operations that end exactly at a grouped prefix are listed beside its groups rather than as a group of their
own, and a group deeper than the top is named by its full path:

```
$ bal discover ballerinax/github client repos
Container: Client
Here:
repos/:owner/:repo — get, delete, patch
Groups: repos/:owner/:repo/actions (72), repos/:owner/:repo/branches (36), repos/:owner/:repo/pulls (31), ...
... 25 more, narrow further: bal discover ballerinax/github client Client repos --filter <keyword>
```

In JSON, an entry with exactly one accessor carries a `call` field: the ready-to-run command that opens its
signature. A path with several accessors carries none, since it would have to guess which one:

```
$ bal discover ballerinax/github client gists --filter star | cat
{"container":"Client","resources":[
{"path":"gists/:gistId/star","accessors":[
"get",
"put",
"delete"
]},
{"path":"gists/starred","accessors":[
"get"
],"call":"bal discover ballerinax/github client Client gists/starred get"}
],"shown":2,"total":2}
```

One callable is the end of a drill-down: its declaration with its doc comment, then the declarations its
signature names, one level deep:

```
$ bal discover ballerinax/kafka client Producer send
# Produces records to the Kafka server.
# ```ballerina
# kafka:Error? result = producer->send({value: "Hello World".toBytes(), topic: "kafka-topic"});
# ```
# + producerRecord - Record to be produced
# + return - A `kafka:Error` if send action fails to send data or else '()'
isolated remote function send(AnydataProducerRecord producerRecord) returns Error?;

Types it names (2):

# Details related to the anydata producer record.
public type AnydataProducerRecord record {|
    # Topic to which the record will be appended
    string topic;
    # Key that is included in the record
    anydata key?;
    # Anydata record content
    anydata value;
    # Timestamp of the record, in milliseconds since epoch
    int timestamp?;
    # Partition to which the record should be sent
    int partition?;
    # Map of headers to be included with the record
    map<byte[]|byte[][]|string|string[]> headers?;
|};

# Defines the common error type for the module.
public type Error distinct error;
```

A listing over the ceiling says so, with the command that continues it:

```
$ bal discover ballerinax/twilio client
Container: Client
Methods: createAccount, createAddress, createApplication, createCall, createCallFeedbackSummary, createCallRecording, createIncomingPhoneNumber, createIncomingPhoneNumberAssignedAddOn, createIncomingPhoneNumberLocal, createIncomingPhoneNumberMobile, createIncomingPhoneNumberTollFree, createMessage, createMessageFeedback, createNewKey, createNewSigningKey, createParticipant, createPayments, createQueue, createSipAuthCallsCredentialListMapping, createSipAuthCallsIpAccessControlListMapping, createSipAuthRegistrationsCredentialListMapping, createSipCredential, createSipCredentialList, createSipCredentialListMapping, createSipDomain, createSipIpAccessControlList, createSipIpAccessControlListMapping, createSipIpAddress, createSiprec, createStream, createToken, createUsageTrigger, createUserDefinedMessage, createUserDefinedMessageSubscription, createValidationRequest, deleteAddress, deleteApplication, deleteCall, deleteCallFeedbackSummary, deleteCallRecording
... 159 more (page 1 of 5), next page: bal discover ballerinax/twilio client Client --page 2

$ bal discover ballerinax/twilio client --filter message
Container: Client
Methods: createMessage, createMessageFeedback, createUserDefinedMessage, createUserDefinedMessageSubscription, deleteMedia, deleteMessage, deleteUserDefinedMessageSubscription, fetchMedia, fetchMessage, listCallNotification, listMedia, listMessage, listNotification, updateMessage
```

A selector that matches nothing is still an answer (exit 0), naming the closest names and what is there:

```
$ bal discover ballerinax/kafka client Producer sendd
Nothing on Producer matches 'sendd'.
Did you mean: send, sendWithMetadata
Available:
Container: Producer
Methods: 'flush, close, getTopicPartitions, send, sendWithMetadata
```

A member declared on several containers is never picked silently:

```
$ bal discover ballerinax/kafka client commit
'commit' is declared on 2 containers — pick one:
  Caller (2 matches): bal discover ballerinax/kafka client Caller commit
  Consumer (3 matches): bal discover ballerinax/kafka client Consumer commit
```

A large readme can be narrowed to the sections that mention a keyword:

```
$ bal discover ballerinax/kafka readme --filter producer
1. Kafka producer (18 lines)
4. Data serialization (26 lines)
```

## Output

Every answer is one structured result, rendered either as text or as JSON. The JSON is compact — fields
inline, one array element per line — and these are its shapes:

| Answer                               | JSON fields                                                                                                      |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| bare package                         | `buckets`, `submodules` (`name`, `summary`, `call`)                                                              |
| several containers                   | `containers` (`name`, `resources`, `remote`, `normal`, `listener`, `call`), `shown`, `total`, `next`             |
| resource groups                      | `container`, `resources` (ending at this prefix), `groups` (`name`, `count`, `call`), `shown`, `total`, `next`    |
| resource paths                       | `container`, `resources` (`path`, `accessors`, `call`), `shown`, `total`, `page`, `pages`, `next`               |
| methods                              | `container`, `methods`, `shown`, `total`, `page`, `pages`, `next`                                               |
| resources and methods together       | `container`, `resources`, `remote`, `normal`, `shown`, `total`, `next`, `documented`                            |
| one callable                         | `container`, `kind`, `name` or `accessor` + `path`, `form` (`->`, `.` or `new`), `declaration`, `params` (`name`, `type`, `default`, `kind`, `description`), `returns`, `deprecated`, `types` (`name`, `declaration`), `omitted`, `documented` |
| nothing matched                      | `requested`, `container`, `candidates`, `paths` (`path`, `call`), `available`, `next`, `documented`              |
| member on several containers         | `requested`, `owners` (`name`, `matches`, `call`), `shown`, `total`, `next`                                      |
| empty bucket                         | `bucket`, `total`, `elsewhere` (`bucket`, `count`, `call`)                                                       |
| readme                               | `readme`, `lines`, and `chunk`, `of`, `title` for one section                                                    |
| readme sections                      | `chunks` (`number`, `title`, `lines`, `call`), `shown`, `total`, `page`, `pages`, `next`                       |

Any answer can also carry `warning` (the version could not be confirmed against the registry) and most can
carry `note` (the symbol was found in a different bucket than the one asked, the path selector was relocated
or a wildcard skipped a branch, or the service's listener). `documented` lists entries a `--filter` matched
only in their documentation.

### The entry ceiling

No listing shows more than **40 entries**. Over that:

- resource paths selected by a path (or by nothing) **group** by their next literal path segment, up to four
  literal levels below the top; path parameters never form a group of their own, and a group carries the
  accessor the listing was narrowed by into its `call`;
- remote and normal methods **page**, alphabetically, with `--page <n>`, and so do resource paths that cannot
  be grouped — selected by a name substring or `--filter`, or already four literal levels deep — and readme
  sections narrowed by `--filter`. Every page keeps the selector and the `--filter`;
- anything else — a roster of containers, a container mixing resources with methods — is cut at 40 and
  points at `--filter`.

A cut listing always says so: `shown`/`total` plus `next` (the command that continues it) in JSON, or a
trailing `... N more, narrow further: <command>` line in text — `... N more (page P of Q), next page:
<command>` for a paged one, where `N` counts what comes after this page and JSON adds `page`/`pages`.

### Paths

Path parameters print as `:name` (`repos/:owner/:repo`), which is safe unquoted in bash, zsh, fish and
PowerShell. A segment that is a Ballerina keyword keeps its escape (`gists/'public`), and every command the
tool prints pre-quotes such a path for the shell. A selector also accepts `[string owner]`, `[owner]`,
`{owner}` and plain `owner` for a parameter, and either spelling of an escaped segment (`code\-scanning` or
`code-scanning`). Paths match from the first segment; the one relaxation is a trailing segment, which is
looked for beneath the prefix that matched and listed rather than chosen when it occurs in several places.
`new` addresses the constructor Ballerina spells `init` — except on a container with resource paths, where a
path selector is tried first, so a path segment called `new` (github's `codespaces/'new`) wins over it.

## The contract

|        |                                                                       |
| ------ | --------------------------------------------------------------------- |
| stdout | the answer, and nothing else                                          |
| stderr | on failure, exactly one JSON object, and nothing else                 |
| exit 0 | success, and stdout is complete                                       |
| exit 1 | every failure; the JSON's `kind` and `suggestion` say what to do next |

```
$ bal discover ballerinax/kafka client NoSuchContainer
{"kind":"symbol-not-found","qualified":"ballerinax/kafka:4.6.5","requested":["NoSuchContainer"],"candidates":[],"suggestion":"Nothing in ballerinax/kafka:4.6.5 is named anything like that. List what is there: `bal discover ballerinax/kafka client`."}
```

`upstream` and `timeout` are worth re-running unchanged; `validation`, `package-not-found` and
`symbol-not-found` need a different command; `schema-drift` means Central's payload changed shape and is for
a maintainer.

**Versions are never an argument.** Inside a Ballerina project the tool walks up to `Ballerina.toml` and uses
the version `Dependencies.toml` locks, so a lookup sees what `bal build` compiles against. Outside one it uses
Central's latest.

## Caching

The raw Central payload is cached, keyed by package coordinates, with atomic writes. Any problem with the
cache — missing, unreadable, corrupt, an unwritable directory — falls back to a live fetch silently; it is
never a failure. The location is the first usable of:

1. `BAL_DISCOVER_CACHE=off` — caching disabled
2. `BAL_DISCOVER_CACHE_DIR=<dir>`
3. `$XDG_CACHE_HOME/bal-discover`
4. `~/.cache/bal-discover`
5. `<tmpdir>/bal-discover-<user>`

and caching is off when none of them is usable.

## Installing it

The tool is not on Ballerina Central yet, so `bal tool pull discover` does not resolve it; see
`internal-docs/distribution.md` for what is left. Until then, build and install it locally through the same
packaging a Central publish uses:

```bash
./gradlew :bal-tool:build -PpublishToLocalCentral=true          # pack the bala, push it to the local repository
bal tool pull discover:<version> --repository=local              # register it as an active tool
bal discover --help                                              # smoke test
```

`<version>` is `gradle.properties`' `version` without its `-SNAPSHOT` suffix.

## Building from the source

### Prerequisites

- OpenJDK 21, with `JAVA_HOME` pointing at it.
- A GitHub token with `read:packages`, exported as `packagePAT` (and your login as `packageUser`).
  `org.ballerinalang:ballerina-cli`, which provides the `BLauncherCmd` interface `bal` discovers the tool
  through, is published only to ballerina-platform's GitHub Packages, which needs authentication even for a
  public read.

```bash
gh auth refresh -h github.com -s read:packages
export packageUser="$(gh api /user -q .login)"
export packagePAT="$(gh auth token)"
```

### Build and test

```bash
./gradlew build                   # everything, including checkstyle, spotbugs and the tests
./gradlew :native:test            # the test suite: offline, no network, no access to your real cache
./gradlew :native:check           # tests plus the coverage floor (80% instructions, 70% branches)
./gradlew :bal-tool:build         # package the tool jar into a ballerina/tool_discover bala
```

When a rendering change is intentional, regenerate the snapshots and review the diff before committing it:

```bash
UPDATE_SNAPSHOTS=1 ./gradlew :native:test               # answer snapshots and usage text
BAL_DISCOVER_UPDATE_KEYSPACE=1 ./gradlew :native:test   # after re-recording the fixtures
```

The `.bal` API snapshots under `native/src/test/resources/snapshots/` have no update switch on purpose: they
are the oracle every quoted declaration is checked against.

## Verifying a build against live Central

The test suite proves the pipeline against recorded payloads. It cannot prove that `bal` routes to the
installed tool, that arguments survive `bal`'s launcher, or that exit codes reach the shell, so after a CLI
change run a few real invocations:

```bash
check() { local want="$1"; shift; bal discover "$@" >/dev/null 2>&1; local got=$?
  [ "$got" = "$want" ] && echo "ok   $* -> $got" || echo "FAIL $* -> $got want $want"; }

check 0 --help
check 0 ballerinax/kafka
check 0 ballerina/http client                       # several clients: a roster
check 0 ballerinax/kafka funcs                      # an empty bucket is an answer
check 0 ballerinax/github client gists
check 0 ballerinax/github client "gists/'public" get
check 0 ballerinax/github client Client zzz         # a selector that matches nothing is an answer
check 0 ballerina/sql client                        # clients Central files as plain declarations
check 1 ballerina/http:2.16.6                       # no version suffix
check 1 ballerina/http client 2.16.6                # no version argument
check 1 ballerina/http nosuchbucket
check 1 no-such-org/no-such-pkg
```

## Design notes

`internal-docs/system-design.md` describes the architecture and the reasoning behind it;
`internal-docs/distribution.md` covers packaging and publishing.

## Contributing to Ballerina

As an open-source project, Ballerina welcomes contributions from the community.

You can also check for
[open issues](https://github.com/ballerina-platform/discover-tool/issues) that interest you. We look
forward to receiving your contributions.

For more information, go to the [contribution guidelines](https://github.com/ballerina-platform/ballerina-lang/blob/master/CONTRIBUTING.md).

## Code of Conduct

All contributors are encouraged to read the [Ballerina Code of Conduct](https://ballerina.io/code-of-conduct).

## Useful Links

- Chat live with us via our [Discord server](https://discord.gg/ballerinalang).
- Post all technical questions on Stack Overflow with the [#ballerina](https://stackoverflow.com/questions/tagged/ballerina) tag.
