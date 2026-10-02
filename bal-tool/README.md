## Overview

`bal discover` is a Ballerina CLI tool that reads a package off Ballerina Central and answers
addressed questions about its real API — clients, services, classes, functions, parameters and
return types — without falling back to web search or guessing.

It exists so an AI agent (or a human) can learn a library or connector's actual signatures directly
from what Central publishes, deterministically and offline-cacheable, rather than reconstructing them
from documentation prose.

## Usage

```bash
bal discover --help                                  # what the tool is, what it can be asked, and how to walk it
bal discover <org>/<package>                         # the buckets the package has
bal discover <org>/<package> client [...]            # objects reached with `->`: remote methods and resource functions
bal discover <org>/<package> service [...]           # service object types, each paired with its listener
bal discover <org>/<package> class [...]             # plain objects reached with `.`
bal discover <org>/<package> funcs [...]             # module-level functions
bal discover <org>/<package> readme [<n>|"<title>"]  # the module's README, or one code-carrying section of it
```

Flags: `--output json|text`, `--filter <keyword>`, `--page <n>`, `-m`/`--module <name>`, `--refresh`.
To find a package in the first place, use `bal search <keyword>`.

## Example

```bash
bal discover ballerinax/kafka
bal discover ballerinax/github client gists
bal discover ballerinax/kafka client Producer send
bal discover ballerinax/kafka readme --filter producer
```

See the [project repository](https://github.com/ballerina-platform/discover-tool) for the full
command reference, design notes and contribution guidelines.
