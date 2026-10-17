# Security

## Reporting a vulnerability

Do not open a public issue, discussion or pull request for a security vulnerability.

Report it privately through GitHub's private vulnerability reporting: on the repository page, open
the **Security** tab and choose **Report a vulnerability**. Include what is affected, how to
reproduce it, and the impact you expect. You will get an acknowledgement, and a fix is coordinated
with you before anything is disclosed publicly.

## Supported versions

No version has been released yet. Until `v1.0.0`, only the latest commit on `main` receives fixes.

| Version | Supported |
|---|---|
| `main` | yes |

## Tunnel abuse is not a security report

If a JBurrow tunnel is serving phishing, malware or other abusive content, that is an abuse report,
not a vulnerability in JBurrow. Please don't send it through the private vulnerability channel.
A dedicated abuse report endpoint is part of the v1 roadmap; until it exists, open an issue that
names the tunnel URL without reproducing the abusive content.

## Secrets

No real secret belongs in any tracked file. Local `docker-compose.yml` credentials only unlock the
local stack. Real values come from environment variables, or a git-ignored override file
(`.env`, `application-secrets.yaml`, `*-credentials.json`) during development.
