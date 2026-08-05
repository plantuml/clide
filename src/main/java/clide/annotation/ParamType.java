package clide.annotation;

/**
 * What kind of value a @Param expects, beyond "just some text". Lets
 * ClideDaemon run a cheap, jdtls-free "surface" check on a parameter's raw
 * text before the command it belongs to ever runs - see
 * ClideDaemon.validate(). Checked today:
 * <ul>
 * <li>TRANSACTION_ID must match TransactionStack.ID_PATTERN: one or more
 * "$segment" chunks chained back to back (e.g. "$refactor_foo",
 * "$refactor_foo$part1"), each segment lower-case word characters only.</li>
 * <li>REGEX must compile as a java.util.regex.Pattern.</li>
 * <li>POSITION must parse as "&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;"
 * (see clide.core.PositionParser.parse()) - the file path relative to the open
 * project, never the daemon's own current directory - and name must actually
 * appear as a whole word on that line of that file.</li>
 * <li>NON_NEGATIVE_INTEGER must parse as a base-10 integer that is zero or
 * more. Zero is accepted and means zero, never quietly turned into one: a
 * client that asks for no results gets none, and a client that asks for -1 or
 * for "many" is told which parameter it got wrong rather than having a value
 * guessed for it. Any upper bound is the command's own business, not this
 * type's - see set_max_results and ClideContext.MAX_RESULTS_CEILING.</li>
 * </ul>
 * SINGLE_LINE accepts any text unchecked, read as exactly one line by
 * ClideDaemon.readParams().
 *
 * MULTI_LINE is the odd one out. A Java method body - or any other chunk of
 * code a client wants to send as a single parameter - is multi-line by
 * nature, and unlike every other ParamType there is no line count to ask for
 * up front: the client itself doesn't necessarily know how many lines it is
 * about to send before it starts typing them. So ClideDaemon.readParams()
 * reads a MULTI_LINE parameter in two steps instead of one: first a single
 * line, the terminator - any discriminant string the client picks, never
 * validated or interpreted, just something unlikely to occur as a whole line
 * of the actual content - then every following line, kept verbatim (no
 * trimming: indentation is part of the value, e.g. a tab-indented method
 * body), until a line equal to that terminator is read. That line is
 * consumed but excluded from the value; every line before it is joined with
 * "\n".
 */
public enum ParamType {
	TRANSACTION_ID, REGEX, POSITION, NON_NEGATIVE_INTEGER, SINGLE_LINE, MULTI_LINE
}