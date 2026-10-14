# docs/site

The Markdown the public docs are made from (ADR 0029). Each page starts with front matter: `title`, `description`, `order` and `path`. `web/scripts/build-site.ts` renders them at build time, together with four pages made from the code:

- **Services**, from `templates/catalog.yaml`;
- **Command line**, from `generated/cli.md`, which `idleo docs --markdown` prints and the CLI's tests keep current (`IDLEO_DOCS_WRITE=1 cargo test docs_reference` after a change to a command);
- **Python SDK**, from `generated/sdk.md`, which `sdk/python/scripts/docs.py` writes from the SDK's docstrings and the SDK's tests keep current (`uv run python scripts/docs.py` after a change to a public call or its words);
- **API reference**, from `api/openapi.yaml`.

Rules for every word here: we provide the GPU, and renters bring their own model, data and code; no page suggests we supply models, images or transcription; no made-up numbers, users, testimonials or logos; where a number is live it comes from the API, otherwise it says "typical". The product's name is `brand.name` in `web/src/brand/brand.ts`, everywhere: the hand-written pages here write it as `{{product}}`, which the build replaces, and a test fails if one names it in plain text.
