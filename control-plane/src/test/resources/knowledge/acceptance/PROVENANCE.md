# Acceptance fixture provenance

These pinned files are redistributable test inputs, not runtime dependencies.
Tests use checked-in bytes and perform no network access or API invocation.

| File | Origin and revision | License / adaptation |
|:--|:--|:--|
| `petstore-readme.md` | [Swagger Petstore README](https://github.com/swagger-api/swagger-petstore/blob/027f0728a454712b40898fcf3ab0847f8810ab98/README.md), commit `027f0728a454712b40898fcf3ab0847f8810ab98` | Unmodified bytes; Apache-2.0, original license in `LICENSE-swagger-petstore`. |
| `petstore.yaml` | [OpenAPI Specification Petstore example](https://github.com/OAI/OpenAPI-Specification/blob/c8e90dfa4ccf8054db1539f7a104c5ccfb1913c3/examples/v3.0/petstore.yaml), release 3.0.3, commit `c8e90dfa4ccf8054db1539f7a104c5ccfb1913c3` | Unmodified bytes; Apache-2.0, original license in `LICENSE-openapi-specification`. |
| `petstore.json` | The preceding YAML example | Adapted by Ergon: serialize the same YAML data as UTF-8 JSON, indent 2, one trailing LF. OpenAPI semantics are unchanged. No parser dependency is added; regeneration used existing PyYAML 6.0.3 and Python's `json.dumps(..., ensure_ascii=False, indent=2)`. |
| `review-runbook.md` | Authored for Ergon acceptance | Original training procedure under this repository's license; not copied or attributed to upstream. |

The unmodified upstream YAML retains its original trailing spaces on line 33.
This is deliberate byte preservation, and `git diff --check` reports that single
vendor-fixture line. Do not normalize it without updating provenance and hashes.

`SHA256SUMS` pins each source/adaptation. Retain provenance and upstream licenses
when redistributing these inputs. Upstream commands, links and server addresses
are inert text; the test does not run them.

The deliberately opposed SUPPORT assertions use the same real README citation
in a fictional `petstore-training` / `compiler-acceptance` scope. This demonstrates
that citation integrity cannot prove a human semantic binding true. It is not a
claim that Petstore documentation contradicts itself, supplies account state, or
supports Ergon's built-in access-restoration capability. Human review and the
training adjudication are explicit inputs; no model extracts or validates truth.
