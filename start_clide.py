#!/usr/bin/env python3
"""Starts clide's daemon - the one step that used to mean typing
`java -jar clide.jar [--human] <project>` (see CLAUDE.md) yourself, now fully
hidden behind this script so that using clide never requires typing `java`
directly, only python3 - same as talking to an already-running daemon
already only ever meant (`clide.py`, never a Java client - see its own
docstring).

For now this script does exactly what that command line did, and nothing
more: it resolves `java` (on PATH, same as the command line it replaces
relied on) and `clide.jar` (next to this script - the two are meant to live
side by side, same as `clide.py` already does), forwards --human and the
project path unchanged, and runs the result in the foreground, blocking,
with this process' own stdin/stdout/stderr simply inherited by the daemon -
a --human session's `> READY`/`> <parameter> ?` prompts and jdtls' own boot
trace on stderr read exactly as they would without this script in between.
Backgrounding the daemon (`nohup ... &`, a systemd unit, a screen/tmux
session...) is still up to whoever runs this script, exactly as it was for
the java command it replaces - see CLAUDE.md, "Step 1".

Deliberately not more than that yet: this script does not detach the daemon
from its own lifetime, does not wait for the daemon to be ready before
returning (there is nothing to wait for - it only returns once the daemon
itself has stopped), and does not guard against two invocations racing to
start the same project's daemon at once (DaemonLock only ever detects one
already running, on the Java side or clide.py's - starting one is still
first-come, unserialized). Doing any of that safely - a startup lock,
somewhere for the daemon's own boot output to go once nothing is left
attached to its stdout, a decision on whether the daemon should keep running
after this script's own process ends - is a follow-up, not folded into this
first version.

Usage:

    python3 start_clide.py [--human] <project path>

Connecting to the daemon this starts is clide.py's job, not this script's -
run it separately once this one reports the daemon is up (--human mode) or
simply keeps running (AI mode, where nothing is printed until a client
connects - see CLAUDE.md).
"""

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Tuple

JAR_NAME = "clide.jar"

# Must keep matching PrintMode.HUMAN_FLAG on the Java side, and HUMAN_FLAG in
# clide.py - the same flag, just forwarded here instead of rejected.
HUMAN_FLAG = "--human"

# The exit code this script reports when Ctrl+C ends the daemon it started -
# the shell's own convention (128 + signal number) for "killed by SIGINT",
# used here for the same reason a shell would: this script did not fail, the
# daemon it was watching was interrupted, on purpose.
SIGINT_EXIT_CODE = 130


def parse_args(args: List[str]) -> Tuple[bool, str]:
	"""(human, project_root) from argv[1:] - mirrors clide.py's own
	parse_project_root(), including project_root's lexical (no symlink
	resolution) normalization, kept for consistency even though nothing here
	has to agree with a lock file's own path the way clide.py does: this
	script never reads or writes one, the daemon it starts does that itself.
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
	rather than left to subprocess.call() to fail on, so a missing JDK is
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


def main() -> None:
	human, project_root = parse_args(sys.argv[1:])

	command = [find_java(), "-jar", find_jar()]
	if human:
		command.append(HUMAN_FLAG)
	command.append(project_root)

	# Foreground and blocking, exactly like typing the java command directly -
	# see the module docstring for why this script goes no further than that
	# for now. No stdio redirection: this process' own stdin/stdout/stderr are
	# simply the daemon's, unchanged, so nothing about what a --human session
	# prints, or how jdtls' own boot trace on stderr reads, differs from
	# running that command line without this script in between.
	try:
		return_code = subprocess.call(command)
	except KeyboardInterrupt:
		# The terminal delivered Ctrl+C to both processes at once (same
		# process group, same as it would without this script in between) -
		# nothing left for this script itself to do once the daemon it
		# started has already seen it too.
		return_code = SIGINT_EXIT_CODE

	sys.exit(return_code)


if __name__ == "__main__":
	main()
