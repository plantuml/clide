
-------------


Attention, dans les sorties (je pense à la commande 'search_regex'), ne JAMAIS mettre de chemin absolu
Tout doit être relatif au chemin du projet.
Y compris pour le > Initial path ?
Mettre un . si on veut cherche dans tout le projet


Sauf peut-être pour hoover:

hover
> Get 'hover' expecting now 3 parameter(s).
> File path ?
src/main/java/clide/jdtls/JdtlsSession.java
> Line ?
62
> Symbol ?
start
void clide.jdtls.JdtlsSession.start() throws IOException, InterruptedException, TimeoutException

Starts jdtls if needed and performs the initialize/initialized handshake.

* **Throws:**
  * IOException
  * InterruptedException
  * TimeoutException

Source: *[clide](file:///home/foo/clide/src/main/java/clide/jdtls/JdtlsSession.java#62)*

D'où vient de "Source:" ?
J'ai l'impression que c'set jdtls qui génère ça: donc on ne touche pas, on garde le texte tel quel


-----

En ce qui concerne les éditions, plusieurs choses.

1) Pour régler le problème protocole « un token par ligne » : un corps de méthode Java est par nature multi-ligne
On va rajouter une notion de "bloc de texte" dans le protocole.
Donc si un paramètre est de type ParamType.TEXT_BLOCK (trouver un meilleur nom que TEXT_BLOCK) on sait que c'est un bloc de lignes.
Du coup, pour les blocs de lignes, l'IDE demande en tout premier un séparateur.
Il faut alors saisir une chaine discriminante (peu importe sa valeur), qui servira de terminateur de fin.
Du coup, l'IDE enregistre toutes les lignes saisies, jusqu'à rencontrer ce terminateur.


2) Pour tout ce qui est modification du code, on va introduire la notion de transaction.
Avant toute modification, il faudra ouvrir une transation. Syntaxe:

open_transaction <transation_id>

Example:
> READY
open_transaction $refactor_foo

Un id de transation commence forcément par un $ (puis \w+ en minuscule)
Ce n'est qu'une fois la transaction appelée qu'on pourra faire des modifs (sinon, échec)

Ensuite, on peut faire les modifications.

Mais on peut faire un:
rollback_transaction $refactor_foo

si on a tout cassé

ou bien un:
commit_transaction $refactor_foo

si tout va bien.

On peut aussi faire un:
"diff_transaction $refactor_foo" pour voir les fichiers modifiés
"diff_transaction $refactor_foo src/foo.java " pour voir les modifications précises sur ce fichier
"restore_file $refactor_foo src/foo.java " pour faire un restore sur ce fichier uniquement


L'implémentation est simple: on créé un répertoire 

.clide/transactions/refactor_foo

Dans lequel on mets des backup des fichiers *avant modification*
Et un fichier "vide" en cas de création

Le commit de la transaction consiste à supprimer ce répertoire
Le rollback (et le diff) à reprendre ces fichiers

De plus, on peut avoir des sous-transactions:

open_transaction $refactor_foo$part1

dans ce cas, ça créé un nouveau répertoire
.clide/transactions/part1

un commit de $refactor_foo$part1 reporte les modifs de .clide/transactions/part1 vers .clide/transactions/refactor_foo

un commit de $refactor_foo commit implicitement d'abord la sous chaine des transactions

car on peut très bien avoir:

open_transaction $refactor_foo$part1$a

Niveau code, il faut réifier Transaction.java et TransactionsStack.java

Un inconvénient, c'est que si le daemon plante pour une raison ou une autre, on sera dans un état instable.
Donc refuser de démarrer si on lance un daemon alors que le répertoire .clide/transactions n'est pas vide
Ca sera à l'utilisateur de faire le ménage.










-----



- Dans le code python transformer jdt-language-server-latest.tar.gz en jdt-language-server-latest.zip
- de façon à auto unziper jdt-language-server-latest.zip dans le code Java si besoin

Réflexion (suite à discussion avec l'utilisateur) sur l'édition de fichiers depuis clide : Claude a déjà ses propres outils d'édition de texte (recherche/remplacement, écriture de fichier), donc des commandes clide de type "supprimer/insérer la ligne N" seraient redondantes, et retomberaient dans le même travers que le grep (numéro de ligne qui a bougé depuis la dernière lecture) que clide cherche justement à éviter — sans compter que le protocole "un token par ligne" de clide s'accommoderait mal d'un paramètre "contenu à insérer" qui contient lui-même des retours à la ligne. Deux pistes semblent en revanche cohérentes avec l'esprit du projet :

- **Commande édition + rebuild + diagnostics en un seul aller-retour** : appliquer une modification textuelle (par correspondance old_text/new_text, comme l'outil Edit de Claude — vérifie que ce qui est remplacé correspond bien à ce qui est attendu, plutôt que de viser un numéro de ligne fragile), puis relancer automatiquement `java/buildWorkspace` et retourner les diagnostics. Ça fusionnerait en une seule commande ce qui prend aujourd'hui deux étapes séparées (édition via les outils de Claude, puis `print_diagnostics`), en ligne directe avec la priorité n°1 du projet ("compiler et récupérer la liste d'erreurs").
- **Opérations de refactoring sémantique via jdtls**, dans la même veine que goto_definition/goto_implementation : par exemple `rename_symbol` (LSP `textDocument/rename`) pour renommer un symbole partout où il est réellement utilisé — ce qu'un grep/edit textuel ne peut pas faire correctement (risque de renommer un symbole homonyme non lié, ou de rater un usage que le texte n'exprime pas littéralement). C'est le genre d'opération où la connaissance sémantique de jdtls apporte une vraie valeur, contrairement à une édition ligne à ligne.
- **`edit_method <chemin fichier> <ligne> <nom méthode> <nouveau corps>`** : implémentation concrète de l'idée « édition + rebuild + diagnostics » ci-dessus, mais localisée au niveau d'une méthode plutôt qu'un edit textuel libre. Localisation par jdtls (`documentSymbol`, qui donne déjà les bornes exactes d'une déclaration — même mécanique que `list_members`) via fichier + ligne + nom, la même convention que `goto_definition`/`hover`, plutôt qu'une recherche de texte : ça lève l'ambiguïté des méthodes surchargées (même nom, signatures différentes) sans avoir à reproduire l'indentation exacte du corps actuel pour le retrouver. Enchaîne rebuild + diagnostics comme l'idée précédente.

  Réserves et questions ouvertes, non tranchées :
  - Périmètre : remplacer uniquement le corps (entre les accolades) ou toute la déclaration (signature, annotations, javadoc) ? Piste retenue pour l'instant : corps seul par défaut — réduit le risque de casser accidentellement la signature/visibilité ; changer une signature mériterait une commande à part.
  - Protocole « un token par ligne » : un corps de méthode Java est par nature multi-ligne, donc cette commande ne peut pas respecter tel quel le protocole stdin actuel (un paramètre = une ligne). Nécessite un marqueur de fin de bloc ou un format proche d'un heredoc pour ce paramètre — question à trancher une bonne fois pour toutes plutôt que commande par commande.
  - Garde-fou : demander aussi l'ancien corps en paramètre (pas seulement le nouveau), pour que clide vérifie que ce qu'il s'apprête à remplacer correspond bien à ce qui est réellement présent — même principe que old_text/new_text dans l'outil Edit de Claude, mais avec une localisation par jdtls plutôt qu'une recherche de texte.
- **`summarize_package <nom package>`** : survol structurel d'un package entier — pour chaque classe/interface/enum qu'il contient, ses signatures (méthodes et champs) sans les corps, sur le modèle de `list_members` mais appliqué automatiquement à tout le package plutôt qu'à un seul type déjà localisé. Comble un vrai trou : aujourd'hui, pour découvrir un package inconnu, il faut soit lire chaque fichier en entier (donc payer tous les corps de méthode), soit grepper via `search_regex` (bruyant, aucune signature fiable) — `find_symbol`/`list_members` supposent tous deux qu'on sait déjà où regarder. C'est la commande la plus directement dans l'esprit de la priorité n°3 du projet (requêtes sémantiques), et contrairement à `edit_method`/`rename_symbol` elle est purement en lecture, donc sans les risques de fragilité des commandes d'écriture.

  Décidé (suite à discussion avec l'utilisateur) :
  - Paramètre = nom de package Java (ex. `clide.core`), pas un chemin de dossier.
  - Sous-packages : apparaissent comme existants (simplement signalés/listés), sans le détail de leur contenu — pas de récursion automatique.

  Questions encore ouvertes :
  - Visibilité : tout montrer (y compris `private`) ou filtrer sur `public`/`protected` pour un survol plus léger ?
  - Javadoc : ajouter la première ligne de Javadoc par membre (comme le fait `hover`), au risque d'alourdir la sortie, ou rester sur signatures nues ?
  - Classes imbriquées (au sein d'un même type, à distinguer des sous-packages) : les lister comme simple membre sans détailler leur contenu, par cohérence avec le comportement actuel de `list_members` — probable mais pas encore confirmé.
- **`change_signature <chemin fichier> <ligne> <nom méthode> ...`** : première brique d'une famille de refactorings de signature de méthode — réordonner des paramètres existants, en ajouter de nouveaux (avec une valeur à donner explicitement dans la commande — Java n'ayant pas de valeurs par défaut natives, voir « Décidé » plus bas), en supprimer. Contrairement à `edit_method`, ce n'est pas un simple remplacement de texte localisé : il faut aussi mettre à jour tous les sites d'appel de la méthode (et potentiellement ses redéfinitions dans les sous-classes), ce qui est un problème nettement plus difficile — initialement jugé « trop risqué à implémenter nous-mêmes ».

  Vérifié (suite à recherche web, voir sources en bas) : ce risque est levé, jdtls sait déjà faire ce refactoring, pas besoin de le réimplémenter à la main. `eclipse.jdt.ls` expose « Change signature » comme un vrai refactoring de protocole depuis la version 1.22.0 (avril 2023, PR #2497, classe `ChangeSignatureHandler`) — et ce n'est pas une fonctionnalité propre à l'UI Eclipse ou à VS Code : `nvim-jdtls`, un client Neovim entièrement headless (sans aucune UI graphique derrière), l'utilise aussi, ce qui confirme que c'est une vraie capacité de protocole et pas un bricolage côté client. Le mécanisme concret, tel qu'observé dans l'implémentation de `nvim-jdtls` :
  1. Requête LSP custom `java/getChangeSignatureInfo` (fichier + position de la méthode) → renvoie la signature actuelle telle que jdtls la voit (paramètres, visibilité, type de retour).
  2. Le client envoie la signature voulue (paramètres réordonnés/ajoutés/supprimés) via une autre requête custom, `java/getRefactorEdit`, qui renvoie un `WorkspaceEdit`.
  3. Ce `WorkspaceEdit` est appliqué tel quel — il couvre déjà la mise à jour des sites d'appel et, d'après la documentation vscode-java, se propage « à travers la hiérarchie d'héritage de la méthode » (donc les redéfinitions dans les sous-classes sont gérées aussi).

  Ce mécanisme suit le même pattern requête/réponse JSON-RPC déjà utilisé ailleurs dans `JdtlsSession`/`LspClient` (comme pour `goto_definition`), donc réutilisable sans nouveau mécanisme à inventer côté clide.

  Décidé (suite à discussion avec l'utilisateur) :
  - Pour l'ajout d'un paramètre, la valeur (celle qui joue le rôle de « valeur par défaut ») est donnée explicitement en paramètre de la commande — pas de tentative d'inférence automatique.

  Questions encore ouvertes :
  - La case « Keep original method as delegate to changed method » vue dans la boîte de dialogue Eclipse (qui génère une surcharge gardant l'ancienne signature au lieu de toucher tous les appelants — la stratégie pressentie pour l'ajout de paramètre) : pas confirmé si elle fait partie des arguments exposés par `java/getChangeSignatureInfo`/`getRefactorEdit`, ou si c'est une option propre à l'UI Eclipse/VS Code. À vérifier concrètement (inspection du jdtls déjà extrait dans `jdtls/`, ou test direct une fois la commande esquissée).
  - Fiabilité de la suppression de paramètre : l'issue vscode-java #3089 rapporte que supprimer un paramètre (ou une exception) fait échouer Preview/Refactor dans certains cas côté outillage existant. Le risque « on doit tout réimplémenter nous-mêmes » est levé, mais pas le risque « jdtls peut se planter sur ce cas précis » — à tester à fond (dans l'esprit des sections « Testé de bout en bout » déjà présentes dans `CLAUDE.md`), en particulier sur la suppression, avant de faire confiance au résultat.
  - Deux risques sémantiques que la recompilation ne peut pas détecter, indépendamment de la fiabilité de jdtls : réordonner deux paramètres de même type (ex. deux `String`) compile sans erreur même si un site d'appel n'a pas été mis à jour correctement ; supprimer un paramètre dont l'expression a, à un site d'appel, un effet de bord (ex. `foo(bar(), calculerEtLoguer())`) fait disparaître silencieusement cet effet de bord. Le rebuild + diagnostics prévu pour les autres commandes ne suffit pas à couvrir ces deux cas.

  Sources :
  - CHANGELOG eclipse.jdt.ls, entrée v1.22.0 : https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/CHANGELOG.md
  - PR eclipse.jdt.ls #2497 (« Add change signature refactoring ») : https://github.com/eclipse/eclipse.jdt.ls/pull/2497
  - Documentation vscode-java, « Change Method Signature » : https://github.com/redhat-developer/vscode-java/blob/master/document/_java.learnMoreAboutRefactorings.md
  - Issue vscode-java #3089 (bug sur la suppression de paramètres) : https://github.com/redhat-developer/vscode-java/issues/3089
  - Implémentation `change_signature` dans nvim-jdtls (`lua/jdtls.lua`) : https://github.com/mfussenegger/nvim-jdtls/blob/master/lua/jdtls.lua