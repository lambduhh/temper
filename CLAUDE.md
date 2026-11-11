# Working in this repo with an AI agent

- **Plans and handoffs go in `notes/`:** `notes/plans/<YYYY-MM-DD>-<slug>.md` and `notes/handoffs/<YYYY-MM-DD>-<slug>.md`. `notes/` is git-ignored and never part of a PR.
- **Skills:** `.claude/skills/grill-me` (align before building) and `.claude/skills/handoff` (write state to `notes/handoffs/`, then start fresh).
- **MCP:** `.mcp.json` declares [basic-memory](https://github.com/basicmachines-co/basic-memory). Install it, e.g. `brew install basic-memory`, or remove the entry.
- **Changes go on a branch and a PR;** don't push to `main`.
- **No AI attribution** in commits or PRs. AI involvement belongs in document metadata.
