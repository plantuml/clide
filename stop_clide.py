#!/usr/bin/env python3
"""Kills every clide daemon running on this machine - whichever project it
serves, whoever started it - together with the processes each one launched
(jdtls, the forked test JVMs...).

Why a script of its own, rather than the `terminate` command: `terminate` is a
command like the others, so it has to be sent through a client connection, and
a daemon serves one client at a time. A daemon that is busy (a Lua script that
loops, a test run that hangs, a client that was never closed) answers every
new connection `?ERROR BUSY`, `terminate` included - exactly the situation in
which the daemon has to be killed from the outside. `terminate` also refuses
while a transaction is open. This script does neither: it does not talk to the
daemon at all.

How it finds the daemons: by their command line, not through the per-project
lock files (.clide/tmp/.clide.lock), which only a project path known in advance
could lead to. A daemon is any `java` process started with `-jar <...>clide.jar`
(start_clide.py starts exactly that, see its docstring). Nothing else is
touched: a `java` process that merely mentions clide.jar elsewhere in its
command line, or this script itself, is not a daemon.

How it kills them: the daemon's descendants first, then the daemon, so that no
jdtls is left orphaned (on Windows, taskkill /T does the whole tree at once).
POSIX: SIGTERM, then SIGKILL for whatever is still alive after
GRACE_SECONDS. Windows: taskkill /F. A lock file left behind is harmless:
clide.py and start_clide.py both treat it as the stale lock of a dead daemon.

Usage:

    python3 stop_clide.py            # kill every clide daemon
    python3 stop_clide.py --list     # only list them, kill nothing

Exit code: 0 if no daemon is left (or none was running), 1 if one could not be
killed.
"""

import json
import os
import re
import signal
import subprocess
import sys
import time
from typing import Dict, List, NamedTuple, Optional

# How long a daemon gets, after SIGTERM, to end by itself before SIGKILL.
GRACE_SECONDS = 3.0

LIST_FLAG = "--list"

# `-jar clide.jar`, with or without a directory in front of the jar's name and
# with or without quotes around the whole path (Windows command lines).
JAR_PATTERN = re.compile(r'-jar\s+"?[^\s"]*clide\.jar"?(?=\s|$)', re.IGNORECASE)


class Proc(NamedTuple):
	pid: int
	ppid: int
	command: str


def is_daemon(proc: Proc) -> bool:
	"""True for a java process launched as `java -jar <...>clide.jar ...`."""
	command = proc.command.lstrip()
	if command.startswith('"'):
		executable = command[1:].split('"', 1)[0]      # quoted: the path may hold spaces
	else:
		executable = command.split(None, 1)[0]
	first = executable.replace("\\", "/").rsplit("/", 1)[-1].lower()
	return first.startswith("java") and JAR_PATTERN.search(proc.command) is not None


def project_of(proc: Proc) -> str:
	"""What follows clide.jar on the command line (the project path, possibly
	preceded by --human), for display only - nothing depends on it."""
	match = JAR_PATTERN.search(proc.command)
	rest = proc.command[match.end():].strip() if match else ""
	rest = re.sub(r"^--human\s*", "", rest)
	return rest.strip('"') or "?"


def parse_ps(output: str) -> List[Proc]:
	"""Parses `ps -eo pid=,ppid=,args=`."""
	procs = []
	for line in output.splitlines():
		parts = line.split(None, 2)
		if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
			procs.append(Proc(int(parts[0]), int(parts[1]), parts[2]))
	return procs


def parse_powershell_json(output: str) -> List[Proc]:
	"""Parses the ConvertTo-Json of Get-CimInstance Win32_Process: one object,
	or a list of them (PowerShell does not wrap a single result)."""
	output = output.strip()
	if not output:
		return []
	data = json.loads(output)
	if isinstance(data, dict):
		data = [data]
	return [Proc(int(item["ProcessId"]), int(item["ParentProcessId"]), item["CommandLine"])
			for item in data if item.get("CommandLine")]


def list_processes() -> List[Proc]:
	if os.name == "nt":
		command = ("Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
				"Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json")
		output = subprocess.run(["powershell", "-NoProfile", "-Command", command],
				capture_output=True, text=True, check=True).stdout
		return parse_powershell_json(output)

	output = subprocess.run(["ps", "-eo", "pid=,ppid=,args="], capture_output=True, text=True, check=True).stdout
	return parse_ps(output)


def descendants(root: int, procs: List[Proc]) -> List[int]:
	"""Pids below root, deepest first - the order to kill them in."""
	children: Dict[int, List[int]] = {}
	for proc in procs:
		children.setdefault(proc.ppid, []).append(proc.pid)

	ordered: List[int] = []
	stack = list(children.get(root, []))
	while stack:
		pid = stack.pop()
		ordered.append(pid)
		stack.extend(children.get(pid, []))
	ordered.reverse()
	return ordered


def alive(pid: int) -> bool:
	try:
		os.kill(pid, 0)
	except ProcessLookupError:
		return False
	except PermissionError:
		return True
	# A zombie still answers kill(0): its parent has not reaped it yet. Not alive for our purposes.
	try:
		with open(f"/proc/{pid}/stat", encoding="utf-8") as stat:
			return stat.read().rsplit(")", 1)[1].split()[0] != "Z"
	except (OSError, IndexError):
		return True


def kill_posix(pids: List[int]) -> List[int]:
	"""SIGTERM, a grace period, then SIGKILL. Returns the pids still alive."""
	for pid in pids:
		try:
			os.kill(pid, signal.SIGTERM)
		except ProcessLookupError:
			pass

	deadline = time.monotonic() + GRACE_SECONDS
	while time.monotonic() < deadline and any(alive(pid) for pid in pids):
		time.sleep(0.1)

	for pid in pids:
		if alive(pid):
			try:
				os.kill(pid, signal.SIGKILL)
			except ProcessLookupError:
				pass

	time.sleep(0.2)
	return [pid for pid in pids if alive(pid)]


def kill_windows(daemon_pid: int) -> List[int]:
	"""taskkill /T /F: the daemon and its whole tree. Returns [daemon_pid] if it failed."""
	result = subprocess.run(["taskkill", "/PID", str(daemon_pid), "/T", "/F"], capture_output=True, text=True)
	return [] if result.returncode == 0 else [daemon_pid]


def main() -> None:
	args = sys.argv[1:]
	if any(arg != LIST_FLAG for arg in args):
		sys.exit(f"Usage: stop_clide.py [{LIST_FLAG}]")
	list_only = LIST_FLAG in args

	procs = list_processes()
	daemons = [proc for proc in procs if is_daemon(proc) and proc.pid != os.getpid()]

	if not daemons:
		print("stop_clide.py: no clide daemon running.")
		return

	failed = 0
	for daemon in daemons:
		target = f"pid {daemon.pid} for {project_of(daemon)}"
		if list_only:
			print(f"stop_clide.py: daemon {target}")
			continue

		if os.name == "nt":
			left = kill_windows(daemon.pid)
		else:
			left = kill_posix(descendants(daemon.pid, procs) + [daemon.pid])

		if left:
			failed += 1
			print(f"stop_clide.py: could not kill the daemon {target} (still alive: {', '.join(map(str, left))})")
		else:
			print(f"stop_clide.py: killed the daemon {target}")

	if failed:
		sys.exit(1)


if __name__ == "__main__":
	main()
