package clide.annotation;

/**
 * What kind of value a @Param expects, beyond "just some text". Lets
 * ClideDaemon run a cheap, jdtls-free "surface" check on a parameter's raw
 * text before the command it belongs to ever runs - see
 * ClideDaemon.validate(). Only REGEX and SYMBOL have a check today:
 * <ul>
 * <li>REGEX must compile as a java.util.regex.Pattern.</li>
 * <li>SYMBOL must parse as "&lt;file path&gt;:&lt;line&gt;:&lt;name&gt;" (see
 * clide.core.Symbol.parse()) - the file path relative to the open project,
 * never the daemon's own current directory - and name must actually appear
 * as a whole word on that line of that file.</li>
 * </ul>
 * TRANSACTION_ID and SINGLE_LINE accept any text unchecked. TEXT_BLOCK is
 * meant, eventually, to be read across several lines instead of one - not
 * implemented yet: every parameter, TEXT_BLOCK included, is currently read
 * as exactly one line by ClideDaemon.readParams().
 */
public enum ParamType {
	TRANSACTION_ID, REGEX, SYMBOL, SINGLE_LINE, TEXT_BLOCK
}
