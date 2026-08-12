# Selector conformance fixtures (T1)

The language-neutral corpus extracted from the Go evaluator's tests (M0.2, W2 step 0) —
one JSON array per extraction chunk under `corpus/`. Both sides run the same files:

- **Go**: `selector_conformance_test.go` in
  `services/work-flow-executor-service/tests/unit/nodes/` — the validation half of W2
  step 0, and a standing tripwire against Go-side semantic drift during shadowing.
- **Java**: the fixture-driven suite in `orca-selector` (same files, from the classpath).

## Why `expected` is recorded, not transcribed

Most of the Go tests assert only `NotNil` — transcribing them would give Java a suite that
proves almost nothing. Instead the fixture's **inputs** come from the Go tests and the
**expected output is recorded by running the real Go evaluator** (`ORCA_FIXTURE_UPDATE=1`).
The evaluator itself is the oracle — which is exactly what a bug-for-bug faithful port
needs. Where a source test *does* assert a precise value it is carried as
`source_expected`; the runner cross-checks it against the recorded output, so a
mis-extraction still fails loudly (the corrupted-oracle trap the plan warns about).

## Fixture shape

```json
{
  "source": "TestSelectorService_ExtractValue_Success",
  "kind": "extractValue",
  "args": { "data": {"field1": {"nested": "nested-value"}}, "path": ["field1", "nested"] },
  "repo": { "GetPrimaryKeyByUUID": {"returns": [5, null]} },
  "source_expected": "nested-value",
  "volatile": false,
  "expected": { "value": "nested-value", "error": null }
}
```

- `kind` selects the evaluator entry point (dispatch table in the Go runner).
- `args` are JSON-native values. **Deliberate**: production inputs arrive JSON-decoded
  (numbers are float64), so fixtures encode that reality even where a Go test passed an
  `int` literal.
- `repo` cans the `SelectorRepositoryInterface` returns, in signature order. An object is
  an unlimited canned return (testify's default); an array means sequential one-shot
  returns. Errors encode as `null`, `{"dto": "message"}`, `{"go": "message"}`, or
  `{"gorm_not_found": true}`.
- `volatile: true` for time-dependent outputs — comparison checks error-presence and value
  type only, never the value.
- `expected.error` is `null`, `{"dto": ...}` or `{"go": ...}`. Java compares dto messages
  exactly (they are ORCA-authored constants) and go errors by presence only (their text is
  Go stdlib wording).
- `skip` (string reason) marks Go-only cases (e.g. constructor smoke tests).

## Regeneration

```bash
cd services/work-flow-executor-service
ORCA_FIXTURE_UPDATE=1 go test ./tests/unit/nodes/ -run TestSelectorConformanceFixtures
go test ./tests/unit/nodes/ -run TestSelectorConformanceFixtures   # must be green
```
