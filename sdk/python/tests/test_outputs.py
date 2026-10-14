"""Outputs: the list, and downloads that carry on from a part file and never leave the folder they were asked into."""

from __future__ import annotations

import httpx
import pytest
from conftest import STORAGE, problem

from idleo import errors
from idleo.outputs import PART_SUFFIX, destination

ID = "01a10e8e-9c65-7789-a526-473491c82218"
ADAPTER = bytes(range(256)) * 4  # 1,024 bytes


def listing(*files: tuple[str, bytes], token: str = "a") -> dict:
    return {
        "sessionId": ID,
        "attempt": 1,
        "linksExpireAt": "2026-10-14T11:00:00Z",
        "files": [{"path": p, "sizeBytes": len(b), "url": f"{STORAGE}/out/{token}/{p}"} for p, b in files],
    }


class Outputs:
    def __init__(self, fake, tmp_path, files):
        self.files = dict(files)
        self.ranges: list[str | None] = []
        self.expired = {"a"}
        self.tokens = ["a", "b"]
        fake.route("GET", f"/v1/sessions/{ID}/outputs", self.listing)
        fake.route("GET", rf"{STORAGE}/out/.*", self.get)
        self.fake = fake
        fake.route(
            "GET",
            f"/v1/sessions/{ID}",
            lambda r: httpx.Response(200, json=__import__("conftest").session("COMPLETED", True)),
        )
        self.session = fake.client(tmp_path).sessions.get(ID)

    def listing(self, request):
        return httpx.Response(200, json=listing(*self.files.items(), token=self.tokens[0]))

    def get(self, request):
        token, path = request.url.path.removeprefix("/out/").split("/", 1)
        if token in self.expired and self.expired_for_good:
            return httpx.Response(403)
        data = self.files[path]
        header = request.headers.get("Range")
        self.ranges.append(header)
        if header:
            start = int(header.removeprefix("bytes=").removesuffix("-"))
            return httpx.Response(206, content=data[start:])
        return httpx.Response(200, content=data)

    expired_for_good = False


@pytest.fixture
def adapter(fake, tmp_path):
    return Outputs(fake, tmp_path, {"adapter_model.safetensors": ADAPTER, "logs/train.txt": b"ok\n"})


def test_the_outputs_are_listed_with_their_sizes(adapter):
    files = adapter.session.outputs.list()
    assert [(f.path, f.size_bytes) for f in files] == [("adapter_model.safetensors", 1024), ("logs/train.txt", 3)]


def test_download_keeps_paths_and_returns_where_each_went(adapter, tmp_path):
    out = tmp_path / "out"
    done = adapter.session.outputs.download(out)
    assert done == [out / "adapter_model.safetensors", out / "logs" / "train.txt"]
    assert (out / "adapter_model.safetensors").read_bytes() == ADAPTER
    assert not list(out.rglob(f"*{PART_SUFFIX}"))


def test_a_part_file_carries_on_from_where_it_stopped(adapter, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / f"adapter_model.safetensors{PART_SUFFIX}").write_bytes(ADAPTER[:300])
    adapter.session.outputs.download(out)
    assert adapter.ranges[0] == "bytes=300-"
    assert (out / "adapter_model.safetensors").read_bytes() == ADAPTER


def test_a_whole_file_is_not_fetched_again(adapter, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "adapter_model.safetensors").write_bytes(ADAPTER)
    adapter.session.outputs.download(out)
    assert len(adapter.ranges) == 1  # only logs/train.txt


def test_a_part_file_longer_than_the_file_starts_over(adapter, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / f"adapter_model.safetensors{PART_SUFFIX}").write_bytes(b"x" * 5000)
    adapter.session.outputs.download(out)
    assert (out / "adapter_model.safetensors").read_bytes() == ADAPTER


def test_a_download_that_stops_short_is_tried_again_and_then_raised(fake, tmp_path):
    files = {"a.bin": b"0123456789"}
    state = Outputs(fake, tmp_path, files)
    short = []

    def get(request):
        short.append(1)
        return httpx.Response(200, content=b"0123", headers={"content-length": "4"})

    fake.route("GET", rf"{STORAGE}/out/.*", get)
    with pytest.raises(errors.NetworkError, match="only 4 of 10 bytes"):
        state.session.outputs.download(tmp_path / "out")
    assert len(short) == 4  # the first try and three more
    assert fake.sleeps == [2, 4, 6]


def test_an_expired_link_is_replaced_by_asking_for_the_list_again(fake, tmp_path):
    state = Outputs(fake, tmp_path, {"a.bin": b"hello"})
    state.expired_for_good = True

    def listing_after_first(request):
        if len(fake.calls("GET", f"/v1/sessions/{ID}/outputs")) > 1:
            state.tokens[0] = "b"
        return httpx.Response(200, json=listing(*state.files.items(), token=state.tokens[0]))

    fake.route("GET", f"/v1/sessions/{ID}/outputs", listing_after_first)
    state.session.outputs.download(tmp_path / "out")
    assert (tmp_path / "out" / "a.bin").read_bytes() == b"hello"
    assert len(fake.calls("GET", f"/v1/sessions/{ID}/outputs")) == 2


def test_links_that_stay_refused_are_an_error_not_a_loop(fake, tmp_path):
    state = Outputs(fake, tmp_path, {"a.bin": b"hello"})
    state.expired_for_good = True
    with pytest.raises(errors.IdleoError, match="refused the download link"):
        state.session.outputs.download(tmp_path / "out")


def test_outputs_not_ready_is_its_exception(fake, tmp_path):
    state = Outputs(fake, tmp_path, {})
    fake.route("GET", f"/v1/sessions/{ID}/outputs", lambda r: problem("outputs-not-ready", 409, "still running"))
    with pytest.raises(errors.OutputsNotReady):
        state.session.outputs.list()


def test_an_output_stays_inside_the_download_folder(tmp_path):
    assert destination(tmp_path, "adapter_model.safetensors") == tmp_path / "adapter_model.safetensors"
    assert destination(tmp_path, "logs/train.txt") == tmp_path / "logs" / "train.txt"


@pytest.mark.parametrize("bad", ["../x", "a/../../x", "/etc/passwd", "", "a\\b", "./a", "a//b"])
def test_a_path_that_would_leave_it_is_refused(tmp_path, bad):
    with pytest.raises(errors.IdleoError, match="would leave the download folder"):
        destination(tmp_path, bad)
