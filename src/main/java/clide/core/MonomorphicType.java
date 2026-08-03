package clide.core;

/**
 * Which of a Monomorphic's payload fields is the meaningful one.
 *
 * JSON has a single number type, Java does not: INTEGER and DECIMAL both stand
 * for a JSON number, and say how it was written. Splitting them here rather
 * than carrying a flag inside a single NUMBER means the distinction sits under
 * the same exhaustiveness check as every other case - a switch that forgets one
 * of them does not compile.
 */
public enum MonomorphicType {

	BOOLEAN, STRING, INTEGER, DECIMAL, MAP, LIST, NULL;

}
