#requires -Version 7.0
<#
.SYNOPSIS
Diagnoses loading the installed Legacy Fabric 1.6.4 OpenAL binary, without playback.
.DESCRIPTION
Uses the installed Java 17, LWJGL and actual native directory. Captures complete
LWJGL debug path errors in a private owned run. Never replaces DLLs, changes
devices/settings or opens worlds. A passing isolated load is not a gameplay or
audible-output PASS. Paths in the raw log are private.
Use -UseJavaw to compare the same executable identity as the real game. Default
java.exe and javaw.exe can have different per-app Windows preferences.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$UseJavaw
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Windows and PowerShell 7 are required.' }
$applicationRoot = (Resolve-Path -LiteralPath $AppRoot).ProviderPath
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$allowedOutputRoot = [IO.Path]::GetFullPath((Join-Path $applicationRoot 'artifacts/agent-runs')).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if (-not $outputRoot.StartsWith($allowedOutputRoot,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be an owned artifacts/agent-runs subdirectory.'
}
for ($current = $outputRoot; $current; $current = [IO.Path]::GetDirectoryName($current)) {
    if ((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Output path must not traverse reparse points.'
    }
}
if (Test-Path -LiteralPath $outputRoot) {
    if (Get-ChildItem -LiteralPath $outputRoot -Force | Select-Object -First 1) { throw 'Use a new empty diagnostic output directory.' }
} else { New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null }
$started = [DateTimeOffset]::UtcNow
[ordered]@{owner='Better Than Wolves audio-load diagnostic';purpose='Read-only installed native loading, no playback';createdUtc=$started.ToString('o');expectedBytes=1048576;deleteAfterUtc=$started.AddDays(1).ToString('o');requiredResults=@('audio-load.json','probe-private.log')} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'RUN.json') -Encoding utf8
$runtimeRoot = Join-Path $applicationRoot 'Minecraft/Launcher'
$javaName = if($UseJavaw){'javaw.exe'}else{'java.exe'}
$java = Join-Path $runtimeRoot ('JavaRuntimes/runtime/windows-x64/java-17/bin/'+$javaName)
$javac = Join-Path (Split-Path -Parent $java) 'javac.exe'
$jar = Join-Path $runtimeRoot 'Shared/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar'
$natives = Join-Path $runtimeRoot 'Shared/versions/legacyfabric-loader-0.18.2-1.6.4/natives'
$dll = Join-Path $natives 'OpenAL-amd64.dll'
$inventory = foreach ($path in @($java,$javac,$jar,$dll,(Join-Path $natives 'lwjgl-windows-amd64.dll'))) {
    $exists = Test-Path -LiteralPath $path -PathType Leaf
    [ordered]@{relativePath=[IO.Path]::GetRelativePath($applicationRoot,$path);exists=$exists;pathCharacters=$path.Length;nonAscii=($path -match '[^\x00-\x7F]');sizeBytes=$(if($exists){(Get-Item -LiteralPath $path).Length}else{$null});sha256=$(if($exists){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}else{$null})}
}
$inventory | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputRoot 'inventory.json') -Encoding utf8
$missing = @($inventory | Where-Object { -not $_.exists })
if ($missing.Count) {
    [ordered]@{status='FAIL_MISSING_INSTALLED_FILE';nativeInventory=$inventory;deviceSettingsChanged=$false;audioPlayed=$false;fullClientOrGuestGameplayClaimed=$false} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputRoot 'audio-load.json') -Encoding utf8
    throw ('Required installed files are missing: ' + (($missing | ForEach-Object relativePath) -join ', '))
}
if (-not ('BtwNativeAudioLoad' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BtwNativeAudioLoad {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr LoadLibraryExW(string path,IntPtr file,uint flags);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool FreeLibrary(IntPtr handle);
 public static int Check(string path){var handle=LoadLibraryExW(path,IntPtr.Zero,8);if(handle==IntPtr.Zero)return Marshal.GetLastWin32Error();FreeLibrary(handle);return 0;}
}
'@
}
$nativeError = [BtwNativeAudioLoad]::Check($dll)
$source = Join-Path $outputRoot 'InstalledAudioLoadProbe.java'
$profile = Join-Path $outputRoot 'profile'
$temp = Join-Path $outputRoot 'temp'
$classes = Join-Path $outputRoot 'classes'
foreach ($directory in @($profile,$temp,$classes)) { New-Item -ItemType Directory -Path $directory | Out-Null }
@'
import org.lwjgl.openal.*;
public final class InstalledAudioLoadProbe {
 public static void main(String[] args)throws Exception {
  System.out.println("java.version="+System.getProperty("java.version")+";arch="+System.getProperty("os.arch"));
  System.out.println("java.library.path="+System.getProperty("java.library.path"));
  try { AL.create();System.out.println("OpenALCreated="+AL.isCreated());
   System.out.println("Vendor="+AL10.alGetString(AL10.AL_VENDOR));
   System.out.println("Renderer="+AL10.alGetString(AL10.AL_RENDERER));
   System.out.println("Device="+ALC10.alcGetString(AL.getDevice(),0x1013));
  } finally {AL.destroy();}
 }
}
'@ | Set-Content -LiteralPath $source -Encoding utf8
function Invoke-OwnedJavaTool([string]$Executable,[string[]]$Arguments,[int]$Seconds) {
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.WorkingDirectory=$outputRoot
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        if (-not $process.Start()) { throw 'Could not start diagnostic child.' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($Seconds*1000)) { $process.Kill(); $process.WaitForExit(); throw 'Owned audio diagnostic child timed out.' }
        [pscustomobject]@{exitCode=$process.ExitCode;stdout=$stdout.GetAwaiter().GetResult();stderr=$stderr.GetAwaiter().GetResult()}
    } finally { $process.Dispose() }
}
$probe = $null
try {
    $compile = Invoke-OwnedJavaTool $javac @('--release','17','-encoding','UTF-8',('-J-Duser.home='+$profile),('-J-Djava.io.tmpdir='+$temp),'-cp',$jar,'-d',$classes,$source) 30
    if ($compile.exitCode -ne 0) { throw ('Compilation failed: '+$compile.stderr) }
    $probe = Invoke-OwnedJavaTool $java @('-XX:-UsePerfData',('-Duser.home='+$profile),('-Djava.io.tmpdir='+$temp),('-Djava.library.path='+$natives),('-Dorg.lwjgl.librarypath='+$natives),'-Dorg.lwjgl.util.Debug=true','-Dbtw.qa.audioLoad=true','-cp',($classes+';'+$jar),'InstalledAudioLoadProbe') 30
    ($probe.stdout + "`n" + $probe.stderr) | Set-Content -LiteralPath (Join-Path $outputRoot 'probe-private.log') -Encoding utf8
    $result = [ordered]@{status=$(if($probe.exitCode -eq 0){'PASS_ISOLATED_NATIVE_LOAD'}else{'FAIL_ISOLATED_NATIVE_LOAD'});exitCode=$probe.exitCode;javaExecutable=$javaName;win32LoadLibraryError=$nativeError;win32LoadLibraryMessage=$(if($nativeError){([ComponentModel.Win32Exception]::new($nativeError)).Message}else{'Success'});nativeDirectoryCharacters=$natives.Length;actualInstalledFilesUsed=$true;nativeInventory=$inventory;deviceSettingsChanged=$false;audioPlayed=$false;fullClientOrGuestGameplayClaimed=$false}
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputRoot 'audio-load.json') -Encoding utf8
    Write-Output ($result | ConvertTo-Json -Depth 6)
} finally {
    # These exact paths are generated only by this invocation; never recurse
    # through a directory that was supplied by a caller or traverse a link.
    foreach ($generated in @($source,(Join-Path $classes 'InstalledAudioLoadProbe.class'))) {
        if (Test-Path -LiteralPath $generated -PathType Leaf) { Remove-Item -LiteralPath $generated }
    }
    foreach ($generatedDirectory in @($classes,$profile,$temp)) {
        if ((Test-Path -LiteralPath $generatedDirectory) -and -not (Get-ChildItem -LiteralPath $generatedDirectory -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $generatedDirectory }
    }
    $runPath = Join-Path $outputRoot 'RUN.json'
    $run = Get-Content -LiteralPath $runPath -Raw | ConvertFrom-Json -AsHashtable
    $run.status = 'complete-evidence-only'
    $run.deleteAfterUtc = $null
    $run | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $runPath -Encoding utf8
}
