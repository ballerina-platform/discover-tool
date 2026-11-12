# Specification: Ballerina Discover Tool

_Owners_: @ThisaruGuruge  
_Reviewers_:  
_Created_: 2026/11/12  
_Updated_: 2026/11/12  
_Edition_: Swan Lake 2201.14.0  

## Introduction

This is the specification for `bal discover`, the Ballerina tool `ballerina/tool_discover`. It states the observable behaviour of the command: what it accepts, what it prints, how it fails, and what it reads and writes. The design and its rationale are in the [proposal](../proposals/initial-design.md); this document records the outcome.

This specification applies to the `bal discover` command of the Swan Lake edition. A conforming implementation MUST satisfy every rule in it. The command contract is independent of the Ballerina runtime, so the same commands, options and output shapes MUST hold on a later runtime.

### Notation

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when, and only when, they appear in all capitals. Sentences without these key words that state behaviour in the present tense are also normative. The overview, examples and notes are informative. Behaviour that is deliberately left open is marked _implementation-defined_. Command lines are shown as `bal discover ...`; `<...>` stands for a value and `[...]` for an optional part.

## Contents

- [1. Overview](#1-overview)
- [2. Terminology](#2-terminology)
- [3. Invocation](#3-invocation)
    - [3.1. Description](#31-description)
    - [3.2. Configuration](#32-configuration)
    - [3.3. Behaviour](#33-behaviour)
    - [3.4. Names and selectors](#34-names-and-selectors)
    - [3.5. Errors](#35-errors)
    - [3.6. Interactions](#36-interactions)
- [4. Version resolution](#4-version-resolution)
    - [4.1. Description](#41-description)
    - [4.2. Configuration](#42-configuration)
    - [4.3. Behaviour](#43-behaviour)
    - [4.4. Errors](#44-errors)
    - [4.5. Interactions](#45-interactions)
- [5. Package overview](#5-package-overview)
    - [5.1. Description](#51-description)
    - [5.2. Behaviour](#52-behaviour)
    - [5.3. Errors](#53-errors)
- [6. Callable nouns](#6-callable-nouns)
    - [6.1. Description](#61-description)
    - [6.2. Behaviour](#62-behaviour)
    - [6.3. Errors](#63-errors)
    - [6.4. Interactions](#64-interactions)
- [7. Methods](#7-methods)
    - [7.1. Description](#71-description)
    - [7.2. Behaviour](#72-behaviour)
    - [7.3. Errors](#73-errors)
- [8. Resource methods](#8-resource-methods)
    - [8.1. Description](#81-description)
    - [8.2. Behaviour](#82-behaviour)
    - [8.3. Errors](#83-errors)
- [9. Types](#9-types)
    - [9.1. Description](#91-description)
    - [9.2. Behaviour](#92-behaviour)
    - [9.3. Errors](#93-errors)
- [10. Readme](#10-readme)
    - [10.1. Description](#101-description)
    - [10.2. Behaviour](#102-behaviour)
    - [10.3. Errors](#103-errors)
- [11. Filtering](#11-filtering)
    - [11.1. Description](#111-description)
    - [11.2. Behaviour](#112-behaviour)
    - [11.3. Errors](#113-errors)
- [12. Pagination and the entry ceiling](#12-pagination-and-the-entry-ceiling)
    - [12.1. Description](#121-description)
    - [12.2. Configuration](#122-configuration)
    - [12.3. Behaviour](#123-behaviour)
    - [12.4. Interactions](#124-interactions)
- [13. Search](#13-search)
    - [13.1. Description](#131-description)
    - [13.2. Behaviour](#132-behaviour)
    - [13.3. Errors](#133-errors)
- [14. Modules](#14-modules)
    - [14.1. Description](#141-description)
    - [14.2. Behaviour](#142-behaviour)
    - [14.3. Errors](#143-errors)
- [15. Output](#15-output)
    - [15.1. Description](#151-description)
    - [15.2. Behaviour](#152-behaviour)
    - [15.3. Interactions](#153-interactions)
- [16. Errors and exit codes](#16-errors-and-exit-codes)
    - [16.1. Description](#161-description)
    - [16.2. Configuration](#162-configuration)
    - [16.3. Behaviour](#163-behaviour)
- [17. Caching](#17-caching)
    - [17.1. Description](#171-description)
    - [17.2. Configuration](#172-configuration)
    - [17.3. Behaviour](#173-behaviour)
- [18. Repositories and network](#18-repositories-and-network)
    - [18.1. Description](#181-description)
    - [18.2. Configuration](#182-configuration)
    - [18.3. Behaviour](#183-behaviour)
- [19. Security considerations](#19-security-considerations)
- [References](#references)

## 1. Overview

`bal discover` reads a Ballerina package's public API from a package repository and prints it, so that an agent or a developer can learn what a library offers without searching the web. The only repository in this edition is Ballerina Central.

The command is noun-first. The first word names the kind of thing that comes back, a name after it says which one, and `-p` says which package. Every answer is one structured result that is rendered either as text for a person or as one line of JSON for a program.

The tool never calls a model, never writes to the project it is run in, and never executes the library it describes.

## 2. Terminology

- **Package**: a unit published to a repository, named `<org>/<name>`. The name may contain dots (`ballerinax/googleapis.gmail`) and is always resolved as a whole.
- **Module**: a namespace within a package. The **default module** carries the package name; a **submodule** is another module of the same package, selected with `--module`.
- **Noun**: the first word of a command that is not a package coordinate: `package`, `search`, `client`, `service`, `class`, `function`, `type`, `method`, `resource` or `readme`.
- **Container**: an object type that has members: a client, a service type or a class. A container flag names one.
- **Client**: an object type whose members are called with `->`.
- **Service type**: a `service object` type.
- **Class**: a plain object type whose members are called with `.` and that is neither a client nor a service type.
- **Listener**: the declaration a service is attached to. A listener is opened with `type`, never with `service`.
- **Resource method**: a member called as `client->path.accessor(...)`. A **path** and an **accessor** together address one resource method.
- **Remote method**: a member called as `client->name(...)`.
- **Method**: a member called as `object.name(...)`, and the generic word for any callable member of a container. The constructor is the method named `init`.
- **Function**: a module-level callable.
- **Listing**: an answer that lists several entries, each of which opens one thing.
- **Leaf**: an answer about one declaration: one callable or one type.
- **Entry**: one row of a listing.
- **Ceiling**: the largest number of entries a listing shows at once (see [Pagination and the entry ceiling](#12-pagination-and-the-entry-ceiling)).
- **Answer**: the structured result of a successful command.
- **Failure**: the structured result of a command that cannot answer.
- **Coordinate**: a package name with an optional version, `<org>/<name>[:<version>]`, in the form `bal pull` accepts.
- **Printed command**: a command line that an answer or a failure prints for the caller to run next.

## 3. Invocation

### 3.1. Description

The command line names a noun, at most one name, the package, and options.

### 3.2. Configuration

```
bal discover <noun> [<name>] -p <org>/<name>[:<version>] [options]
bal discover package <org>/<name>[:<version>] [options]
bal discover <org>/<name>[:<version>] [options]
bal discover search "<query>" [-p <org>/<name>[:<version>]] [options]
```

| Option                                    | Type                | Default                     | Meaning                                                                                                                                                       |
|-------------------------------------------|---------------------|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `-p`, `--package`                         | coordinate          | Required, except on `package` and `search` | The package to read. On `search` it restricts the search to that package. Not accepted by `package`, which takes the coordinate as its name.   |
| `--client`, `--class`, `--service`        | name                | absent                      | The container that declares the method or resource asked for. Accepted by `method` and `resource` only. At most one of the three may be given.                |
| `--accessor`                              | accessor            | absent                      | The accessor of the resource method asked for. Accepted by `resource` only.                                                                                   |
| `--module`                                | module name         | absent                      | Reads a submodule instead of the default module. It has no short form.                                                                                        |
| `--filter`                                | keyword             | absent                      | Narrows a listing (see [Filtering](#11-filtering)). Not accepted by `search`.                                                                                 |
| `--page`                                  | integer, 1 or more  | `1`                         | The page of a paged listing to show.                                                                                                                          |
| `--output-format`                         | `json` or `text`    | `text` on a terminal, otherwise `json` | The renderer.                                                                                                                                      |
| `--refresh`                               | switch              | off                         | Fetches again instead of reading the cache. It takes no value.                                                                                                |
| `-V`, `--version`                         | switch              | off                         | Prints the tool's own version and exits with 0. Accepted on the root command only.                                                                            |
| `-h`, `--help`                            | switch              | off                         | Prints help for the root command, or for the noun when one is given, and exits with 0.                                                                        |

### 3.3. Behaviour

1. An option MAY be written before or after the noun.
2. Every noun takes at most one positional value, its name: the coordinate after `package`, a container name after `client`, `service` or `class`, a method name after `method` or `function`, a resource path after `resource`, a type name after `type`, a readme section after `readme`, and a query after `search`. The noun is not a positional value.
3. A noun given with no name answers with a listing, and a noun given a name answers about that one thing.
4. `bal discover <org>/<name>[:<version>]` is the same command as `bal discover package <org>/<name>[:<version>]`, with the same answer, exit code and options. It is the only form in which the noun may be omitted.
5. With no arguments, the tool prints the root help and exits with 0.
6. A printed command writes its words in this order: the noun, its name, `-p`, the container flag, `--accessor`, then the other options. A printed command always names the container of a method or resource.
7. A value that is the same in a printed command as in the command that produced it, such as `--module` or a version written in the coordinate, is kept in the printed command.
8. A name is matched exactly as spelled, and case-sensitively. No name is matched approximately, and no answer is ever chosen for the caller from a near miss.
9. A name that is typed where a noun is expected, or a noun that is a plural or a former spelling (`funcs`, `methods`), fails with the command that was meant, when one exists (see [Errors and exit codes](#16-errors-and-exit-codes)).
10. The command line is validated before any repository is contacted. A check that needs the package itself, such as whether a name is ambiguous, is made once the package is read.

### 3.4. Names and selectors

The noun fixes what a name means. After `client` a name is a client, after `method` a method, and after `resource` a path.

1. A name that does not exist exactly as typed fails with kind `symbol-not-found`. When the level the lookup reached holds 40 names or fewer, the failure lists every name at that level as `candidates`, in the order the listing uses. Otherwise it carries the command that lists that level. The failure never lists near matches.
2. A name that exists but in another noun fails with kind `symbol-not-found`, and its printed command is the command in the noun that holds it.
3. A name that several containers declare fails with kind `ambiguous`, and lists each owner with its command. A container flag resolves it. A container flag is optional when exactly one container declares the name. A container flag that names a container of another kind fails with the command that has the right one.
4. A path is typed whole, from its first segment, as one argument whose segments are joined by `/`. A path parameter is typed `:name` exactly as printed. A segment that Ballerina escapes is typed plain (`gists/public`) or in its escaped spelling (`gists/'public`). A dot is part of a segment and is never a separator. The root path is `.`, and a rest parameter is `:...path`. A path that leaves out its parameter segments, names a segment deep inside a path, or is split across two arguments does not match.
5. `init` names the constructor of a container.
6. A rest-parameter resource method is a calling shorthand and is not an addressable path: it is listed as `:...path` with its accessors, and the method of the same name is opened with `method`.
7. `--accessor` is strict. It names one method of the path given, never filters a listing, and an accessor the path does not declare fails with kind `symbol-not-found`, listing the accessors of the path as `candidates`.
8. More names than a noun takes fail with kind `usage` and print the command that reads them, when one command does. `method Producer send` prints `method send --client Producer`, `resource gists/public get` prints `resource gists/public --accessor get` when `get` is an accessor of the path, and `search kafka send` prints `search "kafka send"`. When no one command reads them, the failure carries no printed command.

### 3.5. Errors

| Condition                                                                                                                                                                            | Failure kind |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| An unknown option, including `-m` and `--version` after a noun; an option without a required value, or with an invalid one; `--refresh` with a value; an option given twice, or before and after the noun | `usage`      |
| No noun, an unknown noun, a malformed coordinate, an incomplete version, or a version typed as a name                                                                                | `usage`      |
| More names than the noun takes, more than one container flag, `--filter` together with a type name, `--accessor` with no path or on a noun other than `resource`                      | `usage`      |
| `--module` with a value that is not a valid module name, or `--module` on `search` across Central                                                                                    | `usage`      |
| `-p` on `package`, a package typed as a name, and the former package-first form (`bal discover ballerinax/kafka client Producer`)                                                       | `usage`      |

### 3.6. Interactions

`--output-format` is honoured even when the rest of the command line is malformed, so that a failure is rendered in the mode the caller asked for.

### 3.7. Example: A command and its pieces

`bal discover method send -p ballerinax/kafka --client Producer` names the noun `method`, the name `send`, the package `ballerinax/kafka` and the container `Producer`. It answers with the signature of the remote method `send` of `Producer`.

### 3.8. Example: The former package-first form

`bal discover ballerinax/kafka client Producer` is rejected with kind `usage` and the printed command `bal discover client Producer -p ballerinax/kafka`, by rule 9 of [Behaviour](#33-behaviour).

## 4. Version resolution

### 4.1. Description

The version of the package to read is decided once per command.

### 4.2. Configuration

A version is written after a colon in the coordinate (`ballerina/http:2.15.7`). It MUST be a complete semantic version, and MUST NOT start with `v`. A version in a coordinate applies to the package, not to the module.

### 4.3. Behaviour

1. A version written in the coordinate is read exactly, even in a project that locks another version.
2. Otherwise, when the current directory holds `Ballerina.toml`, the version that `Dependencies.toml` in that directory locks for the package is read. No parent directory is searched.
3. Otherwise, the latest version Central publishes is read. This also applies outside a project, in a project with no `Dependencies.toml`, and for a package the project does not depend on.
4. The latest version is remembered for 10 minutes.
5. A version written in the coordinate is carried into every command an answer prints. Without one, printed commands carry no version, with one exception: a command that opens a declaration owned by another package carries the version that declaration was generated against.
6. Every answer carries the version it was read at (`version` in JSON, and the header in text).

### 4.4. Errors

| Condition                                                                         | Failure kind        |
|-----------------------------------------------------------------------------------|---------------------|
| A written version is incomplete (`ballerina/http:2.15`)                           | `usage`             |
| A locked version is not a complete version                                        | `invalid-lock`      |
| A written or locked version that Central does not publish                         | `package-not-found` |

A `package-not-found` failure for a version names the published versions nearest to it.

### 4.5. Interactions

`--module` composes with a version unchanged (`bal discover package ballerina/http:2.15.7 --module httpscerr`).

## 5. Package overview

### 5.1. Description

The `package` noun answers with what a package offers, so that one call replaces a drill-down through each noun.

### 5.2. Behaviour

1. The answer lists the clients, service types, classes and functions of the module, each with the sizes of its members, then one line for the types by kind, one line for the readme sections, and the submodules of the package.
2. A section holds at most 10 rows. When the rows of all sections still exceed 40, the longest section gives up rows until the total is 40. A section that is cut carries the command that lists all of it.
3. The rows of each section are ordered by how often the readme of the module uses each name, most used first, then alphabetically. A name is used when it appears as a whole identifier in a code block or in inline code. Words in prose are not counted.
4. When the module has exactly one client, the answer opens that client in place, under its row, with the first page of what `client <name>` answers.
5. A count of members counts methods, never paths: a resource method is counted once per path and accessor.
6. Submodules are listed apart, each with its bare name, a one-line summary and the command that reads it. Under `--module <name>`, only the children of that module are listed, never its siblings.
7. `--filter <keyword>` on `package` answers as `search "<keyword>" -p <package>` does.

### 5.3. Errors

A coordinate that does not resolve fails with kind `package-not-found`. A coordinate whose name is the dotted name of a module of a shorter package, where the longer name is not a package, fails with kind `package-not-found` and prints the `--module` command that reads it.

## 6. Callable nouns

### 6.1. Description

`client`, `service`, `class` and `function` list the callable surface of a module. A symbol belongs to a noun according to how it is called.

### 6.2. Behaviour

1. A symbol's noun is derived from the form of its declaration. It is never taken from role flags in the repository's own metadata.
2. `client [<name>]` lists the clients, or opens one client. An opened client lists its resource paths, remote methods and methods. When a client has more than 40 entries in all, they page as one sequence: resource paths, then remote methods, then methods, with the size of each form given across all pages.
3. `class [<name>]` lists and opens the classes of the module that are neither clients nor service types nor listeners.
4. `function [<name>]` lists the functions of the module, or opens one.
5. `service [<name>]` lists the service types, each under the listener it can be attached to. A service type that no listener accepts is listed apart, under the heading _Not attachable to a listener_ (`notAttachable` in JSON). Opening such a type says that it is not attachable and, when a signature in the package names it, how it is used.
6. A service type is bound to a listener when it is the type that the `attach` method of the listener takes as its first parameter, resolved through unions and type aliases, or when it includes that type. The binding is derived from the package source when the package listing alone cannot settle it. The source is read from the local Ballerina home repository, then from the distribution, then from the bala that Central publishes.
7. A binding that could not be settled is listed under the candidate listener with `confirmed` false in JSON and the reason in `unconfirmedReason`, and opening the type says why. A binding is never guessed silently.
8. A listener is not a service type and is not listed by `service`. It is listed and opened under `type`.
9. When the package describes the methods that a service defines, opening a service type lists them under _Methods to implement_, each as the declaration a service would write, marked `required`, `optional` or `any number`, with its documentation and one line per parameter. The description is read from `metadata/trigger-metadata.json` in the package. The reader accepts versions `1.x` of the file, ignores fields it does not know, and refuses version `2` and later. A service type whose types cannot be written down lists no methods. A service type that declares its own methods lists those. Nothing is invented for a package that describes none.
10. A noun that the package has nothing in answers with exit code 0, says that it is empty, and lists under _Elsewhere in the package_ (`elsewhere` in JSON) the nouns that hold something, each with its count and command.

### 6.3. Errors

| Condition                                                                        | Failure kind       |
|----------------------------------------------------------------------------------|--------------------|
| A name that is not a container of the noun's kind                                | `symbol-not-found` |
| A name that is a container of another kind, such as `client Response` for a class | `symbol-not-found` |

### 6.4. Interactions

`client`, `service` and `class` open only their own kind, whereas `type` opens any type (see [Types](#9-types)).

### 6.5. Example: An empty noun

`bal discover function -p ballerinax/googleapis.gmail` answers with exit code 0 that the package has no functions, and lists `client` and `type` with their counts under _Elsewhere in the package_.

## 7. Methods

### 7.1. Description

`method` lists the remote and plain methods of a container, or opens one.

### 7.2. Behaviour

1. `method` with no name lists the methods of the container named by the container flag. Without a flag, it answers when exactly one container declares methods. When several do, it fails with kind `ambiguous` and lists each container with its command. When none does, it fails with kind `usage` and prints the command of the noun that holds the callables, such as `client`.
2. `method <name>` opens the method of that name. The answer is the declaration exactly as the library's source states it, including its doc comment, followed by these parts, in this order, each only when it applies:
    1. _Create it_: the line that constructs the container, the signature of `init` and the command that opens it.
    2. _Types it uses_: the declarations of the types the callable names, one level deep.
    3. _Other types it uses, not printed_: every such type that is not printed, each with the command that opens it.
    4. _Readme sections that call it_: the readme sections whose code calls the method.
3. The parameter types of the callable are always printed in full.
4. The return type and the types beneath it are printed only while the printed total stays within 6 KB. A class or object type that the callable names is never printed, because its declaration is a drill-down of its own.
5. Every type the callable names that is not printed is listed by name with its command. The list is never cut.
6. A declaration is never cut, however many fields its types carry.
7. A type declared by another package is spelled module-qualified, is listed under `foreign` with its module, its version and its command, and its command carries the version the signature was generated against. A command is printed only when the package that declares the type is known exactly.

### 7.3. Errors

A method that the container does not declare fails with kind `symbol-not-found`. A method name that several containers declare fails with kind `ambiguous` and lists each owner with its command.

### 7.4. Example: Name in several containers

`bal discover method commit -p ballerinax/kafka` fails with kind `ambiguous` because both `Caller` and `Consumer` declare `commit`. The failure prints `bal discover method commit -p ballerinax/kafka --client Caller` and the same command for `Consumer`, and the exit code is 2.

## 8. Resource methods

### 8.1. Description

`resource` lists the resource paths of a container, groups them when there are many, or opens one resource method.

### 8.2. Behaviour

1. A resource path is one entry however many accessors it has. An entry carries its accessors, and, in JSON, a `commands` object with one command per accessor, for every resource entry including one that has a single accessor.
2. `resource` with no path lists the paths of the container. When a listing selected by a path (or by nothing) holds more than 40 entries, the paths are grouped by their next literal path segment. Path parameters never form a group on their own, and a group's name is the real path prefix, parameters included.
3. A group is subdivided again only when it would itself exceed the ceiling. Subdivision goes at most 4 literal levels below the top-level segment. A listing that still exceeds the ceiling at that depth stops grouping and pages.
4. A level of groups lists first the resource paths that end at that level, under _Paths at this level_, and then the groups, under _Path groups (resource methods in each)_. Groups are ordered by the number of resource methods in each, largest first, and alphabetically among equals. The count of a group is a number of resource methods and not of paths.
5. `resource <path>` lists the path together with every resource path under it. When the path has exactly one accessor and no path beneath it, the answer is the signature of that resource method, as stated for a method in rule 2 of [Methods](#72-behaviour).
6. `resource <path> --accessor <accessor>` opens the resource method of that path with that accessor, as a signature.
7. Path parameters print as `:name`, without their types. A segment that Ballerina escapes prints plain. The code that an answer quotes keeps the spelling of Ballerina.
8. `--filter` on a resource listing matches paths, accessors, parameter names and parameter types, so that `--filter get` narrows a listing to the `get` methods.

### 8.3. Errors

| Condition                                                    | Failure kind       |
|--------------------------------------------------------------|--------------------|
| A path that does not exist as typed                          | `symbol-not-found` |
| An accessor the path does not declare                        | `symbol-not-found` |
| `--accessor` with no path                                    | `usage`            |

A path that does not exist lists the paths under the deepest prefix of it that did match as `candidates`.

### 8.4. Example: One accessor answers directly

`bal discover resource gists/public -p ballerinax/github` answers with the signature of `get`, because `gists/public` has only that accessor and no path beneath it.

### 8.5. Example: Several accessors

`bal discover resource gists/:gistId/star -p ballerinax/github` lists the path with the accessors `get`, `put` and `delete`, and prints the command for each. `--accessor put` opens the signature of `put`.

## 9. Types

### 9.1. Description

`type` lists every type that a module declares, or opens one declaration.

### 9.2. Behaviour

1. The listing includes records, enums, error types, type aliases, constants, module-level variables, annotations and listeners, and also the classes, clients and service types.
2. The listing is grouped into sections by kind, alphabetical within each. The listing is held to the ceiling and pages. The size of every section is given across all pages.
3. `type <name>` opens any type of that name. A class, client or service type gives the same answer as its own noun gives. A listener's answer is its declaration, including `init` and its other methods.
4. An enum member is a type. Opening it shows the enum that declares it, with a note that names the enum.
5. The declaration is verbatim and is never cut. The declarations of the types it uses are printed one level deep, within the limit of rule 4 of [Methods](#72-behaviour), and an inclusion (`*T`) does not count against that depth.
6. Every used type that is not printed is listed with its command, and so is every type of another package, as stated in rule 7 of [Methods](#72-behaviour).
7. A name that no type has fails with kind `symbol-not-found`. It lists the types as `candidates` when there are 40 or fewer, and otherwise carries the listing command.

### 9.3. Errors

`--filter` together with a type name fails with kind `usage`.

### 9.4. Example: An enum member

`bal discover type SSL -p ballerinax/kafka` prints the declaration of the enum `Protocol` and a note that `SSL` is a member of it.

## 10. Readme

### 10.1. Description

`readme` prints the readme of a module, or one of its sections.

### 10.2. Behaviour

1. The readme is the text that Central serves as the description of the module. It is printed verbatim.
2. A bare `readme` of at most 10 KB (10,240 bytes) is printed whole.
3. A bare `readme` of more than 10 KB lists its sections: each with its number, title, line count and the command that opens it. Every section is listed and the list is never cut at the ceiling. There is no option that forces the whole text.
4. A section is any part of the readme delimited by headings, whether or not it holds code.
5. `readme <n>` and `readme "<title>"` print one section, by its number or its exact title.
6. `readme --filter <keyword>` lists the sections whose text contains the keyword, each with its command. The list pages under the ceiling. A keyword that matches no section is an answer with exit code 0.
7. `--module` selects the readme of a submodule.

### 10.3. Errors

| Condition                                                           | Failure kind       |
|---------------------------------------------------------------------|--------------------|
| A section number or title that does not exist                       | `symbol-not-found` |
| A module that publishes no readme                                   | `symbol-not-found` |
| A section asked for with a `--filter` keyword it does not mention  | `filter-no-match`  |

A `filter-no-match` failure prints the command that reads the section without `--filter`.

## 11. Filtering

### 11.1. Description

`--filter <keyword>` narrows a listing without changing what is listed.

### 11.2. Behaviour

1. A keyword is matched as a case-insensitive substring of the name of an entry, its resource path or accessor, and the names and types of its parameters.
2. The keyword is one word, and no query syntax is interpreted.
3. An entry that matches only in its documentation is not dropped. It is listed after every other entry, under the heading _Mentioned only in their documentation_ (`documented`, with `documentedTotal`, in JSON). That list is held to the ceiling and pages like any other.
4. In a listing of containers, a container whose own name matches is kept whole, and a container with a matching member is kept narrowed.
5. A keyword that matches nothing is an answer, with exit code 0.
6. Filtering is done by the tool after the package data has been read. The keyword is never sent to a repository.

### 11.3. Errors

`--filter` together with the name of one declaration fails with kind `usage`.

### 11.4. Example: Filtering remote methods

`bal discover client --filter report -p ballerinax/salesforce` lists the remote methods of `Client` whose names contain `report`.

## 12. Pagination and the entry ceiling

### 12.1. Description

A listing shows at most 40 entries at a time and always says when it is incomplete.

### 12.2. Configuration

| Value             | Meaning                                                                                                                         |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------|
| Entry ceiling `40` | The largest number of entries of a listing.                                                                                    |
| `--page <n>`      | The page to show, from `1`. A page past the last is a failure, and `0` is a usage error.                                         |

### 12.3. Behaviour

1. A listing never shows more than 40 entries. A listing over the ceiling is grouped when it can be (see [Resource methods](#82-behaviour)), and otherwise pages.
2. Every page keeps the names, `--filter` and `--module` of the command.
3. A cut listing says so. In JSON it carries `shown` and `total`, and, when paged, `page`, `pages`, `remaining` and `next`. In text it ends with a line `... N more (page P of Q)`, followed by `Next:` lines.
4. `remaining` counts every entry after the page, including entries that match only in their documentation.
5. `next` is exactly one runnable command that turns the page. It is absent on the last page. A placeholder such as `<name>` never appears in JSON.
6. A leaf is never cut and never paged, except that the lists of documentation-only matches beside it page on their own.
7. A page past the end fails with kind `page-not-found`, naming the last page and printing its command. `--page 0`, and `--page` on an answer that is never paged, fail with kind `usage`.
8. Listings are ordered alphabetically, except a level of path groups (see [Resource methods](#82-behaviour)) and the sections of a package overview (see [Package overview](#52-behaviour)).

### 12.4. Interactions

`--filter` is applied before paging, so a page of a filtered listing counts only filtered entries.

## 13. Search

### 13.1. Description

`search` finds a package or a name. It is the only noun that is not about one chosen package.

### 13.2. Behaviour

1. The query is the one name of the noun. A query of several keywords is written as one quoted value.
2. `search "<query>" -p <package>` finds names inside one package, locally, with nothing sent to a repository. Each keyword is matched as a case-insensitive substring, and a name matches when every keyword does.
3. The names searched are the names of containers, listeners, functions and types, the names of methods but not of their containers, the paths and accessors of resource methods, the titles of readme sections, and the names of submodules. Parameters and documentation are not searched.
4. The matches are grouped by kind and page as one sequence under the ceiling. No match is an answer, with exit code 0, and points at the overview of the package.
5. `search "<query>"` without `-p` asks Central for its package search and its symbol search. It shows 10 packages and 25 symbols a page. Symbols must match every keyword. Packages are ranked as Central ranks them. Each row carries the command that opens it, pinned to the version Central matched. The result is not cached.
6. The tool does not rank or recommend beyond what Central returns.

### 13.3. Errors

`search` with no query, with an unquoted query of several keywords, with `--filter`, or, across Central, with `--module`, fails with kind `usage`.

## 14. Modules

### 14.1. Description

`--module <name>` targets a submodule of a package.

### 14.2. Behaviour

1. The coordinate is always resolved as one complete package name, dots included. No other reading of it is tried.
2. `--module` applies to every noun, `type` and `readme` included.
3. A module name MUST be a valid Ballerina module name, and an invalid name is a usage error before anything is fetched.
4. The page of a submodule is read at the resolved version of the package, and accepted only when the repository marks it as a non-default module of that package.
5. The submodules of a package are those the repository names for its default module.
6. Every printed command under `--module` keeps `--module`.

### 14.3. Errors

| Condition                                              | Failure kind        |
|--------------------------------------------------------|---------------------|
| A module the package does not publish                  | `package-not-found` |

The failure lists the submodules the package does publish as `candidates`, with no printed command, since another submodule or none both fix it. A package that has no submodules prints the same command without `--module`.

### 14.4. Example: A module typed as a package

`bal discover ballerina/graphql.dataloader` fails with kind `package-not-found` and prints `bal discover package ballerina/graphql --module dataloader`.

## 15. Output

### 15.1. Description

Every answer is one structured result rendered as JSON or as text.

### 15.2. Behaviour

1. The renderer is JSON when standard output is not a terminal, and text when it is. `--output-format` overrides either.
2. Standard output holds the answer and nothing else. A warning and a failure go to standard error.
3. Every JSON answer is exactly one line, with no line break inside it. A text answer is never in JSON, and the reverse.
4. Every answer carries `qualified` (as `org/name`) and `version`, except a search across Central, which names no package. The header of a text answer names the same: `org/name:version · noun · container · name`.
5. Every row that opens one thing carries `command`. Every resource row carries `commands`, keyed by accessor.
6. A text answer is a header, a count line, entries in aligned columns under section headings, then a footer made of notes, the continuation line of a cut listing, and `Next:` lines. When every row's command has one shape, one `Next:` line prints it with a placeholder (`<name>`, `<group>`, `<path>`, `<accessor>`, `<query>`).
7. Any answer may carry `note`.
8. The same query gives the same output for the same repository content. No output depends on a model, on time, or on the order in which a repository returns entries.

### 15.3. Interactions

The answers differ in their JSON fields. The list of shapes and their fields is kept in the README of the tool, under _Output_, and a change to a field is a change to this specification.

### 15.4. Example: One query, two renderers

`bal discover client -p ballerinax/kafka` on a terminal prints the three clients as a table. The same command piped to a file prints one JSON line starting `{"qualified":"ballerinax/kafka","version":...`.

## 16. Errors and exit codes

### 16.1. Description

A command that cannot answer ends with one failure and an exit code.

### 16.2. Configuration

| Failure kind        | Core kind | Exit code | Retry helps | Meaning                                                                                               |
|---------------------|-----------|-----------|-------------|-------------------------------------------------------------------------------------------------------|
| `usage`             | `usage`   | 2         | no          | The command is malformed or asks for what cannot be combined.                                         |
| `ambiguous`         | `usage`   | 2         | no          | A name matches more than one thing.                                                                   |
| `package-not-found` | `runtime` | 1         | no          | The repository publishes no such package, version or module.                                          |
| `symbol-not-found`  | `runtime` | 1         | no          | No name in the package matches.                                                                       |
| `page-not-found`    | `runtime` | 1         | no          | A page past the end of a paged listing.                                                               |
| `filter-no-match`   | `runtime` | 1         | no          | A readme section asked with a `--filter` keyword it does not mention.                                 |
| `invalid-lock`      | `runtime` | 1         | no          | `Dependencies.toml` locks the package at a version that is not complete.                              |
| `unavailable`       | `runtime` | 1         | yes         | No answer came back, or the repository or the proxy was throttling or overloaded.                     |
| `timeout`           | `runtime` | 1         | yes         | The repository did not answer in time.                                                                |
| `upstream`          | `runtime` | 1         | no          | The repository or the proxy refused the request, or answered with a body that is not JSON.            |
| `schema-drift`      | `runtime` | 1         | no          | The repository answered with a shape the tool does not read.                                          |
| `internal`          | `runtime` | 1         | no          | A defect in the tool.                                                                                 |

### 16.3. Behaviour

1. The exit code is `0` for an answer, `2` for a usage failure, `1` for every other failure, and `130` when the command is interrupted. The tool never exits with `3` or `4`.
2. On failure standard output is empty and standard error holds exactly one failure.
3. A failure is rendered in the mode that an answer of the same run would have used, chosen once per run.
4. A JSON failure is one object carrying `"severity":"error"`, `kind`, `message` and `suggestion`. It carries `qualified` as `org/name` when the failure concerns a package, `version` when one was reached, and `module` when `--module` was given.
5. A text failure is `bal discover: error: <message>`, then the suggestion indented beneath it, and `Try: <command>` beneath that when one command fixes it.
6. A message states what went wrong in one line, begins in lowercase (except that a message that starts with the name Central keeps its capital) and has no closing period.
7. When one fix applies, a JSON failure carries the whole command of the caller with the fix applied as `command`, and a text failure prints it as the `Try:` line.
8. A usage failure found before anything is fetched is followed in text by a one-line usage synopsis and a pointer to `--help`. The JSON object is unchanged.
9. A name that matches nothing lists the names of its level in `candidates` as stated in [Names and selectors](#34-names-and-selectors). A name that matches several things carries no `command` and lists them in `owners`.
10. An `unavailable`, `upstream` or `timeout` failure names what failed: the host, or the proxy and the `Settings.toml` that sets it. `reached` is `true` when the repository answered, with an error status in `status` or a body that is not JSON, and `false` when no answer came back.
11. A warning never changes the exit code and is never part of an answer. It is a report of its own on standard error, `{"severity":"warning","kind":"stale-cache","message":...}` in JSON, and `bal discover: warning: <message>` in text. The only warning is `stale-cache`: the repository could not be reached to confirm the version, so the cached copy was read.
12. A miss is a failure. An empty `--filter`, an empty search and an empty noun are answers.
13. Only `unavailable` and `timeout` say that running the same command again may succeed.

### 16.4. Example: A miss with candidates

`bal discover client NoSuch -p ballerinax/kafka` exits with 1, kind `symbol-not-found`, and lists `Caller`, `Consumer` and `Producer` as candidates.

### 16.5. Example: Page out of range

`bal discover client --page 9 -p ballerinax/twilio` exits with 1, kind `page-not-found`, and prints the command for page 5, which is the last.

## 17. Caching

### 17.1. Description

The tool keeps a disk cache of what it reads from a repository.

### 17.2. Configuration

| Setting                          | Meaning                                                                                  |
|----------------------------------|------------------------------------------------------------------------------------------|
| `BAL_DISCOVER_CACHE=off`         | Disables caching.                                                                        |
| `BAL_DISCOVER_CACHE_DIR=<dir>`   | The cache directory.                                                                     |
| `XDG_CACHE_HOME`                 | When set, the cache directory is `$XDG_CACHE_HOME/bal-discover`.                         |
| default                          | `~/.cache/bal-discover`, then `<tmpdir>/bal-discover-<user>`.                            |

The location is the first usable one in the order of the table. Caching is off when none is usable.

### 17.3. Behaviour

1. Three things are cached, each keyed by package coordinates and version: the documentation payload of the package, the answer derived from the package source about its service types, and the `metadata/trigger-metadata.json` of the package, or the fact that there is none. A package archive is never cached.
2. A write is atomic. A crash never leaves a corrupt entry.
3. A cache that is missing, unreadable, corrupt or unwritable is never a failure: the tool reads from the repository.
4. `--refresh` ignores the three cached values and reads again. A cached value is replaced only by a read that succeeded.
5. A search across Central is never cached.
6. When the repository cannot be reached to confirm a version and a cached copy exists, the tool reads the copy and reports a `stale-cache` warning.

## 18. Repositories and network

### 18.1. Description

The tool reads packages through one repository interface. Ballerina Central is its only implementation in this edition.

### 18.2. Configuration

| Setting                                         | Meaning                                                                                       |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `[proxy]` in `~/.ballerina/Settings.toml`       | `host`, `port`, and optionally `username` and `password` of the HTTP proxy.                   |
| `BALLERINA_HOME_DIR`                            | When set, `Settings.toml` is read from this directory instead.                                |

### 18.3. Behaviour

1. Requests go through the proxy that `bal pull` uses. A proxy needs `host` and `port`. With no `[proxy]` table, an empty `host`, or no `port`, requests go direct.
2. When `username` and `password` are both set, they answer the authentication challenge of the proxy once per request. A rejected password is a failure and is never retried.
3. When the Java system property `jdk.http.auth.tunneling.disabledSchemes` disables Basic authentication, the credentials are never sent, and the failure says so.
4. A request is bounded in time and a failed request may be retried. The limits are implementation-defined.
5. A bala that is read for service types or their methods is limited in size. A bala over the limit is treated as unreachable.
6. Selecting another repository, and authenticating to one, through `Settings.toml` is not supported in this edition. Only `[proxy]` is read.

## 19. Security considerations

1. The tool only reads. It never writes to the project it is run in, never executes the code of a library, and never executes a command that it prints.
2. The tool does not include credentials in a request except the proxy credentials of `Settings.toml`, which go to the proxy and never to Central. Credentials are never printed in an answer or a failure.
3. A package archive read for source facts is downloaded over HTTPS from the repository and read as data. The size of the archive, of one source file and of the files read from it is bounded, so that an oversized or malformed archive cannot exhaust memory or disk.
4. A cache directory that is a symbolic link, or that belongs to another user (checked where the file system reports owners), is not used, and the cache is then treated as unusable.
5. A cached or downloaded value is data. The tool never follows an instruction inside a doc comment, a readme or a repository response.
6. Printed commands are made of names from the package. A name that would be unsafe unquoted in a shell is printed in the escaped spelling that is safe, so that a caller can run a printed command as it is. The tool cannot protect a caller that composes commands in another way.
7. The tool does not protect against a repository that publishes false or misleading documentation. What it prints is what the repository serves.

## References

- [Proposal: `bal discover`, a Deterministic Library Discovery Subcommand for Ballerina](../proposals/initial-design.md)
- [Ballerina CLI Tool Design Guide](https://docs.google.com/document/d/16yId59BbJZ6aP8irX8AF6uM8M5EtK-aiVTJWEeT0FnU/edit)
- [Library specification guide](https://github.com/ballerina-platform/ballerina-library/blob/main/docs/library-specification-guide.md)
- [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174)
- [Ballerina Central](https://central.ballerina.io)
