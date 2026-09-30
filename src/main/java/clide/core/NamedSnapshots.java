package clide.core;

import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The snapshots a client took on purpose, by the name it chose - the state
 * behind "snapshot" and "changed_since".
 *
 * Nothing to do with the snapshot of the last sync that JdtlsSession keeps: that
 * one is what jdtls was last told, moves on its own at every build, and only
 * covers .java files. These cover whatever glob the client named, are never
 * taken behind its back, and only move when it takes another under the same
 * name - so "what changed since I looked" keeps meaning the same thing however
 * many builds happen in between.
 *
 * Held by the daemon, not by a connection: a client that takes a snapshot in one
 * session and asks in the next must find it, exactly as with an open
 * transaction. They are lost when the daemon stops, and never written to disk
 * (the content behind each signature is, in the md5 store, but that is
 * Md5Repository's business).
 *
 * The longest a name can be, and the characters it may use, are checked here
 * rather than in each command: a name travels into messages and file listings.
 */
public final class NamedSnapshots {

	/** What a snapshot id may be made of - a word, never something that needs quoting. */
	private static final String VALID_ID = "[A-Za-z0-9_.-]{1,64}";

	/** What a snapshot remembers besides the files: which ones it was asked to cover. */
	public record Named(String glob, PathMatcher matcher, Snapshot snapshot) {
	}

	private final Map<String, Named> byId = new LinkedHashMap<>();

	/** Whether id is acceptable as a snapshot name. */
	public static boolean isValidId(final String id) {
		return id != null && id.matches(VALID_ID);
	}

	/**
	 * Keeps snapshot under id, replacing any earlier one of that name, which is
	 * returned - or null when the name was free. A name taken twice is usually a
	 * script run twice, not a mistake, so it is allowed; it is reported so that it
	 * is never silent.
	 *
	 * @throws IllegalArgumentException if id is not a valid name
	 */
	public Named put(final String id, final Named named) {
		if (isValidId(id) == false)
			throw new IllegalArgumentException("'" + id + "' is not a valid snapshot name - expected 1 to 64 of "
					+ "letters, digits, '_', '-' or '.'");

		return byId.put(id, named);
	}

	/** The snapshot named id, or null if there is none. */
	public Named get(final String id) {
		return byId.get(id);
	}

	/** The names in use, in the order they were first taken. */
	public List<String> ids() {
		return Collections.unmodifiableList(new ArrayList<>(byId.keySet()));
	}

}
