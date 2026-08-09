# Comment clarity, part two — Java and non-SQL report

## 1. What was rewritten

Starting commit: `17cb5123ff84eb094a51d9c821afd07744331eba`.

The complete 313-file scope was inspected. Of those files, 213 needed comment
changes. Work package D changed strings in eight files already counted in work
package B, so the repository-wide changed-file total remains 213.

| Package | Files changed | By file type | Comment lines before | Comment lines after |
|---|---:|---|---:|---:|
| B — `platform/`, `build-checks/` | 57 | 51 Java, 6 Gradle Kotlin | 1,648 | 1,626 |
| C — services and root build | 156 | 139 Java, 8 Gradle Kotlin, 6 `application.yaml`, 3 BPMN XML | 5,736 | 5,729 |
| D — build-check messages | 8 overlapping Java files | 15 physical string lines containing all 14 remaining matches | unchanged | unchanged |
| B + C | 213 unique files | 190 Java, 14 Gradle Kotlin, 6 YAML, 3 XML | 7,384 | 7,355 |

The line totals count physical lines occupied by comments in the changed files.
The counter tracks Java/Kotlin strings and text blocks, YAML comment lines and XML
comment spans, and excludes every `build/` directory. The small reduction comes
from replacing reference-only lead-ins and duplicated citations with direct prose;
the guarantees, costs and enforcement facts remain.

Work package B was completed, checked and committed before any service sweep.
Work package D is the separate commit `e4a96eb`; its string changes are not
presented as comment-only changes.

## 2. Proof that behaviour did not change

Before trusting `deploy/tools/assert-comments-only.py`, I watched it fail twice:

1. Temporarily changed `ApiResponse.OK` to `ApiResponse.OK_BROKEN`. The checker
   printed `CHANGED`, named the file and first differing stripped line, and exited
   non-zero. The change was reverted.
2. Temporarily changed the value after `//` in the string
   `http://localhost:8083` to port `8183` in `ExecutionConfiguration`. The checker
   again named the file and differing line and exited 1. The change was reverted.

After B and C, the baseline command reported:

```text
PASS — 213 file(s) checked; every change is a comment change.
```

After D, the same baseline command correctly reports eight `CHANGED` files: the
eight build-check classes whose failure-message strings were deliberately edited.
That is the exception required by the plan, not an unexplained checker regression.
All other 205 changed files still strip identically to the starting commit.

For D's own proof, two deliberate violations were introduced and reverted:

- Removing `HealthApi` from portal's controller made `ContractInterfaceRule`
  print the new explanation that the OpenAPI document is the source of truth and
  that an ungenerated route is invisible in the served document.
- Removing `SystemContext.runAs` from one scheduled edge method made
  `SystemContextRule` print the new explanation that anonymous scheduled work
  cannot be authorised or attributed.

Both targeted test runs exited non-zero, and `./gradlew check` passed after the
violations were reverted.

## 3. Verification results

| Check | Result |
|---|---|
| Baseline comments-only checker | PASS for B+C (213 files); expected FAIL on exactly the 8 D string files after D |
| Banned-token scan over comment lines | **FAIL:** four deliberately untranslated, factually contradictory comments remain; listed below |
| `./gradlew check` | PASS, `BUILD SUCCESSFUL` |
| `./gradlew check integrationTest --rerun-tasks` | PASS in 7m 7s; 60 unit and 222 integration tests, 0 failures, 0 errors, 0 skipped |
| Six service starts | PASS; core, runtime, edge, portal, sync and fleet each returned `status: UP` on ports 18081–18086 |
| One truck through the gate | PASS; `T-JAVA-01` was acknowledged, visit `vis-da5503a9-e620-4b87-aaf4-09a04b15a9c6` was `COMPLETED`, the latest `RAISE_GATE` command was `EXECUTED`, and `runtime.outbox` held `visit.completed` for `lane:LANE-DEMO-01` |

The first demo invocation was issued from `deploy/` as `./gradlew` and failed
because that path has no wrapper. A second attempt with `../gradlew` still used
`deploy/` as Gradle's project directory and was rejected. Running the documented
command from the repository root succeeded. These were invocation errors, not
product failures.

## 4. Decisions the plan did not dictate

- Legacy-source tags were translated as “translated from the legacy 1.x
  caller/listener,” while retaining the named source document and the warning
  that it is not a vendor specification.
- Historical phase wording was retained only when it conveyed a real before/after
  distinction; otherwise the comment now names the old and current behaviour.
- Factual contradictions were not guessed at or silently repaired. They remain
  visible and are listed below, even though that makes the prescribed token scan
  honestly fail.
- Assertion descriptions, display names, log text and BPMN names outside
  `build-checks` were not edited: they are executable strings and D authorises
  only build-check failure messages.

## 5. Comments that could not be translated safely

These comments appear stale or contradict the current tree. Correcting them would
require deciding the intended fact, so they were left unchanged:

- `services/orca-core/src/main/java/com/lynxis/orca/core/api/HealthController.java:27`
  says there is no other controller, although core has many.
- `services/orca-edge/src/main/java/com/lynxis/orca/edge/api/HealthController.java:27`
  says there is no other controller, although edge has product controllers.
- `services/orca-runtime/src/main/java/com/lynxis/orca/runtime/api/HealthController.java:27`
  says there is no other controller, although runtime has product controllers.
- `services/orca-core/src/main/resources/application.yaml:123` says core publishes
  no views; core now publishes views. The empty list may instead mean core
  consumes none, but that interpretation was not invented here.
- `services/orca-core/src/main/java/com/lynxis/orca/core/domain/DeviceTables.java:107`
  says only 12 of 16 device types are seeded and calls a source “the sheet.”
- The same file at line 120 says only 36 of 40 IO port names are extracted, and
  line 133 says IO device kinds are unseeded. `CatalogSeedPropertiesIT` appears to
  assert completed catalogs, but this task did not choose which statement is true.

The prescribed scan prints the first four entries. Its line-prefix heuristic does
not inspect the three one-line Javadocs in `DeviceTables`; they were found by the
broader inspection and are reported rather than hidden by the scanner's shape.

## 6. Enforcement facts preserved

- `ScopeIndexRule` still says it reads migrations and fails the build unless every
  scoped table has an index leading with its scope column; the lane deadlock that
  motivated the rule remains explicit.
- `ScopeSeamRule` still says direct JDBC access in service code fails the build.
- `RetentionClassRule` still says it fails the build when a traffic-growing table
  does not name a retention class, while also stating that it cannot validate the
  unreconciled closed catalog.
- `SystemContextRule`, `ContractInterfaceRule`, `InternalSurfaceRule` and
  `EngineConfinementRule` remain named at the constraints they enforce.
- Outbox transaction enforcement, lease fence-token enforcement and idempotency
  claim ownership remain described as hard guards rather than recommendations.

## 7. Findings from the reading pass

In addition to the seven untranslated comments above,
`build-checks/src/test/java/com/lynxis/orca/checks/ImportedSetGuard.java:19` says
the guard makes “the other five rules” believable. The repository now has ten
build checks, so that count is stale. It was reported rather than corrected.

Several integration-test display names, assertion messages and logs still contain
phase, work-package or architecture references. They are strings, not comments,
and are outside D's explicitly narrow permission. Gradle's Flowable extraction
header also contains such text inside a string that generates SQL; editing it
would change generated code and migration checksums.

## 8. What was not done

- No Java expression, identifier, annotation, configuration value, BPMN element,
  SQL statement, OpenAPI document or generated file was intentionally changed.
- No unrelated stale statement was corrected.
- No file under any `build/` directory was edited or counted.
- The four contradictory comments and three uncertain catalog comments were not
  translated, so the banned-token verification is not claimed as green.
