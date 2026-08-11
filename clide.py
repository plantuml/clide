#!/usr/bin/env python3
"""A thin, fast stand-in for `java -jar clide.jar <project>` - the one call
pattern that matters for latency: an already-running daemon, no --human, no
--lua, no --require-live-daemon. Everything else is delegated to the real
jar, unchanged.

Why this exists: every clide invocation through the usual wrapper pays for
starting a fresh JVM before a single byte of the protocol is exchanged - on
this machine, `java -jar clide.jar` alone (bad args, no daemon work at all)
already costs ~65-70ms, and a full connect-relay-disconnect round trip against
a warm daemon comes in around ~100-150ms. A CPython process doing the same
socket connect and byte relay starts in a fraction of that. For a session that
calls clide many times in a row - the common case once a daemon is up - that
difference adds up to real, felt latency.

Deliberately narrow in scope, and deliberately not a second implementation of
ClideClient: this script knows how to do exactly one thing (connect to an
already-live daemon in default AI mode, relay stdin/stdout, disconnect) and
falls back to `java -jar clide.jar` - via exec, so the fallback is what
actually serves the request - for every other case: no daemon yet, a dead
one, or any of --human/--lua/--require-live-daemon. All of the boot
orchestration, flag handling, and protocol evolution stays in one place -
ClideClient - rather than being ported and kept in sync in two languages.
Concretely: nothing dies from a stale lock file or a build failure here, it
just becomes `java`'s problem exactly as it is today.

Usage - a drop-in replacement for the hot path in the wrapper described in
clide/CLAUDE.md and in a target project's own CLAUDE*.md:

    { cat; echo exit; } | python3 /path/to/clide/clide.py . 2>/dev/null

clide.jar is expected next to this script (see jar_path()) - the layout `ant
dist` already produces at the repository root.
"""

import os
import socket
import sys
import threading
from pathlib import Path
from typing import List, Optional, Tuple

STAGING_DIR = ".clide/tmp"
LOCK_FILE_NAME = ".clide.lock"

# Same budget DaemonLock.PROBE_TIMEOUT_MILLIS gives the daemon on the Java
# side: long enough that a merely slow reply isn't mistaken for a dead
# daemon, short enough that a genuinely dead one doesn't stall this fast
# path - it just falls back instead.
CONNECT_TIMEOUT_SECONDS = 0.3

# Any of these means this invocation isn't the plain "just relay" case this
# script exists for - see the module docstring. Handled by falling back to
# the jar rather than ported here, so this list only has to name them, never
# implement them.
FALLBACK_FLAGS = ("--human", "--lua", "--require-live-daemon")

CHUNK_SIZE = 65536


def jar_path() -> Path:
	return Path(__file__).resolve().parent / "clide.jar"


def fall_back_to_java(args: List[str]) -> None:
	"""Replaces this process with `java -jar clide.jar <args>` - not a
	subprocess call, an exec: the fallback becomes the real process handling
	this invocation, inheriting stdin/stdout/stderr exactly as they are.
	Safe to call at any point up to (but not including) the first stdin read
	in relay() - nothing before that point consumes any of it, so the jar
	still sees the whole of it, unconsumed.
	"""
	java_args = ["java", "-jar", str(jar_path()), *args]
	os.execvp("java", java_args)
	# os.execvp() does not return on success - reaching here means "java"
	# itself could not be found or started, not that clide failed.
	sys.exit(f"clide.py: could not exec java ({' '.join(java_args)})")


def project_root_from_args(args: List[str]) -> Optional[str]:
	"""The lone positional argument this script's narrow case takes, or None
	for anything else - which, same as an unrecognized case in Main.java,
	just means falling back rather than guessing. Flags are handled by the
	caller before this runs (see main()); what's left here is meant to be
	exactly the project path, one argument, nothing more.
	"""
	if len(args) != 1:
		return None
	return args[0]


def read_lock(project_root: str) -> Optional[Tuple[int, int]]:
	"""(port, pid) from <project_root>/.clide/tmp/.clide.lock, or None if
	there's nothing there to read or it doesn't parse - mirrors
	DaemonLock.parse() closely enough to agree with it on every lock file
	the Java side could have written, without needing DaemonLock.State's
	ABSENT/DEAD distinction: both collapse to the same thing here - "fall
	back and let ClideClient work it out".
	"""
	lock_file = os.path.join(project_root, STAGING_DIR, LOCK_FILE_NAME)
	try:
		lines = Path(lock_file).read_text(encoding="utf-8").splitlines()
		if len(lines) < 2:
			return None
		return int(lines[0].strip()), int(lines[1].strip())
	except (OSError, ValueError):
		return None


def connect(port: int) -> Optional[socket.socket]:
	"""A live socket to the daemon on 127.0.0.1:port, or None if it doesn't
	answer within CONNECT_TIMEOUT_SECONDS - a dead or wedged daemon must not
	make this fast path hang, it must hand off to the fallback just as
	promptly as a missing lock file does.
	"""
	try:
		sock = socket.create_connection(("127.0.0.1", port), timeout=CONNECT_TIMEOUT_SECONDS)
	except OSError:
		return None
	sock.settimeout(None)  # the relay itself is meant to block - only the connect attempt is time-boxed
	return sock


def pump_socket_to_stdout(sock: socket.socket) -> None:
	try:
		while True:
			chunk = sock.recv(CHUNK_SIZE)
			if not chunk:
				break
			sys.stdout.buffer.write(chunk)
	except OSError:
		pass  # the daemon closed its side mid-read - the normal end of a connection
	sys.stdout.buffer.flush()


def relay(sock: socket.socket) -> None:
	"""Pumps this process' stdin to the daemon and the daemon's replies back
	to stdout, until stdin runs dry and the daemon has said everything it's
	going to say - see ClideClient.relay() for the Java equivalent this
	mirrors. Runs the daemon-to-client direction on its own thread for the
	same reason that one does: so a daemon that answers before this process
	has finished sending (or that closes the connection on its own, e.g.
	after "exit") is not left waiting on a stdin read that has nothing left
	to unblock it.

	Unlike ClideClient.relay(), there is no System.exit()-equivalent forcing
	a stuck read to give up: this script's fallback rule (see FALLBACK_FLAGS
	and project_root_from_args()) keeps it out of the one case that needs
	that - an interactive --human session with a real keyboard behind
	stdin. Every case relay() actually handles here has stdin as a pipe that
	reaches EOF on its own once its writer is done, which is what actually
	ends both directions below.
	"""
	output_thread = threading.Thread(target=pump_socket_to_stdout, args=(sock,), daemon=True)
	output_thread.start()

	try:
		while True:
			chunk = sys.stdin.buffer.read(CHUNK_SIZE)
			if not chunk:
				break
			sock.sendall(chunk)
	finally:
		# Tells the daemon this client has nothing more to send - same half-close
		# ClideClient.relay() does, and for the same reason (see there): the read
		# direction stays open for whatever the daemon still has to say.
		try:
			sock.shutdown(socket.SHUT_WR)
		except OSError:
			pass

	output_thread.join()


def main() -> None:
	args = sys.argv[1:]

	if any(flag in args for flag in FALLBACK_FLAGS):
		fall_back_to_java(args)
		return

	project_root_arg = project_root_from_args(args)
	if project_root_arg is None:
		fall_back_to_java(args)
		return

	# Deliberately lexical, like Main.parseProjectRoot()'s toAbsolutePath().
	# normalize(): no symlink resolution, so this agrees with the path the
	# Java side used to name the daemon's own lock file even when the
	# project path (or an ancestor of it) is a symlink - Path.resolve()
	# would not have.
	project_root = os.path.abspath(project_root_arg)

	lock = read_lock(project_root)
	if lock is None:
		fall_back_to_java(args)
		return
	port, pid = lock

	sock = connect(port)
	if sock is None:
		fall_back_to_java(args)
		return

	banner = f"*** clide connected to daemon (pid {pid}) for {project_root}\n"
	sys.stdout.buffer.write(banner.encode("utf-8"))
	sys.stdout.buffer.flush()

	with sock:
		relay(sock)


if __name__ == "__main__":
	main()
