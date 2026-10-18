# 6. CLI distribution: native binaries released through JReleaser

- Status: accepted
- Date: 2026-10-18

## Context

The CLI runs on users' machines, so it ships differently from the server: no registry push or
GitOps commit, but a download and an install command. Installing it should be one step on every
major platform, with no Java, Maven or build from source, the way established tunneling CLIs
install.

The plan before this decision built a fat JAR until v1, added native binaries at v1, and left
install methods (install script, Homebrew, Scoop, apt) for after v1. That had two weaknesses:

- **The first public users would get the weakest install.** v1 is when strangers first try
  JBurrow. Shipping it with "download an archive from the release page" and adding package
  managers afterwards puts the friction exactly where first impressions form.
- **Native-image problems would surface late.** Netty enters the client in phase 1, and Netty is
  the dependency most likely to need reflection and initialization configuration under GraalVM
  native-image. Building the native binary for the first time at phase 8 would turn seven phases
  of client code into one pile of fixes.

Nobody outside the maintainer installs the CLI before v1 (accounts arrive in phase 5, the
dashboard in phase 7), so release machinery before then would be maintained without users.

## Decision

- **GraalVM native-image** builds the CLI, settling the roadmap's "native-image or jpackage"
  choice. A JVM fat JAR remains the fallback for any platform without a native build.
- **From phase 1**, when Netty enters the client, the pull request check compiles the native
  binary and runs `jburrow version` with it on Linux x64. This is a build check only: it
  publishes nothing and needs no secrets. A change that breaks the native build fails its pull
  request, and the reflection and resource configuration grows with the code that needs it.
- **At v1**, a tag-triggered GitHub Actions workflow builds native binaries on Linux, macOS and
  Windows runners for x64 and arm64, and **JReleaser** publishes them: the GitHub release with
  archives and checksums, a Homebrew tap, and a Scoop bucket. An `install.sh` script detects the
  OS and architecture, downloads the matching archive from the latest release, verifies its
  checksum and installs `jburrow` on the `PATH`. These four channels (release archives,
  `install.sh`, Homebrew, Scoop) are the v1 install surface.
- **After v1**: winget, `.deb`/`.rpm` packages and repositories, macOS notarization and Windows
  code signing, and self-update.

## Consequences

- At launch, `brew install`, `scoop install` or one `curl | sh` line gets a working `jburrow`
  with no runtime to install. Binaries installed through Homebrew or `curl` aren't quarantined by
  macOS, so v1 doesn't depend on notarization.
- The pull request check gains one native-image job, which is slower than the JVM build. It stays
  a separate job so the JVM test suite reports first.
- Client code has to stay native-image compatible from phase 1: reflection, resources and
  class-initialization needs are recorded in the CLI module's native-image configuration as they
  appear.
- Two more public repositories exist at v1, the Homebrew tap and the Scoop bucket, written to only
  by the release workflow with a token scoped to them.
- Release signing of archives (checksums at minimum) and where `install.sh` is served from are
  settled when the v1 release workflow is designed.

## Alternatives considered

- **Keep install methods after v1.** Less work before launch, but the public launch would ship the
  hardest install path, and that is when it matters most.
- **Full release pipeline and pre-releases from phase 1.** It proves the release path early, but
  maintains a tap, a bucket and published builds through six phases with no users. The native
  build check captures most of the early-warning value at a fraction of the cost.
- **jpackage.** It bundles a JVM instead of compiling ahead of time: larger downloads, slower
  start-up for a command that is often run briefly, and per-platform installers rather than one
  executable.
- **Hand-written release scripts per channel.** JReleaser already produces the release, checksums,
  Homebrew formula and Scoop manifest from one configuration, and adds winget, packages and
  signing later without new tooling.
