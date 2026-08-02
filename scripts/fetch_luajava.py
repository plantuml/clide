#!/usr/bin/env python3
"""Complète lib/ avec les .jar de gudzpoz/luajava (backend natif lua51) pour
le POC d'intégration Lua de clide (voir LUA.md).

Maven Central n'est pas accessible depuis la sandbox Claude (voir CLAUDE.md,
« Builder clide uniquement avec ant ») - ce script est donc fait pour être
lancé sur une machine avec un accès réseau normal, puis les .jar obtenus
commités dans lib/, exactement comme les .jar JUnit de fetch_junit.py.

Cinq artefacts, choisis pour le vrai moteur Lua natif (lua51, via JNI) et
non l'implémentation pure-JVM "luaj" - voir la discussion dans LUA.md :

    luajava           module Java central de gudzpoz/luajava (l'API `Lua`)
    lua51              bindings Java pour Lua 5.1 (classe `Lua51`)
    lua51-platform     bibliothèques natives (.so/.dll/.dylib), classifier
                       "natives-desktop" - couvre Windows/Linux/macOS,
                       x86 et ARM, en un seul jar
    jnigen-loader      charge au runtime le bon .so/.dll du jar ci-dessus
                       selon l'OS/l'architecture (dépendance de luajava)
    jspecify           annotations de nullabilité utilisées par l'API
                       publique de luajava (nécessaires à la compilation)

N'utilise que la bibliothèque standard (urllib) : rien à installer.
Le script est idempotent - un .jar déjà présent et dont l'empreinte SHA-1
correspond n'est pas re-téléchargé.

Usage:
    python scripts/fetch_luajava.py                 # remplit ./lib
    python scripts/fetch_luajava.py --dry-run        # montre sans télécharger
    python scripts/fetch_luajava.py --lib <chemin>   # autre dossier destination

Le script écrit uniquement dans lib/ ; il ne supprime jamais rien.
"""

import argparse
import hashlib
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

# (groupId, artifactId, version, classifier) - classifier=None pour un jar
# "normal". L'ordre est celui de l'affichage.
ARTIFACTS = [
	("party.iroiro.luajava", "luajava", "4.1.0", None),
	("party.iroiro.luajava", "lua51", "4.1.0", None),
	("party.iroiro.luajava", "lua51-platform", "4.1.0", "natives-desktop"),
	("com.badlogicgames.jnigen", "jnigen-loader", "3.1.1", None),
	("org.jspecify", "jspecify", "1.0.0", None),
]

TIMEOUT_SECONDS = 120


def jar_filename(artifact_id: str, version: str, classifier: Optional[str]) -> str:
	suffix = f"-{classifier}" if classifier else ""
	return f"{artifact_id}-{version}{suffix}.jar"


def jar_url(group_id: str, artifact_id: str, version: str, classifier: Optional[str]) -> str:
	group_path = group_id.replace(".", "/")
	filename = jar_filename(artifact_id, version, classifier)
	return f"{MAVEN_CENTRAL}/{group_path}/{artifact_id}/{version}/{filename}"


def fetch(url: str) -> bytes:
	request = urllib.request.Request(url, headers={"User-Agent": "clide-fetch-luajava"})
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


def install(group_id: str, artifact_id: str, version: str, classifier: Optional[str], lib: Path, dry_run: bool) -> str:
	"""Retourne "ok", "déjà présent", "simulé" ou "ÉCHEC"."""
	url = jar_url(group_id, artifact_id, version, classifier)
	target = lib / jar_filename(artifact_id, version, classifier)

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
	parser = argparse.ArgumentParser(description="Complète lib/ avec les .jar luajava (backend natif lua51) manquants.")
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

	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main())
