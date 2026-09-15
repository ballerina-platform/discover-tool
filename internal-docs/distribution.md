# Distribution

How the `bal discover` tool reaches the machines that run it, and what is not possible today.

## Status

**The packaging step exists and is verified; Ballerina Central publishing itself is still not wired.**
A `:bal-tool` subproject now applies `io.ballerina.plugin` the way
`ballerina-platform/openapi-tools`' `openapi-tool` does, wrapping `:native`'s jar into a real
`ballerina/tool_discover` bala. This has been run end to end in a working environment, not just read
off the reference: `./gradlew :bal-tool:build` compiles `:native`, runs `bal pack`, then
`bal push --repository=local`, producing
`ballerina/tool_discover/0.1.0/java21/tool/libs/native-<version>.jar` inside the bala — and a
subsequent `bal tool pull discover:0.1.0 --repository=local` followed by `bal discover --help`
dispatches correctly through the system `bal`. Two things worth knowing before relying on this:

- **The platform directory is `java21`, not `any`.** A tool with a native (JVM) dependency packages
  under a JDK-specific platform directory — `install-local.sh` and `release/install.{sh,ps1}` (what
  `make-dist.sh` assembles into `dist/`) predate `:bal-tool` and originally hand-wrote a bala tree
  under `any` with `"platform": "java"` instead. Both have since been corrected to write `java21`,
  matching what a real `bal pack` produces, even though they still bypass `bal pack` entirely (they
  write `package.json` and the tool jar directly and register the tool via `bal-tools.toml`).
- **`io.ballerina.plugin` runs `commitTomlFiles` as part of `build` on its own initiative** — this
  repo's `bal-tool/build.gradle` only *defines* that task (matching `openapi-tool/build.gradle`'s own
  choice of name), it never wires it as a dependency, yet `./gradlew :bal-tool:build` still invoked
  it and attempted a real `git commit Ballerina.toml BalTool.toml`. It failed harmlessly here only
  because those files were not yet tracked by git in the test environment (`ignoreExitValue true`
  swallows the failure either way) — on a checkout where they ARE tracked, a plain developer build
  would silently attempt a commit. Treat that as inherited upstream behavior to be aware of, not
  something introduced here; openapi-tools carries the identical risk.

What is still missing before an actual Central publish:

- **No CI.** This repository has no `.github/workflows/` at all — a `pull-request.yml` build, a
  `publish-release.yml`, and a `central-publish.yml` mirroring openapi-tools' three still need writing.
- **No Central credentials provisioned.** `BALLERINA_CENTRAL_ACCESS_TOKEN` (plus dev/stage variants),
  `BALLERINA_BOT_USERNAME`/`BALLERINA_BOT_TOKEN`, and the `packagePAT` this repo already needs just to
  *build* (`org.ballerinalang:ballerina-cli` is GitHub-Packages-only) all live in `ballerina-platform`'s
  org secrets, which nobody has requested yet.
- **`-PpublishToCentral=true`** (openapi-tools' actual Central publish switch, wired into
  `openapi-tool/build.gradle`'s own `build` task via `io.ballerina.plugin`) has not been exercised
  against `:bal-tool` — only `bal push --repository=local` has been verified here, deliberately never
  a real Central push.
- **Versioning.** `gradle.properties` still pins `version=0.1.0-SNAPSHOT` and nothing moves it; a real
  release needs `net.researchgate.release` (or an equivalent) wired in, plus an actual versioning
  decision — not just a working pipeline.

So `bal tool pull discover` against the real Ballerina Central still resolves nothing — nobody has
pushed a `ballerina/tool_discover` bala anywhere Central-facing. What changed is that the remaining gap
is CI, credentials and a release process, not a Gradle plugin that fails before the classpath is even
reached.

Open questions if Central publishing is finished:
- whether the tool is officially supported or community-maintained
- versioning and release cadence

## How Ballerina tool distribution works

Ballerina tools are published as `.bala` packages to Ballerina Central. The tool JAR must be bundled
inside the `.bala` under `tool/libs/`.

```
<org>/<name>/<version>/<platform>/
├── Ballerina.toml
├── package.json
└── tool/libs/
    └── native-<version>.jar
```

Once published, users would install with `bal tool pull discover` and remove with
`bal tool remove discover`. The local installers below write this same tree by hand, into the local
bala repository rather than a Central-backed one.

## The two install paths that DO work

Both derive the version from `gradle.properties`, which is the only place it is written.

### `install-local.sh` — the developer loop

Builds the native JAR and installs it into `~/.ballerina/repositories/local/bala`, registering
`[[tool]] id = "discover"` with `repository = "local"` in `~/.ballerina/.config/bal-tools.toml`.

```bash
./install-local.sh
```

This is what host runs resolve — `make eval-bal` and `pnpm play <dir> code --host` execute the tool out of your
own home, so a working-tree jar is invisible to them until this script copies it in. It copies
without `-p`, deliberately: the installed jar's mtime is when it landed, which is what lets the
playground and the evals detect a stale install by comparing mtimes.

Note it `rm -rf`s every installed version of the tool first, so it will remove a copy installed any
other way.

### `make-dist.sh` — for a consumer that has to copy it

Assembles `dist/` — the jar, `Ballerina.toml`, `VERSION`, and both installers — with no network
beyond the dependency resolution the build itself needs.

```bash
./make-dist.sh
cd dist && ./install.sh     # on the target machine, in the target image
```

This is the answer for anything that cannot `bal tool pull discover`: a container image, or another
repository that vendors the tool. It is the ONE place that decides what a distribution contains, so
the runner image and any future release zip cannot disagree about it — which is why the runner image
runs this script rather than `gradlew :native:jar`, and why CI runs it too (`ci.yml`, the
`bal-library-tool` job) instead of only compiling.

The install must happen **where the tool will run**. `install.sh` writes `package.json` with the
distribution the local `bal` reports, and `bal` rejects a tool stamped newer than the distribution
running it — so a bala tree built on one machine and copied into an image with a different
distribution can be refused. That is also why the image installs by running this script rather than
by copying a prebuilt bala tree in.

## How the platform ships it

The runner image builds the tool itself. `runners/remote-worker/Dockerfile`'s first stage reaches the
source through the `bal-library-tool` **named build context**, runs `make-dist.sh`, and the final
stage runs the resulting `install.sh` as the `aep` user, followed by `bal discover --help` as a smoke
test. There is no artifact to refresh and no ordering to remember: a build cannot use a tool that is
not this commit's.

Every build path must pass both that named context and a `packagePAT` **secret** — never a build arg,
because the release workflow publishes builder stages to a public buildcache. The three paths are
`deployments/scripts/build-runner.sh`, `.github/workflows/release.yml`'s matrix row, and
`runners/remote-worker/local/run-local.sh`.

The playground does not rebuild the image for a tool change: it bind-mounts the working-tree jar over
the image's installed copy, aiming the mount with the version from `gradle.properties`.

## Verification

```bash
# 1. Test
./gradlew :native:test

# 2. Coverage floors (the same gate CI applies)
./gradlew :native:jacocoTestCoverageVerification

# 3. Package the distribution — the path the image depends on
./make-dist.sh

# 4. Install locally
./install-local.sh

# 5. Smoke test — see README.md "Verification" for the full protocol
bal discover --help
bal discover overview ballerinax/kafka
bal discover type ballerina/http ClientRequestError --deps
```

## Notes

- The tool JAR contains ONLY our own classes. `gson`, `picocli` and the CLI launcher are on the
  Ballerina distribution's runtime classpath (`bre/lib`), so they are declared `compileOnly` and
  nothing third-party is redistributed. HTTP is `java.net.http` from the JDK.
- Building needs a GitHub token with `read:packages`, because `org.ballerinalang:ballerina-cli` is
  published only to ballerina-platform's GitHub Packages — absent from Maven Central,
  `maven.wso2.org` and JitPack. In Actions a repo-scoped `GITHUB_TOKEN` suffices, so there is no bot
  user or org secret to provision.
- Nothing needs to be bumped when anything upstream releases. The one coupling left is to the
  distribution's own versions of `picocli` and `gson`, so no code here may rely on a feature newer
  than what `bre/lib` ships.
- The SPI entry at `META-INF/services/io.ballerina.cli.BLauncherCmd` wires `DiscoverTool` as the
  `bal discover` command handler.
