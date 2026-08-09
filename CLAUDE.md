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
(backlog), `RESULTS.md` (the shape of every answer, field by field — for
writing a command or a client, not for asking a question), `JDTLS.md`
(low-level LSP protocol details), `LUA.md` (what the Lua bridge still has to
grow — the part that works is described here, under "Scripting a session"),
`JAVALENSE.md`/`ASTPARSER.md` (explorations for future work, not implemented),
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

**And run that jar** — `java -jar clide.jar <project>` — never the compiled
classes with `lib/` on the classpath. `clide.jar` is not just a packaging
convenience: it carries resources the code reads at runtime, and a
classes-based run has none of them. jdtls itself is one
(`resource/jdt-language-server-latest.zip`); the JUnit jars clide gives a
target project so jdtls can compile its test sources are the other
(`resource/vendor-junit/`, see below). Both are looked up on the classpath, and
both come back empty outside the jar — with no message saying so. The symptom
shows up in the *opened project* instead: `rebuild` reports a wave of
`The import org.junit.platform.launcher cannot be resolved` on files nobody
touched, which reads exactly like a project whose classpath is broken. It is
not — the clide that was asked is.

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
find_reference method src/main/java/clide/command/ManualCommand.java:27:21:needsJdtlsSession
```

fails with `?ERROR UNKNOWN_KEYWORD: Unknown command 'find_reference method
src/...'`. Not because the parameters are wrong, but because that entire line
was looked up as a keyword and no keyword by that name exists. The same
command, written correctly:

```
find_reference
method
src/main/java/clide/command/ManualCommand.java:27:21:needsJdtlsSession
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
!WARNING TRANSACTIONS_STILL_OPEN: $refactor_foo
```

- **`?ERROR <CODE>: <message>`** — the command refused. `<CODE>` names *why*,
  so a caller branches on the kind of failure instead of matching on wording:
  `UNKNOWN_KEYWORD`, `MISSING_PARAMETERS`, `INVALID_ENUM_VALUE`,
  `INVALID_REGEX`, `INVALID_INTEGER`, `VALUE_OUT_OF_RANGE`, `NOT_A_DIRECTORY`,
  `MALFORMED_POSITION`, `FILE_NOT_FOUND`, `FILE_UNREADABLE`, `FILE_MODIFIED`,
  `LINE_OUT_OF_RANGE`, `NAME_NOT_ON_LINE`, `NAME_NOT_AT_COLUMN`, `NOT_A_TYPE`,
  `JDTLS_REQUEST_FAILED`,
  `BUILD_FAILED`, `SESSION_START_FAILED`, `TEST_FAILURES`, `NO_TEST_FOUND`,
  `TEST_CLASS_NOT_COMPILED`, `TEST_TIMEOUT`, `TEST_RUNNER_BROKEN`,
  `MULTI_MODULE_PROJECT`, `NO_OUTPUT_FOLDER`, `CLASSPATH_UNAVAILABLE`,
  `TERMINATE_REFUSED`, `NO_OPEN_TRANSACTION`, `TRANSACTION_REFUSED`,
  `TRANSACTION_IO_FAILED`, `IO_FAILED`. This replaces the old
  `?SYNTAX ERROR` / `Error:` pair, which said only *that* something was wrong.

  A `hint:` line may follow, and usually does not. It appears only when clide
  has something to add that the message and this document do not already give
  you: a path it computed, a cause that is not the obvious one, or a next
  command with its arguments already filled in. Absence of a hint means clide
  has nothing useful to add — never that the error is unimportant, and never
  that it knows the cause and kept it to itself.
- **`!WARNING <CODE>: <message>`** — the answer stands and is printed as usual;
  something about it is worth knowing. One exists today:
  `TRANSACTIONS_STILL_OPEN`. (`AMBIGUOUS_NAME_ON_LINE` is gone: the `<position>`
  notation now carries the column, so clide never picks between two occurrences
  of a name on one line — see the notation section above.)
- **anything else** — the answer itself.

`RESULTS.md` documents every answer shape field by field, with an example
each — worth reading before writing a client that parses this output, and
unnecessary for simply using clide.

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

It extracts into a **per-user cache**, shared by every project and every
daemon, and named after the archive's own fingerprint:
`~/.cache/clide/jdtls-<crc>` (`$XDG_CACHE_HOME/clide/…` when set,
`%LOCALAPPDATA%\clide\…` on Windows, `~/Library/Caches/clide/…` on macOS).
Two consequences worth knowing: the 62 MB extraction is paid **once per
machine**, not once per directory clide is started from — and it never lands
in the opened project, nor in the current directory, so it cannot show up in
a `git status`. The startup trace names the exact path it resolved to
(`(2/4) Initializing IDE ... [OK] (jdtls: …)`). A `clide.jar` rebuilt around
a different jdtls gets a different fingerprint and therefore a different
directory, so an upgrade never silently reuses the previous server; the
superseded directory is left in place, inert, and can be deleted by hand.
`CLIDE_JDTLS_HOME` overrides the whole thing with a path used verbatim.

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

## The `<position>` notation: `<file-content-md5>:<file path>:<line>:<column>:<name>`

Most commands that point to a precise spot in the code
(`find_declaration`, `find_reference`, `find_implementation`, `hover`,
`list_members`, `run_test`) take a single `<position>` parameter, written
`<file-content-md5>:<file path>:<line>:<column>:<name>` — for example
`0f5a2c8e:src/main/java/clide/command/ManualCommand.java:27:21:needsJdtlsSession`.

This is the only notation clide accepts today. The shorter forms sketched
in `SYMBOLS.md` (`Classe::membre`, a bare file name, a bare class name) are
not implemented; sending one gets `?ERROR MALFORMED_POSITION`.

Five rules are enough to use it correctly:

- `<file-content-md5>` is the first 8 lowercase hexadecimal characters of
  the md5 of the whole content of the file the position points into — the
  same signature clide uses everywhere else to tell whether a file moved,
  cut down to what is enough to notice an edit (nothing ever looks it up,
  only compares it against the one file the path already names). **It is
  optional on input, and always present on output** (see below).
- `<file path>` is always relative to the root of the opened project,
  never to the current working directory. It also accepts a `file:` URI —
  that's the format every `find_*`/`hover`/`list_members` command already
  prints its results in, so a result copied verbatim from one command into
  the next works without editing.
- `<line>` is 1-based, as shown when reading the file.
- `<column>` is 1-based too — column 1 is the first character of the line,
  tabs counting as one character each. It is the column `<name>` *starts*
  at, not the one it ends at.
- `<name>` is a consistency check, not decoration: clide verifies it starts
  exactly at `<column>` of `<line>`, as a whole word (`\bname\b`). Nothing
  is guessed and nothing falls back — a token that does not check out is
  refused, never answered approximately.

### The md5 is what makes a stale position fail instead of lie

A `<position>` carrying an md5 is checked against the file **as it stands
right now**: if the content no longer signs the same, the file has been
edited since that position was produced, and clide refuses it with
`?ERROR FILE_MODIFIED` before looking at the line, the column or the name.
That check comes first on purpose — the others would report a symptom
(`NAME_NOT_AT_COLUMN`), or worse, would happen to pass while pointing at
whatever the edit moved into that spot.

Note what the signature covers: **the whole file**. Any edit anywhere in a
file invalidates every position in it, not only the ones on the line that
moved. That is the intended strictness — clide already requires a `rebuild`
after an external edit, and the positions worth trusting afterwards are the
ones re-derived from it.

There is no way to repair a refused token in place, and the error
deliberately does not hand you the file's current md5: pasting it back would
produce a token that passes the check while pointing somewhere else. Ask
again — `find_symbol`, or whichever command produced the position — and use
what comes back.

A `?ERROR FILE_MODIFIED` may carry a `hint:` line instead, and it is a
different thing entirely from that shortcut: a complete, freshly re-derived
`<position>` for the same name, offered only when clide found real evidence
that it still names the right spot — the name's exact old line, read back
unchanged from a cached historical revision, still exists byte for byte
somewhere in the current file. That evidence is often missing (any edit to
the line itself defeats it, and the historical revision is only cached at
all if a `rebuild` ran while it was live), so most `FILE_MODIFIED` failures
still carry no hint — but when one appears, it is a genuinely fresh,
already-checked position, safe to use as-is, not a way around the check.

**Omitting the md5 is allowed**, and is the one asymmetry in the notation:
`src/main/java/demo/Square.java:3:14:Square` still works and means
*implicitly* "against the file currently on disk". You get the checks clide
always did (the file exists, the line exists, the name starts at that
column) and nothing more. Convenient when typing a position by hand; it is
also, exactly, opting out of the staleness check.

Written with an md5 that is 8 hexadecimal characters but not lowercase, a
token is refused as `MALFORMED_POSITION` naming that as the reason, rather
than being read as a strange file path.

The older failures still exist and still mean what they meant, for a token
sent without an md5: `?ERROR NAME_NOT_AT_COLUMN`, whose hint names the
columns the name actually starts at now, and `?ERROR NAME_NOT_ON_LINE` —
the name is nowhere on that line, so the line (or the file) is wrong and a
corrected column would not help.

The column is also why clide never has to choose for you. `a.foo(b.foo())`
names two unrelated methods on one line; each has its own column, so each is
reachable, and there is no "answered about the first one" warning left to
print.

**When the file/line/column isn't known yet**, `find_symbol <name>` searches
for a symbol by name across the whole project (fuzzy/camelCase matching
delegated to jdtls — `find_symbol UGraphic` can also surface `UGraphicSvg`,
`UGraphicNull`, etc.). `find_symbol` only finds types and methods, never a
field by its name — a known jdtls limitation, with no parameter to lift it.

**Every command prints locations in that same notation**, so a result feeds
the next command with no editing at all:

```
find_symbol Square
→ [class] 6b1e0a4c:src/main/java/demo/Square.java:3:14:Square public class Square implements Shape {

list_members 6b1e0a4c:src/main/java/demo/Square.java:3:14:Square
→ [method] 6b1e0a4c:src/main/java/demo/Square.java:12:16:area public double area() {
```

The md5 is the same in both lines of that second result because both name
the same file — it signs the file, not the line.

The shape is `<position> <line text>`: a whole `<position>` as one
whitespace-free token, one space, then the line as it reads. Splitting on the
first space is all the parsing a client needs. The `<name>` in a printed
location is read back off the source line at that column, so a generic type
shows as `Box`, not `Box<T extends Comparable<T>>` — the bare word the
notation takes. In the rare case clide could not read the line back, the token
stops at the column (`path:line:column`) and reads as the incomplete answer it
is.

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
| `find_declaration <what> <position>` | Where the symbol is actually declared. `<what>` = `method` (the declaration of the symbol itself) or `type` (the class/interface of its declared type). |
| `find_reference <what> <position>` | All real usages of the symbol, declaration excluded. `<what>` is accepted for naming symmetry but has no effect on the result (only one query exists behind it). |
| `find_implementation <what> <position>` | Which classes/methods actually implement or override the symbol — the polymorphism question. `<what>` is accepted for symmetry, with no effect on the result. |
| `hover <position>` | Signature/Javadoc known for this symbol at this exact spot. |
| `list_members <position>` | **Direct** members (methods, fields, constructors) of a type — never inherited members. |

All accept the `<position>` notation above, except `find_symbol` which takes
a bare name. All fail cleanly — and namefully: `?ERROR FILE_NOT_FOUND`,
`?ERROR LINE_OUT_OF_RANGE`, `?ERROR NAME_NOT_ON_LINE`,
`?ERROR NAME_NOT_AT_COLUMN` — before any request even reaches jdtls. See
"Reading a result" below.

All of them cap their listing at `max_results` (100 by default) and say so
when they do; `set_max_results <count>` changes it for the session.

### Tests of the opened project

| Command | Role |
|---|---|
| `run_test <position>` | Runs the test that `<position>` designates: the whole class if `<position>` names the test class, that single method otherwise. Takes the `<position>` notation, not a fully-qualified class name — a `find_symbol` result pastes in unchanged. |
| `run_tests <all\|failures>` | Runs all tests in the project. `failures` lists only the failing ones (the only readable output on a suite of real size); totals are always shown either way. |

`run_test`/`run_tests` work even if the target project has no JUnit jar of
its own — clide provides whatever is missing. Concretely, the daemon extracts
the JUnit jars bundled in `clide.jar` into the opened project's
`.clide/tmp/jar-junit/` at startup, and adds them to the classpath jdtls
compiles against, *after* whatever jars the project keeps in its own `.clide/`
— so a project that has its own JUnit version keeps it. Nothing to install,
nothing to commit: a project needs no JUnit jar in `.clide/` for its test
sources to compile, and adding one only shadows what clide already brings.
(This is also why clide has to be run from the jar — see "Getting started".)
On a very large suite with
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
| `open_transaction <id>` | Opens a transaction: takes a snapshot of every `.java` source file's content, so any of them can later be cleanly undone. `<id>` must start with `$`, followed by lowercase `\w` characters (e.g. `$refactor_foo`). |
| `commit_transaction <id>` | Keeps the changes made under this transaction. |
| `rollback_transaction <id>` | Undoes all changes made under this transaction. |
| `list_modified_files <id>` | Lists the files modified so far under this transaction. |
| `diff_transaction <id> <path>` | Shows a unified diff of that one file, against its state right before the transaction touched it. |
| `restore_file <id> <path>` | Restores a single file to its pre-transaction state, without closing the transaction or touching anything else — can be called repeatedly. |

`open_transaction` is the one moment that costs something: it snapshots
every `.java` file under the project (same scope `rebuild` itself walks),
not just the ones a later edit will touch. That single upfront snapshot
is also what makes everything else on this list cheap and simple
afterward — a modification is never "recorded" as it happens, it is just
detected, by comparing the live file against what the snapshot already
knows. Only `.java` files are covered: a transaction gives no protection
to anything else the project might contain (build files, resources).

Nested sub-transactions: once `$refactor_foo` is open,
`open_transaction $refactor_foo$part1` opens a sub-transaction beneath it.
It's a stack (LIFO), not a tree: two sibling sub-transactions can't be
open at the same time — the first must be closed before opening another
at the same level. `commit_transaction`/`rollback_transaction` on a parent
transaction automatically closes any still-open children first (deepest
commits first, most recent rollbacks first) — though for `rollback`, that
cascade is really just bookkeeping: `$refactor_foo`'s own snapshot was
taken before `$refactor_foo$part1` ever existed, so rolling `$refactor_foo`
back undoes `part1`'s changes too in the very same step, committed or not.

**Editing outside clide while a transaction is open is the normal way to
use one today.** With no file-modifying command of clide's own yet, the
actual workflow is: `open_transaction`, edit with other tools, `exit` (the
daemon and the open transaction survive — see below), then reconnect later
to decide `commit_transaction` or `rollback_transaction`. A **human**
connection that finds a still-open transaction with files modified since it
opened says so on its own, before anything else — the same output
`list_modified_files` would give, printed unprompted so a reconnecting
person never has to remember to ask. Silent when nothing changed, and
silent when nothing is open.

An **AI** connection never gets this unprompted announcement, even under
the same conditions — its whole contract is to print nothing but what the
commands it was sent actually answer, so a machine client can rely on a
strict 1:1 correspondence between what it wrote and what it reads back. An
AI client that wants to know whether a transaction it is reopening was
touched from outside should call `list_modified_files` itself right after
reconnecting. `--lua` script connections never see this either.

### Help and session

| Command | Role |
|---|---|
| `help` | Lists all commands: one line each in AI mode, an ASCII table under `--human`. |
| `man <keyword>` | Detailed page for a command. |
| `set_max_results <count>` | Caps how many entries a listing command returns, for this session only — see "Truncation" above. |
| `exit` / `quit` | Disconnects cleanly; the daemon and any open transactions survive. |
| `terminate` | Stops the daemon; refuses if a transaction is still open. |

## Scripting a session: `clide --lua <script> <project path>`

`clide --lua audit.lua <project>` sends one Lua script to the daemon instead of
opening an interactive session, and prints back whatever the script printed.
Everything else is unchanged: same daemon, same warm jdtls session, same
project. Use it when the answer needs a loop — "which of this type's methods
has no caller", "which of these files still match X after Y" — where the
command-per-turn mode would spend a round trip per item.

Inside the script, **every command is a function of the same name**, taking its
parameters in the order `help` lists them:

```lua
local members = list_members("6b1e0a4c:src/main/java/demo/Square.java:3:14:Square")
for _, member in ipairs(members.symbols.items) do
  if member.kind == "method" and member.location ~= nil then
    local refs = find_reference("method", member.location.position)
    print(member.name, refs.locations.totalCount)
  end
end
```

Four things are worth knowing before writing one:

- **A result is a table, not text.** No output is parsed: what a command found
  arrives as fields (`totalCount`, `items`, `position.line`…). `RESULTS.md`
  documents every shape; the key names are the record's own.
- **A position may be passed as the table it came in.**
  `find_reference("method", member.location.position)` works, and so does the
  `"md5:path:line:column:name"` string. Either way it is re-checked against
  the file as it stands now, so a position kept across an edit fails loudly
  rather than pointing somewhere else. This is where the `<file-content-md5>`
  earns its keep: a table clide handed you carries it (as `position.md5`), so
  a position held in a local variable across an edit raises `FILE_MODIFIED`
  instead of quietly answering about a file that has moved on. A table a
  script builds by hand, with no `md5` key, means "the file as it is now" —
  the same thing as omitting the md5 from a written token.
- **Listings are still capped.** `maxResults` (100 by default) applies to a
  script exactly as it does to a session: read `totalCount`, not `#items`, and
  call `set_max_results` if the answer needs the whole list.
- **A refused command raises.** `?ERROR <CODE>: <message>` is the error text a
  `pcall` catches, and an uncaught one stops the script and comes back as
  `?ERROR LUA_SCRIPT_FAILED`. A script that wants to handle a failure wraps
  that call in `pcall`; one that does not, stops.

The script gets Lua's `base`, `string`, `table` and `math` libraries — no `io`,
no `os`: everything it touches, it touches through a command. `exit`, `quit`
and `terminate` are not bound either, for the same reason they would make no
sense: a script ends by ending, and the daemon it ran in stays up for the next
one. `--lua` and `--human` cannot be combined.

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
