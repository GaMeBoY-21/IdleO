"""Progress in Jupyter, found without importing it: only when IPython is already loaded and its shell is a notebook's."""

from __future__ import annotations

import sys
import types

from idleo._notebook import Status, in_notebook


class ZMQInteractiveShell:
    pass


def fake_ipython(monkeypatch, shell):
    shown: list[tuple[str, str]] = []

    class Handle:
        def update(self, page):
            shown.append(("update", page.data))

    def display(page, display_id=False):
        shown.append(("display", page.data))
        return Handle()

    ipython = types.ModuleType("IPython")
    ipython.get_ipython = lambda: shell  # type: ignore[attr-defined]
    module = types.ModuleType("IPython.display")
    module.HTML = lambda data: types.SimpleNamespace(data=data)  # type: ignore[attr-defined]
    module.display = display  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "IPython", ipython)
    monkeypatch.setitem(sys.modules, "IPython.display", module)
    return shown


def test_a_plain_script_is_not_a_notebook(monkeypatch):
    monkeypatch.delitem(sys.modules, "IPython", raising=False)
    assert not in_notebook()


def test_a_terminal_ipython_is_not_a_notebook(monkeypatch):
    class TerminalInteractiveShell:
        pass

    fake_ipython(monkeypatch, TerminalInteractiveShell())
    assert not in_notebook()


def test_a_notebook_shows_one_line_and_updates_it_in_place(monkeypatch):
    shown = fake_ipython(monkeypatch, ZMQInteractiveShell())
    assert in_notebook()
    status = Status(None)
    status.update("running on RTX  (₹0.02 so far)")
    status.update("running on RTX  (₹0.02 so far)")  # the same words: nothing new to show
    status.update("completed  (₹0.06 so far)")
    assert shown == [
        ("display", "<code>running on RTX  (₹0.02 so far)</code>"),
        ("update", "<code>completed  (₹0.06 so far)</code>"),
    ]


def test_words_are_escaped(monkeypatch):
    shown = fake_ipython(monkeypatch, ZMQInteractiveShell())
    Status(None).update("<script>x</script>")
    assert "<script>" not in shown[0][1]


def test_a_notebook_can_switch_it_off(monkeypatch):
    shown = fake_ipython(monkeypatch, ZMQInteractiveShell())
    Status(False).update("running")
    assert shown == []
