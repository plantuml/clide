#!/usr/bin/env python3
"""clide's client - the whole of it. There is no more Java client (no more
ClideClient/Main-as-client): the daemon (`java -jar clide.jar [--human]
<project>`, see CLAUDE.md) is now clide.jar's only role, started explicitly
and separately, and this script is the only way to talk to one. Nothing here
falls back to Java for anything - a case this script does not handle is a
case that fails, with a message, rather than being silently served by a jar
this script does not even look for.

Two reasons this replaced the old Java client rather than just growing next
to it. First, latency: a CPython process doing a socket connect and a byte
relay starts in a fraction of the time a fresh JVM needs before it can do the
same - felt on every one of the many short "clide <project>" calls a session
makes once a daemon is already up. Second, and the reason for this rewrite in
particular: once whether a connection prints "> READY"/"> <parameter> ?"
prompts became a property of the *daemon* (fixed at its startup, via --human
- see PrintMode, Main.java) rather than of each connection, there was no
reason left for a client to be able to do anything a daemon it didn't start
could refuse - so the one case last version's fallback existed for (this
script not knowing what to do) simply stopped needing Java to cover for it.

What this script does, precisely: find the daemon already running for a
project (via its lock file - read_lock()/probe(), mirroring DaemonLock on the
Java side closely enough to agree with it on every lock file the daemon could
have written), connect to it, and relay - stdin/stdout for an ordinary
session, or a --lua script's content in place of stdin for a scripted one
(see relay()). If no daemon answers, this script says so and exits - it never
starts one itself, and never re-execs java to fall back to anything.

Usage:

    python3 clide.py <project path>
    python3 clide.py --lua <script path> <project path>

Starting the daemon is a separate, earlier step - not this script's job:

    java -jar clide.jar [--human] <project path>
"""

import os
import socket
import sys
import threading
from pathlib import Path
from typing import IO, List, NamedTuple, Optional, Tuple

STAGING_DIR = ".clide/tmp"
LOCK_FILE_NAME = ".clide.lock"

# Same budget DaemonLock.PROBE_TIMEOUT_MILLIS gives the daemon on the Java
# side: long enough that a merely slow reply isn't mistaken for a dead
# daemon, short enough that a genuinely dead one is reported promptly rather
# than hanging this script.
CONNECT_TIMEOUT_SECONDS = 0.3

# The one flag this script itself still recognizes as a handshake to send -
# see ConnectionMode.SCRIPT_FLAG on the Java side, which this string must
# keep matching exactly.
SCRIPT_FLAG = "--lua"

# Not a flag this script accepts - only ever used to recognize the mistake of
# passing it here and point at where it now belongs (see main()). Must keep
# matching PrintMode.HUMAN_FLAG on the Java side.
HUMAN_FLAG = "--human"

CHUNK_SIZE = 65536


class DaemonState(NamedTuple):
	"""Mirrors DaemonLock.State/DaemonLock on the Java side (see
	clide.daemon.DaemonLock) closely enough to agree with it on every lock file
	the daemon could have written - LIVE/DEAD/ABSENT, plus the port and pid a
	lock file named (both None for ABSENT: nothing was ever recorded to report).
	"""

	live: bool
	dead: bool
	port: Optional[int]
	pid: Optional[int]


def read_lock(project_root: str) -> Optional[Tuple[int, int]]:
	"""(port, pid) from <project_root>/.clide/tmp/.clide.lock, or None if
	there's nothing there to read or it doesn't parse - a lock file that exists
	but can't be parsed is treated the same as a missing one (ABSENT), exactly
	as DaemonLock.parse() does on the Java side: there is no port or pid to
	report either way, so "nothing usable was ever recorded" is the more honest
	of the two.
	"""
	lock_file = os.path.join(project_root, STAGING_DIR, LOCK_FILE_NAME)
	try:
		lines = Path(lock_file).read_text(encoding="utf-8").splitlines()
		if len(lines) < 2:
			return None
		return int(lines[0].strip()), int(lines[1].strip())
	except (OSError, ValueError):
		return None


def is_reachable(port: int) -> bool:
	try:
		with socket.create_connection(("127.0.0.1", port), timeout=CONNECT_TIMEOUT_SECONDS):
			return True
	except OSError:
		return False


def probe(project_root: str) -> DaemonState:
	"""What DaemonLock.probe(projectRoot) returns on the Java side: whether a
	daemon is already running for this project, told apart from a merely stale
	lock left behind by one that stopped answering without cleaning up after
	itself (crash, kill, machine reboot).
	"""
	lock = read_lock(project_root)
	if lock is None:
		return DaemonState(live=False, dead=False, port=None, pid=None)

	port, pid = lock
	live = is_reachable(port)
	return DaemonState(live=live, dead=not live, port=port, pid=pid)


def connect(port: int) -> Optional[socket.socket]:
	"""A live socket to the daemon on 127.0.0.1:port, or None if it doesn't
	answer within CONNECT_TIMEOUT_SECONDS - a daemon that died in the instant
	between probe() and this call must not make this script hang, it must
	report that promptly instead (see main()).
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
			# Flushed per chunk, not just once the loop ends: sys.stdout.buffer is a
			# plain block-buffered BufferedWriter (writing to .buffer bypasses the
			# line-buffering sys.stdout itself would get on a tty), so without this a
			# short reply (e.g. "> READY") sits in this process' own buffer and never
			# reaches the console until the connection eventually closes.
			sys.stdout.buffer.flush()
	except OSError:
		pass  # the daemon closed its side mid-read - the normal end of a connection
	sys.stdout.buffer.flush()


def relay(sock: socket.socket, source: "IO[bytes]") -> None:
	"""Pumps source (this process' own stdin for an ordinary session, a --lua
	script's content for a scripted one - see main()) to the daemon, and the
	daemon's replies back to stdout, until source runs dry and the daemon has
	said everything it's going to say - mirrors ClideClient.relay() on the Java
	side, which this replaces.

	Runs the daemon-to-client direction on its own thread, same reason that one
	does: so a daemon that answers before this process has finished sending (or
	that closes the connection on its own, e.g. after "exit") is not left
	waiting on a source read that has nothing left to unblock it. Unlike
	ClideClient.relay(), there's no need to force a stuck read to give up here:
	every source this script ever relays - a pipe, a script file, or (for a
	--human session) a person's own keyboard - reaches EOF on its own once its
	writer is done.

	Reads with os.read(), one raw syscall at a time, deliberately not
	source.read(CHUNK_SIZE): a plain io.BufferedReader.read(n) is free to issue
	several underlying reads to fill the whole n bytes before returning it to
	the caller. On a real terminal that means it can sit past the line a human
	just typed and pressed Enter on, waiting for enough further typing to fill
	a 64KiB buffer - so a --human session's very first command would never
	even reach the daemon (and so never get a reply) until that much text had
	accumulated on stdin. os.read() is a single syscall: it returns whatever
	the OS already has - one console line for an interactive --human session,
	or up to CHUNK_SIZE bytes of a script/pipe otherwise - every time, which is
	exactly what this loop already handles either way.
	"""
	output_thread = threading.Thread(target=pump_socket_to_stdout, args=(sock,), daemon=True)
	output_thread.start()

	try:
		while True:
			chunk = os.read(source.fileno(), CHUNK_SIZE)
			if not chunk:
				break
			try:
				sock.sendall(chunk)
			except OSError:
				# The daemon already closed its side of the socket - typically
				# rejectBusy() writing its "?ERROR BUSY" envelope and closing
				# right away (see ClideDaemon.rejectBusy()), which output_thread
				# is already reading and printing concurrently on its own
				# thread, same as it does for a daemon that answers before this
				# process has finished sending (see this function's own doc).
				# source - this process' own stdin, or a --lua script's content
				# - is simply abandoned mid-read at this point: there is
				# nothing left worth sending once the daemon has hung up, and
				# the daemon's own message, already on stdout, is the whole
				# explanation - repeating it here as a second message would
				# only be noise. Stopping quietly here, rather than letting
				# sendall's BrokenPipeError/ConnectionResetError propagate out
				# of main() as an unhandled traceback, is the fix: a client
				# that loses the BUSY race while relay() is mid-send used to
				# exit on a raw Python stack trace instead of the same clean
				# "?ERROR BUSY..." line a client that loses the race before
				# ever sending anything already got.
				break
	finally:
		# Tells the daemon this client has nothing more to send - the read
		# direction stays open for whatever the daemon still has to say. For a
		# script this is not a nicety but the protocol: the daemon reads a script
		# to EOF, and this half-close is that EOF - see ClideDaemon.runScript().
		try:
			sock.shutdown(socket.SHUT_WR)
		except OSError:
			pass

	output_thread.join()


def parse_script_path(args: List[str]) -> Tuple[Optional[str], List[str]]:
	"""(script_path, remaining_args) for a "--lua <script> ..." invocation -
	script_path is None (remaining_args is args unchanged) when there is no
	--lua flag at all, which is not an error but the ordinary case. Exits with
	a usage message if the flag is there but what follows it is missing or not
	a readable file - mirrors Main.parseScriptPath() on the Java side.
	"""
	for i, arg in enumerate(args):
		if arg != SCRIPT_FLAG:
			continue

		if i + 1 >= len(args):
			sys.exit(f"Usage: clide.py {SCRIPT_FLAG} <script path> <project path>")

		script_path = os.path.abspath(args[i + 1])
		if not os.path.isfile(script_path):
			sys.exit(f"Not a file: {script_path}")

		remaining = args[:i] + args[i + 2:]
		return script_path, remaining

	return None, args


def parse_project_root(args: List[str]) -> str:
	"""The single "<project path>" argument left once --lua (and the script path
	it consumed) has been stripped - mirrors Main.parseProjectRoot() on the Java
	side, including its lexical (no symlink resolution) normalization: this has
	to agree with the path the daemon used to name its own lock file even when
	the project path (or an ancestor of it) is a symlink, and Java's
	Path.resolve() would not have.
	"""
	if len(args) != 1:
		sys.exit("Usage: clide.py [--lua <script path>] <project path>")

	project_root = os.path.abspath(args[0])
	if not os.path.isdir(project_root):
		sys.exit(f"Not a directory: {project_root}")

	return project_root


def daemon_not_found_message(project_root: str, state: DaemonState) -> str:
	start_command = f"java -jar clide.jar [--human] {project_root}"

	if state.dead:
		return (
			f"clide: daemon for {project_root} is not responding "
			f"(stale lock: last pid {state.pid}, was on port {state.port}) - "
			f"check whether it crashed, then start a fresh one: {start_command}"
		)

	return f"clide: no daemon running for {project_root} - start one first: {start_command}"


def main() -> None:
	args = sys.argv[1:]

	if HUMAN_FLAG in args:
		sys.exit(
			"clide.py takes no --human flag: the print mode is fixed when the daemon "
			"itself is started (java -jar clide.jar --human <project>), not per "
			"connection - see CLAUDE.md."
		)

	script_path, args = parse_script_path(args)
	project_root = parse_project_root(args)

	# Deliberately NOT probe() here, even though probe() (is_reachable(), even)
	# exists for exactly this "is a daemon there" question and is kept around
	# as the Python-side mirror of DaemonLock.probe() on the Java side. Calling
	# it first would open a real TCP connection to the daemon purely to check
	# liveness, then immediately close it without sending a byte - and the
	# daemon has no way to tell that throwaway connection apart from a genuine
	# client (see ClideDaemon.serveOrRejectIfBusy()): it accepts it, spends its
	# one connectionLock slot on it, and only releases that slot once
	# reader.readLine() sees the probe close its socket and returns EOF. That
	# release is fast but not instantaneous, and the very next connection this
	# same invocation makes - connect(), a few lines below, for the real
	# session - can arrive before it, and lose the race: BUSY, on a daemon
	# with no real second client anywhere. Confirmed empirically against this
	# fix: ~1 in 5 runs of a plain "help/exit" against an otherwise idle,
	# fully-warm daemon (see the accompanying patch notes) got BUSY before
	# this change, purely from clide.py racing against itself. connect()
	# below already answers "is it live" at least as well as probe() would -
	# a successful connect *is* the live session, one socket instead of two -
	# so probe() is only reached now on the path where connect() itself just
	# failed, to build a message for the human.
	lock = read_lock(project_root)
	if lock is None:
		sys.exit(daemon_not_found_message(project_root, DaemonState(live=False, dead=False, port=None, pid=None)))

	port, pid = lock
	sock = connect(port)
	if sock is None:
		# A lock file names a port/pid, but nothing answered there just now -
		# whether that daemon crashed a while ago (an ordinary stale lock) or
		# died in the instant of this very connect() attempt is not something
		# this script can tell apart without the second connection this change
		# exists to avoid, and the two read the same to a human either way:
		# "not responding, check whether it crashed" (see
		# daemon_not_found_message()).
		sys.exit(daemon_not_found_message(project_root, DaemonState(live=False, dead=True, port=port, pid=pid)))

	banner = f"*** clide connected to daemon (pid {pid}) for {project_root}\n"
	sys.stdout.buffer.write(banner.encode("utf-8"))
	sys.stdout.buffer.flush()

	with sock:
		if script_path is not None:
			sock.sendall((SCRIPT_FLAG + "\n").encode("utf-8"))
			with open(script_path, "rb") as script_file:
				relay(sock, script_file)
		else:
			relay(sock, sys.stdin.buffer)


if __name__ == "__main__":
	main()
