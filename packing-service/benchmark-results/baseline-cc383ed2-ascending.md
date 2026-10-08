# Packing service 30-combination baseline

- Commit: `cc383ed2`
- Date: 2026-09-28
- Input order: numeric `houseBsId` ascending
- Assignment: `fill-first`, maximum 256 candidates
- Container validation: Balanced Block Beam only
- Candidate parallelism: 1
- Fallback: target N, then N+1, then tail compaction
- Support validation: enabled
- Total: 30 combinations, 18 target successes, 12 target failures
- Total elapsed: 3029.577 seconds

`N result` uses `success/candidates/seconds`. `N+1` and compaction are only populated when N failed.

| Combination | Target | N result | N+1 candidates / seconds | Compaction seconds | Final |
|---|---:|---:|---:|---:|---|
| M1 | 1 | fail / 1 / 6.589 | 1 / 1.636 | 4.019 | 2 cabinets, failed target |
| M2 | 1 | success / 1 / 3.393 | - | - | 1 cabinet |
| M3 | 1 | success / 1 / 6.155 | - | - | 1 cabinet |
| M4 | 1 | success / 1 / 3.958 | - | - | 1 cabinet |
| M5 | 1 | fail / 1 / 34.891 | 1 / 3.555 | 17.167 | 2 cabinets, failed target |
| M1+M2 | 2 | success / 3 / 31.679 | - | - | 2 cabinets |
| M1+M3 | 2 | success / 1 / 9.703 | - | - | 2 cabinets |
| M1+M4 | 2 | success / 3 / 58.660 | - | - | 2 cabinets |
| M1+M5 | 2 | fail / 12 / 180.008 | 1 / 5.980 | 51.679 | 3 cabinets, failed target |
| M2+M3 | 2 | success / 1 / 10.077 | - | - | 2 cabinets |
| M2+M4 | 2 | success / 1 / 7.095 | - | - | 2 cabinets |
| M2+M5 | 2 | fail / 5 / 180.015 | 1 / 6.386 | 16.483 | 3 cabinets, failed target |
| M3+M4 | 2 | success / 1 / 8.993 | - | - | 2 cabinets |
| M3+M5 | 2 | fail / 7 / 179.999 | 1 / 8.084 | 32.742 | 3 cabinets, failed target |
| M4+M5 | 2 | fail / 6 / 180.016 | 1 / 8.357 | 23.974 | 3 cabinets, failed target |
| M1+M2+M3 | 3 | success / 1 / 25.945 | - | - | 3 cabinets |
| M1+M2+M4 | 3 | success / 7 / 130.100 | - | - | 3 cabinets |
| M1+M2+M5 | 3 | fail / 15 / 180.000 | 1 / 9.229 | 52.893 | 4 cabinets, failed target |
| M1+M3+M4 | 3 | success / 3 / 55.806 | - | - | 3 cabinets |
| M1+M3+M5 | 3 | fail / 12 / 180.007 | 1 / 12.212 | 59.977 | 4 cabinets, failed target |
| M1+M4+M5 | 3 | fail / 12 / 180.005 | 1 / 9.306 | 30.641 | 4 cabinets, failed target |
| M2+M3+M4 | 3 | success / 1 / 13.090 | - | - | 3 cabinets |
| M2+M3+M5 | 3 | success / 1 / 27.363 | - | - | 3 cabinets |
| M2+M4+M5 | 3 | success / 1 / 34.650 | - | - | 3 cabinets |
| M3+M4+M5 | 3 | success / 4 / 82.164 | - | - | 3 cabinets |
| M1+M2+M3+M4 | 4 | success / 8 / 67.043 | - | - | 4 cabinets |
| M1+M2+M3+M5 | 4 | fail / 16 / 180.015 | 1 / 14.196 | 48.142 | 5 cabinets, failed target |
| M1+M2+M4+M5 | 4 | fail / 19 / 180.007 | 1 / 16.703 | 50.381 | 5 cabinets, failed target |
| M1+M3+M4+M5 | 4 | fail / 13 / 180.014 | 1 / 15.348 | 60.130 | 5 cabinets, failed target |
| M2+M3+M4+M5 | 4 | success / 1 / 51.405 | - | - | 4 cabinets |

## Aggregate

| Target cabinet count | Combinations | Success | Failure | Total seconds |
|---:|---:|---:|---:|---:|
| 1 | 5 | 3 | 2 | 81.950 |
| 2 | 10 | 6 | 4 | 1000.252 |
| 3 | 10 | 7 | 3 | 1083.765 |
| 4 | 5 | 2 | 3 | 863.610 |

The 12 failed target searches all found an N+1 baseline, but tail compaction did not reduce them back to N.
