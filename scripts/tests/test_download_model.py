import hashlib
import io

import pytest

from scripts.download_model import save_verified


def test_verified_download_is_written_atomically(tmp_path):
    destination = tmp_path / "model.bin"
    data = b"sample model content"
    save_verified(io.BytesIO(data), destination, hashlib.sha256(data).hexdigest(), len(data))
    assert destination.read_bytes() == data
    assert not destination.with_suffix(".bin.part").exists()


def test_bad_checksum_leaves_no_model_or_partial_file(tmp_path):
    destination = tmp_path / "model.bin"
    with pytest.raises(ValueError, match="checksum"):
        save_verified(io.BytesIO(b"bad"), destination, "0" * 64, 3)
    assert not destination.exists()
    assert not destination.with_suffix(".bin.part").exists()


def test_download_cannot_overwrite_an_existing_model(tmp_path):
    destination = tmp_path / "model.bin"
    destination.write_bytes(b"keep")
    with pytest.raises(FileExistsError):
        save_verified(io.BytesIO(b"new"), destination, "0" * 64, 3)
    assert destination.read_bytes() == b"keep"


def test_oversized_response_is_rejected(tmp_path):
    destination = tmp_path / "model.bin"
    with pytest.raises(ValueError, match="size"):
        save_verified(io.BytesIO(b"too much"), destination, "0" * 64, 2)
    assert not destination.exists()
