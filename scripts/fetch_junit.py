#!/usr/bin/env python3
"""Complète lib/ avec les .jar JUnit 5 manquants pour compiler et exécuter les
tests unitaires de clide.

Ce que contient déjà lib/ (commit "add junit") suffit à *écrire* des tests,
mais pas à les *lancer* : il manque la couche plateforme (launcher + engine)
et opentest4j, que tous les assertXxx() lèvent. Détail :

    junit-platform-console-standalone  le runner en ligne de commande, appelé
                                       par la cible "ant test". Contient déjà
                                       tout JUnit 5 en interne, d'où sa taille.
    junit-platform-launcher            API de lancement, pour Gradle/Eclipse
    junit-platform-engine              requis par junit-jupiter-engine
    opentest4j                         AssertionFailedError
    apiguardian-api                    annotations @API (compilation)
    junit-platform-commons 1.10.1      remplace le 1.9.1 présent, incohérent
                                       avec Jupiter 5.10.1

N'utilise que la bibliothèque standard (urllib) : rien à installer.
Le script est idempotent - un .jar déjà présent et dont l'empreinte SHA-1
correspond n'est pas re-téléchargé.

Usage:
    python scripts/fetch_junit.py                 # remplit ./lib
    python scripts/fetch_junit.py --dry-run       # montre sans télécharger
    python scripts/fetch_junit.py --lib <chemin>  # autre dossier destination

Le script écrit uniquement dans lib/ ; il ne supprime jamais rien. Le
junit-platform-commons-1.9.1.jar devenu inutile est signalé en fin
d'exécution, avec la commande git pour le retirer.
"""

import argparse
import hashlib
import sys
import urllib.error
import urllib.request
from pathlib import Path

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

# (groupId, artifactId, version) - l'ordre est celui de l'affichage.
ARTIFACTS = [
	("org.junit.platform", "junit-platform-console-standalone", "1.10.1"),
	("org.junit.platform", "junit-platform-launcher", "1.10.1"),
	("org.junit.platform", "junit-platform-engine", "1.10.1"),
	("org.junit.platform", "junit-platform-commons", "1.10.1"),
	("org.opentest4j", "opentest4j", "1.3.0"),
	("org.apiguardian", "apiguardian-api", "1.1.2"),
]

# Présents dans lib/ mais périmés une fois les artefacts ci-dessus installés.
OBSOLETE = ["junit-platform-commons-1.9.1.jar"]

TIMEOUT_SECONDS = 120


def jar_url(group_id: str, artifact_id: str, version: str) -> str:
	group_path = group_id.replace(".", "/")
	return f"{MAVEN_CENTRAL}/{group_path}/{artifact_id}/{version}/{artifact_id}-{version}.jar"


def fetch(url: str) -> bytes:
	request = urllib.request.Request(url, headers={"User-Agent": "clide-fetch-junit"})
	with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
		return response.read()


def expected_sha1(url: str) -> str:
	"""Empreinte publiée à côté du .jar sur Maven Central.

	Garantit que le transfert n'a pas été tronqué ou altéré en route (proxy
	d'entreprise, coupure réseau) - pas que l'artefact amont est légitime,
	l'empreinte venant du même serveur que le .jar.
	"""
	return fetch(url + ".sha1").decode("ascii").split()[0].strip().lower()


def sha1_of(path: Path) -> str:
	digest = hashlib.sha1()
	with path.open("rb") as stream:
		for chunk in iter(lambda: stream.read(1 << 20), b""):
			digest.update(chunk)
	return digest.hexdigest()


def install(group_id: str, artifact_id: str, version: str, lib: Path, dry_run: bool) -> str:
	"""Retourne "ok", "déjà présent", "simulé" ou "ÉCHEC"."""
	url = jar_url(group_id, artifact_id, version)
	target = lib / f"{artifact_id}-{version}.jar"

	if dry_run:
		state = "déjà présent" if target.exists() else "à télécharger"
		print(f"  {target.name:<52} {state}")
		print(f"      {url}")
		return "simulé"

	try:
		wanted = expected_sha1(url)
	except (urllib.error.URLError, OSError, IndexError) as problem:
		print(f"  {target.name:<52} ÉCHEC ({problem})")
		return "ÉCHEC"

	if target.exists() and sha1_of(target) == wanted:
		print(f"  {target.name:<52} déjà présent, SHA-1 OK")
		return "déjà présent"

	try:
		payload = fetch(url)
	except (urllib.error.URLError, OSError) as problem:
		print(f"  {target.name:<52} ÉCHEC ({problem})")
		return "ÉCHEC"

	got = hashlib.sha1(payload).hexdigest()
	if got != wanted:
		print(f"  {target.name:<52} ÉCHEC (SHA-1 {got} != {wanted} attendu)")
		return "ÉCHEC"

	# Écriture en deux temps : un .part renommé une fois complet, pour ne
	# jamais laisser un .jar tronqué dans lib/ si le script est interrompu.
	partial = target.with_suffix(".jar.part")
	partial.write_bytes(payload)
	partial.replace(target)
	print(f"  {target.name:<52} téléchargé, {len(payload) // 1024} Ko, SHA-1 OK")
	return "ok"


def main() -> int:
	parser = argparse.ArgumentParser(description="Complète lib/ avec les .jar JUnit 5 manquants.")
	parser.add_argument("--lib", default=None, help="dossier destination (défaut : lib/ à la racine du projet)")
	parser.add_argument("--dry-run", action="store_true", help="liste ce qui serait téléchargé, sans rien écrire")
	options = parser.parse_args()

	lib = Path(options.lib) if options.lib else Path(__file__).resolve().parent.parent / "lib"
	lib.mkdir(parents=True, exist_ok=True)
	print(f"Destination : {lib}\n")

	results = [install(*artifact, lib, options.dry_run) for artifact in ARTIFACTS]

	failed = results.count("ÉCHEC")
	print()
	if options.dry_run:
		print(f"{len(results)} artefact(s) - simulation, rien n'a été écrit.")
		return 0

	print(f"{results.count('ok')} téléchargé(s), {results.count('déjà présent')} déjà à jour, {failed} en échec.")

	leftovers = [name for name in OBSOLETE if (lib / name).exists()]
	if leftovers:
		print("\nDevenu(s) inutile(s) - à retirer du dépôt :")
		for name in leftovers:
			print(f"    git rm lib/{name}")

	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main())