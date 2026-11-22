#!/usr/bin/env python3
"""Back up legacy Dictée universelle DEBUG APKs without uninstalling them.

Requires Python 3, Android platform-tools (adb), a connected unlocked Android
phone with USB debugging. Android "run-as" works only for debuggable APKs.
The ZIP can be imported into the future production Dictée universelle.
No file on the device is modified.
"""
import pathlib
import re
import subprocess
import sys
import tarfile
import zipfile

ROOT = pathlib.Path.cwd()
VALID = re.compile(r"^dictations/(d_[0-9]+_[a-f0-9]{8})/(metadata[.]json|part-[0-9]{3}[.]m4a)$")
MAX_ENTRY = 10_000_000


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, **kwargs)


def export(package: str) -> pathlib.Path:
    filename = ROOT / ("dictee-sauvegarde-" + package.replace(".", "-") + ".zip")
    if filename.exists():
        raise RuntimeError("Une sauvegarde de ce nom existe déjà ; renommez-la avant de recommencer : " + str(filename))
    command = ["adb", "exec-out", "run-as", package, "tar", "-C", "files", "-cf", "-", "dictations"]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert proc.stdout and proc.stderr
    count = 0
    try:
        with zipfile.ZipFile(filename, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            with tarfile.open(fileobj=proc.stdout, mode="r|") as original:
                for item in original:
                    if item.isdir():
                        continue
                    if not item.isfile() or not VALID.fullmatch(item.name):
                        raise RuntimeError("La sauvegarde contient un fichier inattendu : " + item.name)
                    if item.size > MAX_ENTRY:
                        raise RuntimeError("Fichier trop volumineux : " + item.name)
                    entry = original.extractfile(item)
                    if entry is None:
                        raise RuntimeError("Fichier inaccessible : " + item.name)
                    with output.open(item.name, "w") as dest:
                        while True:
                            block = entry.read(32768)
                            if not block:
                                break
                            dest.write(block)
                    count += 1
        error = proc.stderr.read().decode("utf8", "replace").strip()
        if proc.wait() != 0 or count == 0:
            raise RuntimeError(error or "Sauvegarde indisponible ; l'application n'est peut-être pas débogable")
        return filename
    except BaseException:
        proc.kill()
        proc.wait()
        filename.unlink(missing_ok=True)
        raise


def main():
    try:
        result = adb("devices", capture_output=True, text=True)
        devices = [line for line in result.stdout.splitlines()[1:] if line.endswith("\tdevice")]
        if len(devices) != 1:
            raise RuntimeError("Connectez un seul téléphone déverrouillé en mode débogage USB")
        packages = adb("shell", "pm", "list", "packages", "fr.denizot.dictee",
                       capture_output=True, text=True).stdout
        names = [line.removeprefix("package:").strip() for line in packages.splitlines()
                 if line.startswith("package:fr.denizot.dictee")]
        if not names:
            raise RuntimeError("Aucune version de Dictée universelle n'a été trouvée")
        print("Versions trouvées : " + ", ".join(names))
        print("Aucune application ne sera désinstallée ou modifiée.")
        for package in names:
            print("Sauvegarde de " + package + "…")
            try:
                location = export(package)
                print("OK : " + str(location))
            except (OSError, RuntimeError, tarfile.TarError, subprocess.CalledProcessError) as exc:
                print("Non sauvegardée : " + str(exc))
        print("Transférez le ZIP obtenu sur votre téléphone et utilisez « Importer une sauvegarde ».")
        print("N'effacez aucune ancienne version avant d'avoir vérifié les audios dans la nouvelle.")
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print("ERREUR : " + str(exc), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
