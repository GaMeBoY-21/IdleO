"""An upload against a fake API and a fake storage: parts, their ETags, resume, expired links and the folder's paths."""

from __future__ import annotations

import json

import httpx
import pytest
from conftest import STORAGE, body, new_id, problem

from idleo import errors
from idleo._resume import ResumeStore

PART = 4  # bytes: small enough to make a few parts of a small file


class Storage:
    """The API's upload calls and the storage behind its links, with a switch for each way a part can fail."""

    def __init__(self, fake, files: dict[str, int]):
        self.id = new_id()
        self.files = files
        self.taken: dict[tuple[str, int], bytes] = {}
        self.fail_part: tuple[str, int] | None = None
        self.fail_times = 0
        self.expire_once = False
        self.puts: list[tuple[str, int]] = []
        self.completed: dict[str, list[dict]] = {}
        self.opens: list[str | None] = []
        fake.route("POST", "/v1/uploads", self.open)
        fake.route("POST", rf"/v1/uploads/{self.id}/part-urls", self.part_urls)
        fake.route("POST", rf"/v1/uploads/{self.id}/complete", self.complete)
        fake.route("PUT", rf"{STORAGE}/put/.*", self.put)

    def detail(self, status="pending"):
        return {
            "upload": {
                "id": self.id,
                "name": "x",
                "status": status,
                "partSizeBytes": PART,
                "fileCount": len(self.files),
                "sizeBytes": sum(self.files.values()),
                "createdAt": "2026-10-14T10:00:00Z",
                "expiresAt": "2026-11-14T10:00:00Z",
            },
            "files": [
                {"path": p, "sizeBytes": size, "partCount": -(-size // PART), "done": p in self.completed}
                for p, size in self.files.items()
            ],
        }

    def open(self, request):
        self.opens.append(request.headers.get("Idempotency-Key"))
        return httpx.Response(201, json=self.detail())

    def part_urls(self, request):
        data = body(request)
        parts = [{"number": n, "url": f"{STORAGE}/put/{data['path']}/{n}"} for n in data["partNumbers"]]
        return httpx.Response(200, json={"parts": parts, "urlsExpireAt": "2026-10-14T11:00:00Z"})

    def put(self, request):
        path, number = request.url.path.removeprefix("/put/").rsplit("/", 1)
        self.puts.append((path, int(number)))
        if self.expire_once:
            self.expire_once = False
            return httpx.Response(403)
        if self.fail_part == (path, int(number)) and self.fail_times > 0:
            self.fail_times -= 1
            return httpx.Response(500)
        self.taken[(path, int(number))] = request.content
        return httpx.Response(200, headers={"ETag": f'"etag-{path}-{number}"'})

    def complete(self, request):
        for file in body(request)["files"]:
            self.completed[file["path"]] = file["parts"]
        return httpx.Response(200, json={})


@pytest.fixture
def folder(tmp_path):
    root = tmp_path / "my-model"
    (root / "shards").mkdir(parents=True)
    (root / "config.json").write_bytes(b"{}")
    (root / "shards" / "w.bin").write_bytes(b"0123456789AB")  # 3 parts of 4
    return root


def run(fake, tmp_path, folder, storage):
    return fake.client(tmp_path).uploads.upload(folder)


def test_a_folder_goes_up_with_its_paths_and_every_part(fake, tmp_path, folder):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    upload_id = run(fake, tmp_path, folder, storage)
    assert str(upload_id) == storage.id
    assert storage.taken[("shards/w.bin", 2)] == b"4567"
    assert b"".join(storage.taken[("shards/w.bin", n)] for n in (1, 2, 3)) == b"0123456789AB"
    opened = body(fake.calls("POST", "/v1/uploads")[0])
    assert opened["files"] == [{"path": "config.json", "sizeBytes": 2}, {"path": "shards/w.bin", "sizeBytes": 12}]
    assert storage.completed["shards/w.bin"] == [{"number": n, "etag": f'"etag-shards/w.bin-{n}"'} for n in (1, 2, 3)]


def test_storage_gets_no_key(fake, tmp_path, folder):
    Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    run(fake, tmp_path, folder, None)
    puts = [r for r in fake.requests if r.method == "PUT"]
    assert puts
    assert all("authorization" not in r.headers for r in puts)


def test_a_failed_part_is_retried_and_the_upload_goes_on(fake, tmp_path, folder):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    storage.fail_part, storage.fail_times = ("shards/w.bin", 2), 2
    run(fake, tmp_path, folder, storage)
    assert storage.puts.count(("shards/w.bin", 2)) == 3
    assert fake.sleeps == [1, 2]


def test_an_expired_link_is_replaced_with_a_fresh_one(fake, tmp_path, folder):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    storage.expire_once = True
    run(fake, tmp_path, folder, storage)
    assert len(storage.taken) == 4
    assert fake.sleeps == []


def test_a_part_that_never_goes_through_stops_the_upload_and_the_next_run_sends_only_what_is_missing(
    fake, tmp_path, folder
):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    storage.fail_part, storage.fail_times = ("shards/w.bin", 2), 99
    with pytest.raises(errors.NetworkError, match=r"part 2 of shards/w\.bin couldn.t be sent"):
        run(fake, tmp_path, folder, storage)
    assert storage.completed == {}
    # What storage took is remembered, in the command line's file and format.
    saved = list((tmp_path / "config" / "uploads").glob("*.json"))
    assert len(saved) == 1
    record = json.loads(saved[0].read_text())
    assert record["upload_id"] == storage.id
    assert "2" not in record["parts"].get("shards/w.bin", {})

    taken_before = set(storage.taken)
    storage.fail_times = 0
    storage.puts.clear()
    run(fake, tmp_path, folder, storage)
    # Only the parts storage hadn't taken are sent again, and the upload opens with the same key.
    assert set(storage.puts).isdisjoint(taken_before)
    assert ("shards/w.bin", 2) in storage.puts
    assert storage.opens[0] == storage.opens[1] == record["key"]
    assert len(storage.completed["shards/w.bin"]) == 3
    assert list((tmp_path / "config" / "uploads").glob("*.json")) == []


def test_an_upload_already_ready_sends_nothing(fake, tmp_path, folder):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    fake.route("POST", "/v1/uploads", lambda r: httpx.Response(200, json=storage.detail("ready")))
    run(fake, tmp_path, folder, storage)
    assert storage.puts == []


def test_a_remembered_upload_the_api_no_longer_has_starts_over(fake, tmp_path, folder):
    storage = Storage(fake, {"config.json": 2, "shards/w.bin": 12})
    from idleo._files import collect, fingerprint
    from idleo._resume import Unfinished

    name, files = collect(folder)
    ResumeStore(tmp_path / "config").write(
        fingerprint(name, files), Unfinished(upload_id=new_id(), key="old", parts={})
    )
    run(fake, tmp_path, folder, storage)
    assert storage.opens[0] == "old"
    assert storage.opens[1] != "old"
    assert len(storage.completed) == 2


def test_the_apis_refusal_reaches_the_caller_by_type(fake, tmp_path, folder):
    fake.route("POST", "/v1/uploads", lambda r: problem("upload-quota-exceeded", 409, "full"))
    with pytest.raises(errors.UploadQuotaExceeded):
        run(fake, tmp_path, folder, None)
