import hashlib
import os
from pathlib import Path

import pytest

from idleo import errors
from idleo._files import MAX_FILES, LocalFile, collect, fingerprint, part_range


def tree(tmp_path):
    (tmp_path / "config.json").write_text("{}")
    (tmp_path / "shards").mkdir()
    (tmp_path / "shards" / "part-0.bin").write_bytes(b"0" * 10)
    return tmp_path


def test_a_folders_files_are_listed_with_their_paths_inside_it(tmp_path):
    name, files = collect(tree(tmp_path))
    assert name == tmp_path.name
    assert [(f.path, f.size) for f in files] == [("config.json", 2), ("shards/part-0.bin", 10)]


def test_one_file_is_an_upload_of_one(tmp_path):
    name, files = collect(tree(tmp_path) / "config.json")
    assert (name, [f.path for f in files]) == ("config.json", ["config.json"])


def test_an_empty_folder_is_refused(tmp_path):
    with pytest.raises(errors.UsageError, match="no files"):
        collect(tmp_path)


def test_more_than_a_thousand_files_is_refused(tmp_path):
    for i in range(MAX_FILES + 1):
        (tmp_path / f"f{i}").write_text("")
    with pytest.raises(errors.UsageError, match="at most 1000"):
        collect(tmp_path)


@pytest.mark.skipif(os.name == "nt", reason="links need privileges on Windows")
def test_links_are_left_out(tmp_path):
    tree(tmp_path)
    (tmp_path / "link.json").symlink_to(tmp_path / "config.json")
    _, files = collect(tmp_path)
    assert len(files) == 2


def test_the_fingerprint_follows_the_files(tmp_path):
    name, files = collect(tree(tmp_path))
    same = fingerprint(name, files)
    assert fingerprint(name, files) == same
    (tmp_path / "config.json").write_text("{ }")
    os.utime(tmp_path / "config.json", (1, 1))
    assert fingerprint(*collect(tmp_path)) != same


def test_the_fingerprint_is_the_command_lines():
    # cli/src/files.rs: sha256 of the name, then "\n{path}\t{size}\t{modified}" for each file, in path order.
    files = [LocalFile("a.bin", 3, Path("a.bin"), 7), LocalFile("b/c.bin", 4, Path("b/c.bin"), 8)]
    expected = hashlib.sha256(b"model\na.bin\t3\t7\nb/c.bin\t4\t8").hexdigest()
    assert fingerprint("model", files) == expected


def test_parts_cover_the_file_and_the_last_is_what_is_left():
    assert part_range(1, 10, 25) == (0, 10)
    assert part_range(3, 10, 25) == (20, 25)
    assert part_range(4, 10, 25) == (25, 25)
