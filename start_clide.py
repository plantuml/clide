#!/usr/bin/env python3
"""Starts clide's daemon - the one step that used to mean typing
`java -jar clide.jar [--human] <project>` (see CLAUDE.md) yourself, now fully
hidden behind this script so that using clide never requires typing `java`
directly, only python3 - same as talking to an already-running daemon
already only ever meant (`clide.py`, never a Java client - see its own
docstring).

What this script does: resolves `java` (on PATH) and `clide.jar` (next to
this script - the two are meant to live side by side, same as `clide.py`
already does), forwards --human and the project path unchanged, and launches
the daemon *detached* from this script's own lifetime - its own process
group (see spawn_detached()), stdout/stderr redirected to a log file instead
of inherited, so it keeps running long after this script has exited, exactly
as intended for something meant to stay up "across many later client
connections" (see CLAUDE.md). It then waits - polling the same lock file
clide.py itself reads (see probe(), imported from clide.py rather than
re-implemented, so the two can never disagree on what counts as "ready") -
and returns only once the daemon answers, or exits early with a clear error
if the daemon dies before that. Either way, `python3 clide.py <project>` is
safe to run the moment this script's own process ends successfully - that is
the whole point: knowing the daemon is ready is what this script now does
that a bare `java -jar clide.jar` command line never could tell you itself.

While it waits, it also echoes the daemon's own boot trace - the log file's
new content as it is written - to this script's stdout (see
wait_for_ready()/echo_new_log_output()), so what you see running
`start_clide.py` reads the same as running `java -jar clide.jar` directly
would, even though the daemon's own stdout is, underneath, a file rather
than this script's terminal.

If a daemon already answers for this project when this script starts, it
reports that daemon as ready and starts nothing new - calling this script
again is safe, not a way to end up with two daemons for one project.

What this deliberately does NOT do: guard against two invocations racing to
launch a fresh daemon for the same project at the same instant (the
already-running check above closes the common, sequential case - calling
this script again after an earlier one succeeded - not two calls landing in
the same instant, which would need a real cross-process lock this version
does not add); pick a boot timeout to give up after (see wait_for_ready() -
a large project's first build can take minutes, so this waits for either
readiness or the daemon's own process exiting, never on a clock); or keep
the daemon running once THIS SCRIPT is asked to stop being kept running
itself - Ctrl+C here only interrupts the wait, never the daemon, since it is
already detached before the wait begins (see main()).

Because the daemon ends up detached, with nothing printing to a terminal
anyone is watching live, this script is not the right fit for a process
supervisor that wants to own and track the daemon as its own main process
(systemd's plain Type=simple, a foreground Docker entrypoint...) - run
`java -jar clide.jar [--human] <project>` directly for that, unchanged; this
script only ever wraps that exact command line, it does not replace it.

Usage:

    python3 start_clide.py [--human] <project path>

Connecting to the daemon this starts is clide.py's job, not this script's -
run it separately once this one has returned.
"""

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import IO, List, NamedTuple, Optional, Tuple

import clide

JAR_NAME = "clide.jar"

# The daemon's own boot trace and everything the JVM itself might print
# (a stack trace on a port bind failure, "Picked up JAVA_TOOL_OPTIONS...",
# whatever else nothing here can predict) - kept next to the other files
# clide leaves in the project's own staging directory (clide.STAGING_DIR),
# and under the SAME name (.clide-daemon.log) the old Java client
# (ClideClient, before clide.py replaced it - see HISTORY.md) used for
# exactly the same purpose, back when it also started the daemon itself.
LOG_FILE_NAME = ".clide-daemon.log"

# How often to check whether the daemon has become reachable yet, once it
# has been launched - see wait_for_ready(). Small enough that "ready" is
# reported promptly once it actually is, large enough not to spend this
# script's time doing nothing but probing a daemon that is still busy
# extracting jdtls or indexing a large project.
POLL_INTERVAL_SECONDS = 0.3

# How many of the log file's own last lines to show inline when the daemon
# exits before becoming ready - enough to usually catch the actual error
# without dumping an entire boot trace back at whoever is reading this
# script's own error message.
LOG_TAIL_LINES = 20

# Must keep matching PrintMode.HUMAN_FLAG on the Java side, and HUMAN_FLAG in
# clide.py - the same flag, just forwarded here instead of rejected.
HUMAN_FLAG = "--human"


def parse_args(args: List[str]) -> Tuple[bool, str]:
	"""(human, project_root) from argv[1:] - mirrors clide.py's own
	parse_project_root(), including project_root's lexical (no symlink
	resolution) normalization: this DOES have to agree with the path the
	daemon will use to name its own lock file, unlike the first version of
	this script - see probe() below, which reads that same lock file.
	"""
	human = HUMAN_FLAG in args
	remaining = [arg for arg in args if arg != HUMAN_FLAG]

	if len(remaining) != 1:
		sys.exit(f"Usage: start_clide.py [{HUMAN_FLAG}] <project path>")

	project_root = os.path.abspath(remaining[0])
	if not os.path.isdir(project_root):
		sys.exit(f"Not a directory: {project_root}")

	return human, project_root


def find_java() -> str:
	"""The `java` this script runs `-jar clide.jar` with - resolved from PATH,
	same as the command line it replaces already relied on. Checked explicitly
	rather than left to subprocess.Popen() to fail on, so a missing JDK is
	reported in clide's own terms instead of an OS-level "No such file or
	directory" naming an executable the person never typed themselves.
	"""
	java = shutil.which("java")
	if java is None:
		sys.exit(
			"start_clide.py: no 'java' on PATH - clide's daemon is a JVM program, "
			"a JDK has to be installed and on PATH before this script can start it "
			"(a JRE alone will not do - see CLAUDE.md)."
		)
	return java


def find_jar() -> str:
	"""clide.jar, expected next to this script - the two are meant to be run
	from the same place (see the module docstring), never one found via PATH
	and the other via cwd. A missing jar means "not built yet", not "not
	found": pointed at `ant dist`, the only supported way to produce it (see
	CODING.md) - never at a stray `-cp build/classes` invocation, which is
	missing the resources clide.jar carries for the daemon's own runtime use
	(the jdtls archive, the vendored JUnit jars - see CLAUDE.md).
	"""
	jar = Path(__file__).resolve().parent / JAR_NAME
	if not jar.is_file():
		sys.exit(
			f"start_clide.py: {jar} not found - build it first with 'ant dist' "
			"(see CLAUDE.md), or place clide.jar next to this script."
		)
	return str(jar)


def spawn_detached(command: List[str], log_path: Path) -> subprocess.Popen:
	"""Launches command with its stdout/stderr going to log_path (created
	fresh, its parent directory too if this is the very first thing clide
	ever writes there - same reason ClideClient.ensureDaemon() used to create
	it itself rather than depend on the daemon having done so first, see
	HISTORY.md) instead of being inherited, and in its own process group
	(start_new_session on POSIX, CREATE_NEW_PROCESS_GROUP on Windows) instead
	of this script's - the two together are what let the daemon keep running,
	unaffected, after this script's own process exits (successfully, on an
	error, or on Ctrl+C: see main()), the same as it would if backgrounded by
	hand with `nohup ... &` or a systemd unit (see CLAUDE.md).

	Opened "wb" (truncated), not "ab" like the old ClideClient.ensureDaemon()
	this otherwise mirrors: main() only ever calls this once there is no live
	daemon for the project already (see its own probe() check), so whatever
	is left in an existing log at that point is a past run's story, not this
	one's - and tail_log_while_waiting() below reads this same file from its
	very start, which would otherwise replay that old content as if it were
	live output the moment this call is made.

	stdin is DEVNULL, not inherited either: the daemon never reads from it (it
	only ever talks over the TCP socket clide.py connects to - see
	ClideDaemon), and a detached process has no terminal of its own to read
	from in the first place.
	"""
	log_path.parent.mkdir(parents=True, exist_ok=True)
	log_file = open(log_path, "wb")  # noqa: SIM115 - closed by the Popen call below owning its fd

	popen_kwargs = {}
	if os.name == "posix":
		popen_kwargs["start_new_session"] = True
	elif os.name == "nt":
		popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP

	try:
		return subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log_file, stderr=subprocess.STDOUT,
				**popen_kwargs)
	finally:
		log_file.close()  # the child has its own duplicated fd by now; this process no longer needs one


def log_tail(log_path: Path) -> str:
	"""The log file's own last LOG_TAIL_LINES lines, for an error message that
	needs to show why the daemon died without requiring a second command -
	best-effort: an unreadable or missing log is not this function's problem
	to raise, the caller already has a real error of its own to report.
	"""
	try:
		lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
	except OSError:
		return "(could not read the log)"

	return "\n".join(lines[-LOG_TAIL_LINES:])


class WaitOutcome(NamedTuple):
	"""Exactly one of the two is ever set - see wait_for_ready(). Kept separate
	from clide.DaemonState rather than folding "the process already exited"
	into one of its existing fields (port/pid) with a special meaning: a
	repurposed field is exactly the kind of thing CLAUDE.md/RESULTS.md warn
	against elsewhere in clide's own payloads, for the same reason - a reader
	should never have to remember that pid sometimes means something else.
	"""

	ready: Optional["clide.DaemonState"]
	exited_with: Optional[int]


def open_log_tail(log_path: Path) -> Optional[IO[bytes]]:
	"""A read handle on log_path, positioned at its start, for
	wait_for_ready() to poll for new bytes as the daemon writes them - or None
	if it can't be opened for reading right now, which is not fatal to
	anything this script actually promises (see its caller): the daemon's own
	progress is still all there in the file itself, this only loses the
	live copy of it on this script's own stdout while waiting.
	"""
	try:
		return open(log_path, "rb")
	except OSError as error:
		print(f"start_clide.py: could not tail {log_path} live ({error}) - waiting silently; "
				f"its own output is still all there once this returns.")
		return None


def echo_new_log_output(tail: Optional[IO[bytes]]) -> None:
	"""Copies whatever log bytes have appeared since the last call straight to
	this script's own stdout, unprocessed - the same bytes the daemon itself
	would have printed here without this script in between (see the module
	docstring), including a build stage's own " [OK]" landing on the line
	`System.out.print()` started earlier rather than a fresh one. Flushed
	explicitly for the same reason clide.py's pump_socket_to_stdout() already
	is: sys.stdout.buffer on its own is block-buffered, and a short chunk
	would otherwise sit unseen until enough further output arrives to fill
	that buffer.
	"""
	if tail is None:
		return

	chunk = tail.read()
	if chunk:
		sys.stdout.buffer.write(chunk)
		sys.stdout.buffer.flush()


def wait_for_ready(process: "subprocess.Popen[bytes]", project_root: str, log_path: Path) -> WaitOutcome:
	"""Blocks until either the daemon this script just launched answers on the
	port it wrote to its own lock file (see clide.probe(), imported rather
	than re-implemented so the two scripts can never disagree on what counts
	as "ready" - the exact moment DaemonLock.write() runs on the Java side,
	see ClideDaemon), or that same process exits on its own first - a crash,
	a bad project path, a port bind failure, anything that means it will
	never become ready and this script must say so instead of waiting
	forever. Meanwhile, echoes log_path's own new content to this script's
	stdout as it is written (see echo_new_log_output()) - the daemon's boot
	trace reads exactly as it would running `java -jar clide.jar` directly,
	the one thing lost by redirecting it to a file instead of inheriting it
	(see spawn_detached()).

	Deliberately no timeout beyond "the daemon answers or its process exits":
	a big project's first build (jdtls extraction plus its own indexing) can
	take minutes, not seconds, and guessing a cutoff that is safe for both a
	small project and PlantUML itself is not a number this script can pick
	honestly - see the module docstring.
	"""
	tail = open_log_tail(log_path)
	try:
		while True:
			echo_new_log_output(tail)

			exit_code = process.poll()
			if exit_code is not None:
				echo_new_log_output(tail)  # whatever landed between the read above and this exit becoming visible
				return WaitOutcome(ready=None, exited_with=exit_code)

			state = clide.probe(project_root)
			if state.live:
				return WaitOutcome(ready=state, exited_with=None)

			time.sleep(POLL_INTERVAL_SECONDS)
	finally:
		if tail is not None:
			tail.close()


def main() -> None:
	human, project_root = parse_args(sys.argv[1:])

	already = clide.probe(project_root)
	if already.live:
		print(f"start_clide.py: daemon already running on port {already.port} (pid {already.pid}) "
				f"for {project_root} - ready to use, nothing started.")
		return

	command = [find_java(), "-jar", find_jar()]
	if human:
		command.append(HUMAN_FLAG)
	command.append(project_root)

	log_path = Path(project_root) / clide.STAGING_DIR / LOG_FILE_NAME
	process = spawn_detached(command, log_path)

	print(f"start_clide.py: daemon starting for {project_root} (pid {process.pid}, detached) - "
			f"waiting for it to be ready (see {log_path} for its own progress) ...")

	try:
		outcome = wait_for_ready(process, project_root, log_path)
	except KeyboardInterrupt:
		sys.exit(f"start_clide.py: stopped waiting, not the daemon - pid {process.pid} keeps starting in the "
				f"background (it was launched detached before this wait began). Check {log_path}, or just run "
				f"start_clide.py again shortly: it will report the same daemon as ready once it is, without "
				f"starting a second one.")

	if outcome.exited_with is not None:
		sys.exit(f"start_clide.py: the daemon for {project_root} exited before becoming ready "
				f"(exit code {outcome.exited_with}) - see {log_path}, last {LOG_TAIL_LINES} line(s):\n"
				f"{log_tail(log_path)}")

	state = outcome.ready
	print(f"start_clide.py: daemon ready on port {state.port} (pid {state.pid}) for {project_root} - "
			f"python3 clide.py {project_root} is ready to use.")


if __name__ == "__main__":
	main()
