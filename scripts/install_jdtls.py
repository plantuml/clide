#!/usr/bin/env python3
"""Télécharge et extrait la dernière version d'Eclipse JDT Language Server (jdtls).

Ne fait appel qu'à la bibliothèque standard (urllib, tarfile) : rien à installer
au préalable avec pip.

Utilise le lien stable documenté par le projet, qui pointe toujours vers le
dernier build :
    https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz

Usage:
    python install_jdtls.py [destination]

L'archive .tar.gz elle-même est déposée à la racine du repo.
"""

import sys
import tarfile
import urllib.request
from pathlib import Path

JDTLS_URL = "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz"


def repo_root() -> Path:
	return Path(__file__).resolve().parent.parent


def archive_path() -> Path:
	return repo_root() / "jdt-language-server-latest.tar.gz"


def default_destination() -> Path:
	return repo_root() / "jdtls"


def download(url: str, dest: Path) -> None:
	print(f"Téléchargement de {url} ...")
	with urllib.request.urlopen(url) as response, open(dest, "wb") as out_file:
		total = getattr(response, "length", 0) or 0
		read = 0
		chunk_size = 256 * 1024
		while True:
			chunk = response.read(chunk_size)
			if not chunk:
				break
			out_file.write(chunk)
			read += len(chunk)
			if total:
				pct = read * 100 // total
				print(f"\r  {pct}% ({read // (1024 * 1024)} Mo / {total // (1024 * 1024)} Mo)", end="")
	print()


def extract(archive: Path, destination: Path) -> None:
	print(f"Extraction dans {destination} ...")
	destination.mkdir(parents=True, exist_ok=True)
	with tarfile.open(archive, "r:gz") as tar:
		tar.extractall(destination)


def main() -> int:
	destination = Path(sys.argv[1]) if len(sys.argv) > 1 else default_destination()
	archive = archive_path()

	download(JDTLS_URL, archive)
	extract(archive, destination)

	print()
	print(f"Archive       : {archive}")
	print("Terminé.")
	print(f"Dossier jdtls : {destination}")

	launcher_candidates = [
		destination / "bin" / "jdtls.bat",
		destination / "bin" / "jdtls",
	]
	launcher = next((candidate for candidate in launcher_candidates if candidate.exists()), None)
	if launcher:
		print(f"Lanceur      : {launcher}")
	else:
		print("Note : aucun lanceur bin/jdtls(.bat) trouvé, vérifier le contenu de l'archive extraite.")

	return 0


if __name__ == "__main__":
	raise SystemExit(main())
