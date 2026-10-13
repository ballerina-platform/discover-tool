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
bal discover <org>/<package>[:<version>] [bucket] [name ...] [flags]
bal discover search <keyword> ...
```

The package comes first and every further positional drills one level down. With no bucket, the answer is
the package's overview: its clients, service types, classes and functions with their member counts, one line for
its types by kind, one for its readme's sections, and its submodules, each with the command that opens it.

`<org>/<package>` always names a package, never a module of one: `bal discover ballerinax/aws.auth` fails with
the command that reads that module, `bal discover ballerinax/aws --module auth`. To find a package in the first
place, `bal discover search <keyword> ...` asks Central (see Searching, below). A version goes in the
coordinate, the form `bal pull` takes: `bal discover ballerina/http:2.15.7` (see Which version is read, below).

| Bucket    | What it holds                                                                                            |
| --------- | -------------------------------------------------------------------------------------------------------- |
| `client`  | Objects reached with `->`: remote methods and resource functions (`client->path.accessor(...)`).         |
| `service` | Service object types, under the listener(s) that accept them; the rest are listed apart.                 |
| `class`   | Plain objects reached with `.`.                                                                          |
| `funcs`   | Module-level functions.                                                                                  |
| `type`    | Every type the module declares: records, enums, errors, type aliases, constants, module variables, annotations, listeners, and the classes, clients and service types the buckets above also list. `type <Name>` opens any of them. |
| `readme`  | The module's README, verbatim. `readme <n>` or `readme "<title>"` opens one section.                   |
| `search`  | Every name in the module that holds each keyword: `search <keyword> ...` (see Searching, below).         |

The first four are derived from how a symbol is called, not from Central's `isClient`-style flags:
`ballerina/http` declares ten clients, two of which Central files as ordinary declarations. `type` and `readme`
are not callable, so they have no containers or members.

`type` lists its declarations grouped by kind (`records`, `enums`, `errors`, `aliases`, `constants`, `variables`,
`annotations`, `listeners`, `classes`, `clients`, `serviceTypes`) and pages them like any listing; `--filter` is how a large one — `ballerinax/github` declares
over a thousand records — is narrowed. `type <Name>` prints the declaration whole, then the declarations it
names one level deep, each only while it is no larger than 6 KB and never a class; every type it uses but does
not print is listed, all of them, with the command that opens each, and so are the declarations another package
owns (`bal discover ballerina/http type BearerTokenConfig`).
It takes one name and no `--filter`. A class, client or service type answers as its own bucket does
(`type Response` as `class Response`), a listener — opened nowhere else — prints its own declaration, `init` and
its other methods included, with the types it uses, and an enum member (`HTTP_1_1`) answers with the enum that
declares it (`HttpVersion`) and a note naming it.

Selectors after the bucket name a container, then a member: a method name, or a resource path followed by
its accessor (`client Client gists/public get`). The container may be left out when exactly one container in
the bucket declares what follows (`client send` answers as `client Producer send`); when several do, the failure
lists each one's command. Every command the tool prints names the container. Every name is typed as the
listings print it, or in Ballerina's own escaped spelling of the same identifier (`'public`, `code\-scanning`):
anything else fails, with the listing to choose from (see Paths and The contract, below).

| Flag                   | Meaning                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------ |
| `--output json\|text`  | Override the default: text when stdout is a terminal, JSON otherwise.                                  |
| `--filter <keyword>`   | Narrow the selected bucket to entries whose name, path, parameter or type contains the keyword. Filtering is local to the fetched payload; nothing is sent to Central. On the bare package it runs `search <keyword>`. |
| `--page <n>`           | Show page `<n>` of a listing longer than 40 entries — every listing pages, from a roster of clients to the readme sections a `--filter` narrows. Pages start at 1; a page outside the listing, or against an answer that does not page, is a `validation` failure. |
| `-m, --module <module>` | Target a submodule instead of the default module, in every bucket including `readme`. The bare package lists the submodules it has. |
| `--refresh`            | Ignore the cached payload (and any cached source-derived answer) and fetch it again. Only a fetch that succeeds replaces the cached copy; a failed one leaves it in place. |

## Walkthrough

The output below is real, produced against recorded Central payloads (Central-wide search against live
Central); live Central may have moved on since they were recorded.

What a package has:

```
$ bal discover ballerinax/kafka
ballerinax/kafka:4.6.5

Clients (3)
  Caller     3 remote methods
  Consumer  24 remote methods
  Producer   5 remote methods
Service types (1)
  Service
Classes (4)
  AvroDeserializer  1 method
  AvroSerializer    1 method
  Deserializer      1 method
  Serializer        1 method
Types   bal discover ballerinax/kafka type    14 records, 3 enums, 3 errors, 8 aliases, 29 constants, 1 annotation, 1 listener
Readme  bal discover ballerinax/kafka readme  11 sections

Next: bal discover ballerinax/kafka client <name>
Next: bal discover ballerinax/kafka service <name>
Next: bal discover ballerinax/kafka class <name>
Next: bal discover ballerinax/kafka search <keyword>

$ bal discover ballerinax/kafka | cat
{"qualified":"ballerinax/kafka","version":"4.6.5","clients":{"entries":[{"name":"Caller","remote":3,"command":"bal discover ballerinax/kafka client Caller"},{"name":"Consumer","remote":24,"command":"bal discover ballerinax/kafka client Consumer"},{"name":"Producer","remote":5,"command":"bal discover ballerinax/kafka client Producer"}],"shown":3,"total":3,"command":"bal discover ballerinax/kafka client"},"serviceTypes":{"entries":[{"name":"Service","command":"bal discover ballerinax/kafka service Service"}],"shown":1,"total":1,"command":"bal discover ballerinax/kafka service"},"classes":{"entries":[{"name":"AvroDeserializer","normal":1,"command":"bal discover ballerinax/kafka class AvroDeserializer"},{"name":"AvroSerializer","normal":1,"command":"bal discover ballerinax/kafka class AvroSerializer"},{"name":"Deserializer","normal":1,"command":"bal discover ballerinax/kafka class Deserializer"},{"name":"Serializer","normal":1,"command":"bal discover ballerinax/kafka class Serializer"}],"shown":4,"total":4,"command":"bal discover ballerinax/kafka class"},"types":{"counts":{"records":14,"enums":3,"errors":3,"aliases":8,"constants":29,"annotations":1,"listeners":1},"command":"bal discover ballerinax/kafka type"},"readme":{"sections":11,"command":"bal discover ballerinax/kafka readme"}}
```

The overview stays inside the 40-row ceiling: a bucket shows at most 10 rows, the type and readme lines are a
row each, and submodules are never cut (no other answer lists them). If that still comes to more than 40 rows,
the section showing the most gives up one row at a time (the later bucket on a tie) until it fits. A cut
section ends with `... N more: <command>`, the command that lists the bucket whole; in JSON each section has
`shown`, `total` and that `command`. `ballerina/http`, the largest overview measured, shows 10 of 10 clients,
7 service types, 10 of 83 classes and 7 functions.

Every JSON answer is exactly one line, however long, so cutting the output with `head` or `tail` never
leaves half an answer. The JSON samples below are that real output, unwrapped.

Every answer says which package it read: a JSON answer leads with `qualified` (`org/name`), `version` and, under
`--module`, `module`, named as a failure names them, and at a terminal the header names the package as
`org/name:version`, whether the version was written, locked or Central's latest.

At a terminal every answer opens with a header naming where it is — package, bucket, container, selector — and
a count, lists one entry per line in aligned columns, and ends with a footer: notes, how much was left out,
and the `Next:` commands. A listing whose JSON gives every row its own command (`command`, or `commands` on a
resource row) prints the shape those commands share once, as a `Next:` line with a placeholder; a row whose
command does not fit that shape (a path that needs shell quoting, say) carries its own beside it.

Several containers in one bucket are a roster with their member counts — resource methods (one per path and
accessor), so http's one `:...path` answering seven accessors counts seven; service types are listed under the
listener they bind to, and the ones no listener accepts are listed apart, still addressable by name:

```
$ bal discover ballerina/http client
ballerina/http:2.16.6 · client
10 clients

  Caller                                                     5 remote methods  1 method
  Client                                 1 resource method  15 remote methods  4 methods
  ClientOAuth2Handler                                        1 remote method   2 methods
  ClientObject                           1 resource method  15 remote methods
  FailoverClient                         1 resource method  15 remote methods  1 method
  ListenerLdapUserStoreBasicAuthHandler                      2 remote methods
  ListenerOAuth2Handler                                      1 remote method
  LoadBalanceClient                      1 resource method  15 remote methods
  StatusCodeClient                       1 resource method  15 remote methods  4 methods
  StatusCodeClientObject                 1 resource method  15 remote methods

Next: bal discover ballerina/http client <name>

$ bal discover ballerina/http service
ballerina/http:2.16.6 · service
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
ballerina/http:2.16.6 · service
7 service types

http:Listener
  Service

http:Listener — unverified (package source unavailable)
  ServiceContract
  RequestInterceptor
  ResponseInterceptor
  RequestErrorInterceptor
  ResponseErrorInterceptor
  InterceptableService      1 method

Next: bal discover ballerina/http service <name>
```

Most listener packages declare their service type with no methods (`public type Service distinct service object {};`),
because the methods a service defines are enforced by the package's compiler plugin. Recent versions publish them as
data, in the bala's `metadata/trigger-metadata.json`, found the same way as the source above and cached per version.
Opening such a service type lists them under *Methods to implement*, each marked `required`, `optional` or
`any number`, with its parameters and documentation; naming one shows it alone. A version that publishes no such
file keeps the answer without methods.

```
$ bal discover ballerinax/kafka service Service
ballerinax/kafka:4.6.6 · service · Service
Methods to implement (2)

remote function onConsumerRecord(kafka:Caller caller, kafka:AnydataConsumerRecord[]|kafka:BytesConsumerRecord[] records) returns error?
  required
  Invoked with each batch of records polled from the subscribed topics. [...]
  caller (optional): Handle for committing offsets explicitly. Needed only when auto-commit is disabled.
  records (required): The polled batch. [...]

remote function onError(kafka:Error kafkaError) returns error?
  optional
  Invoked when polling fails, or when a polled batch cannot be dispatched, [...]
  kafkaError (required): The failure that prevented the batch from being delivered.

Note: attach it to kafka:Listener
```

A listener whose `attach` takes another package's type lists that type under the prefix it is written with, and
opens it in the listener's own package: that package's `trigger-metadata.json` is where the methods a service of it
defines are described (`ballerinax/cdc` publishes none). Without one, the answer is the one for a service type
without methods. Either way the note names the package that declares it:

```
$ bal discover ballerinax/postgresql service
ballerinax/postgresql:1.19.1 · service · cdc:Service
Methods to implement (6)

remote function onRead(record {} afterEntry, string tableName) returns error?
  optional
  Invoked for each row captured during the initial snapshot of a table, [...]
  afterEntry (required): The row as captured. Declare a record type to project its columns.
  tableName (required): Name of the table the row came from, useful when one service handles several tables.
[...]

Rules
  At least one of: onRead, onCreate, onUpdate, onDelete, onTruncate — A CDC service must declare at least one change handler. [...]

Note: attach it to postgresql:CdcListener; declared in ballerinax/cdc — bal discover ballerinax/cdc service Service
```

A bucket with too many resource paths to list is grouped by path segment:

```
$ bal discover ballerinax/github client
ballerinax/github:6.0.0 · client · Client
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
$ bal discover ballerinax/github client Client gists
ballerinax/github:6.0.0 · client · Client · gists
10 resource paths

  gists                              get, post
  gists/:gistId                      get, delete, patch
  gists/:gistId/:sha                 get
  gists/:gistId/comments             get, post
  gists/:gistId/comments/:commentId  get, delete, patch
  gists/:gistId/commits              get
  gists/:gistId/forks                get, post
  gists/:gistId/star                 get, put, delete
  gists/public                       get
  gists/starred                      get

Next: bal discover ballerinax/github client Client <path> <accessor>
```

Operations that end exactly at a grouped prefix are listed beside its groups rather than as a group of their
own, and a group deeper than the top is named by its full path:

```
$ bal discover ballerinax/github client Client repos
ballerinax/github:6.0.0 · client · Client · repos
1 resource path here, 64 path groups

Paths at this level
  repos/:owner/:repo  get, delete, patch

Path groups (resource methods in each)
  repos/:owner/:repo/actions                   72
  repos/:owner/:repo/branches                  36
  repos/:owner/:repo/pulls                     31
  ...
  repos/:owner/:repo/notifications              2

... 25 more (page 1 of 2)
Next: bal discover ballerinax/github client Client <path> <accessor>
Next: bal discover ballerinax/github client Client <group>
Next: bal discover ballerinax/github client Client repos --page 2
Next: bal discover ballerinax/github client Client repos --filter <keyword>
```

A path is typed whole, from its first segment, exactly as listed. One that leaves out its parameter segments
(`repos/actions/runs`) fails, suggesting the deepest level it did reach,
`bal discover ballerinax/github client Client repos`.

In JSON, every resource entry carries a `commands` object keyed by accessor, in `accessors` order: for each
accessor, the ready-to-run command that opens that signature. A path with one accessor has the same shape with
one key, so a caller reads every resource row the same way, and a path with several gets a command for each
rather than a guess at one:

```
$ bal discover ballerinax/github client Client gists --filter star | cat
{"qualified":"ballerinax/github","version":"6.0.0","container":"Client","resources":[{"path":"gists/:gistId/star","accessors":["get","put","delete"],"commands":{"get":"bal discover ballerinax/github client Client gists/:gistId/star get","put":"bal discover ballerinax/github client Client gists/:gistId/star put","delete":"bal discover ballerinax/github client Client gists/:gistId/star delete"}},{"path":"gists/starred","accessors":["get"],"commands":{"get":"bal discover ballerinax/github client Client gists/starred get"}}],"shown":2,"total":2}
```

Everything else that opens exactly one thing — a method, a group, a container, a submodule, a readme section —
carries a plain `command`. A method row is `name` plus that `command`. Names print without Ballerina's escapes
(the method declared `'flush` is `flush`), and either spelling reaches it:

```
$ bal discover ballerinax/kafka client Producer
ballerinax/kafka:4.6.5 · client · Producer
5 remote methods

  close
  flush
  getTopicPartitions
  send
  sendWithMetadata

Next: bal discover ballerinax/kafka client Producer <name>

$ bal discover ballerinax/kafka client Producer | cat
{"qualified":"ballerinax/kafka","version":"4.6.5","container":"Producer","methods":[{"name":"close","command":"bal discover ballerinax/kafka client Producer close"},{"name":"flush","command":"bal discover ballerinax/kafka client Producer flush"},{"name":"getTopicPartitions","command":"bal discover ballerinax/kafka client Producer getTopicPartitions"},{"name":"send","command":"bal discover ballerinax/kafka client Producer send"},{"name":"sendWithMetadata","command":"bal discover ballerinax/kafka client Producer sendWithMetadata"}],"shown":5,"total":5}
```

A container whose methods come in more than one call form is split by form, since `->` against `.` is what a
caller has to get right:

```
$ bal discover ballerinax/postgresql client
ballerinax/postgresql:1.19.0 · client · Client
5 remote methods, 1 method

Remote methods (->)
  batchExecute
  call
  execute
  query
  queryRow

Methods (.)
  close

Next: bal discover ballerinax/postgresql client Client <name>

$ bal discover ballerinax/postgresql client | cat
{"qualified":"ballerinax/postgresql","version":"1.19.0","container":"Client","remote":[{"name":"batchExecute","command":"bal discover ballerinax/postgresql client Client batchExecute"},{"name":"call","command":"bal discover ballerinax/postgresql client Client call"},{"name":"execute","command":"bal discover ballerinax/postgresql client Client execute"},{"name":"query","command":"bal discover ballerinax/postgresql client Client query"},{"name":"queryRow","command":"bal discover ballerinax/postgresql client Client queryRow"}],"normal":[{"name":"close","command":"bal discover ballerinax/postgresql client Client close"}],"counts":{"remote":5,"normal":1},"shown":6,"total":6}
```

One callable is the end of a drill-down: its declaration with its doc comment; for a method of a client or
class with an `init`, how to construct it; the declarations its signature names, one level deep; and, for a
remote or resource method, the readme sections whose code calls it. The container is left out here, since only
Producer declares `send`:

```
$ bal discover ballerinax/kafka client send
ballerinax/kafka:4.6.5 · client · Producer · send

# Produces records to the Kafka server.
# ```ballerina
# kafka:Error? result = producer->send({value: "Hello World".toBytes(), topic: "kafka-topic"});
# ```
# + producerRecord - Record to be produced
# + return - A `kafka:Error` if send action fails to send data or else '()'
isolated remote function send(AnydataProducerRecord producerRecord) returns Error?;

Create it
  kafka:Producer producer = check new (bootstrapServers, config);
  init(string|string[] bootstrapServers, *kafka:ProducerConfiguration config)
  bal discover ballerinax/kafka client Producer init

Types it uses (2)
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

Readme sections that call it
  7  Data serialization  bal discover ballerinax/kafka readme 7
```

*Create it* is derived from the container's `init` alone. Its first line is Ballerina that compiles once its
arguments are in scope: the type with the prefix its import binds, a variable named after it (with the prefix when
that would be a keyword: `github:Client githubClient`), `check` exactly when `init` declares a return type, and the
required parameters passed by name, an included record (`*kafka:ProducerConfiguration config`) as `config`; a
parameter with a default and a rest parameter are left out. The next line is `init`'s parameters as declared, so
each argument's type and every default stay in view. A class with no `init` is `new ()`. A function, a constructor,
a service type and an object type (`sql:Client`) get none.

*Readme sections that call it* are read off each section's fenced code: `->name(` for a remote method; for a
resource method its accessor (`get` when the call names none) and literal segments, with any segment matching a
path parameter and the most literal resource winning, as Ballerina resolves the call. Several containers often
declare the same method (`http:Client` and `http:FailoverClient` both `post`), so the type the readme declares
the call's variable with decides whose call it is; a variable declared nowhere counts only when no other container
declares the same method. A plain method (`.name(`) is never matched.

A listing over the ceiling says so, with the command that continues it:

```
$ bal discover ballerinax/twilio client
ballerinax/twilio:5.0.2 · client · Client
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
ballerinax/twilio:5.0.2 · client · Client · --filter message
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

Mentioned only in their documentation
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

A selector that matches nothing fails (exit 1, see The contract, below), suggesting the listing to choose from
rather than a guess at what was meant:

```
$ bal discover ballerinax/kafka client Producer sendd
error: 'sendd' is not a member of Producer in ballerinax/kafka:4.6.5.
  List its members: `bal discover ballerinax/kafka client Producer`.
```

So does a name asked in a bucket that does not hold it — the suggestion is the command in the bucket that does,
`type` for a record, enum, listener or other type, or the owner's command for a member another bucket's
container declares — and a member several containers of the bucket declare, with each one's command:

```
$ bal discover ballerinax/kafka client Listener
error: 'Listener' is not a client in ballerinax/kafka:4.6.5.
  It is a listener: `bal discover ballerinax/kafka type Listener`.

$ bal discover ballerinax/kafka client close
error: 'close' is declared by Consumer and Producer in ballerinax/kafka:4.6.5.
  Name the one you mean:
    Consumer  bal discover ballerinax/kafka client Consumer close
    Producer  bal discover ballerinax/kafka client Producer close
```

A name several containers declare has no one command to run, so that failure carries none: its JSON lists each
container in `owners`, as `{"name", "command"}` rows whose commands open what was named on it (at most 40; past
that the suggestion quotes the package's `search` for the name).

A service type that declares no methods, and whose package does not describe the ones a service defines,
answers with its own documentation and the readme sections whose code declares such a service — typed as it;
untyped on one of its listeners, when it is the type that listener's `attach` takes or the one service type the
listener accepts (an untyped service on `http:Listener` is an `http:Service`, not an `http:ServiceContract`); or,
for a module's only service type with no listener of its own, any service — or, when none does, the readme's
command:

```
$ bal discover ballerina/http service Service
ballerina/http:2.16.6 · service · Service
Service declares no methods: a service of it defines its own.

The HTTP service type.

Readme sections that declare one
  5  Listener  bal discover ballerina/http readme 5

Note: attach it to http:Listener
```

A declaration that is not callable, read whole with the declarations it names:

```
$ bal discover ballerinax/kafka type TopicPartitionOffset
ballerinax/kafka:4.6.5 · type · TopicPartitionOffset

# Represents a topic partition and an offset with a timestamp.
public type TopicPartitionOffset [TopicPartition, OffsetAndTimestamp?];

Types it uses (2)
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

A readme of at most 10 KB prints whole, under the same header line as every other answer. A larger one lists every
one of its sections (a heading and everything down to the next heading) with the command that opens it, never cut off.
A large readme can be narrowed to the sections that mention a keyword:

```
$ bal discover ballerinax/kafka readme --filter producer
ballerinax/kafka:4.6.5 · readme · --filter producer
2 matching sections

  1  Kafka producer      18 lines
  4  Data serialization  26 lines

Next: bal discover ballerinax/kafka readme <n>
```

## Searching

`bal discover <org>/<package> search <keyword> ...` finds names inside one package, locally, with nothing sent
to Central. Each keyword is matched as a case-insensitive substring, and a name matches when every keyword does.
The names are a container's, a listener's, a function's or a type's own name, a method's own name (not its
container's), a resource method's path and accessor (`repos/:owner/:repo/issues post`), and a readme section's
title, all read without Ballerina's escapes. It reads names only, never parameters or documentation (that is
`--filter`, inside a bucket). Matches are grouped by kind — clients, service types, classes, listeners,
functions, remote methods, resource methods, methods, types, readme sections — and paged as one sequence under
the 40-entry ceiling. No match is an answer (exit 0) pointing at the package's overview. `--filter <keyword>` on
the bare package runs the same search.

```
$ bal discover ballerinax/kafka search send
ballerinax/kafka:4.6.5 · search · send
2 remote methods

Remote methods
  Producer  send
  Producer  sendWithMetadata

Next: bal discover ballerinax/kafka client <container> <name>
```

`bal discover search <keyword> ...` asks Central: its package search (what `bal search` asks) and its symbol
search across every package, 10 packages and 25 symbols a page, each row with the `bal discover` command that
opens it, pinned to the version Central matched. Symbols must match every keyword (the query joins them with
Central's `AND`); packages are ranked on the keywords as typed, since Central's package search has no such
operator. The ranking is Central's, so the same keywords can rank differently as Central changes. `--page <n>`
turns both rankings at once; `shown` and `total` are reported for each. It is not cached.

A symbol row opens what it names in the bucket this tool lists it under: a method in its container's bucket, a
function under `funcs`, a declaration under `type`. A plain method of an object type opens the object
(`type SchemaClient`), since Central does not say whether that object is a client or a class; a row of a kind no
bucket lists (a `configurable`), or one missing the container or path its command needs, opens the package.

```
$ bal discover search kafka send
search · kafka send
8 packages, 33 symbols

Packages (8)
  ballerinax/kafka:4.6.6            Apache Kafka is a distributed event streaming platform used for high-performance data pipelines, streaming analytics, data integration, and mission-critical applications.
  wso2/choreo.sendemail:0.6.1       Demo connector to send an email.
  ...

Symbols (25 of 33)
  remote method  ballerinax/kafka:4.6.6 client Producer send
  remote method  ballerinax/kafka:4.6.6 client Producer sendWithMetadata
  error          thambaru/debezium_to_kafka:0.2.1 type KafkaPublishError
  remote method  xlibb/solace.semp:0.2.0 client Client getMsgVpnKafkaSenders
  ...

... 8 more (page 1 of 2)
Next: bal discover <row>
Next: bal discover search kafka send --page 2
```

## Output

Every answer is one structured result, rendered either as text or as JSON. The JSON is one line per answer —
no line breaks inside it, so a `head`/`tail` cut never splits one — and these are its shapes:

| Answer                               | JSON fields                                                                                                      |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| bare package (overview)              | `clients`, `serviceTypes`, `classes`, `functions` (each `entries` (`name`, `resources`, `remote`, `normal`, `command`), `shown`, `total`, `command`), `types` (`counts` per kind, `command`), `readme` (`sections`, `command`), `submodules` (`name`, `summary`, `command`) |
| several containers                   | `containers` (`name`, `resources` (resource methods, not paths), `remote`, `normal`, `listener`, `confirmed`, `unconfirmedReason`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, and in `service` `notAttachable` (`name`, `command`) with `notAttachableTotal` when some are on another page; `total` counts both lists |
| resource groups                      | `container`, `resources` (ending at this prefix; `path`, `accessors`, `commands`), `groups` (`name`, `count`, `command`), `counts` (`resources`, `groups`), `shown`, `total`, `page`, `pages`, `remaining`, `next` |
| resource paths                       | `container`, `resources` (`path`, `accessors`, `commands`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| methods of one call form             | `container`, `methods` (`name`, `command`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`)                 |
| more than one call form              | `container`, `resources` (`path`, `accessors`, `commands`), `remote` (`name`, `command`), `normal` (`name`, `command`), `counts` (`resources`, `remote`, `normal`), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| one callable                         | `container`, `kind`, `name` or `accessor` + `path`, `form` (`->`, `.` or `new`), `declaration`, `params` (`name`, `type`, `default`, `kind`, `description`), `returns`, `deprecated`, `types` (`name`, `declaration`), `omitted` (`name`, `command`), `foreign` (`name`, `module`, `version`, `command`), `create` (`code`, `command`), `callers` (`number`, `title`, `lines`, `command`), `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`), `page`, `pages`, `remaining`, `next` |
| type declarations                    | `sections` (`records`, `enums`, `errors`, `aliases`, `constants`, `variables`, `annotations`, `listeners`, `classes`, `clients`, `serviceTypes`, each a list of `name`, `command`), `counts` (per kind, every page included), `shown`, `total`, `page`, `pages`, `remaining`, `next`, `documented` (`name`, `command`, or for a resource `path`, `accessors`, `commands`) |
| one declaration                      | `name`, `kind`, `declaration`, `types` (`name`, `declaration`), `omitted` (`name`, `command`), `foreign` (`name`, `module`, `version`, `command`) |
| empty bucket                         | `bucket`, `total`, `elsewhere` (`bucket`, `count`, `command`)                                                       |
| one service type                     | `container`, `note`, `warning`; when the package describes the methods a service defines, `handlers` (`name`, `kind`, `signature`, `required`, `repeatable`, `accessors`, `doc`, `deprecated`, `params`, `returns`) and `rules` (`requirement`, `handlers`, `message`); when it declares none and describes none, `methods` (empty), `description`, `examples` (`number`, `title`, `lines`, `command`) and `next` (the readme) when no section declares one |
| search in a package                  | `keywords`, then per kind with a match `clients`, `serviceTypes`, `classes`, `listeners`, `functions` (`name`, `command`), `remote`, `methods` (`container`, `name`, `command`), `resources` (`container`, `path`, `accessor`, `command`), `types` (`name`, `kind`, `command`), `readme` (`number`, `title`, `command`); `counts` (per kind), `shown`, `total`, `page`, `pages`, `remaining`, `next`, and `overview` when nothing matched |
| search of Central                    | `keywords`, `packages` (`entries` (`qualified`, `version`, `summary`, `command`), `shown`, `total`), `symbols` (`entries` (`qualified`, `version`, `module`, `kind`, `container`, `name` or, for a resource method, `path` and `accessor`, `command`), `shown`, `total`), `page`, `pages`, `remaining`, `next`; it names no one package, so it has no `qualified` |
| readme                               | `readme`, `lines`, and `section`, `of`, `title` for one section                                                  |
| readme sections                      | `sections` (`number`, `title`, `lines`, `command`), `matching` (true when narrowed by `--filter` or a title), `shown`, `total`, `page`, `pages`, `remaining`, `next` |

Any answer can also carry `warning` (the version could not be confirmed against the registry), and a service
type's answers `note` (the listener it binds to, or that it attaches to none), as can an enum member's (the enum
it shows). `documented` lists entries a `--filter` matched
only in their documentation, alphabetically and shaped as their listing rows are — a method, function or type
with its `command`, a resource path once with every matching accessor's `commands` — held to the same ceiling
and paged with the same `--page`, with `documentedTotal` giving how many there are (in text, a `Mentioned only in their
documentation (40 of 102)` heading and a `Next:` line with the command's shape). Beside a listing they are
its last section, after every entry the filter matched by name; beside one signature, or as all a filter found,
they page on their own, and `next` turns that page — absent on the last page, as on every paged answer. A
`--filter` that matches nothing by name is still an answer (exit 0): the listing with no entries, and its
documentation-only matches if it has any.

### Order

Every listing is alphabetical: containers in a roster, service types under their listener (and the listeners
themselves), methods, resource paths and `type` declarations within their kind. The exception is a level of path
groups, which goes by the number of resource methods under each, largest first (alphabetical among equals), because it is
read to choose where to go next.

### The entry ceiling

No listing shows more than **40 entries**. Over that:

- resource paths selected by a path (or by nothing) **group** by their next literal path segment, up to four
  literal levels below the top; path parameters never form a group of their own, and a group carries the
  accessor the listing was narrowed by into its `command`;
- everything else **pages** with `--page <n>`: a roster of containers, a level of groups (the paths ending
  there first, then the groups, with `counts` giving both across every page), methods of one call form
  (alphabetically), resource paths that cannot be grouped — selected by `--filter`, or already four literal
  levels deep — and readme sections narrowed by `--filter`. Every page keeps the selector,
  the `--filter` and the `--module`;
- a container mixing call forms (resources with methods, or remote methods with normal ones) pages the same
  way, as one sequence — resource paths, then remote methods, then normal ones — so a page can end partway
  through one section and pick up the next; each page keeps the section headings (`resources`, `remote`,
  `normal` in JSON) for whatever it holds and omits the ones it holds nothing of, while `counts` (and the text
  header, `111 remote methods, 1 method`) gives every form's size across the whole listing, so a form
  that only appears on a later page is never hidden;
- documentation-only matches page as described above; a roster's `--filter` keeps a container whose own name
  matches (opened whole, since a container with no methods has nothing else to match) as well as one with a
  matching member (opened narrowed).

A cut listing always says so: `shown`/`total`, `page`/`pages`, `remaining` (everything after this page,
documentation-only matches included) and `next` (the `--page` command that continues it) in JSON, or a
`... N more (page P of Q)` line followed by `Next: <command>` in text, where `N` counts what comes after this page
(`... N more mention it only in their documentation (page P of Q)` when only those are paged, beside one signature or in
a listing with no entries of its own). In text, a listing that continues also ends with the command that narrows it by keyword,
`Next: bal discover <package> <bucket> --filter <keyword>`, which is usually shorter than paging.

### Paths

Path parameters print as `:name` (`repos/:owner/:repo`), which is safe unquoted in bash, zsh, fish and
PowerShell. A segment Ballerina has to escape prints plain (`gists/public` for `gists/'public`, `code-scanning`
for `code\-scanning`), so a printed path needs no shell quoting; the code an answer quotes keeps Ballerina's
spelling (`resource function get gists/'public(...)`). A selector is matched as it is printed, or in Ballerina's
escaped spelling of the same segment: case-sensitive, a parameter only as `:owner` (not `[owner]`, `{owner}` or
`owner`), and a path whole, from its first segment, in one argument joined by `/`.
A path that matches nothing fails, suggesting the listing under the deepest prefix it did match.
`new` addresses the constructor Ballerina spells `init`, unless the container has a resource path `new`. A member
name is one selector. On a container with
resource paths, two selectors that resolve as a path and its accessor, in either order, are read together and
win over a member of the same name; otherwise a first selector naming a method, function or `new` is read
alone, and any other pair is read as a path and an accessor that match nothing, which fails.
A selector beyond what the container reads is a `validation` failure naming the command without it, never
silently dropped.

## The contract

|        |                                                                                  |
| ------ | -------------------------------------------------------------------------------- |
| stdout | the answer, and nothing else                                                     |
| stderr | on failure, exactly one failure, and nothing else                                |
| exit 0 | success, and stdout is complete                                                  |
| exit 2 | a usage error — the command line itself is malformed, so nothing was looked up: stdout is empty |
| exit 1 | every other failure: stdout is empty, and the failure's `kind` and `suggestion` say what to do next |

A failure is written in the same mode as an answer would have been: JSON off a terminal, text at one, either
forced with `--output` (honoured even when the rest of the arguments fail to parse). In JSON it is one object
carrying `kind`, `message` (the one-sentence headline text mode prints after `error:`) and `suggestion`. Its
`qualified` is always `org/name` (an `upstream` or `timeout` failure carries it once the request was for a
package), and `version` (when one was reached) and `module` (when `--module` was passed) are keys of their own.
When the failure has one fix, the suggestion quotes your whole command with it applied, and `command` holds
that command, ready to run (`bal discover ballerinax/kafka client --version 4.6.5 Producer` is answered with
`bal discover ballerinax/kafka:4.6.5 client Producer`). When it has several, one per container that declares the
name, it carries no `command` and lists them in `owners` instead:

```
$ bal discover ballerinax/kafka client NoSuchContainer | cat
{"kind":"symbol-not-found","message":"'NoSuchContainer' is not a client in ballerinax/kafka:4.6.5.","qualified":"ballerinax/kafka","version":"4.6.5","requested":["NoSuchContainer"],"suggestion":"List the clients: `bal discover ballerinax/kafka client`.","command":"bal discover ballerinax/kafka client"}
```

In text it is `error: <message>`, then the suggestion and anything else the failure carries (schema issues, a
readme's sections, the owners) indented below it; a usage error then ends with the synopsis and a pointer to `--help`:

```
$ bal discover ballerina/http:2.15
error: '2.15' in 'ballerina/http:2.15' is not a complete version.
  Write it in full, ballerina/http:<major>.<minor>.<patch>, or drop it to read the version your project locks or Central's latest: ballerina/http
Usage: bal discover <org>/<name>[:<version>] [bucket] [name ...] [--output json|text] [--filter <keyword>] [--page <n>] [-m <module>] [--refresh]
Run `bal discover --help` for details.
```

A usage error is a `validation` failure found before anything is looked up: an unknown option, an option with
a missing or invalid value (`--output xml`, `--page x`, `--page 0`, `--refresh=yes`) or given twice, no package,
a malformed package name or incomplete version, `--version`, a version typed as a positional, an unknown bucket,
more than one name after `funcs` or `type`, a `type` name with `--filter`, or a `search` with no keyword, with
`--filter`, or (across Central) with `--module`. An unknown bucket's `command` searches the package for the
word, since it is most often a member typed without its bucket (`bal discover ballerinax/kafka close`). A
`validation` failure that
depends on what the package holds — an extra selector a container does not read, a page past the end of the
listing, a module with no readme — is exit 1.

A search that matches nothing is an answer, not a failure (exit 0). `upstream` and `timeout` are worth re-running unchanged once their cause has passed or been fixed; `validation`,
`package-not-found` and `symbol-not-found` need a different command — `symbol-not-found` is a container, member,
path or type not named exactly as listed, or asked in a bucket that does not hold it (a `--module` the package does not publish
is `package-not-found` with `module`, and lists the submodules it does in `candidates`; a name several containers
declare lists them in `owners`); `schema-drift` means Central's payload
changed shape and is for a maintainer; `internal` is a defect in `bal discover` itself, to report with the
command that produced it. An `upstream` failure's `message` names what failed: the host, or the
proxy and the `Settings.toml` that sets it. Its `reached` is `true` when Central answered, with an error status
(in `status`) or a body that is not JSON, and `false` when no answer from Central came back: the connection, DNS,
TLS or the proxy failed, and `status` is then the proxy's own answer when it gave one (407 when it wants a
username and password).

**Which version is read.** The one written in the coordinate (`ballerina/http:2.15.7`), even inside a project that
locks another. Otherwise, inside a Ballerina project the tool walks up to `Ballerina.toml` and uses the version
`Dependencies.toml` locks, so a lookup sees what `bal build` compiles against, and outside one (or for a package
the project does not depend on yet) it uses Central's latest. A written version must be complete SemVer, with no
leading `v` or zero: `ballerina/http:2.15` is a `validation` failure, and so is a version typed as a positional
(`bal discover ballerina/http 2.15.0`) or as `--version`, each suggesting the coordinate to type. A lock that
names no complete version, or one Central does not publish, is a failure naming the `Dependencies.toml` it was
read from; `bal build` regenerates the file when it is deleted. A version Central does not publish is answered
with the published versions nearest it. `--module` composes unchanged:
`bal discover ballerina/http:2.15.7 --module httpscerr`. A written version is carried into every command the
answer prints (`bal discover ballerina/http:2.15.7 client Client`), so drilling in stays on it; without one the
printed commands are unpinned (a locked version is resolved again), except one that opens a declaration another
package owns, which pins the version that declaration was generated against
(`bal discover ballerina/crypto:2.9.3 type TrustStore`).

## Proxy

Requests to Central go through the HTTP proxy `bal pull` uses: the `[proxy]` table of `~/.ballerina/Settings.toml`
(or of `$BALLERINA_HOME_DIR/Settings.toml` when that is set). A proxy needs `host` and `port`; `username` and
`password`, when both are set, answer the proxy's own authentication challenge, once per request: a rejected
password is a failure, never retried. With no such table, an empty `host` or no `port`, requests go direct. If
the `jdk.http.auth.tunneling.disabledSchemes` system property disables Basic, the credentials are never sent,
and the failure says so.

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
silently; it is never a failure. A search of Central is never cached, since its ranking changes as Central does.
The location is the first usable of:

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
