package clide.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a classic unified diff (the "---"/"+++"/"@@" format `diff -u`
 * produces) between two in-memory line lists, used by diff_transaction (see
 * clide.command.transaction.DiffTransactionCommand, CLAUDE.md) to show what a
 * transaction changed in one file without shelling out to an external diff
 * tool - clide
 * stays dependency-free (see CLAUDE.md).
 *
 * The alignment is computed with the textbook dynamic-programming longest
 * common subsequence (LCS) over lines, then walked back from
 * (before.size(), after.size()) to (0, 0) to recover the sequence of
 * equal/delete/insert operations. Hunks are grouped with up to 3 lines of
 * unchanged context on each side, merging two change groups into a single
 * hunk when the unchanged gap between them is 6 lines or less (so their
 * respective 3-line contexts would otherwise overlap) - the same convention
 * `diff -u`'s default context uses.
 */
public final class UnifiedDiff {

	private static final int CONTEXT = 3;

	private UnifiedDiff() {
	}

	/** One line of a diff, tagged with how it relates to before/after. */
	private enum Kind {
		EQUAL, DELETE, INSERT
	}

	private record DiffLine(Kind kind, String text) {
	}

	/** A hunk's lines, plus the script index its first line starts at (needed
	 * to compute the "@@ -a,b +c,d @@" header's real 1-based line numbers). */
	private record Hunk(int start, List<DiffLine> lines) {
	}

	/**
	 * Renders a unified diff of before -&gt; after, headed by "--- beforeLabel"
	 * and "+++ afterLabel". Returns "" (no header, nothing at all) when before
	 * and after are equal - callers that always want a header even for a no-op
	 * diff should check that case themselves.
	 */
	public static String render(final List<String> before, final List<String> after, final String beforeLabel,
			final String afterLabel) {
		final List<DiffLine> script = diff(before, after);
		final List<Hunk> hunks = groupHunks(script);
		if (hunks.isEmpty())
			return "";

		// beforePrefix[k]/afterPrefix[k]: how many before-/after-lines (respectively)
		// script[0..k) accounts for - lets each hunk report the real 1-based starting
		// line number it lands on in before/after, instead of always "1".
		final int[] beforePrefix = new int[script.size() + 1];
		final int[] afterPrefix = new int[script.size() + 1];
		for (int k = 0; k < script.size(); k++) {
			beforePrefix[k + 1] = beforePrefix[k] + (script.get(k).kind() != Kind.INSERT ? 1 : 0);
			afterPrefix[k + 1] = afterPrefix[k] + (script.get(k).kind() != Kind.DELETE ? 1 : 0);
		}

		final StringBuilder out = new StringBuilder();
		out.append("--- ").append(beforeLabel).append('\n');
		out.append("+++ ").append(afterLabel).append('\n');

		for (final Hunk hunk : hunks)
			appendHunk(out, hunk, beforePrefix[hunk.start()], afterPrefix[hunk.start()]);

		return out.toString().stripTrailing();
	}

	/**
	 * Walks the LCS table back from the bottom-right corner to produce the
	 * ordered edit script: an EQUAL line for every line common to both inputs
	 * (in the order it appears in both), a DELETE for every before-only line, an
	 * INSERT for every after-only line - interleaved in the order a human diff
	 * expects (deletes just before the inserts that "replace" them).
	 */
	private static List<DiffLine> diff(final List<String> before, final List<String> after) {
		final int n = before.size();
		final int m = after.size();
		final int[][] lcs = new int[n + 1][m + 1];
		for (int i = n - 1; i >= 0; i--)
			for (int j = m - 1; j >= 0; j--)
				lcs[i][j] = before.get(i).equals(after.get(j)) ? lcs[i + 1][j + 1] + 1
						: Math.max(lcs[i + 1][j], lcs[i][j + 1]);

		final List<DiffLine> script = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < n && j < m) {
			if (before.get(i).equals(after.get(j))) {
				script.add(new DiffLine(Kind.EQUAL, before.get(i)));
				i++;
				j++;
			} else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
				script.add(new DiffLine(Kind.DELETE, before.get(i)));
				i++;
			} else {
				script.add(new DiffLine(Kind.INSERT, after.get(j)));
				j++;
			}
		}
		while (i < n) {
			script.add(new DiffLine(Kind.DELETE, before.get(i)));
			i++;
		}
		while (j < m) {
			script.add(new DiffLine(Kind.INSERT, after.get(j)));
			j++;
		}
		return script;
	}

	/**
	 * Groups the edit script into hunks: each hunk keeps up to CONTEXT lines of
	 * unchanged context before/after its changes, and two change regions whose
	 * unchanged gap is at most 2*CONTEXT lines are merged into a single hunk
	 * (their contexts would otherwise overlap). A script with no DELETE/INSERT
	 * at all yields no hunks.
	 */
	private static List<Hunk> groupHunks(final List<DiffLine> script) {
		final List<Hunk> hunks = new ArrayList<>();
		int i = 0;
		while (i < script.size()) {
			if (script.get(i).kind() == Kind.EQUAL) {
				i++;
				continue;
			}

			// found a change; walk back up to CONTEXT lines of leading context
			int start = i;
			int contextTaken = 0;
			while (start > 0 && contextTaken < CONTEXT && script.get(start - 1).kind() == Kind.EQUAL) {
				start--;
				contextTaken++;
			}

			int end = i;
			while (end < script.size()) {
				if (script.get(end).kind() != Kind.EQUAL) {
					end++;
					continue;
				}
				// how long is this run of unchanged lines?
				int runEnd = end;
				while (runEnd < script.size() && script.get(runEnd).kind() == Kind.EQUAL)
					runEnd++;

				final boolean moreChangesFollow = runEnd < script.size();
				final int runLength = runEnd - end;
				if (moreChangesFollow && runLength <= 2 * CONTEXT) {
					// gap small enough to bridge - absorb it and keep extending this hunk
					end = runEnd;
					continue;
				}

				// otherwise this hunk ends after at most CONTEXT lines of trailing context
				end = Math.min(runEnd, end + CONTEXT);
				break;
			}

			hunks.add(new Hunk(start, new ArrayList<>(script.subList(start, end))));
			i = end;
		}
		return hunks;
	}

	private static void appendHunk(final StringBuilder out, final Hunk hunk, final int beforeStartIndex,
			final int afterStartIndex) {
		int beforeCount = 0;
		int afterCount = 0;
		for (final DiffLine line : hunk.lines()) {
			if (line.kind() != Kind.INSERT)
				beforeCount++;
			if (line.kind() != Kind.DELETE)
				afterCount++;
		}

		// diff -u convention: a hunk with zero lines on one side reports that side's
		// start as the line right before the insertion/deletion point (beforeStartIndex/
		// afterStartIndex already is that point, 0-based -> printed as-is); otherwise the
		// start is the first line of the hunk on that side, 1-based.
		final int beforeStart = beforeCount == 0 ? beforeStartIndex : beforeStartIndex + 1;
		final int afterStart = afterCount == 0 ? afterStartIndex : afterStartIndex + 1;

		out.append("@@ -").append(beforeStart).append(',').append(beforeCount).append(" +").append(afterStart)
				.append(',').append(afterCount).append(" @@").append('\n');

		for (final DiffLine line : hunk.lines()) {
			final char marker = switch (line.kind()) {
			case EQUAL -> ' ';
			case DELETE -> '-';
			case INSERT -> '+';
			};
			out.append(marker).append(line.text()).append('\n');
		}
	}

}
