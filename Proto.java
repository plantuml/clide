import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Prototype "niveau 2" (voir JAVALENSE.md) : ASTParser JDT en batch,
 * standalone (sans OSGi, sans workspace), bindings resolus par le
 * compilateur. Mesure le cout d'un balayage complet d'un projet et
 * demontre deux requetes impossibles via LSP : find_field_writes
 * (lecture vs ecriture) et find_casts.
 *
 * <p>Classpath : les jars extraits de plugins/ dans
 * jdt-language-server-latest.zip (16 jars, dont org.osgi.service.prefs
 * sans lequel ASTParser.newParser() echoue en NoClassDefFoundError).
 *
 * <p>Usage :
 * <pre>
 * javac -cp 'lib/*' Proto.java
 * java -Xmx4g -cp 'lib/*:.' Proto /chemin/projet \
 *     [classe.qualifiee#champ] [classe.qualifiee.du.cast]
 * </pre>
 *
 * <p>Mesures obtenues sur PlantUML (3601 fichiers, 479 789 lignes,
 * sandbox Claude, 2026-08-01) : balayage complet avec bindings 22,3 s
 * (6,2 ms/fichier), reparse d'un seul fichier 77 ms, pic heap 791 Mo.
 *
 * <p>Caveat connu : le nom du champ dans sa propre declaration est
 * compte comme lecture ; une vraie commande exclurait les
 * VariableDeclarationFragment (equivalent d'includeDeclaration: false).
 */
public class Proto {

	private static final int BATCH = 500;

	// Cibles des demos (cle = classe.qualifiee#nom)
	private static String targetField;
	private static String targetCastType;

	// Compteurs globaux (tout le projet)
	private static final AtomicLong globalFieldReads = new AtomicLong();
	private static final AtomicLong globalFieldWrites = new AtomicLong();
	private static final AtomicInteger problemErrors = new AtomicInteger();
	private static final AtomicInteger unitCount = new AtomicInteger();
	private static final AtomicLong lineCount = new AtomicLong();

	// Resultats des demos
	private static final List<String> fieldReadHits = Collections.synchronizedList(new ArrayList<>());
	private static final List<String> fieldWriteHits = Collections.synchronizedList(new ArrayList<>());
	private static final List<String> castHits = Collections.synchronizedList(new ArrayList<>());

	public static void main(String[] args) throws Exception {
		final Path root = Paths.get(args[0]);
		targetField = args.length > 1 ? args[1] : "net.sourceforge.plantuml.TitledDiagram#useSmetana";
		targetCastType = args.length > 2 ? args[2] : "net.sourceforge.plantuml.abel.Entity";

		final List<String> files = new ArrayList<>();
		final List<String> roots = new ArrayList<>();
		for (String sub : new String[] { "src/main/java", "src/test/java" }) {
			final Path p = root.resolve(sub);
			if (Files.isDirectory(p) == false)
				continue;
			roots.add(p.toString());
			try (Stream<Path> s = Files.walk(p)) {
				s.filter(f -> f.toString().endsWith(".java")).forEach(f -> files.add(f.toString()));
			}
		}
		Collections.sort(files);
		final String[] sourceRoots = roots.toArray(new String[0]);
		System.out.println("Fichiers .java : " + files.size());

		// --- Balayage complet avec bindings ---
		final long t0 = System.nanoTime();
		for (int i = 0; i < files.size(); i += BATCH) {
			final String[] batch = files.subList(i, Math.min(i + BATCH, files.size())).toArray(new String[0]);
			final ASTParser parser = newParser(sourceRoots);
			parser.createASTs(batch, null, new String[0], new FileASTRequestor() {
				@Override
				public void acceptAST(String sourceFilePath, CompilationUnit cu) {
					visitUnit(root, sourceFilePath, cu);
				}
			}, null);
			System.out.printf("  batch %d..%d ok (%.1fs)%n", i, Math.min(i + BATCH, files.size()),
					(System.nanoTime() - t0) / 1e9);
		}
		final long sweepNanos = System.nanoTime() - t0;

		// --- Reparse d'un seul fichier (scenario reparation incrementale) ---
		final String oneFile = files.stream().filter(f -> f.endsWith("TitledDiagram.java")).findFirst().orElse(files.get(0));
		long bestSingle = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			final long s0 = System.nanoTime();
			final ASTParser parser = newParser(sourceRoots);
			parser.createASTs(new String[] { oneFile }, null, new String[0], new FileASTRequestor() {
			}, null);
			bestSingle = Math.min(bestSingle, System.nanoTime() - s0);
		}

		long heapPeak = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
			if (pool.getType() == MemoryType.HEAP)
				heapPeak += pool.getPeakUsage().getUsed();

		System.out.println();
		System.out.println("=== RESULTATS ===");
		System.out.printf("Unites parsees          : %d (%d lignes)%n", unitCount.get(), lineCount.get());
		System.out.printf("Balayage complet        : %.1f s (%.1f ms/fichier)%n", sweepNanos / 1e9,
				sweepNanos / 1e6 / files.size());
		System.out.printf("Reparse 1 fichier       : %.0f ms (meilleur de 3)%n", bestSingle / 1e6);
		System.out.printf("Pic memoire heap        : %d MB%n", heapPeak / (1024 * 1024));
		System.out.printf("Erreurs de compilation  : %d (attendu sans les jars .clide)%n", problemErrors.get());
		System.out.println();
		System.out.printf("Acces champs (projet)   : %d lectures, %d ecritures%n", globalFieldReads.get(),
				globalFieldWrites.get());
		System.out.println();
		System.out.println("find_field_writes " + targetField + " :");
		fieldWriteHits.forEach(h -> System.out.println("  [write] " + h));
		System.out.println("find_field_reads " + targetField + " :");
		fieldReadHits.forEach(h -> System.out.println("  [read]  " + h));
		System.out.println();
		System.out.println("find_casts vers " + targetCastType + " : " + castHits.size());
		castHits.forEach(h -> System.out.println("  [cast] " + h));
	}

	private static ASTParser newParser(String[] sourceRoots) {
		final ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		final java.util.Map<String, String> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions("21", options);
		parser.setCompilerOptions(options);
		// classpath vide (pas de jars .clide dans le clone public teste) ; la
		// JRE du process est incluse ; sourcepath = les racines du projet.
		parser.setEnvironment(new String[0], sourceRoots, null, true);
		return parser;
	}

	private static void visitUnit(Path root, String sourceFilePath, CompilationUnit cu) {
		unitCount.incrementAndGet();
		lineCount.addAndGet(cu.getLineNumber(cu.getLength() - 1));
		for (IProblem p : cu.getProblems())
			if (p.isError())
				problemErrors.incrementAndGet();

		final String rel = root.relativize(Paths.get(sourceFilePath)).toString().replace('\\', '/');
		cu.accept(new ASTVisitor() {

			@Override
			public boolean visit(Assignment node) {
				final boolean compound = node.getOperator() != Assignment.Operator.ASSIGN;
				markWrite(node.getLeftHandSide(), compound);
				return true;
			}

			@Override
			public boolean visit(PostfixExpression node) {
				markWrite(node.getOperand(), true); // x++ : lecture + ecriture
				return true;
			}

			@Override
			public boolean visit(PrefixExpression node) {
				if (node.getOperator() == PrefixExpression.Operator.INCREMENT
						|| node.getOperator() == PrefixExpression.Operator.DECREMENT)
					markWrite(node.getOperand(), true);
				return true;
			}

			@Override
			public boolean visit(SimpleName node) {
				countRead(node, node.resolveBinding());
				return true;
			}

			@Override
			public boolean visit(CastExpression node) {
				final ITypeBinding tb = node.getType().resolveBinding();
				if (tb != null && targetCastType.equals(tb.getQualifiedName()))
					castHits.add(rel + ":" + cu.getLineNumber(node.getStartPosition()));
				return true;
			}

			private void markWrite(Expression lhs, boolean alsoRead) {
				final IVariableBinding f = fieldBindingOf(lhs);
				if (f == null)
					return;
				globalFieldWrites.incrementAndGet();
				if (alsoRead)
					globalFieldReads.incrementAndGet();
				if (isTargetField(f)) {
					final int line = cu.getLineNumber(lhs.getStartPosition());
					fieldWriteHits.add(rel + ":" + line);
					if (alsoRead)
						fieldReadHits.add(rel + ":" + line + " (compound)");
				}
				writesSeen.add(baseName(lhs));
			}

			private final java.util.Set<Object> writesSeen = new java.util.HashSet<>();

			private void countRead(SimpleName node, IBinding b) {
				if (b instanceof IVariableBinding == false)
					return;
				final IVariableBinding f = (IVariableBinding) b;
				if (f.isField() == false)
					return;
				if (writesSeen.contains(node))
					return; // deja compte comme ecriture
				globalFieldReads.incrementAndGet();
				if (isTargetField(f))
					fieldReadHits.add(rel + ":" + cu.getLineNumber(node.getStartPosition()));
			}

			private Object baseName(Expression e) {
				if (e instanceof FieldAccess fa)
					return fa.getName();
				if (e instanceof SuperFieldAccess sfa)
					return sfa.getName();
				if (e instanceof QualifiedName qn)
					return qn.getName();
				return e;
			}

			private IVariableBinding fieldBindingOf(Expression e) {
				IBinding b = null;
				if (e instanceof FieldAccess fa)
					b = fa.resolveFieldBinding();
				else if (e instanceof SuperFieldAccess sfa)
					b = sfa.resolveFieldBinding();
				else if (e instanceof Name n)
					b = n.resolveBinding();
				if (b instanceof IVariableBinding f && f.isField())
					return f;
				return null;
			}

			private boolean isTargetField(IVariableBinding f) {
				final ITypeBinding decl = f.getDeclaringClass();
				if (decl == null)
					return false;
				return targetField.equals(decl.getQualifiedName() + "#" + f.getName());
			}
		});
	}
}
