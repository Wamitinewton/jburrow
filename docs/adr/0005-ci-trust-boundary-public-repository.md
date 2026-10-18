# 5. CI trust boundary for a public repository

- Status: accepted
- Date: 2026-10-18

## Context

JBurrow is a public repository that accepts pull requests from anyone, including from forks. Two
kinds of build have to happen:

- **Verifying a change**: the full `./mvnw verify` (unit, architecture and integration tests,
  with Docker for Testcontainers) and a container image build, on every pull request.
- **Shipping `main`**: building and pushing the server image to the registry, then committing the
  new tag to a private GitOps repository that the cluster's ArgoCD watches.

Shipping needs credentials that must never be reachable from untrusted code: a registry token with
write access, write access to the GitOps repository, and a build host with a Docker socket (which
is root on that host). Pull request code is untrusted by definition. Whatever runs it must hold
none of those.

The deployment target itself (registry, GitOps repository, manifest path, commit identity) is
private, and nothing in this repository may name it.

## Decision

- **GitHub Actions verifies.** `.github/workflows/verify.yml` runs on every pull request and every
  push to `main`: `./mvnw verify`, then a `docker build` of the server image that is never pushed.
  It triggers on `pull_request`, never `pull_request_target`, its token is limited to
  `contents: read`, and it references no secret. Third-party actions are pinned to commit SHAs and
  kept current by Dependabot. Workflows from first-time outside contributors wait for maintainer
  approval before they run.
- **Jenkins deploys.** The `Jenkinsfile` builds pushes to `main` only, from the repository's
  webhook, and never builds a pull request. Jenkins holds every credential: the registry token and
  the GitOps repository credential, each dedicated to JBurrow.
- **Deployment details are job parameters.** Every deployment-specific value (image repository,
  GitOps repository URL, manifest path, commit identity, credential ids) is configured on the
  Jenkins job. The pipeline's first stage checks each one is set and fails with the missing name
  otherwise. None has a default, and none is written in the repository.

## Consequences

- A stranger's pull request gets the same checks as a maintainer's and can reach nothing beyond a
  read-only checkout of a public repository.
- Two CI systems run the same verify command and have to be kept in step when it changes: the
  workflow and the `Jenkinsfile` both call `./mvnw -B verify`, and both build the image from
  `jburrow-server/Dockerfile`.
- A broken Dockerfile fails on the pull request, not on the first Jenkins build after merge.
- Anyone can reuse the `Jenkinsfile` against their own registry and GitOps repository by setting
  their own job parameters, without forking it.
- Repository settings carry part of the boundary and are not in version control: fork workflow
  approval, read-only default workflow permissions, and branch protection on `main` requiring the
  `verify` and `image` checks.

## Alternatives considered

- **Jenkins builds pull requests too.** One CI system, but fork code would run on a host with a
  Docker socket next to the registry and GitOps credentials. Isolating that safely (separate
  agents, no shared socket, no credentials in scope) is more work than using hosted runners built
  for untrusted code.
- **GitHub Actions for everything, deploy included.** One system, but the production GitOps
  credential and registry token would live in GitHub secrets, one misconfigured trigger away from
  a pull request, and the existing self-hosted build host would go unused.
- **Deployment values written into the `Jenkinsfile`, kept in a private fork.** Simplest to
  configure, but the private fork drifts from the public one with every change, and a single
  accidental push of the fork's history publishes the deployment.
