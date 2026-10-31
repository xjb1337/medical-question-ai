param(
    [string]$Adb = 'E:\android-sdk\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'emulator-5554',
    [string]$Package = 'com.xjb.medicalquestionai.benchmark',
    [Parameter(Mandatory = $true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$starts = @()
for ($sample = 0; $sample -lt 10 -and $starts.Count -lt 5; $sample++) {
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 400
    $launch = & $Adb -s $Serial shell am start -W -n "$Package/com.xjb.medicalquestionai.MainActivity"
    $launch | Set-Content -Encoding utf8 -Path (Join-Path $OutputDir "startup-$sample.txt")
    if (($launch -join "`n") -notmatch ('Activity:\s*' + [regex]::Escape($Package) + '/')) {
        # Some emulators report a launcher transition while finishing the preceding task.
        # Keep the raw output, but do not count a different Activity as an app start.
        continue
    }
    $match = [regex]::Match(($launch -join "`n"), 'TotalTime:\s*(\d+)')
    if (-not $match.Success) { throw 'App launch failed; no TotalTime was returned.' }
    $starts += [int]$match.Groups[1].Value
    Start-Sleep -Milliseconds 400
}
if ($starts.Count -ne 5) { throw 'Could not collect five launches of the requested Activity.' }
& $Adb -s $Serial shell uiautomator dump /sdcard/navigation-sample.xml | Out-Null
& $Adb -s $Serial pull /sdcard/navigation-sample.xml (Join-Path $OutputDir 'navigation-ui.xml') | Out-Null
[xml]$tree = Get-Content -Raw -Encoding utf8 -Path (Join-Path $OutputDir 'navigation-ui.xml')
$tabs = @{}
foreach ($label in @('拍题', '题库', '设置')) {
    $node = $tree.SelectSingleNode("//node[@text='$label']")
    if ($null -eq $node) { throw "Missing tab: $label. Dismiss any update dialog before sampling." }
    $bounds = [regex]::Matches($node.bounds, '\d+')
    $tabs[$label] = @([int]( ([int]$bounds[0].Value + [int]$bounds[2].Value) / 2 ),
                     [int]( ([int]$bounds[1].Value + [int]$bounds[3].Value) / 2 ))
}
# Warm the three destinations before taking frame samples.
foreach ($label in @('题库', '设置', '拍题')) {
    & $Adb -s $Serial shell input tap $tabs[$label][0] $tabs[$label][1]
    Start-Sleep -Milliseconds 400
}
& $Adb -s $Serial shell dumpsys gfxinfo $Package reset | Out-Null
for ($round = 0; $round -lt 10; $round++) {
    foreach ($label in @('题库', '设置', '拍题')) {
        & $Adb -s $Serial shell input tap $tabs[$label][0] $tabs[$label][1]
        Start-Sleep -Milliseconds 400
    }
}
& $Adb -s $Serial shell dumpsys gfxinfo $Package framestats |
    Set-Content -Encoding utf8 -Path (Join-Path $OutputDir 'frames.txt')
& $Adb -s $Serial shell dumpsys meminfo $Package |
    Set-Content -Encoding utf8 -Path (Join-Path $OutputDir 'memory.txt')
$ordered = $starts | Sort-Object
@{ package = $Package; processColdStartMs = $starts; medianStartMs = $ordered[2]; tabSwitches = 30 } |
    ConvertTo-Json | Set-Content -Encoding utf8 -Path (Join-Path $OutputDir 'summary.json')
Get-Content -Path (Join-Path $OutputDir 'summary.json')
