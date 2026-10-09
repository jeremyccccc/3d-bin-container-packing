# Packing service P0 regression baseline

- Commit: `aef52f66`
- Date: 2026-10-09
- Input order: numeric `houseBsId` ascending
- Source SHA-256: `D85EF81E3DADE66A9DB84C3C2944FC1B490239D7E09435848129CC2A99A8BBB2`
- Executable JAR SHA-256: `389630E54B329E3DF30310BF1C9084BFEF5DB6DC7E0A041716E1150B26A97CC5`
- Assignment: `fill-first`, maximum 256 candidates
- Container validation: Balanced Block Beam only
- Candidate parallelism: 1
- Candidate probe ranking: disabled
- Fallback: target N, then N+1, then tail compaction
- Support validation: 85% area, center required, maximum 20 mm or 5% overhang
- Scope: the 18 combinations that succeeded in baseline `cc383ed2`
- Result: 18/18 successful, no fallback or tail compaction required
- Algorithm elapsed total: 737.281 seconds

Wall-clock time is machine-load dependent. Candidate count and success/failure are the primary regression signals.

| Combination | Old candidate | New candidate | Old seconds | New seconds | Delta seconds | Result |
|---|---:|---:|---:|---:|---:|---|
| M2 | 1 | 1 | 3.393 | 4.041 | +0.648 | success |
| M3 | 1 | 1 | 6.155 | 7.488 | +1.333 | success |
| M4 | 1 | 1 | 3.958 | 5.792 | +1.834 | success |
| M1+M2 | 3 | 3 | 31.679 | 40.719 | +9.040 | success |
| M1+M3 | 1 | 1 | 9.703 | 11.966 | +2.263 | success |
| M1+M4 | 3 | 3 | 58.660 | 69.182 | +10.522 | success |
| M2+M3 | 1 | 1 | 10.077 | 11.815 | +1.738 | success |
| M2+M4 | 1 | 1 | 7.095 | 8.390 | +1.295 | success |
| M3+M4 | 1 | 1 | 8.993 | 11.437 | +2.444 | success |
| M1+M2+M3 | 1 | 1 | 25.945 | 31.829 | +5.884 | success |
| M1+M2+M4 | 7 | 7 | 130.100 | 167.621 | +37.521 | success, performance warning |
| M1+M3+M4 | 3 | 3 | 55.806 | 65.825 | +10.019 | success |
| M2+M3+M4 | 1 | 1 | 13.090 | 15.242 | +2.152 | success |
| M2+M3+M5 | 1 | 1 | 27.363 | 31.428 | +4.065 | success |
| M2+M4+M5 | 1 | 1 | 34.650 | 39.462 | +4.812 | success |
| M3+M4+M5 | 4 | 3 | 82.164 | 80.335 | -1.829 | success, improved candidate |
| M1+M2+M3+M4 | 8 | 4 | 67.043 | 73.571 | +6.528 | success, improved candidate |
| M2+M3+M4+M5 | 1 | 1 | 51.405 | 61.138 | +9.733 | success |

## Input-order smoke test

`M2+M3+M4` was executed through the packaged HTTP service in all three supported benchmark orders:

| Input order | Candidate | Seconds | Result |
|---|---:|---:|---|
| ascending | 1 | 16.916 | 3 containers, success |
| descending | 1 | 17.817 | 3 containers, success |
| shuffled | 1 | 16.625 | 3 containers, success |

The real-data JUnit determinism test also confirmed that all 256 candidates and the probe order are byte-for-byte equivalent across these request orders.

## Additional representative run

`M3+M4+M5` succeeded in 3 containers on candidate 3 in 83.850 seconds. It did not require N+1 fallback or tail compaction.

## Automatic fallback and failed-layout Viewer smoke test

The two known single-container failures were also executed to cover the complete fallback path.

| Combination | Target N | N search | N+1 search | Compaction | Final result |
|---|---:|---:|---:|---:|---|
| M1 | 1 | 1 candidate / 8.346 s | 1 candidate / 1.918 s | 4.245 s | 2 containers, failed target, Viewer saved |
| M5 | 1 | 1 candidate / 41.269 s | 1 candidate / 4.689 s | 17.579 s | 2 containers, failed target, Viewer saved |

Both requests returned `装箱失败：可查看2柜布局` with a non-empty `resultId`. Neither request produced an HTTP 5xx response or lost the successful N+1 layout.

## Follow-up warning

`M1+M2+M4` still succeeds at the same candidate position, but its 167.621-second runtime leaves only about 12 seconds before the 180-second deadline. Treat this combination as the first performance canary when changing Block Beam search or candidate validation.
