"""Materialize only image directories with matching NBT files for deployment."""

import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile


def digest(path: Path) -> bytes:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").digest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, default=Path("../schematic-service-data"))
    parser.add_argument("--source", type=Path, help="Defaults to the existing schematic-images symlink target")
    args = parser.parse_args()
    data_dir = args.data_dir.resolve()
    destination = data_dir / "schematic-images"
    if destination.exists() and not destination.is_symlink():
        parser.error(f"Refusing to replace an existing directory: {destination}")
    if args.source is None and not destination.is_symlink():
        parser.error("Provide --source or an existing schematic-images symlink")
    source = (args.source or destination).resolve(strict=True)
    if not source.is_dir():
        parser.error(f"Source is not a directory: {source}")
    nbt_ids = {p.stem for p in (data_dir / "Schematics-nbt").glob("*.nbt") if p.is_file()}
    if not nbt_ids:
        parser.error("No NBT files found; refusing to replace images")
    matched = sorted(i for i in nbt_ids if (source / i).is_dir())
    if not matched:
        parser.error("No matching image directories found")
    total_files = total_bytes = 0
    # Finish and verify the copy before replacing the original link.
    with tempfile.TemporaryDirectory(prefix=".schematic-images-", dir=data_dir) as temporary:
        staged = Path(temporary) / "images"
        staged.mkdir()
        for schematic_id in matched:
            shutil.copytree(source / schematic_id, staged / schematic_id, symlinks=False)
            for copied in (staged / schematic_id).rglob("*"):
                if copied.is_file():
                    original = source / copied.relative_to(staged)
                    if digest(original) != digest(copied):
                        raise RuntimeError(f"Copy verification failed: {copied}")
                    total_files += 1
                    total_bytes += copied.stat().st_size
        if destination.is_symlink():
            destination.unlink()
            try:
                staged.rename(destination)
            except OSError:
                destination.symlink_to(source, target_is_directory=True)
                raise
        else:
            staged.rename(destination)
    print(f"Copied and SHA-256 verified {total_files} files ({total_bytes:,} bytes) "
          f"for {len(matched)} NBT structures into {destination}")
    print(f"NBT structures without source images: {len(nbt_ids) - len(matched)}")


if __name__ == "__main__":
    main()
