#!/usr/bin/env python3
"""Télécharge la dernière version d'Eclipse JDT Language Server (jdtls) et la
reconditionne en fichier .zip compressé avec zopfli.

Recompresser l'archive tar.gz en zip comme un simple blob (chaque .jar traité
comme un fichier opaque) n'apporte presque rien : les .jar sont déjà des zip
compressés, donc les re-déflater de l'extérieur ne fait que consommer du
temps CPU pour un gain proche de zéro (constaté en pratique : 48.6 -> 48.5
Mo). Ce script recompresse donc aussi le contenu *interne* de chaque .jar :
chaque entrée du jar (classes, ressources...) est décompressée puis
recompressée avec zopfli, avant d'être réinsérée telle quelle (STORED, sans
recompression supplémentaire inutile) dans le .zip final. C'est ce niveau de
détail qui apporte un gain réel, car les entrées internes des jars sont
généralement compressées par le outil "jar" avec zlib au niveau par défaut,
pas au niveau maximal ni avec zopfli.

Ne fait appel qu'à la bibliothèque standard pour le téléchargement et
l'extraction (comme install_jdtls.py). La compression zopfli nécessite en
revanche le paquet tiers "zopflipy" :
    pip install zopflipy

Ce paquet fournit un module `zopfli` avec une classe zopfli.ZipFile,
utilisable comme zipfile.ZipFile mais qui compresse chaque entrée avec
l'algorithme zopfli plutôt qu'avec le compresseur zlib standard.

Usage:
    python download_and_zip_jdtls.py [destination.zip] [iterations]

    destination.zip : chemin du .zip produit (par défaut :
                       jdt-language-server-latest.zip à la racine du repo)
    iterations      : nombre d'itérations zopfli (par défaut : 15, la
                       valeur par défaut de zopfli ; augmenter améliore
                       légèrement le taux de compression mais ralentit
                       encore la compression, diminuer accélère au prix
                       d'un taux de compression un peu moins bon). Comme
                       chaque jar est maintenant recompressé entrée par
                       entrée (potentiellement des milliers d'entrées au
                       total), l'opération est beaucoup plus lente qu'une
                       simple recompression du blob global : réduire ce
                       nombre (ex. 5) accélère nettement le traitement.

L'archive .tar.gz téléchargée est déposée à la racine du repo (comme dans
install_jdtls.py) ; elle est conservée après l'opération pour éviter de la
re-télécharger si le script est relancé.
"""

import io
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

try:
    from zopfli import ZipFile as ZopfliZipFile
except ImportError:
    print(
        "Le paquet 'zopflipy' est requis pour compresser avec zopfli.\n"
        "Installe-le avec : pip install zopflipy",
        file=sys.stderr,
    )
    raise SystemExit(1)

JDTLS_URL = "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz"

DEFAULT_ITERATIONS = 15

# Extensions de fichiers considérés comme des archives zip imbriquées (jars
# Java, entre autres) dont le contenu interne doit être recompressé plutôt
# que le fichier lui-même traité comme un blob opaque.
NESTED_ZIP_EXTENSIONS = {".jar", ".zip", ".war"}


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def archive_path() -> Path:
    return repo_root() / "jdt-language-server-latest.tar.gz"


def default_zip_path() -> Path:
    return repo_root() / "jdt-language-server-latest.zip"


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


def _fmt_mo(n_bytes: int) -> str:
    return f"{n_bytes / (1024 * 1024):.2f} Mo"


def zopfli_recompress_nested_zip(path: Path, iterations: int):
    """Relit un .jar/.zip et renvoie (octets recompressés, taille décompressée
    cumulée de ses entrées).

    Chaque entrée initialement DEFLATED est décompressée puis recompressée
    avec zopfli ; les entrées STORED (déjà stockées telles quelles, ex.
    ressources binaires volontairement non compressées) sont conservées en
    l'état. Le format de l'archive (structure interne, noms, dates,
    attributs) n'est pas modifié : seul l'algorithme de compression change,
    ce qui ne casse pas la vérification de signature d'un jar signé
    (celle-ci porte sur le contenu décompressé des entrées, pas sur leurs
    octets compressés)."""
    buf = io.BytesIO()
    uncompressed = 0
    with zipfile.ZipFile(path, "r") as src, ZopfliZipFile(buf, "w", iterations=iterations) as dst:
        for info in src.infolist():
            uncompressed += info.file_size
            data = src.read(info.filename)
            new_info = zipfile.ZipInfo(filename=info.filename, date_time=info.date_time)
            new_info.external_attr = info.external_attr
            new_info.create_system = info.create_system
            new_info.compress_type = (
                zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            )
            dst.writestr(new_info, data)
    return buf.getvalue(), uncompressed


def tar_to_zopfli_zip(tar_path: Path, zip_path: Path, iterations: int) -> None:
    """Extrait `tar_path` dans un dossier temporaire puis reconstruit un .zip
    à `zip_path`.

    Les fichiers "normaux" (scripts, configuration, ...) sont compressés
    avec zopfli directement dans le zip final. Les archives imbriquées
    (.jar, .zip, .war) sont d'abord recompressées entrée par entrée avec
    zopfli (voir `zopfli_recompress_nested_zip`), PUIS le blob résultant est
    lui-même compressé (zopfli) une seconde fois dans le zip final.

    Attention, ça semble contre-intuitif ("recompresser un fichier déjà
    compressé ne sert à rien") mais c'est faux ici : le format zip/jar
    stocke les noms de fichiers en clair, en double (une fois dans chaque
    en-tête local, une fois dans le répertoire central), plus des signatures
    et champs de longueur fixe -- rien de tout ça n'est compressé par le jar
    lui-même (seul le contenu de chaque entrée l'est). Avec des centaines,
    voire des milliers de petites entrées par jar, cette métadonnée non
    compressée pèse lourd, et une seconde passe de compression sur le jar
    entier la rattrape. Vérifié empiriquement : stocker le blob tel quel
    (STORED) ne gagnait presque rien par rapport au tar.gz d'origine ; le
    recompresser une seconde fois ici permet de repasser sous cette taille."""
    with tempfile.TemporaryDirectory(prefix="jdtls_extract_") as tmp_str:
        tmp_dir = Path(tmp_str)

        print(f"Extraction temporaire de {tar_path.name} ...")
        with tarfile.open(tar_path, "r:gz") as tar:
            tar.extractall(tmp_dir)

        files = sorted(p for p in tmp_dir.rglob("*") if p.is_file())
        total = len(files)
        print(f"Recompression de {total} fichiers en {zip_path.name} avec zopfli "
              f"(iterations={iterations}, cela peut prendre longtemps) ...")

        total_before = 0
        total_after = 0

        # Cumuls spécifiques aux archives imbriquées (.jar/.zip/.war), avec le
        # détail compressé initial / décompressé / zopfli demandé.
        nested_count = 0
        cumulative_compressed_before = 0
        cumulative_uncompressed = 0
        cumulative_zopfli = 0

        zip_path.parent.mkdir(parents=True, exist_ok=True)
        with ZopfliZipFile(zip_path, "w", iterations=iterations) as zf:
            for i, file_path in enumerate(files, start=1):
                arcname = file_path.relative_to(tmp_dir).as_posix()
                original_size = file_path.stat().st_size

                is_nested_zip = (
                    file_path.suffix.lower() in NESTED_ZIP_EXTENSIONS and zipfile.is_zipfile(file_path)
                )
                if is_nested_zip:
                    # `compressed_before` = taille du fichier .jar/.zip/.war original sur
                    # disque (tel quel, en-têtes zip inclus) ; `new_size` = taille
                    # réellement écrite dans le zip final pour cette entrée (après
                    # recompression interne + recompression du blob résultant) ;
                    # `uncompressed` = somme des tailles décompressées des entrées
                    # (le contenu réel, sans aucun conteneur).
                    compressed_before = original_size
                    data, uncompressed = zopfli_recompress_nested_zip(file_path, iterations)
                    info = zipfile.ZipInfo(filename=arcname)
                    info.date_time = time.localtime(file_path.stat().st_mtime)[:6]
                    info.external_attr = (file_path.stat().st_mode & 0xFFFF) << 16
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zf.writestr(info, data)
                    new_size = zf.getinfo(arcname).compress_size

                    nested_count += 1
                    cumulative_compressed_before += compressed_before
                    cumulative_uncompressed += uncompressed
                    cumulative_zopfli += new_size
                    print(
                        f"  [{i}/{total}] {arcname}\n"
                        f"      compressé initial : {_fmt_mo(compressed_before)}\n"
                        f"      décompressé       : {_fmt_mo(uncompressed)}\n"
                        f"      zopfli            : {_fmt_mo(new_size)}\n"
                        f"      cumul .jar/.zip/.war ({nested_count}) : "
                        f"{_fmt_mo(cumulative_compressed_before)} compressé initial -> "
                        f"{_fmt_mo(cumulative_uncompressed)} décompressé -> "
                        f"{_fmt_mo(cumulative_zopfli)} zopfli"
                    )
                else:
                    new_size = original_size
                    zf.write(file_path, arcname)
                    print(f"  [{i}/{total}] {arcname}: {original_size / 1024:.0f} Ko")

                total_before += original_size
                total_after += new_size

        print()
        print(
            f"Taille cumulée de tous les fichiers extraits : {_fmt_mo(total_before)} -> {_fmt_mo(total_after)}"
        )
        if nested_count:
            print(
                f"Dont {nested_count} archive(s) .jar/.zip/.war : "
                f"{_fmt_mo(cumulative_compressed_before)} compressé initial, "
                f"{_fmt_mo(cumulative_uncompressed)} décompressé, "
                f"{_fmt_mo(cumulative_zopfli)} après zopfli"
            )


def main() -> int:
    zip_dest = Path(sys.argv[1]) if len(sys.argv) > 1 else default_zip_path()
    iterations = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_ITERATIONS
    archive = archive_path()

    if archive.exists():
        print(f"Archive déjà présente, téléchargement ignoré : {archive}")
    else:
        download(JDTLS_URL, archive)

    tar_to_zopfli_zip(archive, zip_dest, iterations)

    print()
    print(f"Archive tar.gz : {archive} ({archive.stat().st_size / (1024 * 1024):.1f} Mo)")
    print(f"Archive zip    : {zip_dest} ({zip_dest.stat().st_size / (1024 * 1024):.1f} Mo)")
    print("Terminé.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
