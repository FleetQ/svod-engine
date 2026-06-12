# ADR-0004 — Embedder abstraction, provider model, and model-license default

- Status: **Accepted**
- Date: 2026-06-12
- Supersedes the Ollama-as-default embedding sketch in ADR-0003 §4.

## Context

Semantic search must not impose an external-server dependency or a heavy/global install on
anyone running Svod, and the default embedding model must be license-compatible with the
Apache-2.0 product. Ollama is only a runtime; the weight is the model — so we can drop the
separate server and embed in-process. Crucially, the vault must remain fully searchable
with **no** embedding model at all.

## Decisions

### 1. Pluggable `Embedder`, provider-selected
One interface, three providers chosen by config:

| Provider | What | Default? |
|---|---|---|
| `onnx-local` | In-process ONNX Runtime (DJL) running multilingual-e5-small | **yes** |
| `ollama` | External Ollama server (`/api/embed`) | optional |
| `none` | No embeddings — BM25-only | the floor |

`Embedders.create(config, vaultRoot)` is the single construction point; the rest of the
engine depends only on `Embedder`. Model name + dim are recorded in index metadata, so a
provider/model/dim change is detected and auto-triggers a reindex (ADR-0003 §5).

### 2. `none` is a first-class, guaranteed baseline
Semantic retrieval is strictly opt-in **over** BM25. With `none`, documents carry no vector
field, `HYBRID`/`SEMANTIC` queries degrade cleanly to lexical, and search is fully usable.
Vector setup can never gate basic search. *(Tested: lexical search incl. Cyrillic under
`none`; swap `none ↔ active` reindexes correctly.)*

### 3. Default model: multilingual-e5-small (MIT), in-process via DJL + ONNX Runtime
- **License:** MIT — clean for an Apache-2.0 product.
- **Footprint:** 118M params, **384-dim** (smaller kNN index than larger models), int8 ONNX,
  CPU, in-process. No server, no global install.
- **e5 correctness (silent quality killers if wrong), implemented and regression-pinned:**
  - input prefixes — `query: ` for queries, `passage: ` for indexed chunks;
  - **attention-mask-weighted mean pooling** of token embeddings, then **L2 normalization**
    (DJL `TextEmbeddingTranslator`, `pooling=mean`, `normalize=true`);
  - the model's own **XLM-RoBERTa tokenizer** (`tokenizer.json`) via DJL HF tokenizers —
    not a generic tokenizer;
  - this export also requires `token_type_ids` (zeros for single-sequence) — set explicitly.
  - *Pinned:* embeddings are unit-length; `cos(query:"capital of France", passage:"Paris…")
    ≈ 0.88` and clearly outranks an unrelated passage; cross-lingual EN↔RU pair aligns.

### 4. Model file management: download-on-first-run, checksum-verified, or pre-placed
`ModelManager` resolves `model.onnx` + `tokenizer.json` either from a caller-supplied local
directory or by downloading the **SHA-256-pinned** artifacts into `.svod/models/<id>/`
(atomic write, verify, rename). No global install; cached and reused across runs.

### 5. EmbeddingGemma is a documented alternative, not bundled or default
EmbeddingGemma-300M (768→128 MRL) is higher quality but falls under the **Gemma Terms of
Use**, which conflicts with the Apache-2.0 default. It stays a *documented* provider option
the user may opt into by accepting that license; we neither bundle nor default to it.
e5-small (MIT) is the default.

## Consequences

- Zero-dependency search out of the box; semantic is a config flip, not a prerequisite.
- The default is fully OSS-license-clean (Apache product + MIT model).
- DJL onnxruntime-engine + tokenizers pull native libs on first use; pinned to DJL 0.30,
  Lucene 9.12, JDK 20.

## Alternatives considered

- **Ollama as the default** — adds an external-server prerequisite for the common case;
  demoted to an optional provider. Rejected as default.
- **Bundle EmbeddingGemma for quality** — license incompatible with Apache-2.0 default.
  Rejected as default; kept as opt-in.
- **No `none` mode (always require a model)** — would gate basic search on model setup and
  break the "works everywhere, no prerequisites" promise. Rejected.
- **multilingual-e5-large** (1024-dim) — better recall but bigger index + heavier in-process
  cost; e5-small is the right default for a local-first tool, large remains a config option.
