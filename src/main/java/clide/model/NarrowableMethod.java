package clide.model;

import java.util.List;

/**
 * A public method list_could_be_private judged safe to narrow: every real
 * usage jdtls found for it - possibly none at all, see neverCalled - stays
 * inside the class/interface/enum it belongs to.
 *
 * overriddenIn names every supertype (a class, an interface, or
 * "Object" for java.lang.Object's own equals/hashCode/toString/clone/
 * finalize) whose own member shares this method's name and parameter count.
 * Java forbids reducing an override's visibility below what it overrides, so
 * such a method is still listed here - never silently dropped, the same
 * principle SYMBOLS.md states for ambiguous notation - but flagged so the
 * caller can judge whether narrowing it would even compile. Empty when this
 * method overrides nothing found by that check.
 */
public record NarrowableMethod(CodeLocation location, List<String> overriddenIn, boolean neverCalled) {

	public NarrowableMethod {
		if (location == null)
			throw new IllegalArgumentException("location must not be null");

		overriddenIn = List.copyOf(overriddenIn);
	}

}
