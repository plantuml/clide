package clide.command.result;

/**
 * Whether a command answered the question it was asked, or refused to.
 *
 * Deliberately binary, and deliberately not a third "OK but degraded" value: a
 * result can carry warnings while still being OK (see CommandResult.warnings()),
 * and a tri-state would silently break every "status == OK" test written before
 * the third value existed. A warning is a field, not a status.
 *
 * Note what is NOT an error here: finding nothing. find_reference with zero
 * usages, list_members on a type with no members, search_regex with no match -
 * all of those are OK results whose listing is empty. An empty answer is an
 * answer; only a question clide could not answer at all is an ERROR.
 */
public enum CommandStatus {
	OK, ERROR
}
