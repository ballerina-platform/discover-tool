# Distribution

How the `bal discover` tool reaches the machines that run it, and what is not possible today.

## Status

**The packaging step exists and is verified; CI now builds every PR; an actual Central publish is
still not wired.** A `:bal-tool` subproject applies `io.ballerina.plugin` the way
`ballerina-platform/openapi-tools`' `openapi-tool` does, wrapping `:native`'s jar into a real
`ballerina/tool_discover` bala. This has been run end to end in a working environment, not just read
off the reference: `./gradlew :bal-tool:build` compiles `:native`, runs `bal pack`, then
`bal push --repository=local`, producing
`ballerina/tool_discover/0.1.0/java21/tool/libs/native-<version>.jar` inside the bala — and a
subsequent `bal tool pull discover:0.1.0 --repository=local` followed by `bal discover --help`
dispatches correctly through the system `bal`. `.github/workflows/pull-request.yml` runs this same
build (on Ubuntu and Windows) on every PR now, and `.github/workflows/central-publish.yml` is the
manual dev/stage Central push, mirroring `openapi-tools`' own two workflows.

One thing worth knowing before relying further on this: **`io.ballerina.plugin` runs
`commitTomlFiles` as part of `build` on its own initiative** — `bal-tool/build.gradle` only *defines*
that task (matching `openapi-tool/build.gradle`'s own choice of name), it never wires it as a
dependency, yet `./gradlew :bal-tool:build` still invokes it and attempts a real
`git commit Ballerina.toml BalTool.toml` (`ignoreExitValue true` swallows the failure either way). A
local developer who bumps `gradle.properties`'s version and then runs a plain `./gradlew build`
before committing that bump themselves could get an unwanted local commit ahead of their own. Treat
this as inherited upstream behavior, not something introduced here — `openapi-tools` carries the
identical risk under the identical task name.

What is still missing before an actual Central publish:

- **No Central credentials provisioned.** `BALLERINA_CENTRAL_ACCESS_TOKEN` (plus dev/stage variants),
  `BALLERINA_BOT_USERNAME`/`BALLERINA_BOT_TOKEN`, and the `packagePAT` this repo already needs just to
  *build* (`org.ballerinalang:ballerina-cli` is GitHub-Packages-only) all live in `ballerina-platform`'s
  org secrets, which nobody has requested yet.
- **`-PpublishToCentral=true`** (openapi-tools' actual Central publish switch, wired into
  `openapi-tool/build.gradle`'s own `build` task via `io.ballerina.plugin`) has not been exercised
  against `:bal-tool` — only `bal push --repository=local` has been verified here, deliberately never
  a real Central push.
- **No release automation.** `gradle.properties` still pins `version=0.1.0-SNAPSHOT` and nothing moves
  it; a real release needs `net.researchgate.release` (or an equivalent) wired in, an actual
  `publish-release.yml` (openapi-tools' version-bump/tag/publish/GitHub-release workflow, not yet
  ported), and a versioning decision — not just a working packaging pipeline.

So `bal tool pull discover` against the real Ballerina Central still resolves nothing — nobody has
pushed a `ballerina/tool_discover` bala anywhere Central-facing. What changed is that the remaining gap
is credentials and a release process, not a Gradle plugin that fails before the classpath is even
reached, or a missing CI build.

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

Once published, users install with `bal tool pull discover` and remove with `bal tool remove discover`.

## Local development

There is one path, and it is the same one a real Central publish uses — nothing hand-rolled, on
purpose. A separate, hand-written bala-building script was tried first and dropped: it drifted from
what `bal pack` actually produces (wrong platform directory, a version string that could disagree
with `:bal-tool`'s), which is exactly the kind of bug two implementations of the same thing invite.

```bash
./gradlew :bal-tool:build                                        # packs and pushes to the local repository
bal tool pull ballerina/tool_discover:<version> --repository=local  # registers it as an active tool
bal discover --help                                               # smoke test
```

`<version>` is whatever `gradle.properties`' `version` resolves to once `-SNAPSHOT` is stripped (see
`bal-tool/build.gradle`'s `stripBallerinaExtensionVersion`) — for the current `0.1.0-SNAPSHOT`, that's
`0.1.0`. Re-run both commands after any change to `:native` or `:bal-tool` to pick it up locally.

## Verification

```bash
# 1. Test
./gradlew :native:test

# 2. Coverage floors (the same gate CI applies)
./gradlew :native:jacocoTestCoverageVerification

# 3. Package and install it locally (see "Local development" above)
./gradlew :bal-tool:build
bal tool pull ballerina/tool_discover:<version> --repository=local

# 4. Smoke test
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
  user or org secret to provision just to build and test.
- Nothing needs to be bumped when anything upstream releases. The one coupling left is to the
  distribution's own versions of `picocli` and `gson`, so no code here may rely on a feature newer
  than what `bre/lib` ships.
- The SPI entry at `META-INF/services/io.ballerina.cli.BLauncherCmd` wires `DiscoverTool` as the
  `bal discover` command handler.
