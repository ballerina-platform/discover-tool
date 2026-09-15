## Overview

`bal discover` is a Ballerina CLI tool that reads a package off Ballerina Central and answers
addressed questions about its real API — clients, services, classes, functions, parameters and
return types — without falling back to web search or guessing.

It exists so an AI agent (or a human) can learn a library or connector's actual signatures directly
from what Central publishes, deterministically and offline-cacheable, rather than reconstructing them
from documentation prose.

## Commands

```bash
bal discover --help                      # what the tool is, what it can be asked, and how to walk it
bal discover find     <keywords...>       # packages matching free-text keywords
bal discover overview <org/name>          # a map of the package
bal discover client   <org/name> [...]    # clients, addressed by name or by resource path
bal discover class    <org/name> [...]    # classes and object types, addressed with `.`
bal discover funcs    <org/name> [...]    # module-level functions, addressed with no receiver
bal discover type     <org/name> <Name>   # one declaration, whole
bal discover guide    <org/name> [<n>]    # the package's own readme, addressable one chunk at a time
bal discover api      <org/name>          # the whole package as one Ballerina document
```

## Example

```bash
bal discover overview ballerinax/kafka
bal discover client ballerinax/github Client repos
bal discover type ballerina/http ClientRequestError -r
```

See the [project repository](https://github.com/ballerina-platform/discover-tool) for the full
command reference, design notes and contribution guidelines.
