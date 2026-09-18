---
name: code-review
description: Review changes to this Jenkins Groovy shared library (vars/*.groovy, src/test/groovy/*.groovy, build.gradle, settings.gradle, gradle.lockfile) against the repository's established style, structure, testing, documentation, and shell-safety conventions. Use when asked to review a diff, PR, or specific Groovy file in this repo — not for generic or non-Groovy code review.
user-invocable: true
---

# Code Review — qa-jenkins-library

Read-only review skill for this Jenkins Global Shared Library. It checks changes
against the conventions already established in `.github/copilot-instructions.md`
and the existing `vars/` modules (e.g. `container.groovy` is the style exemplar).

## When to use it

- Reviewing a diff, PR, or specific file under `vars/**`, `src/test/groovy/**`,
  or `build.gradle` / `settings.gradle` / `gradle.lockfile`.
- Checking whether a new/changed pipeline function follows this repo's
  parameter-validation, documentation, and test-coverage conventions.

## When not to use it

- Non-Groovy files, unrelated repositories, or purely generic style/quality
  questions with no connection to this library's conventions.
- Security-only requests (`/security-review` or explicit vulnerability hunts) —
  use the dedicated security-review agent instead; this skill folds security
  checks in as one category among several, not an exhaustive audit.

## Scope

1. Identify changed files (git diff against the base branch, or the specific
   files/PR the user names). Restrict analysis to:
   - `vars/*.groovy`
   - `src/test/groovy/*.groovy`
   - `build.gradle`, `settings.gradle`, `gradle.lockfile` (only if touched)
2. Read each changed `vars/*.groovy` file in full, plus its matching test file
   `src/test/groovy/<Name>ScriptTest.groovy` (may not exist yet — flag if missing).
3. Read `.github/copilot-instructions.md` if not already in context, since it is
   the authoritative convention reference for this repo.

## Review checklist

Evaluate every changed function against each category below. Only report real
deviations found in the diff — do not invent issues or flag pre-existing code
outside the change unless it is directly coupled to it.

### Style
- New public functions should use named Map parameters (`def fn(Map config)`); do not introduce positional args.
- Private helpers are prefixed with `_` (e.g. `_getImage`, `_containerCommand`).
- Cross-module calls use `new` (`new config()`, `new infrastructure()`, `new tofu()`).
- Formatting/spacing is consistent with neighboring functions in the same file
  (blank line between statements, brace style, etc. — use `container.groovy` as
  the reference).

### Structure & patterns
- Required parameters are validated at function entry, failing fast via
  `error '...'` with a clear message.
- Optional parameters fall back to `new config().getConfig('section')` defaults
  rather than hardcoded literals duplicated across functions.
- Docker invocations follow the established shape: `docker run --rm --platform
  ${platform} <env/vol args> -v <workspace>:/workspace -w /workspace <image> sh -c
  "<command>"`.
- Error handling: `error()` for fatal/unrecoverable conditions, `steps.echo` for
  warnings; cleanup (e.g. `container.remove(...)`) happens on failure paths that
  create external resources (containers, images, temp files).

### Documentation
- Every public function has a Javadoc-style comment block with `Parameters`,
  `Returns`, and `Example` sections, matching the format in
  `.github/copilot-instructions.md`.
- New/changed `vars/*.groovy` files retain (or add, if new) a module-level header
  comment: filename, purpose, and a usage/workflow example.

### Tests
- Every new or behaviorally-changed function in `vars/` has corresponding
  coverage in `src/test/groovy/<Name>ScriptTest.groovy`.
- Test classes extend `BasePipelineTest`, use JUnit 5 annotations (`@Test`,
  `@BeforeEach`, `@DisplayName`), and AssertJ assertions (`assertThat(...)`).
- Tests mock `error()` to throw `RuntimeException` so failure paths can be
  asserted, and mock `steps` via `metaClass` on a plain `Object`.
- New error/validation branches, new optional parameters, and new defaulting
  logic all have at least one corresponding test case.

### Security (shell/docker interpolation)
- Any value interpolated into `steps.sh(...)` or a `docker run` command string
  is either regex-validated (e.g. `value.matches(/[a-zA-Z0-9._-]+/)`) or properly
  escaped (`.replace('\\', '\\\\').replace('"', '\\"')`) before use.
- No credentials are hardcoded; secrets flow through
  `steps.withCredentials`/`steps.withFolderProperties`.
- Flag any user-/env-supplied string reaching a shell command without
  validation or escaping as the highest-priority finding.

### File references & cross-file consistency
- If a function was renamed, removed, or had its signature changed, confirm all
  call sites across `vars/**` and `src/test/groovy/**` were updated — grep for
  stale references.
- If `build.gradle` dependencies changed, confirm `gradle.lockfile` was
  regenerated (`./gradlew dependencies --write-locks`); flag if it looks stale.
