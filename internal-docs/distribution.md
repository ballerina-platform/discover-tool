# Distribution

How the `bal discover` tool reaches the machines that run it, and what is not possible today.

## Status

**The packaging step exists and is verified, CI builds every PR, and the release machinery is in place —
but nothing has been published to Central yet.**

- **Packaging.** The `:bal-tool` subproject applies `io.ballerina.plugin` the way
  `ballerina-platform/openapi-tools`' `openapi-tool` does, wrapping `:native`'s jar into a
  `ballerina/tool_discover` bala (platform `java21`, since the tool bundles native Java code). This has been
  run end to end: build, `bal pack`, `bal push --repository=local`, `bal tool pull`, and `bal discover --help`
  dispatching through the system `bal`.
- **CI.** `.github/workflows/pull-request.yml` builds and tests every PR on Ubuntu and Windows.
- **Central publishing.** `.github/workflows/central-publish.yml` is a manual push to dev or stage Central;
  `.github/workflows/publish-release.yml` runs the shared `ballerina-library` release template for
  `ballerina/tool_discover`. The root build applies `net.researchgate.release` (tag `v<version>`, releases
  from a `release-<version>` branch).

What is still missing before a real release:

- **Central credentials.** `BALLERINA_CENTRAL_ACCESS_TOKEN` (and its dev/stage variants) and the
  `BALLERINA_BOT_*` secrets the workflows pass explicitly live in `ballerina-platform`'s org secrets, and have
  not been provisioned for this repository.
- **A first real publish.** `-PpublishToCentral=true`, the plugin's switch for pushing to Central, has not
  been exercised against `:bal-tool`; only a push to the local repository has.

So `bal tool pull discover` against the real Ballerina Central still resolves nothing.

Open questions once publishing is unblocked:

- whether the tool is officially supported or community-maintained
- versioning and release cadence

## How Ballerina tool distribution works

Ballerina tools are published as `.bala` packages to Ballerina Central, with the tool jar bundled under
`tool/libs/`:

```
<org>/<name>/<version>/<platform>/
├── Ballerina.toml
├── package.json
└── tool/libs/
    └── native-<version>.jar
```

`BalTool.toml` declares the tool id, `discover`, which is the name `bal` dispatches on. Once published, users
install with `bal tool pull discover` and remove with `bal tool remove discover`.

## Local development

There is one path, and it is the one a Central publish uses. A hand-written bala-building script was tried
first and dropped: it drifted from what `bal pack` produces (wrong platform directory, a version string that
could disagree with `:bal-tool`'s), which is the kind of bug two implementations of the same thing invite.

```bash
./gradlew :bal-tool:build -PpublishToLocalCentral=true   # pack the bala and push it to the local repository
bal tool pull discover:<version> --repository=local       # register it as an active tool
bal discover --help                                       # smoke test
```

`<version>` is `gradle.properties`' `version` with its `-SNAPSHOT` suffix stripped (see
`bal-tool/build.gradle`'s `stripBallerinaExtensionVersion`) — `0.1.0` for the current `0.1.0-SNAPSHOT`.
Re-run both commands after any change to `:native` or `:bal-tool`.

`bal-tool/build.gradle` defines a `commitTomlFiles` task, after `openapi-tool`'s, that commits the generated
`Ballerina.toml`/`BalTool.toml`. It runs only when `-PautoCommitToml` is passed, so a local build never commits
on its own.

## Verification

```bash
./gradlew build                                           # tests, checkstyle, spotbugs
./gradlew :native:jacocoTestCoverageVerification          # the coverage floors CI applies
./gradlew :bal-tool:build -PpublishToLocalCentral=true
bal tool pull discover:<version> --repository=local
bal discover --help
bal discover ballerinax/kafka
bal discover ballerinax/kafka client Producer send
```

`README.md`'s "Verifying a build against live Central" has the fuller exit-code check.

## Notes

- The tool jar contains only our own classes. `gson`, `picocli` and the CLI launcher are on the Ballerina
  distribution's runtime classpath (`bre/lib`), so they are `compileOnly` and nothing third-party is
  redistributed. HTTP is `java.net.http` from the JDK.
- Building needs a GitHub token with `read:packages`, because `org.ballerinalang:ballerina-cli` is published
  only to ballerina-platform's GitHub Packages. In Actions the repository's own `GITHUB_TOKEN` suffices, so
  nothing has to be provisioned just to build and test.
- The one upstream coupling is to the distribution's own versions of `picocli` and `gson`, so no code here may
  rely on a feature newer than what `bre/lib` ships.
- `META-INF/services/io.ballerina.cli.BLauncherCmd` wires `DiscoverTool` as the `bal discover` command handler.
