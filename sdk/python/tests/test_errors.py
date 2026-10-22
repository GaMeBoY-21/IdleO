import json
from pathlib import Path

import httpx
import pytest
from conftest import problem

from idleo import errors
from idleo._http import problem_from
from idleo._problem import exit_code_for

CATALOG = json.loads((Path(__file__).parents[3] / "api" / "problems.json").read_text())


def test_the_catalog_is_all_exported_with_readable_names():
    names = {cls.__name__: cls.slug for cls in (getattr(errors, n) for n in errors.__all__)}
    assert names["InsufficientCredit"] == "insufficient-credit"
    assert names["NoGpuFree"] == "no-gpu-free"
    assert names["RateLimited"] == "rate-limited"
    for entry in CATALOG:
        assert entry["slug"] in names.values(), entry["slug"]
    assert all(issubclass(getattr(errors, n), errors.IdleoError) for n in errors.__all__)


def test_a_problem_becomes_its_class_and_its_detail():
    response = problem("insufficient-credit", 402, "Booking holds 150 paise, but only 40 is available.")
    error = problem_from(response)
    assert isinstance(error, errors.InsufficientCredit)
    assert str(error) == "Booking holds 150 paise, but only 40 is available."
    assert error.exit_code == 3
    assert error.status == 402


def test_terms_not_accepted_carries_where_to_accept_and_exits_as_not_signed_in():
    detail = "Accept the Terms and the privacy notice before you create anything: sign in at https://app.example.invalid/agree/."
    error = problem_from(problem("terms-not-accepted", 403, detail))
    assert isinstance(error, errors.TermsNotAccepted)
    assert str(error) == detail
    assert error.exit_code == 5


def test_a_limit_says_how_long_to_wait():
    response = httpx.Response(
        429,
        json={"type": "/problems/rate-limited", "detail": "More than 60 requests in a minute."},
        headers={"Retry-After": "17"},
    )
    error = problem_from(response)
    assert isinstance(error, errors.RateLimited)
    assert error.retry_after == 17
    assert str(error).endswith("Try again in 17 s.")
    assert error.exit_code == 6


def test_invalid_fields_are_listed():
    response = problem(
        "validation-failed",
        400,
        "The request has invalid fields.",
        errors=[{"field": "params.epochs", "detail": "must be at most 10"}],
    )
    error = problem_from(response)
    assert isinstance(error, errors.ValidationFailed)
    assert error.fields == {"params.epochs": "must be at most 10"}
    assert str(error).endswith("\n  params.epochs: must be at most 10")
    assert error.exit_code == 2


def test_a_server_error_with_no_problem_is_a_server_error():
    assert isinstance(problem_from(httpx.Response(502, text="bad gateway")), errors.ServerError)


def test_a_type_this_version_doesnt_know_is_still_an_idleo_error():
    error = problem_from(problem("something-new", 409, "later"))
    assert type(error) is errors.IdleoError
    assert error.slug == "something-new"
    assert error.exit_code == 1


@pytest.mark.parametrize(
    ("slug", "code"),
    [
        ("unauthenticated", 5),
        ("invite-required", 5),
        ("browser-sign-in-required", 5),
        ("insufficient-credit", 3),
        ("no-gpu-free", 4),
        ("rate-limited", 6),
        ("too-many-new-sessions", 6),
        ("validation-failed", 2),
        ("malformed-request", 2),
        ("too-many-sessions", 1),
        ("input-not-accepted", 1),
        ("upload-in-use", 1),
        ("not-found", 1),
    ],
)
def test_each_problem_type_has_the_command_lines_exit_code(slug, code):
    assert exit_code_for(slug) == code


def test_the_sdks_own_failures_have_their_codes():
    assert errors.NotSignedIn().exit_code == 5
    assert errors.NetworkError().exit_code == 7
    assert errors.UsageError().exit_code == 2


def test_the_exit_table_is_the_command_lines():
    """cli/src/error.rs is the one table; this reads it, so the two can't drift."""
    import re

    source = (Path(__file__).parents[3] / "cli" / "src" / "error.rs").read_text()
    enum = re.search(r"pub enum Exit \{(.*?)\n\}", source, re.S).group(1)
    names = [m.group(1) for m in re.finditer(r"^\s{4}([A-Z]\w*)", enum, re.M)]
    code = {name: number for number, name in enumerate(names)}
    assert code["Success"] == 0 and code["Network"] == 7
    arms = re.search(r"match slug \{(.*?)_ => Self::Failed", source, re.S).group(1)
    expected = {}
    for arm in re.finditer(r"((?:\"[a-z-]+\"\s*\|?\s*)+)=> Self::(\w+)", arms):
        for slug in re.findall(r"\"([a-z-]+)\"", arm.group(1)):
            expected[slug] = code[arm.group(2)]
    assert len(expected) == 10
    from idleo._problem import _EXIT_BY_SLUG

    assert expected == _EXIT_BY_SLUG
