import json
from pathlib import Path

import pytest

from idleo import paise, rupees

CASES = json.loads((Path(__file__).parents[3] / "api" / "money-format.json").read_text())["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["text"] for c in CASES])
def test_money_is_written_as_the_other_clients_write_it(case):
    assert rupees(case["paise"]) == case["text"]


def test_what_a_person_types_is_exact_paise():
    assert (paise("500"), paise("12.5"), paise("₹1,200.05"), paise("0.01")) == (50000, 1250, 120005, 1)


@pytest.mark.parametrize("bad", ["", "abc", "1.234", "-5", "1.2.3", "₹"])
def test_an_amount_that_isnt_one_is_refused(bad):
    with pytest.raises(ValueError):
        paise(bad)
