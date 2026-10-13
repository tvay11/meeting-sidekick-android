"""Download a Whisper model and verify its size and SHA-256 against Hub metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen

REPOSITORY = "ggerganov/whisper.cpp"


def save_verified(source, destination: Path, expected_sha: str, expected_size: int):
    if destination.exists():
        raise FileExistsError(f"Refusing to replace {destination}")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or not 0 < expected_size <= 250_000_000:
        raise ValueError("Invalid model checksum or size metadata.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        digest, size = hashlib.sha256(), 0
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=destination.name, suffix=".part", delete=False) as output:
            temp = Path(output.name)
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                if size > expected_size:
                    raise ValueError("Downloaded model exceeds the expected size.")
                digest.update(chunk)
                output.write(chunk)
        if size != expected_size:
            raise ValueError("Downloaded model size does not match metadata.")
        if digest.hexdigest() != expected_sha:
            raise ValueError("Downloaded model checksum does not match metadata.")
        # Same-directory hard-link publication is atomic and cannot overwrite an existing file.
        os.link(temp, destination)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=["tiny.en", "base.en"], default="base.en")
    parser.add_argument("--output", type=Path, default=Path("models"))
    parser.add_argument("--info-only", action="store_true", help="Check metadata without downloading the model")
    args = parser.parse_args()
    request = Request(f"https://huggingface.co/api/models/{REPOSITORY}?blobs=true", headers={"User-Agent": "MeetingSidekick-model-downloader"})
    with urlopen(request, timeout=30) as response:
        metadata = json.load(response)
    revision = metadata["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Unexpected repository revision.")
    name = f"ggml-{args.model}.bin"
    entry = next(item for item in metadata["siblings"] if item["rfilename"] == name)
    expected_sha, size = entry["lfs"]["sha256"], entry["lfs"]["size"]
    print(f"Model: {name}\nRepository: {REPOSITORY}\nRevision: {revision}\nBytes: {size}\nSHA-256: {expected_sha}")
    if args.info_only:
        return
    destination = args.output / name
    if destination.exists():
        with destination.open("rb") as existing:
            if hashlib.file_digest(existing, "sha256").hexdigest() == expected_sha:
                print(f"Already verified: {destination}")
                return
        raise FileExistsError(f"{destination} exists with a different checksum. Choose another output directory.")
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{revision}/{name}"
    print("Downloading…")
    with urlopen(Request(url, headers={"User-Agent": "MeetingSidekick-model-downloader"}), timeout=60) as response:
        save_verified(response, destination, expected_sha, size)
    print(f"Verified: {destination}\nCopy this file to the tablet and choose Import model in Sidekick.")


if __name__ == "__main__":
    main()
