package fixture;

/**
 * Un programme qui ne fait que consommer du CPU et allouer pendant environ
 * 2,5 s, avec une chaîne d'appels main -> spin -> hash.
 *
 * Sert de cobaye aux tests de profilage : on l'enregistre avec JFR dans un JVM
 * forké, exactement comme clide enregistre les tests d'un projet, puis on lit
 * l'enregistrement. Ce n'est pas un test (aucun @Test, et il vit dans le
 * package fixture, que `ant test` ne ramasse pas).
 */
public final class Burner {

	/** Lu à la fin pour que le compilateur ne puisse pas jeter le travail. */
	static long sink;

	private Burner() {
	}

	public static void main(final String[] args) {
		final long end = System.nanoTime() + 2_500_000_000L;
		while (System.nanoTime() < end)
			spin();

		System.out.println(sink);
	}

	static void spin() {
		final long[] data = new long[4096];
		for (int i = 0; i < data.length; i++)
			data[i] = Long.rotateLeft(i * 31L, i);

		sink += hash(data);
	}

	static long hash(final long[] data) {
		long h = 17;
		for (final long value : data)
			h = h * 31 + Long.rotateLeft(value, 7);

		return h;
	}

}
