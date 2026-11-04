#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$QaRoot,
    [Parameter(Mandatory)][string]$SourceInstallRoot,
    [Parameter(Mandatory)][string]$PrivateRoot,
    [string]$HostPath = (Join-Path $PSScriptRoot 'bin\Debug\net10.0-windows\win-x64\LegacyRuntimeQa.exe')
)
$ErrorActionPreference = 'Stop'
$privateEvidence = [IO.Path]::GetFullPath($PrivateRoot)
[void][IO.Directory]::CreateDirectory($privateEvidence)
$arguments = @('identity', [IO.Path]::GetFullPath($QaRoot), '--source-install-root',
    [IO.Path]::GetFullPath($SourceInstallRoot), '--private-root', $privateEvidence, '--hold') |
    ForEach-Object { '"' + $_.TrimEnd('\') + '"' }
$fixtureProcess = Start-Process -FilePath ([IO.Path]::GetFullPath($HostPath)) -ArgumentList $arguments -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $privateEvidence 'host-stdout.log') `
    -RedirectStandardError (Join-Path $privateEvidence 'host-stderr.log') -PassThru
$readyPath = Join-Path $privateEvidence 'ready.json'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ([DateTime]::UtcNow -lt $deadline) {
    $fixtureProcess.Refresh()
    if ($fixtureProcess.HasExited) { throw 'Identity host stopped; inspect its private stderr file.' }
    if (Test-Path -LiteralPath $readyPath) {
        $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
        if ($ready.pid -eq $fixtureProcess.Id -and $ready.liveSteamBound) {
            [pscustomobject]@{ Ready = $true; Pid = $ready.pid; SelectedSkinPrepared = $ready.selectedSkinPrepared }
            return
        }
    }
    Start-Sleep -Milliseconds 200
}
throw 'Identity host readiness timed out; inspect its private evidence files.'
