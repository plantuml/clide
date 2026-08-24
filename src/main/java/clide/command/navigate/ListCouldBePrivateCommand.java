package clide.command.navigate;

import java.io.IOException;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.PositionParser;
import clide.jdtls.JdtlsSession;
import clide.model.Listing;
import clide.model.NarrowableMethod;
import clide.model.Position;

/**
 * Every public, directly-declared method of the class/interface/enum named by
 * &lt;position&gt; whose every real usage - textDocument/references, the same
 * request find_reference sends - stays inside that type itself: a candidate
 * for narrowing to private. See JdtlsSession.narrowableMethods() for the
 * actual analysis; this class only turns &lt;position&gt; into the type to
 * inspect and the result into text, the same division of labor
 * ListMembersCommand already has with JdtlsSession.listMembers().
 *
 * A method flagged here is a <b>candidate</b>, not a verdict clide would ever
 * apply on its own - this command only lists, it writes nothing, and never
 * will: jdtls' own references can never see a call made by reflection, a
 * framework (JUnit, serialization, a service loader...), so an entry here is
 * a hint to go check, not a guarantee that narrowing it is safe.
 */
public class ListCouldBePrivateCommand extends Command {

	@Keyword("list_could_be_private")
	@Help("Lists the public methods of the class/interface/enum named by <position> whose every real usage stays inside it - candidates for narrowing to private.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				list_could_be_private - find public methods usable as private

			SYNOPSIS
				list_could_be_private <position>

			DESCRIPTION
				Lists every public method directly declared on the class,
				interface or enum named at <position> - given as
				<file-content-md5>:<file path>:<line>:<column>:<name>,
				name starting exactly at that column - whose every real
				usage (the same textDocument/references find_reference
				sends) stays inside that type itself. A method never
				called anywhere at all is listed too, exactly the same
				as one only ever called from inside - both pass the one
				question this command asks: is there any usage outside
				this type that a narrower visibility would break.

				A method that Java forbids narrowing at all - it
				implements an interface method or overrides a
				superclass one, walked all the way up the hierarchy,
				java.lang.Object's own equals/hashCode/toString/clone/
				finalize included - is still listed, never silently
				dropped, but marked with what it implements/overrides:
				reducing its visibility would not compile. Judging
				whether that is still worth acting on (the interface
				might be internal too) is left to the caller.

				public static void main(String[] args) is never listed: nothing in
				the project calls it - the JVM launcher does - so it
				would otherwise look like the safest possible candidate
				and be exactly the one that breaks running the program
				at all.

			WHAT THIS COMMAND CANNOT SEE
				Only real, syntactic usages jdtls can find are counted.
				A call made through reflection, a framework annotation
				(JUnit's @Test, serialization's readObject/writeObject,
				a ServiceLoader-discovered class...) is invisible to
				textDocument/references and would still be listed here
				as a candidate. Every entry is a hint to go check by
				hand, never a change clide has already verified safe -
				this command writes nothing.

				"Public" itself is read off the method's own declaration
				line as literal text, since jdtls' documentSymbol never
				reports visibility as data. An interface's abstract
				methods are implicitly public without ever spelling the
				word out, so pointing <position> straight at an
				interface finds nothing - not a wrong answer (none of an
				interface's own abstract methods can usefully be
				narrowed from outside an implementing class anyway), but
				worth knowing before reading silence as "nothing to
				narrow here".

			ERRORS
				NOT_A_TYPE - <position> resolved, but does not name a
				class, interface, or enum.

			SEE ALSO
				list_members(1), find_reference(1), find_supertypes(1),
				rename(1)
			""")
	public ListCouldBePrivateCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), session, params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "list_could_be_private failed: " + e.getMessage());
		}

		try {
			final Listing<NarrowableMethod> methods = Listing.of(session.narrowableMethods(position),
					context.getMaxResults());
			return CommandResult.ok(new CommandPayload.NarrowableMethods(position.name(), methods));
		} catch (final IOException e) {
			// narrowableMethods() raises this exact IOException when position names
			// something that is not a class/interface/enum - the same contract
			// listMembers() has, and the same reasoning for why NOT_A_TYPE is the
			// right code here rather than JDTLS_REQUEST_FAILED - see ListMembersCommand.
			return CommandResult.error(ErrorCode.NOT_A_TYPE, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a type");
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "list_could_be_private failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.NarrowableMethods found -> {
			final Listing<NarrowableMethod> methods = found.methods();
			if (methods.totalCount() == 0)
				yield "list_could_be_private: " + found.subject()
						+ " has no public method that looks safe to narrow to private";

			final StringBuilder out = new StringBuilder();
			out.append("list_could_be_private: ").append(found.subject()).append(", ")
					.append(methods.summarize("method")).append(" could be private");
			for (final NarrowableMethod method : methods.items()) {
				out.append('\n').append(method.location().display());
				if (method.neverCalled())
					out.append("  (never called)");
				if (method.overriddenIn().isEmpty() == false)
					out.append("  (implements/overrides ").append(String.join(", ", method.overriddenIn()))
							.append(" - reducing visibility would not compile)");
			}
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
