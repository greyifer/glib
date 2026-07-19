# Security policy

Please do not disclose a suspected vulnerability in a public issue. Use the repository's **Security** tab to open a private vulnerability report instead.

Include the affected version, reproduction steps, and impact. Do not include real Minecraft access tokens, Railway variables, database URLs, private messages, or other users' personal data in a report.

## Secrets

G'Network intentionally has no secret embedded in the Fabric mod. A client-side secret would be recoverable from the jar. The backend accepts only short-lived bearer sessions issued after Minecraft session verification, and stores only SHA-256 token hashes.

Production credentials belong in Railway Variables. `.gitignore` helps prevent accidental additions but is not a security boundary: if a credential is ever committed, rotate it immediately and remove it from Git history.
