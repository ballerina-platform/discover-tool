## Overview

`bal discover` reads a Ballerina package off **Ballerina Central** and answers what its API actually is:
the clients, services, classes and functions it declares, how each is called, and the exact signature of
any one of them. It exists so an AI agent (or a human) can learn a package's real API instead of guessing it
from a web search or memory. It is deterministic — no model runs inside it.

```bash
bal discover --help
```

## Usage

```
bal discover <org>/<package>[:<version>] [bucket] [selector ...] [flags]
```

The package comes first and every further positional drills one level down. With no bucket, the answer is
the list of buckets the package has.

`<org>/<package>` always names a package, never a module of one: `bal discover ballerinax/aws.auth` fails with
the command that reads that module, `bal discover ballerinax/aws --module auth`. To find a package in the first
place, use `bal search <keyword>`; `bal discover` only drills into one you already know. A version goes in the
coordinate, the form `bal pull` takes: `bal discover ballerina/http:2.15.7` (see Which version is read, below).

| Bucket    | What it holds                                                                                            |
| --------- | -------------------------------------------------------------------------------------------------------- |
| `client`  | Objects reached with `->`: remote methods and resource functions (`client->path.accessor(...)`).         |
| `service` | Service object types, under the listener(s) that accept them; the rest are listed apart.                 |
| `class`   | Plain objects reached with `.`.                                                                          |
| `funcs`   | Module-level functions.                                                                                  |
| `type`    | Everything else the module declares: records, enums, errors, type aliases, constants, module variables and annotations. `type <Name>` reads one whole. |
| `readme`  | The module's README, verbatim. `readme <n>` or `readme "<title>"` opens one code-carrying section.       |

The first four are derived from how a symbol is called, not from Central's `isClient`-style flags:
`ballerina/http` declares ten clients, two of which Central files as ordinary declarations. `type` and `readme`
are not callable, so they have no containers or members.

`type` lists its declarations grouped by kind (`records`, `enums`, `errors`, `aliases`, `constants`, `variables`,
`annotations`) and pages them like any listing; `--filter` is how a large one — `ballerinax/github` declares
over a thousand records — is narrowed. `type <Name>` prints the declaration whole, then the declarations it
names one level deep, within a size budget: what the budget left out is listed with the command that opens each
one (up to 40; `omittedTotal` or an `... N more` line says how many there were in all, and `omittedNext` or the
`Next:` line is the command that lists them: the whole `type` roster), and so are the declarations another
package owns (`bal discover ballerina/http type BearerTokenConfig`).
It takes one name and no `--filter`. A name that is really a class, client, service type or listener is
answered by the bucket that holds it, and a record asked of `client` is answered by `type`; either way a note
says which. The name of an enum member (`HTTP_1_1`) is answered by the enum that declares it (`HttpVersion`), with a
note.

Selectors after the bucket name a container, then a member: a method name, or a resource path followed by
its accessor (`client "gists/'public" get`). With one container in the bucket, the container name can be
left out.

| Flag                   | Meaning                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------ |
| `--output json\|text`  | Override the default: text when stdout is a terminal, JSON otherwise.                                  |
| `--filter <keyword>`   | Narrow the selected bucket to entries whose name, path, parameter or type contains the keyword. Applied client-side to the fetched payload, never sent to Central. |
| `--page <n>`           | Turn the page of a listing over the entry ceiling — every listing pages: a roster, a level of path groups, methods, resource paths, a container listed by call form, the `type` declarations, documentation-only matches, and readme sections narrowed by `--filter`. Pages start at 1; a page outside the listing, or against an answer that does not page, is a `validation` failure. |
| `-m, --module <name>`  | Target a submodule instead of the default module, in every bucket including `readme`. The bare package lists the submodules it has. |
| `--refresh`            | Ignore the cached payload (and any cached source-derived answer) and fetch it again. Only a fetch that succeeds replaces the cached copy; a failed one leaves it in place. |

## Walkthrough

The output below is real, produced against recorded Central payloads; live Central may have moved on since
they were recorded.

What a package has:

```
$ bal discover ballerinax/kafka
ballerinax/kafka
5 buckets

  client
  service
  class
  type
  readme

Next: bal discover ballerinax/kafka <bucket>

$ bal discover ballerinax/kafka | cat
{"buckets":["client","service","class","type","readme"]}
```

Every JSON answer is exactly one line, however long, so cutting the output with `head` or `tail` never
leaves half an answer. The JSON samples below are that real output, unwrapped.

At a terminal every answer opens with a header naming where it is — package, bucket, container, selector — and
a count, lists one entry per line in aligned columns, and ends with a footer: notes, how much was left out,
and the `Next:` commands. A listing whose JSON gives every row its own command (`command`, or `commands` on a
resource row) prints the shape those commands share once, as a `Next:` line with a placeholder; a row whose
command does not fit that shape (a path that needs shell quoting, say) carries its own beside it.

Several containers in one bucket are a roster with their member counts — resource paths, not the operations
on them, so http's one `:...path` answering seven accessors counts once; service types are listed under the
listener they bind to, and the ones no listener accepts are listed apart, still addressable by name:

```
$ bal discover ballerina/http client
ballerina/http · client
10 clients

  Caller                                              5 remote  1 normal
  Client                                 1 resource  15 remote  4 normal
  ClientOAuth2Handler                                 1 remote  2 normal
  ClientObject                           1 resource  15 remote
  FailoverClient                         1 resource  15 remote  1 normal
  ListenerLdapUserStoreBasicAuthHandler               2 remote
  ListenerOAuth2Handler                               1 remote
  LoadBalanceClient                      1 resource  15 remote
  StatusCodeClient                       1 resource  15 remote  4 normal
  StatusCodeClientObject                 1 resource  15 remote

Next: bal discover ballerina/http client <name>

$ bal discover ballerina/http service
ballerina/http · service
7 service types

http:Listener
  InterceptableService  1 normal
  Service
  ServiceContract

Not attachable to a listener
  RequestErrorInterceptor
  RequestInterceptor
  ResponseErrorInterceptor
  ResponseInterceptor

Next: bal discover ballerina/http service <name>
```

A service type binds to a listener when it is the type the listener's `attach` takes (resolved through unions
and type aliases) or includes that type (`*Service;`). Central's docs payload does not publish a service
type's inclusions, so when some service type is not an `attach` target, an answer that shows a service type —
the `service` bucket, or a service type reached through any other bucket — reads the package's own source,
once per version, caching what it finds. It looks for the exact version first in your Ballerina home
(`~/.ballerina/repositories/central.ballerina.io/bala/...`, or under `BALLERINA_HOME_DIR`), where `bal pull` and
`bal build` put packages, then in the running distribution's own repository (`<ballerina.home>/repo/bala/...`,
the standard library it ships), and only then downloads the bala Central publishes. If the source cannot be
read, a type it would have settled is listed under the listener marked as not confirmed — `"confirmed":false`
in JSON, with the reason in `unconfirmedReason` — and opening one says why. With http's source unavailable:

```
$ bal discover ballerina/http service
ballerina/http · service
7 service types

http:Listener
  Service

http:Listener — not confirmed (package source unavailable)
  ServiceContract
  RequestInterceptor
  ResponseInterceptor
  RequestErrorInterceptor
  ResponseErrorInterceptor
  InterceptableService      1 normal

Next: bal discover ballerina/http service <name>
```

A listener whose `attach` takes another package's type points there:

```
$ bal discover ballerinax/postgresql service
ballerinax/postgresql · service
1 service type

postgresql:CdcListener
  cdc:Service  bal discover ballerinax/cdc service Service
```

A bucket with too many resource paths to list is grouped by path segment:

```
$ bal discover ballerinax/github client
ballerinax/github · client · Client
36 path groups

Groups (operations under each)
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
  installation           2
  licenses               2
  markdown               2
  .                      1
  app-manifests          1
  apps                   1
  emojis                 1
  events                 1
  feeds                  1
  issues                 1
  meta                   1
  networks               1
  octocat                1
  organizations          1
  rate_limit             1
  versions               1
  zen                    1

Next: bal discover ballerinax/github client Client <group>
```

A group small enough to list shows one entry per path, with every accessor it answers to:

```
$ bal discover ballerinax/github client gists
ballerinax/github · client · Client · gists
10 resource paths

  gists                              get, post
  gists/:gistId                      get, delete, patch
  gists/:gistId/:sha                 get
  gists/:gistId/comments             get, post
  gists/:gistId/comments/:commentId  get, delete, patch
  gists/:gistId/commits              get
  gists/:gistId/forks                get, post
  gists/:gistId/star                 get, put, delete
  gists/'public                      get                 bal discover ballerinax/github client Client "gists/'public" get
  gists/starred                      get

Next: bal discover ballerinax/github client Client <path> <accessor>
```

Operations that end exactly at a grouped prefix are listed beside its groups rather than as a group of their
own, and a group deeper than the top is named by its full path:

```
$ bal discover ballerinax/github client repos
ballerinax/github · client · Client · repos
1 resource path here, 64 path groups

Here
  repos/:owner/:repo  get, delete, patch

Groups (operations under each)
  repos/:owner/:repo/actions                   72
  repos/:owner/:repo/branches                  36
  repos/:owner/:repo/pulls                     31
  ...
  repos/:owner/:repo/notifications              2

... 25 more (page 1 of 2)
Next: bal discover ballerinax/github client Client <path> <accessor>
Next: bal discover ballerinax/github client Client <group>
Next: bal discover ballerinax/github client Client repos --page 2
Next: bal discover ballerinax/github client repos --filter <keyword>
```

A path may leave out its parameter segments: `repos/actions/runs` opens `repos/:owner/:repo/actions/runs`, with
a `note` saying where it was relocated to. When several real paths fit the shorthand, none is picked: the answer
lists each full path with the command that opens it.

In JSON, every resource entry carries a `commands` object keyed by accessor, in `accessors` order: for each
accessor, the ready-to-run command that opens that signature. A path with one accessor has the same shape with
one key, so a caller reads every resource row the same way, and a path with several gets a command for each
rather than a guess at one:

```
$ bal discover ballerinax/github client gists --filter star | cat
{"container":"Client","resources":[{"path":"gists/:gistId/star","accessors":["get","put","delete"],"commands":{"get":"bal discover ballerinax/github client Client gists/:gistId/star get","put":"bal discover ballerinax/github client Client gists/:gistId/star put","delete":"bal discover ballerinax/github client Client gists/:gistId/star delete"}},{"path":"gists/starred","accessors":["get"],"commands":{"get":"bal discover ballerinax/github client Client gists/starred get"}}],"shown":2,"total":2}
```

Everything else that opens exactly one thing — a method, a group, a container, a submodule, a readme section —
carries a plain `command`. A method row is `name` plus that `command`, the name quoted as one shell word where
it needs it (`'flush`):

```
$ bal discover ballerinax/kafka client Producer
ballerinax/kafka · client · Producer
5 methods

  'flush              bal discover ballerinax/kafka client Producer "'flush"
  close
  getTopicPartitions
  send
  sendWithMetadata

Next: bal discover ballerinax/kafka client Producer <name>

$ bal discover ballerinax/kafka client Producer | cat
{"container":"Producer","methods":[{"name":"'flush","command":"bal discover ballerinax/kafka client Producer \"'flush\""},{"name":"close","command":"bal discover ballerinax/kafka client Producer close"},{"name":"getTopicPartitions","command":"bal discover ballerinax/kafka client Producer getTopicPartitions"},{"name":"send","command":"bal discover ballerinax/kafka client Producer send"},{"name":"sendWithMetadata","command":"bal discover ballerinax/kafka client Producer sendWithMetadata"}],"shown":5,"total":5}
```

A container whose methods come in more than one call form is split by form, since `->` against `.` is what a
caller has to get right:

```
$ bal discover ballerinax/postgresql client
ballerinax/postgresql · client · Client
5 remote methods, 1 normal method

Remote (->)
  batchExecute
  call
  execute
  query
  queryRow

Normal (.)
  close

Next: bal discover ballerinax/postgresql client Client <name>

$ bal discover ballerinax/postgresql client | cat
{"container":"Client","remote":[{"name":"batchExecute","command":"bal discover ballerinax/postgresql client Client batchExecute"},{"name":"call","command":"bal discover ballerinax/postgresql client Client call"},{"name":"execute","command":"bal discover ballerinax/postgresql client Client execute"},{"name":"query","command":"bal discover ballerinax/postgresql client Client query"},{"name":"queryRow","command":"bal discover ballerinax/postgresql client Client queryRow"}],"normal":[{"name":"close","command":"bal discover ballerinax/postgresql client Client close"}],"counts":{"remote":5,"normal":1},"shown":6,"total":6}
```

One callable is the end of a drill-down: its declaration with its doc comment, then the declarations its
signature names, one level deep:

```
$ bal discover ballerinax/kafka client Producer send
ballerinax/kafka · client · Producer · send

# Produces records to the Kafka server.
# ```ballerina
# kafka:Error? result = producer->send({value: "Hello World".toBytes(), topic: "kafka-topic"});
# ```
# + producerRecord - Record to be produced
# + return - A `kafka:Error` if send action fails to send data or else '()'
isolated remote function send(AnydataProducerRecord producerRecord) returns Error?;

Types it names (2)
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
ballerinax/twilio · client · Client
199 methods

  createAccount
  createAddress
  createApplication
  createCall
  ...
  deleteCallRecording

... 159 more (page 1 of 5)
Next: bal discover ballerinax/twilio client Client <name>
Next: bal discover ballerinax/twilio client Client --page 2
Next: bal discover ballerinax/twilio client --filter <keyword>

$ bal discover ballerinax/twilio client --filter message
ballerinax/twilio · client · Client · --filter message
14 methods

  createMessage
  createMessageFeedback
  createUserDefinedMessage
  createUserDefinedMessageSubscription
  deleteMedia
  deleteMessage
  deleteUserDefinedMessageSubscription
  fetchMedia
  fetchMessage
  listCallNotification
  listMedia
  listMessage
  listNotification
  updateMessage

Matched by documentation only
  listAvailablePhoneNumberLocal
  listAvailablePhoneNumberMachineToMachine
  listAvailablePhoneNumberMobile
  listAvailablePhoneNumberNational
  listAvailablePhoneNumberSharedCost
  listAvailablePhoneNumberTollFree
  listAvailablePhoneNumberVoip
  listSigningKey

Next: bal discover ballerinax/twilio client Client <name>
```

The entries a `--filter` matched only in their documentation are named after the ones it matched by name,
path, parameter or type, and paged after them under the same `--page` — each is one more call away by name.

A selector that matches nothing is still an answer (exit 0), naming the closest names and what is there:

```
$ bal discover ballerinax/kafka client Producer sendd
ballerinax/kafka · client · Producer · sendd
Nothing on Producer matches 'sendd'.

Did you mean
  send
  sendWithMetadata

Available
  5 methods

    'flush              bal discover ballerinax/kafka client Producer "'flush"
    close
    getTopicPartitions
    send
    sendWithMetadata

  Next: bal discover ballerinax/kafka client Producer <name>
```

A member declared on several containers is never picked silently:

```
$ bal discover ballerinax/kafka client commit
ballerinax/kafka · client · commit
'commit' is declared on 2 containers; pick one.

  Caller    2 matches  bal discover ballerinax/kafka client Caller commit
  Consumer  3 matches  bal discover ballerinax/kafka client Consumer commit
```

A declaration that is not callable, read whole with the declarations it names:

```
$ bal discover ballerinax/kafka type TopicPartitionOffset
ballerinax/kafka · type · TopicPartitionOffset

# Represents a topic partition and an offset with a timestamp.
public type TopicPartitionOffset [TopicPartition, OffsetAndTimestamp?];

Types it names (2)
  # Represents a topic partition.
  public type TopicPartition record {|
      # Topic to which the partition is related
      string topic;
      # Index of the specific partition
      int partition;
  |};

  # Represents an offset and a timestamp for a topic partition.
  public type OffsetAndTimestamp record {|
      # The offset of the record in the topic partition
      int offset;
      # The timestamp of the record in the topic partition
      int timestamp;
      # The leader epoch of the record in the topic partition
      int? leaderEpoch = ();
  |};
```

A large readme can be narrowed to the sections that mention a keyword:

```
$ bal discover ballerinax/kafka readme --filter producer
ballerinax/kafka · readme · --filter producer
2 matching chunks

  1  Kafka producer      18 lines
  4  Data serialization  26 lines

Next: bal discover ballerinax/kafka readme <n>
```

## Output

Every answer is one structured result, rendered either as text or as JSON. The JSON is one line per answer —
no line breaks inside it, so a `head`/`tail` cut never splits one — and these are its shapes:

| Answer                               | JSON fields                                                                                                      |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| bare package                         | `buckets`, `submodules` (`name`, `summary`, `command`)                                                              |
| several containers                   | `containers` (`name`, `resources`, `remote`, `normal`, `listener`, `confirmed`, `unconfirmedReason`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, and in `service` `notAttachable` (`name`, `command`) with `notAttachableTotal` when some are on another page; `total` counts both lists |
| resource groups                      | `container`, `resources` (ending at this prefix; `path`, `accessors`, `commands`), `groups` (`name`, `count`, `command`), `counts` (`resources`, `groups`), `shown`, `total`, `page`, `pages`, `remaining`, `next` |
| resource paths                       | `container`, `resources` (`path`, `accessors`, `commands`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| methods of one call form             | `container`, `methods` (`name`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`)                 |
| more than one call form              | `container`, `resources` (`path`, `accessors`, `commands`), `remote` (`name`, `command`), `normal` (`name`, `command`), `counts` (`resources`, `remote`, `normal`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| one callable                         | `container`, `kind`, `name` or `accessor` + `path`, `form` (`->`, `.` or `new`), `declaration`, `params` (`name`, `type`, `default`, `kind`, `description`), `returns`, `deprecated`, `types` (`name`, `declaration`), `omitted` (`name`, `command`), `omittedTotal`, `omittedNext`, `foreign` (`name`, `module`, `version`, `command`), `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`), `page`, `pages`, `remaining`, `next` |
| type declarations                    | `sections` (`records`, `enums`, `errors`, `aliases`, `constants`, `variables`, `annotations`, each a list of `name`, `command`), `counts` (per kind, every page included), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| one declaration                      | `name`, `kind`, `declaration`, `types` (`name`, `declaration`), `omitted` (`name`, `command`), `omittedTotal`, `omittedNext`, `foreign` (`name`, `module`, `version`, `command`) |
| nothing matched                      | `requested`, `container`, `candidates`, `paths` (`path`, `command`), `available`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`), `page`, `pages`, `remaining` |
| member on several containers         | `requested`, `owners` (`name`, `matches`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`                    |
| empty bucket                         | `bucket`, `total`, `elsewhere` (`bucket`, `count`, `command`)                                                       |
| readme                               | `readme`, `lines`, and `chunk`, `of`, `title` for one section                                                    |
| readme sections                      | `chunks` (`number`, `title`, `lines`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`                       |

Any answer can also carry `warning` (the version could not be confirmed against the registry) and most can
carry `note` (the symbol was found in a different bucket than the one asked, the path selector was relocated
or a wildcard skipped a branch, or the service's listener). `documented` lists entries a `--filter` matched
only in their documentation, alphabetically and shaped as their listing rows are — a method, function or type
with its `command`, a resource path once with every matching accessor's `commands` — held to the same ceiling
and paged with the same `--page`, with `documentedTotal` giving how many there are (in text, a `Matched by
documentation only (40 of 102)` heading and a `Next:` line with the command's shape). Beside a listing they are
its last section, after every entry the filter matched by name; beside one signature, or as all a filter found,
they page on their own, and `next` turns that page — absent on the last page, as on every paged answer. A miss
that has them lists them in place of `available`, and when they fit on one page its `next` opens the whole
container.

### Order

Every listing is alphabetical: containers in a roster, service types under their listener (and the listeners
themselves), methods, resource paths and `type` declarations within their kind. The exception is a level of path
groups, which goes by the number of operations under each, largest first (alphabetical among equals), because it is
read to choose where to go next.

### The entry ceiling

No listing shows more than **40 entries**. Over that:

- resource paths selected by a path (or by nothing) **group** by their next literal path segment, up to four
  literal levels below the top; path parameters never form a group of their own, and a group carries the
  accessor the listing was narrowed by into its `command`;
- everything else **pages** with `--page <n>`: a roster of containers, a level of groups (the paths ending
  there first, then the groups, with `counts` giving both across every page), methods of one call form
  (alphabetically), resource paths that cannot be grouped — selected by a name substring or `--filter`, or
  already four literal levels deep — and readme sections narrowed by `--filter`. Every page keeps the selector,
  the `--filter` and the `--module`;
- a container mixing call forms (resources with methods, or remote methods with normal ones) pages the same
  way, as one sequence — resource paths, then remote methods, then normal ones — so a page can end partway
  through one section and pick up the next; each page keeps the section headings (`resources`, `remote`,
  `normal` in JSON) for whatever it holds and omits the ones it holds nothing of, while `counts` (and the text
  header, `111 remote methods, 1 normal method`) gives every form's size across the whole listing, so a form
  that only appears on a later page is never hidden;
- documentation-only matches page as described above; a roster's `--filter` keeps a container whose own name
  matches (opened whole, since a container with no methods has nothing else to match) as well as one with a
  matching member (opened narrowed).

A cut listing always says so: `shown`/`total`, `page`/`pages`, `remaining` (everything after this page,
documentation-only matches included) and `next` (the `--page` command that continues it) in JSON, or a
`... N more (page P of Q)` line followed by `Next: <command>` in text, where `N` counts what comes after this page
(`... N more matched by documentation only (page P of Q)` when only those are paged, beside one signature or a
miss). In text, a listing that continues also ends with the command that narrows it by keyword,
`Next: bal discover <package> <bucket> --filter <keyword>`, which is usually shorter than paging.

### Paths

Path parameters print as `:name` (`repos/:owner/:repo`), which is safe unquoted in bash, zsh, fish and
PowerShell. A segment that is a Ballerina keyword keeps its escape (`gists/'public`), and every command the
tool prints pre-quotes such a path for the shell. A selector also accepts `[string owner]`, `[owner]`,
`{owner}` and plain `owner` for a parameter, and either spelling of an escaped segment (`code\-scanning` or
`code-scanning`). Paths match from the first segment; the one relaxation is a trailing segment, which is
looked for beneath the prefix that matched and listed rather than chosen when it occurs in several places.
`new` addresses the constructor Ballerina spells `init` — except on a container with resource paths, where a
path selector is tried first, so a path segment called `new` (github's `codespaces/'new`) wins over it.
A member name is one selector. On a container with resource paths, two selectors that resolve as a path and
its accessor, in either order, are read together and win over a member of the same name; otherwise a first
selector naming a method, function or `new` is read alone, and any other pair is a no-match answer listing
the path's accessors.
A selector beyond what the container reads is a `validation` failure naming the command without it, never
silently dropped; a path's segments go in one argument joined by `/`. Typed apart, two segments are a no-match
answer whose `next` is the joined command, and three or more are that failure, suggesting the joined command.

## The contract

|        |                                                                                  |
| ------ | -------------------------------------------------------------------------------- |
| stdout | the answer, and nothing else                                                     |
| stderr | on failure, exactly one failure, and nothing else                                |
| exit 0 | success, and stdout is complete                                                  |
| exit 2 | a usage error — the command line itself is malformed, so nothing was looked up: stdout is empty |
| exit 1 | every other failure: stdout is empty, and the failure's `kind` and `suggestion` say what to do next |

A failure is written in the same mode as an answer would have been: JSON off a terminal, text at one, either
forced with `--output` (honoured even when the rest of the arguments fail to parse). In JSON it is one object,
whose `qualified` is always `org/name`, and whose `version` (when one was reached) and `module` (when
`--module` was passed) are keys of their own:

```
$ bal discover ballerinax/kafka client NoSuchContainer | cat
{"kind":"symbol-not-found","qualified":"ballerinax/kafka","version":"4.6.5","requested":["NoSuchContainer"],"candidates":[],"suggestion":"Nothing in ballerinax/kafka:4.6.5 is named anything like that. List what is there: `bal discover ballerinax/kafka client`."}
```

In text it is `error: <message>`, then the suggestion and anything else the failure carries (candidates, schema
issues) indented below it; a usage error then ends with the synopsis and a pointer to `--help`:

```
$ bal discover ballerina/http:2.15
error: '2.15' in 'ballerina/http:2.15' is not a complete version.
  Write it in full, ballerina/http:<major>.<minor>.<patch>, or drop it to read the version your project locks or Central's latest: ballerina/http
Usage: bal discover <org>/<name>[:<version>] [bucket] [name ...] [--output json|text] [--filter <keyword>] [--page <n>] [-m <module>] [--refresh]
Run 'bal discover --help' for details.
```

A usage error is a `validation` failure found before anything is looked up: an unknown option, an option with
a missing or invalid value (`--output xml`, `--page x`, `--page 0`, `--refresh=yes`) or given twice, no package,
a malformed package name or incomplete version, `--version`, a version typed as a positional, an unknown bucket,
more than one name after `funcs` or `type`, or a `type` name with `--filter`. A `validation` failure that
depends on what the package holds — an extra selector a container does not read, a page past the end of the
listing, a module with no readme — is exit 1.

`upstream` and `timeout` are worth re-running unchanged once their cause has passed or been fixed; `validation`,
`package-not-found` and `symbol-not-found` need a different command; `schema-drift` means Central's payload
changed shape and is for a maintainer. An `upstream` failure's `message` names what failed: the host, or the
proxy and the `Settings.toml` that sets it. Its `reached` is `true` when Central answered, with an error status
(in `status`) or a body that is not JSON, and `false` when no answer from Central came back: the connection, DNS,
TLS or the proxy failed, and `status` is then the proxy's own answer when it gave one (407 when it wants a
username and password).

**Which version is read.** The one written in the coordinate (`ballerina/http:2.15.7`), even inside a project that
locks another. Otherwise, inside a Ballerina project the tool walks up to `Ballerina.toml` and uses the version
`Dependencies.toml` locks, so a lookup sees what `bal build` compiles against, and outside one (or for a package
the project does not depend on yet) it uses Central's latest. A written version must be complete:
`ballerina/http:2.15` is a `validation` failure, and so is a version typed as a positional (`bal discover
ballerina/http 2.15.0`) or as `--version`, each suggesting the coordinate to type. `--module` composes unchanged:
`bal discover ballerina/http:2.15.7 --module httpscerr`. A written version is carried into every command the
answer prints (`bal discover ballerina/http:2.15.7 client Client`), so drilling in stays on it; without one the
printed commands are unpinned (a locked version is resolved again), except one that opens a declaration another
package owns, which pins the version that declaration was generated against
(`bal discover ballerina/crypto:2.9.3 type TrustStore`).

## Proxy

Requests to Central go through the HTTP proxy `bal pull` uses: the `[proxy]` table of `~/.ballerina/Settings.toml`
(or of `$BALLERINA_HOME_DIR/Settings.toml` when that is set). A proxy needs `host` and `port`; `username` and
`password`, when both are set, answer the proxy's own authentication challenge, once per request: a rejected
password is a failure, never retried. With no such table, an empty `host` or no `port`, requests go direct.

```toml
[proxy]
host = "proxy.example.com"
port = 3128
username = "alice"
password = "secret"
```

## Caching

The raw Central payload is cached, keyed by package coordinates, with atomic writes, and so is what a
package's source says about its service types' inclusions (the small derived answer, never the bala). Any
problem with the cache — missing, unreadable, corrupt, an unwritable directory — falls back to a live fetch
silently; it is never a failure. The location is the first usable of:

1. `BAL_DISCOVER_CACHE=off` — caching disabled
2. `BAL_DISCOVER_CACHE_DIR=<dir>`
3. `$XDG_CACHE_HOME/bal-discover`
4. `~/.cache/bal-discover`
5. `<tmpdir>/bal-discover-<user>`

and caching is off when none of them is usable.

## Installing it

Install it from Ballerina Central:

```bash
bal tool pull discover
```
