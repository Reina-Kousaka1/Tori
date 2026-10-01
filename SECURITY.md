# Security Policy

## Reporting a vulnerability

Do not report security vulnerabilities in a public bug report, pull request, or discussion. Do not publish exploit details, credentials, or private user data.

If GitHub's **Report a vulnerability** option is available under this repository's **Security → Advisories** page, use that private reporting flow. If it is not available, use a private contact method listed on the [repository owner's GitHub profile](https://github.com/Reina-Kousaka1). If none is listed, ask only how to establish a private reporting channel; do not disclose vulnerability details publicly.

Private vulnerability reporting is controlled by a GitHub repository setting. The repository owner should verify or enable it in GitHub's security settings so researchers can use GitHub's private report form.

## Keep sensitive data private

Never include any of the following in public reports, commits, screenshots, or logs:

- Discord bot tokens or application credentials
- Webhook URLs, tokens, or signing secrets
- PostgreSQL credentials or connection strings
- `.env` contents or Economy API secrets
- Private Discord messages, user identifiers linked to sensitive reports, or other personal data

Redact logs before sharing them. A public report should describe impact and affected code without including a working secret or private data.

## If credentials are exposed

Revoke or rotate the exposed credential immediately. Removing it from a later commit does not make an already-published credential safe. Then report the incident through the private channel described above.
