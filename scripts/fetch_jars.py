#!/usr/bin/env python3
"""Télécharge des .jar depuis Maven Central dans le dossier .clide d'un projet.

N'utilise que la bibliothèque standard (urllib) : rien à installer.

Usage:
    python fetch_jars.py <dossier_destination> <url_maven_central> [<url> ...]

Exemple (JUnit 5, pour PlantUML) :
    python fetch_jars.py C:\\github\\tmp\\plantuml\\.clide ^
        https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-api/5.10.1/junit-jupiter-api-5.10.1.jar ^
        https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.10.1/junit-jupiter-engine-5.10.1.jar ^
        https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-params/5.10.1/junit-jupiter-params-5.10.1.jar ^
        https://repo1.maven.org/maven2/org/junit/platform/junit-platform-commons/1.9.1/junit-platform-commons-1.9.1.jar ^
        https://repo1.maven.org/maven2/org/opentest4j/opentest4j/1.2.0/opentest4j-1.2.0.jar
"""

import sys
import urllib.request
from pathlib import Path


def download(url: str, destination: Path) -> None:
	filename = url.rsplit("/", 1)[-1]
	target = destination / filename
	print(f"Téléchargement de {filename} ...")
	urllib.request.urlretrieve(url, target)
	print(f"  -> {target} ({target.stat().st_size // 1024} Ko)")


def main() -> int:
	if len(sys.argv) < 3:
		print("Usage: python fetch_jars.py <dossier_destination> <url> [<url> ...]")
		return 1

	destination = Path(sys.argv[1])
	destination.mkdir(parents=True, exist_ok=True)

	for url in sys.argv[2:]:
		download(url, destination)

	return 0


if __name__ == "__main__":
	raise SystemExit(main())
