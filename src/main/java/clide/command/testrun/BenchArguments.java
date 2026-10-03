package clide.command.testrun;

import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;

/**
 * The <warmup> and <iterations> of bench_test, profile_bench and compare_test,
 * checked the same way for all three.
 *
 * Bounded because a benchmark holds the daemon for as long as it runs - the
 * suite's 600 seconds at most, see ProjectTests - and a typo of a zero too many
 * would otherwise spend all of them before saying anything.
 */
final class BenchArguments {

	static final int MAX_WARMUP = 1000;
	static final int MAX_ITERATIONS = 1000;

	final int warmup;
	final int iterations;
	final CommandResult error;

	private BenchArguments(final int warmup, final int iterations, final CommandResult error) {
		this.warmup = warmup;
		this.iterations = iterations;
		this.error = error;
	}

	static BenchArguments parse(final String warmup, final String iterations) {
		final int w;
		final int n;
		try {
			w = Integer.parseInt(warmup);
			n = Integer.parseInt(iterations);
		} catch (final NumberFormatException tooBig) {
			return new BenchArguments(0, 0, CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE,
					"<warmup> is at most " + MAX_WARMUP + " and <iterations> at most " + MAX_ITERATIONS));
		}

		if (w > MAX_WARMUP)
			return new BenchArguments(0, 0,
					CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, "<warmup> is at most " + MAX_WARMUP + ": got " + w));

		if (n < 1 || n > MAX_ITERATIONS)
			return new BenchArguments(0, 0, CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE,
					"<iterations> is between 1 and " + MAX_ITERATIONS + ": got " + n));

		return new BenchArguments(w, n, null);
	}

}
