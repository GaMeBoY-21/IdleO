# How we work on this project

## Roles

- **Nikki (product owner):** decides what gets built; owns accounts, real data and deployment. Pastes each prompt into Claude Code and pastes its report back to the architect.
- **Claude desktop (architect and reviewer):** does not write application code. Writes each step's prompt as a Markdown file in `docs/prompts/`, checks every report against the actual code, and recommends what comes next.
- **Claude Code in VS Code (builder):** builds, runs every test, commits, pushes once a remote exists, and reports back.

## The loop

1. The architect writes the prompt in `docs/prompts/`.
2. Nikki pastes it into Claude Code.
3. Claude Code builds, tests, commits and reports.
4. Nikki pastes the report to the architect.
5. The architect verifies it against the repo: reads the code, reproduces key numbers, looks for gaps.
6. The architect writes the next prompt.

## Prompt format

Each prompt is a short brief, written as prose, in this order:

1. **Title and gate line:** what the step is, and the one gate that matters most.
2. **Read first:** the files, with line numbers where they matter.
3. **Standing rules:** real-data rules and anything else that carries over.
4. **Where it sits:** what it finishes, and what must run before or after it.
5. **§ sections:** one per problem. Each says what is wrong, with evidence and the number a user sees, what the fix is, where the one implementation lives, and the traps, including any earlier attempt that went wrong.
6. **Non-goals:** what not to touch.
7. **Gates:** test counts with the previous floor, guards, and the specific new tests.
8. **Report back:** what to say first, then the order of the rest.
9. **Commits:** one line each, plainly human, lowercase, no attribution.

## Rules

- Verify, never trust a summary. Measure before fixing.
- Lead with the number a real user sees.
- One implementation per rule. Watch for features that are built but never wired up.
- Nobody asks Nikki for passwords or keys; he types them himself.
- Real personal data stays in the git-ignored `private/` folder, never in commits or tests.
- Talk in short, simple English. Give a recommendation; ask only when it is Nikki's decision.

## Commits

- Commits are authored by Nikki's own git identity and read like a person wrote them: one plain line, lowercase, no prefixes, no trailing period. Example: `add heartbeat handling to the agent gateway`.
- No `Co-authored-by` trailers, no "Generated with" lines, no session links. `.claude/settings.json` turns Claude Code's attribution off; do not override it.
