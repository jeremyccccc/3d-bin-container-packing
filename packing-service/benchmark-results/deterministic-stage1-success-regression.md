# Deterministic search stage 1 regression

- Branch: `feat/deterministic-search`
- Base commit: `cc383ed2`
- Date: 2026-09-28
- Scope: only the 18 combinations which succeeded in the ascending baseline
- Configuration: fill-first, Balanced Block Beam only, candidate parallelism 1
- Result: 18/18 remained successful
- Candidate position changes: 0/18
- Baseline elapsed: 627.908 seconds
- Stage 1 elapsed: 602.447 seconds
- Elapsed change: -25.461 seconds (-4.05%)

Elapsed time is a performance observation rather than a strict assertion. Success and successful-candidate position are the regression gates.

| Combination | Baseline candidate | Stage 1 candidate | Baseline seconds | Stage 1 seconds | Result |
|---|---:|---:|---:|---:|---|
| M2 | 1 | 1 | 3.393 | 3.583 | success |
| M3 | 1 | 1 | 6.155 | 6.027 | success |
| M4 | 1 | 1 | 3.958 | 3.866 | success |
| M1+M2 | 3 | 3 | 31.679 | 30.865 | success |
| M1+M3 | 1 | 1 | 9.703 | 9.612 | success |
| M1+M4 | 3 | 3 | 58.660 | 59.632 | success |
| M2+M3 | 1 | 1 | 10.077 | 10.033 | success |
| M2+M4 | 1 | 1 | 7.095 | 6.941 | success |
| M3+M4 | 1 | 1 | 8.993 | 9.205 | success |
| M1+M2+M3 | 1 | 1 | 25.945 | 25.674 | success |
| M1+M2+M4 | 7 | 7 | 130.100 | 127.105 | success |
| M1+M3+M4 | 3 | 3 | 55.806 | 51.804 | success |
| M2+M3+M4 | 1 | 1 | 13.090 | 12.283 | success |
| M2+M3+M5 | 1 | 1 | 27.363 | 25.450 | success |
| M2+M4+M5 | 1 | 1 | 34.650 | 32.487 | success |
| M3+M4+M5 | 4 | 4 | 82.164 | 77.818 | success |
| M1+M2+M3+M4 | 8 | 8 | 67.043 | 59.392 | success |
| M2+M3+M4+M5 | 1 | 1 | 51.405 | 50.670 | success |

## Input-order determinism

The real five-master request was generated in ascending, descending and fixed shuffled house-bill order. All three runs produced exactly the same 256 candidates, including:

- candidate signature and order;
- candidate source and generation index;
- score bits and container validation order;
- item sequence assigned to each container.

## Test verification

- Real-data determinism test: 1 passed, 0 failed.
- Maven reactor: 434 tests, 0 failed, 10 skipped.

Deep reordering inside a house bill and Block Beam internal comparator changes are intentionally not included in stage 1. An experimental version changed the Block Beam search path and regressed M1+M3, so stage 1 preserves the established per-house-bill item order while removing dependence on the order of house bills in the request.
