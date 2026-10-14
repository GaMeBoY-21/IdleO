import json
import os
import sys

import pytest

from idleo import Client, errors
from idleo._config import api_url, config_dir
from idleo._keys import stored_key


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in ("IDLEO_API_KEY", "IDLEO_API_URL", "IDLEO_CONFIG_DIR"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("IDLEO_NO_KEYCHAIN", "1")


def test_the_argument_beats_the_environment_beats_the_local_stack(monkeypatch):
    assert api_url() == "http://localhost:8080"
    monkeypatch.setenv("IDLEO_API_URL", "https://b.example/")
    assert api_url() == "https://b.example"
    assert api_url("https://a.example") == "https://a.example"
    monkeypatch.setenv("IDLEO_API_URL", "  ")
    assert api_url() == "http://localhost:8080"


@pytest.mark.parametrize("bad", ["localhost:8080", "ftp://x.example", "not a url", "file:///etc"])
def test_an_address_that_isnt_http_is_refused(bad):
    with pytest.raises(errors.UsageError):
        api_url(bad)


def test_the_config_folder_can_be_given_or_set(monkeypatch, tmp_path):
    assert config_dir(tmp_path) == tmp_path
    monkeypatch.setenv("IDLEO_CONFIG_DIR", str(tmp_path / "x"))
    assert config_dir() == tmp_path / "x"


def test_the_config_folder_follows_the_system(monkeypatch, tmp_path):
    monkeypatch.setattr("pathlib.Path.home", lambda: tmp_path)
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    found = config_dir()
    if sys.platform == "darwin":
        assert found == tmp_path / "Library/Application Support/idleo"
    elif sys.platform == "win32":
        assert found.name == "idleo"
    else:
        assert found == tmp_path / ".config/idleo"


def write_credentials(directory, api, key):
    directory.mkdir(parents=True, exist_ok=True)
    # The command line's file: a map from the API address to the key and its id.
    (directory / "credentials.json").write_text(
        json.dumps({api: {"key": key, "id": "0194f2a0-0000-7000-8000-000000000000"}})
    )


def test_nothing_is_stored_until_a_key_is_saved(tmp_path):
    assert stored_key("http://localhost:8080", tmp_path) is None


def test_the_command_lines_file_is_read(tmp_path):
    write_credentials(tmp_path, "http://localhost:8080", "idleo_from_file")
    assert stored_key("http://localhost:8080", tmp_path) == "idleo_from_file"


def test_a_key_is_kept_for_its_own_api_address_only(tmp_path):
    write_credentials(tmp_path, "http://localhost:8080", "idleo_local")
    assert stored_key("https://api.example", tmp_path) is None


def test_the_environment_wins(monkeypatch, tmp_path):
    write_credentials(tmp_path, "http://localhost:8080", "idleo_stored")
    monkeypatch.setenv("IDLEO_API_KEY", " idleo_env ")
    assert stored_key("http://localhost:8080", tmp_path) == "idleo_env"


def test_a_damaged_file_is_as_good_as_none(tmp_path):
    (tmp_path / "credentials.json").write_text("{ not json")
    assert stored_key("http://localhost:8080", tmp_path) is None


def test_the_keychain_entry_the_command_line_made_is_read(monkeypatch, tmp_path):
    monkeypatch.delenv("IDLEO_NO_KEYCHAIN")
    asked = []

    class Fake:
        @staticmethod
        def get_password(service, account):
            asked.append((service, account))
            return json.dumps({"key": "idleo_keychain", "id": None})

    monkeypatch.setitem(sys.modules, "keyring", Fake)
    assert stored_key("http://localhost:8080", tmp_path) == "idleo_keychain"
    assert asked == [("idleo", "http://localhost:8080")]


def test_a_machine_with_no_keychain_falls_back_to_the_file(monkeypatch, tmp_path):
    monkeypatch.delenv("IDLEO_NO_KEYCHAIN")

    class Broken:
        @staticmethod
        def get_password(service, account):
            raise RuntimeError("no keyring backend")

    monkeypatch.setitem(sys.modules, "keyring", Broken)
    write_credentials(tmp_path, "http://localhost:8080", "idleo_file")
    assert stored_key("http://localhost:8080", tmp_path) == "idleo_file"


def test_a_client_with_no_key_says_how_to_sign_in(tmp_path):
    client = Client(config_dir=tmp_path)
    with pytest.raises(errors.NotSignedIn, match="idleo login"):
        client.me()
    assert os.environ.get("IDLEO_API_KEY") is None


@pytest.mark.parametrize(
    ("value", "off"),
    [
        ("1", True),
        ("true", True),
        (" TRUE ", True),
        ("0", False),
        ("false", False),
        ("", False),
        ("yes", False),
        (None, False),
    ],
)
def test_no_keychain_turns_it_off_only_for_one_or_true_as_the_command_line_does(value, off):
    from idleo._keys import keychain_off

    assert keychain_off(value) is off
