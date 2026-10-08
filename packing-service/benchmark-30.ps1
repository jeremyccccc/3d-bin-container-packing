param(
    [Parameter(Mandatory = $true)]
    [string] $BaseJsonPath,
    [string] $Endpoint = 'http://localhost:18084/api/packing/pack',
    [ValidateSet('ascending', 'descending', 'shuffled')]
    [string] $InputOrder = 'ascending',
    [switch] $SuccessfulOnly,
    [string[]] $Combinations,
    [string] $ServiceLogPath,
    [string] $OutputPath
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ServiceLogPath)) {
    $ServiceLogPath = Join-Path $PSScriptRoot 'target\benchmark30.out.log'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot "target\benchmark30-$InputOrder-summary.json"
}

$masterGroups = [ordered]@{
    M1 = @('314001','314101','314201','314301','314701','314801','314901','315001','315101','315201','315701')
    M2 = @('315801','315901','316001','316101','316201','316301','316401','316501','316601','316701','316801')
    M3 = @('316901','317001','317101','317201','317301','317401','317501','317601','317701','317801','317901')
    M4 = @('318001','318101','318201','318301','318401','318501','318601','318701','318801','318901')
    M5 = @('319001','319101','319201','319301','319401','319501','319601','319701','319801','319901','320001','320101','320201')
}

$successfulBaseline = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    'M2','M3','M4',
    'M1+M2','M1+M3','M1+M4','M2+M3','M2+M4','M3+M4',
    'M1+M2+M3','M1+M2+M4','M1+M3+M4','M2+M3+M4','M2+M3+M5','M2+M4+M5','M3+M4+M5',
    'M1+M2+M3+M4','M2+M3+M4+M5'
))

function Get-Combinations([string[]] $items, [int] $count, [int] $start = 0, [string[]] $prefix = @()) {
    if ($prefix.Count -eq $count) {
        ,$prefix
        return
    }
    for ($index = $start; $index -le $items.Count - ($count - $prefix.Count); $index++) {
        Get-Combinations $items $count ($index + 1) ($prefix + $items[$index])
    }
}

function Get-IntField([string] $line, [string] $name) {
    if ($line -match ("(?:^| )" + [regex]::Escape($name) + '=([0-9]+)')) { return [long]$Matches[1] }
    return 0L
}

function Read-SharedLines([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $reader = [IO.StreamReader]::new($stream)
        try {
            $lines = [System.Collections.Generic.List[string]]::new()
            while (-not $reader.EndOfStream) { $lines.Add($reader.ReadLine()) }
            return $lines.ToArray()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

$source = Get-Content -LiteralPath $baseJsonPath -Raw | ConvertFrom-Json
$allCombinations = @()
foreach ($size in 1..4) {
    $allCombinations += @(Get-Combinations @($masterGroups.Keys) $size)
}
if ($SuccessfulOnly) {
    $allCombinations = @($allCombinations | Where-Object { $successfulBaseline.Contains(($_ -join '+')) })
}
if ($null -ne $Combinations -and $Combinations.Count -gt 0) {
    $requestedCombinations = [System.Collections.Generic.HashSet[string]]::new([string[]]$Combinations)
    $allCombinations = @($allCombinations | Where-Object { $requestedCombinations.Contains(($_ -join '+')) })
}

$results = [System.Collections.Generic.List[object]]::new()
$sequence = 0
foreach ($combination in $allCombinations) {
    $sequence++
    $name = $combination -join '+'
    $targetCount = $combination.Count
    $ids = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($group in $combination) {
        foreach ($id in $masterGroups[$group]) { [void]$ids.Add($id) }
    }

    $request = $source | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $request.masterBsId = "BENCH30-$('{0:D2}' -f $sequence)-$name"
    # Always normalize house bills numerically. Where-Object preserves the source
    # JSON order, which made benchmark results depend on whether the input happened
    # to be ascending or descending.
    $selectedHouseBills = @($source.houseBillList |
        Where-Object { $ids.Contains([string]$_.houseBsId) } |
        Sort-Object { [long]$_.houseBsId })
    if ($InputOrder -eq 'descending') {
        [array]::Reverse($selectedHouseBills)
    } elseif ($InputOrder -eq 'shuffled') {
        $random = [Random]::new(20260928 + $sequence)
        for ($index = $selectedHouseBills.Count - 1; $index -gt 0; $index--) {
            $swap = $random.Next($index + 1)
            $temporary = $selectedHouseBills[$index]
            $selectedHouseBills[$index] = $selectedHouseBills[$swap]
            $selectedHouseBills[$swap] = $temporary
        }
    }
    $request.houseBillList = $selectedHouseBills
    $containers = @()
    foreach ($containerIndex in 1..$targetCount) {
        $containers += [pscustomobject]@{ id = "BENCH-$('{0:D2}' -f $sequence)-C$containerIndex"; size = 40; type = 'HQ' }
    }
    $request.containerLists = $containers

    $beforeLines = @(Read-SharedLines $serviceLogPath).Count
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $response = $null
    $requestError = $null
    try {
        $body = $request | ConvertTo-Json -Depth 100 -Compress
        $response = Invoke-RestMethod -Method Post -Uri $endpoint -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 500
    } catch {
        $requestError = $_.Exception.Message
    }
    $stopwatch.Stop()
    Start-Sleep -Milliseconds 150
    $allLines = @(Read-SharedLines $serviceLogPath)
    $afterLines = $allLines.Count
    $slice = if ($afterLines -gt $beforeLines) { @($allLines[$beforeLines..($afterLines - 1)]) } else { @() }

    $searchSummaries = @($slice | Where-Object { $_ -like '*packing-service whole-order candidates=*' })
    $targetSummary = if ($searchSummaries.Count -ge 1) { $searchSummaries[0] } else { '' }
    $fallbackSummary = if ($searchSummaries.Count -ge 2) { $searchSummaries[1] } else { '' }
    $tailSummary = @($slice | Where-Object { $_ -like '*packing-service tail-compaction success=*' } | Select-Object -Last 1)
    if ($tailSummary.Count -eq 0) { $tailSummary = @('') }

    $row = [pscustomobject]@{
        sequence = $sequence
        combination = $name
        targetContainers = $targetCount
        requestSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
        success = if ($null -ne $response) { [bool]$response.success } else { $false }
        finalContainers = if ($null -ne $response -and $null -ne $response.containerLists) { @($response.containerLists).Count } else { 0 }
        message = if ($null -ne $response) { [string]$response.message } else { [string]$requestError }
        resultId = if ($null -ne $response) { [string]$response.resultId } else { '' }
        targetCandidates = Get-IntField $targetSummary 'attempted'
        targetMillis = Get-IntField $targetSummary 'elapsedMs'
        fallbackCandidates = Get-IntField $fallbackSummary 'attempted'
        fallbackMillis = Get-IntField $fallbackSummary 'elapsedMs'
        directAttempts = Get-IntField $tailSummary[0] 'attempts'
        directSuccesses = Get-IntField $tailSummary[0] 'directSuccesses'
        directMovedBills = Get-IntField $tailSummary[0] 'directMovedBills'
        directMovedCubicMetres = [math]::Round((Get-IntField $tailSummary[0] 'directMovedVolume') / 1000000000.0, 6)
        spaceGuidedAttempts = Get-IntField $tailSummary[0] 'targetedAttempts'
        spaceGuidedSuccesses = Get-IntField $tailSummary[0] 'targetedSuccesses'
        spaceGuidedMovedBills = Get-IntField $tailSummary[0] 'targetedMovedBills'
        spaceGuidedMovedCubicMetres = [math]::Round((Get-IntField $tailSummary[0] 'targetedMovedVolume') / 1000000000.0, 6)
        compactionMillis = Get-IntField $tailSummary[0] 'elapsedMs'
        logStartLine = $beforeLines + 1
        logEndLine = $afterLines
    }
    $results.Add($row)
    $row | ConvertTo-Json -Compress
}

$results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output "SUMMARY=$OutputPath"
