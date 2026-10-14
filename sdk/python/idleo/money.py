"""Money is integer paise everywhere in the SDK; `rupees()` is for showing it, never for sums."""

from __future__ import annotations

import re

# The minus sign the control plane and the web app write (api/money-format.json).
MINUS = "\u2212"


def rupees(paise: int) -> str:
    """`₹1,23,456.78` for 12,345,678 paise, with Indian digit grouping; a minus sign for a negative amount."""
    whole = abs(paise)
    sign = MINUS if paise < 0 else ""
    return f"{sign}₹{_group_indian(str(whole // 100))}.{whole % 100:02d}"


def _group_indian(digits: str) -> str:
    if len(digits) <= 3:
        return digits
    head, tail = digits[:-3], digits[-3:]
    groups = []
    while head:
        groups.append(head[-2:])
        head = head[:-2]
    return ",".join([*reversed(groups), tail])


_AMOUNT = re.compile(r"(\d+)(?:\.(\d{1,2}))?")


def paise(text: str) -> int:
    """Rupees a person types, to paise, exactly: `500`, `12.5` and `₹1,200.05` are 50000, 1250 and 120005."""
    cleaned = text.strip().removeprefix("₹").replace(",", "")
    found = _AMOUNT.fullmatch(cleaned)
    if not found:
        raise ValueError(f"{text!r} isn't an amount in rupees, like 500 or 12.50")
    return int(found.group(1)) * 100 + int((found.group(2) or "").ljust(2, "0") or 0)
