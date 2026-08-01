package clide.jdtls;

import java.util.List;
import java.util.Map;

public class Monomorphic {

	private boolean bool;
	private String string;
	private int number;

	private List<Monomorphic> list;
	private Map<String, Monomorphic> map;

	private final MonomorphicType type;

	private Monomorphic(MonomorphicType type) {
		this.type = type;
	}

	public static Monomorphic createString(String value) {
		final Monomorphic result = new Monomorphic(MonomorphicType.STRING);
		result.string = value;
		return result;
	}

	public static Monomorphic createNull() {
		return new Monomorphic(MonomorphicType.NULL);
	}

}
