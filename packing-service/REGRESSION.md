# Packing service regression baseline

Run these checks before and after changes to packing, assignment, support, or compaction logic.

## 1. Full automated tests

```powershell
mvn -pl packing-service -am clean test
```

Expected result: reactor build succeeds. The optional real-data determinism test is skipped unless a data file is supplied.

## 2. Real-data candidate determinism

```powershell
mvn -pl packing-service -am `
  "-Dpacking.benchmark.json=F:\拼箱测试\request_20260907_184417_153_2e3bcb66.json" `
  "-Dtest=DeterministicCandidateBenchmarkTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

This verifies that ascending, descending, and fixed shuffled house-bill input orders produce exactly the same 256 candidates and probe order.

## 3. Build and start the executable service

```powershell
mvn -pl packing-service -am package -DskipTests
$env:SERVER_PORT = '18084'
java -jar packing-service\target\packing-service-4.2.2-SNAPSHOT.jar
```

Use the default production algorithm settings unless the test explicitly targets a configuration change:

- assignment: `fill-first`
- Block Beam only: enabled
- candidate parallelism: 1
- candidate probe ranking: disabled
- tail compaction: enabled, 60 seconds
- minimum support area: 85%
- center support: required
- maximum overhang: 20 mm or 5%

## 4. Run the known-success business regression

Run this in another terminal while the service is listening on port 18084:

```powershell
& .\packing-service\benchmark-30.ps1 `
  -BaseJsonPath 'F:\拼箱测试\request_20260907_184417_153_2e3bcb66.json' `
  -Endpoint 'http://localhost:18084/api/packing/pack' `
  -InputOrder ascending `
  -SuccessfulOnly
```

Acceptance criteria:

- all 18 known-success combinations remain successful;
- candidate success positions do not regress unexpectedly;
- `M1+M2+M4` remains below its 180-second target-search deadline;
- no HTTP 5xx response, JVM exit, fixed-placement overlap exception, or missing Viewer result.

The complete 30-combination run is the same command without `-SuccessfulOnly`. It is intentionally not part of every quick regression because known failures consume the full 180-second target budget.

## 5. Input-order smoke test

Run a representative combination in all three orders:

```powershell
foreach ($order in 'ascending', 'descending', 'shuffled') {
  & .\packing-service\benchmark-30.ps1 `
    -BaseJsonPath 'F:\拼箱测试\request_20260907_184417_153_2e3bcb66.json' `
    -Endpoint 'http://localhost:18084/api/packing/pack' `
    -InputOrder $order `
    -Combinations @('M2+M3+M4')
}
```

Expected result: all three runs succeed on candidate 1 with three containers. Wall-clock time may vary by machine load.
