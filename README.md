# clide — Command Line IDE

clide gives an AI agent working on a Java codebase the same semantic
abilities a graphical IDE gives a human — compile and get exact errors, run
a single test in isolation, ask "who calls this", "who implements this",
"where is this really declared" — without trying to be a graphical IDE
itself. No autocomplete, no breakpoint debugger: those are features built
for someone typing character by character, not for an agent that works in
turns with code it has already fully written.

Grepping a Java codebase is purely textual: it's blind to inheritance,
overrides and polymorphism, and it can't tell whether the code just written
actually compiles. clide closes that gap by driving a real semantic engine —
the Eclipse JDT Language Server (jdtls) — from the command line, and asking
it the same questions an IDE would.

Three things matter, in priority order, and clide covers all three:

1. **Compile and get exact errors** (`rebuild`) — catches missing imports,
   mismatched signatures and incomplete refactors that would otherwise need
   a human to re-read the code to find.
2. **Run a targeted test**, isolated, not the whole suite (`run_test` /
   `run_tests`).
3. **Semantic queries** — who calls this method, who implements this
   interface, where is the real definition (`find_*` / `hover` /
   `list_members`).

It can also modify code semantically rather than textually: `rename`,
`remove_unused_imports` and `move_class` all run through jdtls' own
refactoring engine, inside a transaction that can be rolled back.

## How it's built

clide has two parts, started separately, in that order:

- a Java **daemon**, one per project, that owns the jdtls session and does
  all the work — started with `python3 start_clide.py <project-path>`;
- a Python **client**, `clide.py`, with no dependency outside the standard
  library, that connects to an already-running daemon and relays commands
  to it — started with `python3 clide.py <project-path>`.

Both are `python3` from the outside; the daemon is still a JVM process
underneath (`start_clide.py` just runs `java -jar clide.jar [--human]
<project-path>` on your behalf), but nothing about using clide requires
typing `java` yourself. `start_clide.py` detaches the daemon and waits for it
to become ready before returning, so `clide.py <project-path>` is safe to run
the moment it reports success; running it again for a project that already
has a daemon up is safe too — it just says so, it never starts a second one.
It also remembers that project path, so a bare `python3 clide.py` — no path
at all — defaults to it; passing a path explicitly still works exactly as
before and never changes what that default is.

The daemon and the client are never the same command: starting the daemon
means picking its print mode (default machine-readable, or `--human`) for
its whole lifetime, and nothing starts a daemon automatically on a client's
behalf — including `start_clide.py` itself, which only ever starts one when
it is the command being run, never as a fallback from anything else.

A session can also be scripted in Lua instead of driven command by command:
`python3 clide.py --lua <script.lua> <project-path>` — useful for a
question that needs a loop, like "which of this type's methods has no
caller".

## Building clide itself

**Build with `ant` only, never with `gradlew`/Gradle.** The Gradle wrapper
needs to download its distribution from `services.gradle.org`, which isn't
reachable from every sandboxed environment; `ant` compiles and packages
`clide.jar` with no network access needed.

```
ant dist    # builds clide.jar
ant test    # runs the test suite
```

**Always run the packaged jar** (`python3 start_clide.py <project-path>`,
which runs `java -jar clide.jar <project-path>` for you), never the compiled
classes with `lib/` on the classpath by hand — `clide.jar` carries resources
(a bundled jdtls, JUnit jars for the target project) that the code reads at
runtime and that a classes-only run silently doesn't have.

jdtls itself is bundled inside `clide.jar` — nothing to install separately —
and the `.project`/`.classpath` files jdtls needs are generated and cleaned
up automatically for every project clide opens; they should never be
prepared by hand.

## Talking to clide

The client speaks a strict line-oriented protocol: one token per line, the
command name first, then each parameter on its own line. Every answer comes
back as one of three shapes: the result itself, `?ERROR <CODE>: <message>`,
or `!WARNING <CODE>: <message>` followed by the result. Locations are given
in a single self-contained `<position>` token
(`<file-content-md5>:<file path>:<line>:<column>:<name>`, with shorter
forms also accepted) so a result from one command can be pasted straight
into the next one with no editing.

Built-in help covers the rest: `help` lists every command, `man <keyword>`
gives a detailed page for one of them.

## Documentation map

- **`CLAUDE.md`** — the full reference for using clide today: every
  command, the `<position>` notation, transactions, result shapes. Start
  here.
- **`SYMBOLS.md`** — the full grammar of the position/symbol notation.
- **`RESULTS.md`** — the exact shape of every command's answer, field by
  field — for writing a client or a command, not for using one.
- **`JDTLS.md`** — low-level notes on the LSP protocol exchange with jdtls.
- **`LUA.md`** — what the Lua scripting bridge still has to grow.
- **`JAVALENSE.md`, `ASTPARSER.md`** — explorations for future work, not
  implemented yet.
- **`JACOCO.md`** — prototype notes for a test-coverage command.
- **`AGENT-TESTING.md`, `AGENT-TESTING-RESULT.md`, `TESTS.md`** — records
  of test campaigns running clide against real codebases (PlantUML in
  particular).
- **`TODO.md`** — backlog and open design notes.
- **`HISTORY.md`** — past design decisions and reflections, kept as a
  snapshot rather than updated further.
- **`CODING.md`** — style conventions for clide's *own* source code
  (unrelated to using clide as a tool).

## License

No license file is currently published in this repository.
