# JAVALENSE.md — Idées à reprendre de javalens-mcp

Analyse (2026-08-01) de https://github.com/pzalutski-pixel/javalens-mcp :
un serveur MCP de 75 outils d'analyse sémantique Java. Différence
d'architecture fondamentale avec clide : JavaLens n'utilise pas de language
server — il embarque Eclipse JDT Core directement via OSGi/Equinox
(SearchEngine, ASTParser, moteur de refactoring appelés en Java), avec un
build Tycho résolu depuis download.eclipse.org (bloqué en sandbox Claude).
clide, lui, parle LSP à jdtls. Même moteur (jdtls est une façade LSP
au-dessus de JDT), deux façons opposées d'y accéder.

Le constat fondateur est identique à celui de clide : grep est aveugle à
l'héritage, aux overrides, au polymorphisme ; un agent IA a besoin de
réponses « compiler-accurate ». Leur noyau d'outils recouvre le nôtre
(`find_references`/`find_implementations`/`go_to_definition`/`get_hover_info`/
`get_document_symbols`/`search_symbols` ≈ `find_reference`/
`find_implementation`/`find_declaration`/`hover`/`list_members`/
`find_symbol`).

Ce document liste les idées qui valent d'être reprises, par ordre
d'intérêt décroissant pour clide. Un clone du dépôt a été analysé en
détail ; les chemins cités ci-dessous sont relatifs à leur dépôt.

## 1. Vérification disque à chaque requête (leur 1.5.0 — la meilleure idée)

Avant toute logique d'outil, le serveur hash (MD5, en parallèle) tous les
`.java` connus du projet, détecte lui-même les éditions/ajouts/
suppressions depuis le dernier état connu, répare exactement le delta
(refresh ciblé + reconcile de la seule unité modifiée + barrière d'index),
puis répond. La boucle de l'agent devient : **éditer → interroger** — plus
aucun « reload » à ne pas oublier. Coût mesuré chez eux : ~2 ms par
requête à 72 fichiers, ~25 ms à 1 000, ~180 ms à 10 000. Si un fichier de
*build* change (pom.xml, etc.), la requête échoue explicitement en
`RELOAD_REQUIRED` plutôt que de deviner ; si la vérification elle-même
échoue, `VERIFICATION_FAILED` plutôt qu'une réponse non vérifiée.

**Pourquoi c'est notre angle mort** : Claude édite les fichiers *en
dehors* de clide (Write/Edit directs), donc le modèle jdtls construit par
le `java/buildWorkspace` du démarrage du daemon devient périmé
silencieusement — toutes les commandes `find_*`/`hover`/`list_members`
répondent alors sur un état ancien, sans aucun signal.

**Transposition clide** : clide a déjà la brique bas niveau, dans
`JdtlsSession.refreshChangedFiles()` — diff d'un instantané
`chemin → mtime` contre l'arbre courant, puis notification
`workspace/didChangeWatchedFiles` (pas `textDocument/didChange` : ce
dernier suppose un document *ouvert* via `textDocument/didOpen`, et
clide n'ouvre volontairement jamais les fichiers un par un — voir
JDTLS.md ; `didChangeWatchedFiles` est le bon analogue LSP, il porte sur
des fichiers simplement *observés* sur disque, aucune ouverture requise)
avant un `java/buildWorkspace`. Deux écarts avec ce que fait
`DiskStampService`, par ordre d'importance :

- **Déclenchement** : chez eux la vérification tourne avant *chaque*
  appel d'outil (« éditer → interroger », zéro reload à retenir) ; chez
  nous `refreshChangedFiles()` n'est appelé que par la commande
  `rebuild`, sur demande explicite — `find_*`/`hover`/`list_members`
  continuent de répondre sur l'état du dernier build sans jamais
  vérifier le disque entre-temps. C'est le vrai angle mort à combler,
  plus que le choix mtime/hash ci-dessous : brancher la vérification en
  amont de toute commande qui a besoin de jdtls, pas seulement de
  `rebuild`.
- **Détection** : mtime seul aujourd'hui, pas de hash — rien ne distingue
  un vrai changement de contenu d'un simple touch, ni ne détecte un
  contenu changé sans que le mtime bouge (horloge peu fiable, outil qui
  préserve le mtime). Un équivalent de leur `Stamp` (taille + mtime +
  MD5, cf. le `FilesRepository` en cours de conception) comblerait ça —
  avec cette fois un vrai pré-filtre mtime avant rehash, contrairement à
  leur propre code (voir plus bas).

Précision après lecture du code : taille/mtime sont bien stockés dans le
`Stamp` mais ne servent en fait à rien dans leur `verify()` — chaque
fichier connu est rehashé sans condition à chaque appel (parallélisé) ;
ce sont des champs diagnostiques inertes aujourd'hui, pas un pré-filtre
actif, seul le hash est comparé. Autre précision : `DiskStampService`
n'est que le moteur stamp/diff (`stampAll`/`verify`/`restamp`) ; la
réparation proprement dite (refresh ciblé, attente d'index, invalidation
de cache, `RELOAD_REQUIRED` sur fichier de build) vit dans l'appelant
(`JdtServiceImpl.ensureFresh`), et passe chez eux par l'API de ressources
Eclipse (`IFile#refreshLocal`) — pas par du LSP, puisqu'ils n'en ont
aucune couche. Leur choix MD5 est assumé : détection de changement sur
ses propres sources, pas une frontière de sécurité.

## 2. Enveloppe de réponse pensée pour l'agent

Chaque réponse porte `success`/`data`/`error{code, message, hint}`/
`meta{totalCount, returnedCount, truncated, suggestedNextTools}`. Trois
détails à retenir :

- **`totalCount`/`truncated` exacts** : `truncated` est calculé contre le
  vrai compte avant plafonnement — ils ont corrigé le cas menteur où
  `matches == maxResults` était marqué tronqué à tort. Aujourd'hui les
  commandes clide ne bornent ni ne comptent : un `find_reference` sur un
  symbole très utilisé de PlantUML renvoie tout, sans indication. Même en
  protocole texte, un suffixe `(shown 50 of 312, truncated)` + un
  paramètre `max_results` changeraient la donne.
- **Le `hint` d'erreur dit quoi faire ensuite** (« Call health_check to
  monitor loading status »). Nos `?SYNTAX ERROR` sont déjà clairs sur la
  cause ; ajouter la prochaine action utile quand elle existe.
- **`maxResults` honoré littéralement** : négatif → erreur nommant le
  paramètre, `0` → zéro résultat (pas de clamp silencieux à 1), plafond
  de sécurité documenté (1000). Toute la validation avant exécution —
  même principe que notre `ClideDaemon.validate()`.

À noter aussi : leurs avertissements de chargement structurés
(`LoadWarning` : `MAVEN_SUBPROCESS_FAILED`, `COMPLIANCE_LEVEL_UNKNOWN`…)
— quand l'analyse est dégradée, le dire explicitement plutôt que de
renvoyer silencieusement un classpath vide.

## 3. Commandes composées (réduire les allers-retours)

Chaque tour d'agent coûte cher ; ils regroupent : `analyze_type` = membres
+ hiérarchie + usages + diagnostics en un appel ; `analyze_method` =
signature + appelants + appelés + overrides ; `analyze_file` = imports +
types + diagnostics ; `diagnose_and_fix` = diagnostics d'un fichier + les
corrections proposées de chaque problème. C'est le prolongement naturel de
notre notation `chemin:ligne:nom` recopiable d'un résultat vers la
commande suivante. Candidat clide évident : un `describe_type` combinant
`hover` + `list_members` + `find_implementation`.

## 4. Graphe whole-program → quels tests lancer

Un graphe projet (types/méthodes/champs, arêtes CALLS/CREATES/READS/
WRITES, construit paresseusement, mis en cache jusqu'au prochain build)
alimente trois capacités (`org.javalens.core/src/org/javalens/core/graph/`) :

- `find_affected_tests` : **les tests qui exercent un symbole,
  transitivement** (à travers les helpers non-test et le dispatch
  d'interface) — l'ensemble à lancer après l'avoir modifié. Rejoint
  directement notre priorité n°2 (« lancer un test ciblé ») par l'autre
  bout : il répond à *quel* test lancer.
- `find_unreachable_code` : code mort inatteignable depuis tout `main` ou
  test (avec l'honnêteté d'annoncer la limite : les points d'entrée
  réflexion/DI sont invisibles — « inatteignable » ≠ « supprimable »).
- `analyze_change_impact` : rayon d'impact transitif d'un changement
  (fermeture inverse sur le graphe, en franchissant les overrides).

Piège qu'ils ont corrigé en 1.5.1 (#32) : la fermeture inverse partant
d'un *type* doit agréger les appelants du type **et de tous ses membres**
(constructeurs, méthodes, champs), pas seulement du nœud type.

## 5. Recherches à grain fin — et la cartographie des trois niveaux

Neuf outils s'appuient sur les constantes de référence du `SearchEngine`
JDT que le protocole LSP n'expose pas : casts vers un type, `instanceof`,
`new Type()`, `throws`, blocs `catch`, `Type::method`, arguments
génériques `List<Type>`, usages d'annotations, et surtout la distinction
**lecture vs écriture de champ** (`find_field_writes` — « qui mute cet
état ? » est sans doute la plus utile pour un agent). Leur tableau « Why
Not LSP » est un peu vendeur (call hierarchy et type hierarchy existent en
LSP), mais ces recherches-là sont réellement hors de portée de jdtls.

Cartographie pour décider commande par commande, le jour où un besoin
réel arrive :

1. **jdtls (notre socle, inchangé)** : build + diagnostics, navigation
   indexée, call hierarchy — tout ce qui bénéficie de l'index. Couvre
   entièrement nos trois priorités fondatrices.
2. **`org.eclipse.jdt.core` en jar ordinaire sur le classpath** — extrait
   de l'archive `jdt-language-server-latest.zip` déjà commitée (voir le
   prototype ci-dessous ; Maven Central est bloqué en sandbox, mais les
   bundles du zip suffisent) : l'`ASTParser` en mode batch fonctionne
   **sans workspace ni OSGi** (`setEnvironment(classpath, sourcepaths)`,
   `setResolveBindings(true)`, `FileASTRequestor`) et donne des AST avec
   bindings résolus par le compilateur — de quoi implémenter par visite
   d'AST : lecture/écriture de champs, casts, `instanceof`, complexité,
   flux de contrôle, privés inutilisés. Pas d'index : balayage
   proportionnel au projet, mais cachable.
3. **Le grand saut Equinox/workspace (ce que fait JavaLens)** :
   `SearchEngine` indexé à grain fin + moteur de refactoring, au prix
   d'OSGi + Tycho + toute la plomberie que jdtls maintient pour nous
   (import de projet, enregistrement de la JRE, compliance, cycle de vie
   de l'index — une grosse part de leurs releases corrige des bugs de ce
   niveau-là). ~5 200 lignes de socle chez eux avant le premier outil.
   À éviter sauf besoin massif et démontré.

### Prototype niveau 2 — chiffré (sandbox Claude, 2026-08-01)

Prototype réalisé pour valider le niveau 2 : `ASTParser` JDT en batch,
standalone — sans OSGi, sans workspace, un simple classpath de jars.

**Aucun jar supplémentaire à committer** : les bundles nécessaires sont
déjà dans `jdt-language-server-latest.zip` (répertoire `plugins/`). 16
jars suffisent (~11 Mo) : `org.eclipse.jdt.core`, `org.eclipse.osgi`,
`org.eclipse.core.{resources,runtime,jobs,contenttype,filesystem,
expressions,commands}`, `org.eclipse.equinox.{common,preferences,
registry,app}`, `org.eclipse.text`, `org.eclipse.jdt.core.compiler.batch`
— et attention au piège : il faut aussi `org.osgi.service.prefs_*.jar`
(sinon `NoClassDefFoundError: org/osgi/service/prefs/
BackingStoreException` dès `ASTParser.newParser()`). Tous présents dans
l'archive. Maven Central est bloqué depuis la sandbox (403, vérifié) —
l'archive commitée est donc aussi la bonne source côté sandbox.

Recette : `setResolveBindings(true)` + `setBindingsRecovery(true)` +
`setEnvironment(classpath, sourceRoots, null, true)` + `createASTs()`
avec un `FileASTRequestor`, par lots de 500 fichiers (le parser est
à usage unique : le reconfigurer à chaque lot).

Mesures sur PlantUML (clone frais `--depth 1`, 3601 fichiers, 479 789
lignes, JDK 21, sandbox Claude, mesure unique) :

- **Balayage complet avec bindings : 22,3 s** (6,2 ms/fichier).
- **Reparse d'un seul fichier : 77 ms** (meilleur de 3) — le coût d'une
  réparation incrémentale après édition est donc négligeable.
- **Pic mémoire heap : 791 Mo** (les AST sont visités puis relâchés lot
  par lot, jamais tous retenus).
- 6526 erreurs de compilation — attendu : le clone public n'a pas les
  jars `.clide/` (stubs ant/openpdf/teavm, JUnit). `bindingsRecovery`
  fait que les requêtes ci-dessous répondent juste quand même.

Démos de requêtes hors de portée de LSP, sur ce même balayage :

- `find_field_writes` sur `TitledDiagram#useSmetana` : exactement
  **1 écriture** (`this.useSmetana = useSmetana;` dans le setter,
  ligne 254) et les lectures (`return useSmetana;` ligne 270),
  correctement distinguées — le paramètre homonyme `useSmetana` du
  setter n'est *pas* compté (le binding le distingue du champ), ce
  qu'aucun grep ne sait faire. Caveat du prototype : le nom dans la
  déclaration du champ est compté comme lecture ; une vraie commande
  l'exclurait (équivalent de notre `includeDeclaration: false`).
- `find_casts` vers `net.sourceforge.plantuml.abel.Entity` : **18 casts
  exacts** dans 8 fichiers, `Entity` résolu sémantiquement à travers les
  imports de chaque fichier.
- Au passage, le même balayage classe **64 420 lectures et 8 987
  écritures de champs** sur tout le projet — la matière première d'un
  `find_field_writes` généralisé.

Conclusion : le niveau 2 est validé et bon marché. Modèle d'usage
réaliste pour clide : un balayage complet au premier besoin (~22 s,
cachable), réparation à 77 ms/fichier édité ensuite — ou, plus simple,
balayage à la demande pour des requêtes ponctuelles. Prototype :
`Proto.java`, ~250 lignes, zéro dépendance hors jars JDT.

## 6. Refactorings = text edits, jamais d'écriture directe

Leurs 16 outils de refactoring (rename, extract/inline, change signature,
pull up/push down…) pilotent le moteur JDT en headless et renvoient des
**text edits** (+ contenu des fichiers créés) sans rien écrire sur disque
— l'agent voit ce qui changerait avant d'appliquer. Complémentaire de nos
transactions : eux ont le « proposer », nous avons le « appliquer/
annuler » (`open_transaction`/`rollback_transaction`/`diff_transaction`).
Le jour où clide a une commande d'édition : proposer le diff d'abord,
appliquer dans une transaction ensuite.

Leçon de leur 1.4.1 : la génération d'edits par concaténation de chaînes
et arithmétique d'offsets a fini réécrite sur `ASTRewrite`/`ImportRewrite`
(structurel) — la substitution textuelle touchait du texte identique dans
des littéraux de chaînes ou sous shadowing. Et un bon réflexe : **refuser
avec une raison** plutôt que produire du code incompilable (extraction
dans un corps sans accolades, inline d'un corps contenant `super.`, etc.).

## 7. Catalogue de pièges JDT (leur changelog = notre radar)

À garder sous la main si on va plus loin avec jdtls/JDT :

- **Compliance hors plage = cécité silencieuse** : un niveau de
  compilateur non supporté (1.5/1.6/1.7, ou plus récent que le JDT
  embarqué) fait que JDT ne produit *aucun* diagnostic — projet
  « propre » quel que soit son état réel. Leur correctif : clamper dans
  la plage supportée et le signaler par un avertissement nommant les deux
  niveaux.
- **Positions dans littéraux/commentaires** : leurs outils positionnels
  renvoyaient le membre englobant pour une position dans une chaîne, un
  commentaire ou après l'EOF, au lieu de refuser. Notre résolution
  `\bnom\b` sur la ligne nous immunise en partie ; garder le principe :
  refuser proprement plutôt que répondre à côté.
- **Course d'indexation** : leur `load_project` rendait la main avant la
  fin de l'indexeur → premières recherches incomplètes ; corrigé par un
  flush `WAIT_UNTIL_READY_TO_SEARCH`. C'est exactement notre TODO
  « attendre réellement la fin d'indexation (`language/status` →
  `ServiceReady`) plutôt que le délai fixe ».
- **`super(...)`/`this(...)` non-premier statement** (flexible
  constructor bodies, Java 25) : raté par l'index de recherche JDT —
  `find_references` et la call hierarchy héritaient de l'angle mort.
- **Rename d'une méthode d'interface** : doit se propager aux overrides
  dans les sous-types et aux déclarations surchargées dans les
  super-types, sinon le projet ne compile plus.
- **Tests : les snapshots capturés sont des pièges.** Un golden capturé
  est un test de caractérisation, pas un oracle : un bug présent le jour
  de la capture devient « correct » pour toujours (chez eux, ~18 outils
  sur 75 avaient un snapshot dégénéré — enveloppe vide figée comme
  attendue). Leur règle finale : toute valeur attendue dérivée à la main
  de la fixture, jamais capturée depuis l'outil testé. Cohérent avec
  notre pratique des suites hors dépôt à cas construits.
- Leur `JdtContractTest` épingle les comportements JDT dont ils dépendent
  (quirks d'API), pour qu'une montée de version JDT dise « JDT a changé »
  et non « notre wrapper est cassé ».

## 8. Guidance de l'agent consommateur

- **Convention de description uniforme** sur les 75 outils : `USAGE:` /
  `OUTPUT:` / exemples d'appel / les pièges (« IMPORTANT: zero-based »).
  Transposable à nos pages `man` et à `help_ai`.
- **Le biais d'entraînement vers grep est réel** (leur section « AI
  Training Bias ») : les modèles préfèrent grep même quand l'outil
  sémantique est supérieur. Leur parade : recommander d'ajouter au
  CLAUDE.md du projet *analysé* des instructions « préfère
  `find_references` à grep pour les usages, `find_implementations` pour
  les implémentations… ». Pour clide : une ligne de guidance
  « quand utiliser quoi » dans `help_ai`, pas seulement la liste des
  commandes.
- **Uniformiser tôt** : leur passe 1.3.2 a dû renormaliser d'un coup les
  noms de champs (`locations`/`totalCount` partout) et la casse des kinds
  (minuscules partout) après dérive. Notre harmonisation `find_*` a fait
  ce travail plus tôt ; maintenir la discipline pour les commandes à
  venir.

## Validation de notre syntaxe au passage

Leur issue #31 valide involontairement notre choix `chemin:ligne:nom` :
avec leurs coordonnées explicites zero-based (ligne + colonne), un agent
qui obtenait zéro résultat s'est mis à réessayer des positions adjacentes
en soupçonnant un off-by-one. La colonne explicite est fragile pour un
LLM qui compte mal les caractères ; notre résolution par mot entier sur
la ligne élimine la question à la racine.
