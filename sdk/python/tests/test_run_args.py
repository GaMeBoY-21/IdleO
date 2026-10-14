import uuid

import pytest
from conftest import template

from idleo import _run_args as args
from idleo import errors
from idleo._generated.models import TemplateResponse


@pytest.fixture
def t():
    return TemplateResponse.model_validate(template())


def test_inputs_are_mapped_by_name_and_kind(t, tmp_path):
    (tmp_path / "m").mkdir()
    (tmp_path / "t.jsonl").write_text("{}")
    given = args.map_inputs({"model": str(tmp_path / "m"), "data": tmp_path / "t.jsonl"}, t)
    assert given == {"model": args.Upload(tmp_path / "m"), "data": args.Upload(tmp_path / "t.jsonl")}


def test_a_link_an_existing_upload_and_an_upload_are_kept_apart(t, tmp_path):
    up = uuid.uuid4()
    (tmp_path / "t.jsonl").write_text("{}")
    given = args.map_inputs({"model": "https://huggingface.co/x", "data": up}, t)
    assert given == {"model": args.Link("https://huggingface.co/x"), "data": args.Existing(up)}


def test_a_missing_required_input_is_named(t):
    with pytest.raises(errors.UsageError, match="needs data"):
        args.map_inputs({"model": "https://x.example/m"}, t)


@pytest.mark.parametrize(
    ("value", "match"),
    [
        ("http://x.example/m", "https://"),
        ("/no/such/folder", "can't be read"),
    ],
)
def test_a_bad_input_says_what_is_wrong(t, value, match):
    with pytest.raises(errors.UsageError, match=match):
        args.map_inputs({"model": value, "data": "https://x.example/d"}, t)


def test_a_file_for_a_folder_and_a_folder_for_a_file_are_refused(t, tmp_path):
    (tmp_path / "f").write_text("x")
    (tmp_path / "d").mkdir()
    with pytest.raises(errors.UsageError, match="takes a folder"):
        args.map_inputs({"model": str(tmp_path / "f"), "data": "https://x.example/d"}, t)
    with pytest.raises(errors.UsageError, match="takes a single file"):
        args.map_inputs({"model": "https://x.example/m", "data": str(tmp_path / "d")}, t)


def test_an_input_the_template_doesnt_have_is_refused_with_the_ones_it_has(t):
    with pytest.raises(errors.UsageError, match="model, data"):
        args.map_inputs({"weights": "x"}, t)


def test_params_are_typed_as_the_template_says(t):
    assert args.map_params({"epochs": 2, "learning_rate": 1, "lora_rank": 16, "verbose": True}, t) == {
        "epochs": 2,
        "learning_rate": 1,
        "lora_rank": "16",
        "verbose": True,
    }


@pytest.mark.parametrize(
    ("name", "value"),
    [("epochs", 1.5), ("epochs", True), ("epochs", "2"), ("learning_rate", "fast"), ("verbose", "yes")],
)
def test_a_param_of_the_wrong_type_is_refused(t, name, value):
    with pytest.raises(errors.UsageError, match=name):
        args.map_params({name: value}, t)


def test_a_param_the_template_doesnt_have_is_refused(t):
    with pytest.raises(errors.UsageError, match="no parameter 'seed'"):
        args.map_params({"seed": 1}, t)


def test_a_preset_answers_every_question_with_its_usual_option_unless_given(t):
    assert args.map_presets(None, t) == {"thoroughness": "standard"}
    assert args.map_presets({"thoroughness": "quick"}, t) == {"thoroughness": "quick"}


def test_a_preset_the_template_doesnt_offer_is_refused(t):
    with pytest.raises(errors.UsageError, match="quick or standard"):
        args.map_presets({"thoroughness": "extreme"}, t)
    with pytest.raises(errors.UsageError, match="doesn't ask 'speed'"):
        args.map_presets({"speed": "x"}, t)


def test_gpu_memory_is_a_class_and_never_less_than_the_template_needs(t):
    assert args.vram_mb(None, t) is None
    assert args.vram_mb(12, t) == 12_000
    assert args.vram_mb(8, t) == 8_000
    with pytest.raises(errors.UsageError, match="8, 12, 16 or 24"):
        args.vram_mb(10, t)


def test_the_budget_is_paise_and_defaults_to_the_templates(t):
    assert args.spend(None, t) == 10000
    assert args.spend(200_00, t) == 20000
    for bad in (0, -1, 1.5, True):
        with pytest.raises(errors.UsageError):
            args.spend(bad, t)  # type: ignore[arg-type]
    with pytest.raises(errors.UsageError, match="no usual budget"):
        args.spend(None, TemplateResponse.model_validate(template(defaultBudgetPaise=None)))
