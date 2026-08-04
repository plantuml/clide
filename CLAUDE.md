# CLAUDE.md — Using clide

This file changed role on 2026-08-02. It no longer holds the history of
design decisions, test campaigns, or past refactorings — that history was
moved as-is into `HISTORY.md` (a snapshot from that date). `CLAUDE.md` now
describes only **how to use clide today**, for an AI discovering the
project for the first time: why it's worth using, how to start it, what
each command does. Not the historical "why" behind each choice — just the
current state, kept up to date.

To go further: `HISTORY.md` (past reflections and decisions), `TESTS.md`
(journal of test campaigns on PlantUML, kept up to date), `TODO.md`
(backlog), `JDTLS.md` (low-level LSP protocol details), `JAVALENSE.md`/
`LUA.md`/`ASTPARSER.md` (explorations for future work, not implemented),
`CODING.md` (style conventions for clide's own source code — unrelated to
using it).

## Why use clide

Navigating a Java codebase with grep is purely textual: blind to
inheritance, overrides, polymorphism, and unable to tell whether the code
just written actually compiles. clide closes that gap by asking a real
semantic engine (Eclipse JDT Language Server, driven from the command
line) the same questions an IDE would, without trying to be a graphical
IDE — no autocomplete, no breakpoint debugger: features designed for a
human typing character by character, not for an agent that works in turns
with already fully-written code.

Three things matter, in priority order, and all three are covered:

1. **Compile and get exact errors** (`rebuild`) — removes a whole class of
   bugs (missing imports, mismatched signatures, incomplete refactors)
   that would otherwise need a human to catch by re-reading the code.
2. **Run a targeted test**, isolated, not the full build (`run_test`/
   `run_tests`).
3. **Semantic queries**: who calls this method, who implements this
   interface, where is the real definition (`find_*`/`hover`/
   `list_members`).

## Getting started

**Build clide with `ant` only, never with `gradlew`/Gradle.** The Gradle
wrapper downloads its distribution from `services.gradle.org`, a domain
not reachable from a Claude sandbox (403 verified) — `gradlew`/
`gradlew.bat` will therefore always fail in this environment. `ant`
compiles and packages `clide.jar` with no network access needed. `ant run`
starts an interactive session; type `help` at the prompt for the list of
commands.

`clide <project-path>` opens (or joins, if already running) a daemon
dedicated to that project — one per project, running in the background,
staying alive across multiple launches. There's no separate command to
"open" a project, and no notion of a current project to switch. The first
launch builds the project automatically; subsequent launches are fast
(~0.25 s per session once the daemon is up).

`clide --human <project-path>` opens that same session in HUMAN print mode:
`> READY` before each command, then one `> <parameter> ?` prompt per
parameter being read. Without the flag a session runs in AI mode, which
prints nothing but the commands' own output. The flag can go before or
after the project path, and applies to that one connection only — never to
the daemon, so a human session and an AI session can use the same daemon in
turn without either ever seeing the other's prompts.

Two levels of built-in help:

- `help` — every command with its parameters and one-line description.
  The print mode picks the shape: one bare line per command in AI mode,
  a human-readable ASCII table under `--human`. Same content either way,
  so there is nothing to strip before parsing and nothing to squint at.
- `man <keyword>` — a detailed page per command (`man(1)` format, ERRORS/
  SEE ALSO sections), explaining edge cases and what to chain next.
  Enough to use a command without reading this document.

To end a session: `exit` (or `quit`) disconnects cleanly without touching
the daemon or any open transactions — they stay in place for the next
connection. `terminate` stops the daemon for good; it refuses to run if a
transaction is still open (close it first).

## Sending a command: one line per token

The client speaks a strictly line-oriented protocol: **one token per
line**. The keyword goes alone on the first line, then each parameter
follows on its own line, in the order `help`/`man` list them.
Nothing is ever split on spaces — a whole line is one token, always.

This makes one mistake very easy to hit on the very first command:

```
find_reference method src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession
```

fails with `?ERROR UNKNOWN_KEYWORD: Unknown command 'find_reference method
src/...'`. Not because the parameters are wrong, but because that entire line
was looked up as a keyword and no keyword by that name exists. The same
command, written correctly:

```
find_reference
method
src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession
exit
```

Three consequences worth knowing before sending anything:

- **Nothing prompts for what is still missing.** In the default AI mode no
  `> READY` and no `> <parameter> ?` is ever printed, so each command's
  arity has to be known up front — `help` gives it for every command.
  `clide --human` (see above) turns those prompts on: worth it when typing
  by hand, only noise when piping.
- **A parameter containing spaces needs no quoting**, and must not be
  given any: the line *is* the value (trimmed), so quotes would end up
  inside it. `search_regex`'s content regex, typically, is written
  `private static final String` bare on its own line.
- **Ending the input mid-command drops the connection**: the daemon
  answers `?ERROR MISSING_PARAMETERS: missing parameter(s) for <keyword>`
  and closes that client (the daemon itself stays up). When piping a batch
  of commands, give every one of them all of its parameters, and finish
  with `exit`.

## Reading a result: three shapes, and only three

Every command answers in the same envelope, whatever it was asked. There is
nothing to strip on success — the answer is the whole output — and exactly two
markers to recognize otherwise:

```
?ERROR LINE_OUT_OF_RANGE: Line 999 out of range (file has 312 line(s)): Foo.java
hint: find_symbol Foo locates it
!WARNING AMBIGUOUS_NAME_ON_LINE: 'add' appears 3 times on line 12 (columns 10, 14, 25) - answered about the first one
```

- **`?ERROR <CODE>: <message>`** — the command refused. `<CODE>` names *why*,
  so a caller branches on the kind of failure instead of matching on wording:
  `UNKNOWN_KEYWORD`, `MISSING_PARAMETERS`, `INVALID_ENUM_VALUE`,
  `INVALID_REGEX`, `INVALID_INTEGER`, `VALUE_OUT_OF_RANGE`, `NOT_A_DIRECTORY`,
  `MALFORMED_POSITION`, `FILE_NOT_FOUND`, `FILE_UNREADABLE`,
  `LINE_OUT_OF_RANGE`, `NAME_NOT_ON_LINE`, `NOT_A_TYPE`, `JDTLS_REQUEST_FAILED`,
  `BUILD_FAILED`, `SESSION_START_FAILED`, `TEST_FAILURES`, `NO_TEST_FOUND`,
  `TEST_CLASS_NOT_COMPILED`, `TEST_TIMEOUT`, `TEST_RUNNER_BROKEN`,
  `MULTI_MODULE_PROJECT`, `NO_OUTPUT_FOLDER`, `CLASSPATH_UNAVAILABLE`,
  `TERMINATE_REFUSED`, `NO_OPEN_TRANSACTION`, `TRANSACTION_REFUSED`,
  `TRANSACTION_IO_FAILED`, `IO_FAILED`. An optional `hint:` line on the next
  line says what to actually do about it. This replaces the old
  `?SYNTAX ERROR` / `Error:` pair, which said only *that* something was wrong.
- **`!WARNING <CODE>: <message>`** — the answer stands and is printed as usual;
  something about it is worth knowing. Two exist today:
  `AMBIGUOUS_NAME_ON_LINE` (the `<name>` of a `<symbol>` occurs more than once
  on its line, and clide answered about the first occurrence — see the notation
  section above) and `TRANSACTIONS_STILL_OPEN`.
- **anything else** — the answer itself.

**Finding nothing is not an error.** `find_reference` with no usage,
`list_members` on a type with no members, `search_regex` with no match: all
succeed, and say so in words. Only a question clide could not answer at all
gets a `?ERROR`.

### Truncation: `set_max_results`

Every command that answers with a list caps it — 100 entries by default — and
reports the real total either way:

```
find_reference: 50 location(s) shown out of 312, truncated - raise the limit with set_max_results
```

Two guarantees worth relying on. The total is counted before the cap is
applied, so it is the real one; and a result of exactly `max_results` entries
with nothing left over is *not* reported as truncated.

`set_max_results <count>` changes the cap for the current session only — a new
session starts back at 100 — and prints the previous value alongside the new
one, which is also the only way to read the setting back (the protocol's fixed
arity leaves no room for an argument-less form). `0` is honoured literally;
values above 10000 are refused naming the ceiling rather than clamped.

## jdtls and the `.project`/`.classpath` files: fully automatic

**jdtls (Eclipse JDT Language Server) is bundled inside `clide.jar`** —
nothing to install separately. The server archive is packaged as a
resource inside the jar and extracts itself on first use.

**`.project`/`.classpath`: generated and cleaned up automatically, never
to be prepared by hand.** When the daemon starts on a project, clide
always writes its own `.project`/`.classpath` (source folders detected
from the tree — `src/main/java`, `src/test/java`, etc. — and every jar
dropped in the project's `.clide/` added as a library), uses them for the
jdtls import, then erases them once the initial build finishes: if the
project already had its own `.project`/`.classpath`, they're restored
exactly as they were; if not, nothing is left behind at the root. Either
way, a `git status` on the opened project never shows anything moving at
its root because of clide.

**Practical consequence: never run `./gradlew eclipse` or write
`.project`/`.classpath` by hand before opening a project with clide.**
It's unnecessary — and even counterproductive, since it would mask the
normal automatic behavior instead of helping it.

## The `<symbol>` notation: `<file path>:<line>:<name>`

Most commands that point to a precise spot in the code
(`find_declaration`, `find_reference`, `find_implementation`, `hover`,
`list_members`) take a single `<symbol>` parameter, written
`<file path>:<line>:<name>` — for example
`src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession`.

Three rules are enough to use it correctly:

- `<file path>` is always relative to the root of the opened project,
  never to the current working directory. It also accepts a `file:` URI —
  that's the format every `find_*`/`hover`/`list_members` command already
  prints its results in, so a result copied verbatim from one command into
  the next works without editing.
- `<line>` is 1-based, as shown when reading the file.
- `<name>` is looked up as a whole word on that line (`\bname\b`) — no
  column to count by hand, clide works it out itself.

**When the file/line isn't known yet**, `find_symbol <name>` searches for
a symbol by name across the whole project (fuzzy/camelCase matching
delegated to jdtls — `find_symbol UGraphic` can also surface `UGraphicSvg`,
`UGraphicNull`, etc.) and returns its results already in the `<symbol>`
notation above, ready to paste into the next command. `find_symbol` only
finds types and methods, never a field by its name — a known jdtls
limitation, with no parameter to lift it.

## Staying up to date after an edit: `rebuild`

clide does not detect on its own files modified outside of it (with
direct editing tools rather than a clide command). After any such
modification, call `rebuild <all|errors>` before continuing to query the
project — otherwise `print_diagnostics`, the `find_*` commands, and
`hover` will silently answer against a stale state.

`rebuild` recompiles the target project via jdtls, reports the number of
files changed since the last build and the exact errors (file, line,
message), and **also refreshes the semantic model**, not just the
diagnostics — a symbol just added becomes immediately findable via
`find_symbol`/`find_reference` after a `rebuild`. Measured cost on a
project the size of PlantUML (3600+ files): 9 to 12 s, whether or not any
files changed (`rebuild` with nothing changed still pays for the full
build today).

## Commands

### Diagnostics and build

| Command | Role |
|---|---|
| `print_diagnostics <all\|errors>` | Re-displays the diagnostics from the last build (`all`: everything, `errors`: errors only), without recompiling anything. |
| `rebuild <all\|errors>` | Recompiles the target project and refreshes the semantic model — see above. |

### Text search

| Command | Role |
|---|---|
| `search_regex <starting path> <path regex> <search regex>` | Walks `<starting path>` (relative to the project, `.` for the whole project), keeps only files whose path matches `<path regex>`, then greps `<search regex>` line by line. Paths in and out are relative to the project. |

Reserve this for cases where a real semantic search (below) can't answer
the question — a grep remains blind to inheritance and polymorphism.

### Semantic queries

| Command | Role |
|---|---|
| `find_symbol <name>` | Looks up a type or method by name across the whole project, without knowing the file/line in advance. |
| `find_declaration <what> <symbol>` | Where the symbol is actually declared. `<what>` = `method` (the declaration of the symbol itself) or `type` (the class/interface of its declared type). |
| `find_reference <what> <symbol>` | All real usages of the symbol, declaration excluded. `<what>` is accepted for naming symmetry but has no effect on the result (only one query exists behind it). |
| `find_implementation <what> <symbol>` | Which classes/methods actually implement or override the symbol — the polymorphism question. `<what>` is accepted for symmetry, with no effect on the result. |
| `hover <symbol>` | Signature/Javadoc known for this symbol at this exact spot. |
| `list_members <symbol>` | **Direct** members (methods, fields, constructors) of a type — never inherited members. |

All accept the `<symbol>` notation above, except `find_symbol` which takes
a bare name. All fail cleanly — and namefully: `?ERROR FILE_NOT_FOUND`,
`?ERROR LINE_OUT_OF_RANGE`, `?ERROR NAME_NOT_ON_LINE` — before any request
even reaches jdtls. See "Reading a result" below.

All of them cap their listing at `max_results` (100 by default) and say so
when they do; `set_max_results <count>` changes it for the session.

### Tests of the opened project

| Command | Role |
|---|---|
| `run_test <symbol>` | Runs the test that `<symbol>` designates: the whole class if `<symbol>` names the test class, that single method otherwise. Takes the `<symbol>` notation, not a fully-qualified class name — a `find_symbol` result can be pasted in unchanged. |
| `run_tests <all\|failures>` | Runs all tests in the project. `failures` lists only the failing ones (the only readable output on a suite of real size); totals are always shown either way. |

`run_test`/`run_tests` work even if the target project has no JUnit jar of
its own — clide provides whatever is missing. On a very large suite with
missing external dependencies (e.g. a system tool some tests call out to),
`run_tests` may never finish in a reasonable time; prefer a targeted
`run_test` in that case.

### Transactions — for a future modification command

**No command modifies a file today.** The transaction mechanism below
exists and works, but nothing uses it yet: for now, edit with your own
tools (not through clide), then `rebuild`. Whenever an editing command
exists, it will operate within this framework.

| Command | Role |
|---|---|
| `open_transaction <id>` | Opens a transaction: backs up every file touched afterward, so everything can be cleanly undone. `<id>` must start with `$`, followed by lowercase `\w` characters (e.g. `$refactor_foo`). |
| `commit_transaction <id>` | Keeps the changes made under this transaction. |
| `rollback_transaction <id>` | Undoes all changes made under this transaction. |
| `diff_transaction <id> [<path>]` | Without `<path>`: lists the modified files. With `<path>`: shows a unified diff of that specific file. |
| `restore_file <id> <path>` | Restores a single file to its pre-transaction state, without closing the transaction or touching anything else — can be called repeatedly. |

Nested sub-transactions: once `$refactor_foo` is open,
`open_transaction $refactor_foo$part1` opens a sub-transaction beneath it.
It's a stack (LIFO), not a tree: two sibling sub-transactions can't be
open at the same time — the first must be closed before opening another
at the same level. `commit_transaction`/`rollback_transaction` on a parent
transaction automatically closes any still-open children first (deepest
commits first, most recent rollbacks first).

### Help and session

| Command | Role |
|---|---|
| `help` | Lists all commands: one line each in AI mode, an ASCII table under `--human`. |
| `man <keyword>` | Detailed page for a command. |
| `set_max_results <count>` | Caps how many entries a listing command returns, for this session only — see "Truncation" above. |
| `exit` / `quit` | Disconnects cleanly; the daemon and any open transactions survive. |
| `terminate` | Stops the daemon; refuses if a transaction is still open. |

## Known limitations to keep in mind

- **`find_declaration type` targeting a JDK type** (`String`, `List`, …)
  never responds — a roughly 30 s timeout instead of a clear error. Use
  `hover` instead for a JDK type (answers instantly).
- **`list_members`** only lists a type's direct members, never those
  inherited from a superclass.
- **`find_symbol`** never finds a field by its name (types and methods
  only) — a jdtls limitation, not clide's. An empty `find_symbol` result now
  says so in place, so "no symbol found" on a field is not mistaken for "that
  field does not exist".
- **`rebuild` with nothing changed** still pays the cost of a full build —
  no shortcut to the already-known diagnostics.
- On a very large test suite with missing system dependencies,
  `run_tests` may never finish — see above.
- **`run_test`/`run_tests` refuse with "this repository holds N modules"**
  when the opened project already had its own `.project`/`.classpath`
  before clide ran (clide's own repository, self-hosted, is one such
  case). Those original files get moved into `.clide/tmp/` while clide's
  generated ones are used at the root (see above) — but `.clide/tmp/`
  stays inside the workspace tree, so jdtls's own recursive project import
  picks the moved copy back up as a second project. Only hits a target
  project that already ships its own `.project`/`.classpath`; not fixed
  yet (excluding `.clide/**` from jdtls's import scan would be the natural
  fix).
- **jdtls's `workspace/applyEdit` (server-initiated edits) can't reach
  clide today.** One concrete case: saving `Truc.java` when it actually
  declares `public class Machin` — jdtls detects the
  `PublicClassMustMatchFileName` error and, entirely on its own
  initiative (not in response to any client request), can rename the file
  to `Machin.java` via `workspace/applyEdit`. clide never advertises
  `workspace.applyEdit` support during `initialize`, so jdtls won't
  attempt this; even if it did, `LspClient`'s message dispatch would
  currently misroute the incoming request as a notification and never
  send back a reply, leaving jdtls waiting indefinitely.
