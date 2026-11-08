#requires -Version 5.1
<#
.SYNOPSIS
Diagnoses loading the installed Legacy Fabric 1.6.4 OpenAL binary, without playback.
.DESCRIPTION
Works with built-in Windows PowerShell 5.1 and PowerShell 7. Uses installed Java
17, LWJGL and the actual native directory. Captures private loader errors without
replacing DLLs, changing devices/settings or opening worlds. An isolated load
PASS is not a gameplay or audible-output PASS. Use -UseJavaw to compare the same
executable identity as the real game. Paths in the raw log are private.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$UseJavaw
)
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Windows PowerShell 5.1 or PowerShell 7 on Windows is required.' }
$applicationRoot = (Resolve-Path -LiteralPath $AppRoot).ProviderPath.TrimEnd('\','/')
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
$utf8 = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
function Write-Utf8([string]$Path,[string]$Text) { [IO.File]::WriteAllText($Path,$Text,$utf8) }
function Get-DiagnosticSha256([string]$Path) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $hash.Dispose() }
}
function Write-Json([string]$Path,$Value) { Write-Utf8 $Path (($Value | ConvertTo-Json -Depth 8) + "`n") }
function Get-InstalledRelativePath([string]$Path) {
    $prefix = $applicationRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Installed input escaped application root.' }
    return $Path.Substring($prefix.Length)
}
function ConvertTo-WindowsArgument([AllowEmptyString()][string]$Argument) {
    # ProcessStartInfo.Arguments is passed directly to CreateProcess, not a
    # shell. Quote by the Windows C-runtime argv rules, including trailing '\'.
    if ($Argument.Length -gt 0 -and $Argument -notmatch '[\s"]') { return $Argument }
    $quoted = New-Object Text.StringBuilder
    [void]$quoted.Append('"'); $slashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq [char]92) { $slashes++; continue }
        if ($character -eq [char]34) {
            [void]$quoted.Append(('\' * (2 * $slashes + 1)))
            [void]$quoted.Append('"')
        } else {
            [void]$quoted.Append(('\' * $slashes)); [void]$quoted.Append($character)
        }
        $slashes = 0
    }
    [void]$quoted.Append(('\' * (2 * $slashes))); [void]$quoted.Append('"')
    return $quoted.ToString()
}
function Invoke-OwnedJavaTool([string]$Executable,[string[]]$Arguments,[int]$Seconds) {
    $info = New-Object Diagnostics.ProcessStartInfo($Executable)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=$utf8; $info.StandardErrorEncoding=$utf8
    $info.WorkingDirectory=$outputRoot
    $info.Arguments = (@($Arguments | ForEach-Object { ConvertTo-WindowsArgument $_ }) -join ' ')
    $process = New-Object Diagnostics.Process; $process.StartInfo=$info
    try {
        if (-not $process.Start()) { throw 'Could not start diagnostic child.' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($Seconds*1000)) { $process.Kill(); $process.WaitForExit(); throw 'Owned audio diagnostic child timed out.' }
        [pscustomobject]@{exitCode=$process.ExitCode;stdout=$stdout.GetAwaiter().GetResult();stderr=$stderr.GetAwaiter().GetResult()}
    } finally { $process.Dispose() }
}
$started = [DateTimeOffset]::UtcNow
$run = [ordered]@{owner='Better Than Wolves audio-load diagnostic';purpose='Read-only installed native loading, no playback';createdUtc=$started.ToString('o');expectedBytes=1048576;deleteAfterUtc=$started.AddDays(1).ToString('o');requiredResults=@('audio-load.json','inventory.json','probe-private.log')}
Write-Json (Join-Path $outputRoot 'RUN.json') $run
$runtimeRoot = Join-Path $applicationRoot 'Minecraft/Launcher'
$javaName = if($UseJavaw){'javaw.exe'}else{'java.exe'}
$java = Join-Path $runtimeRoot ('JavaRuntimes/runtime/windows-x64/java-17/bin/'+$javaName)
$javac = Join-Path (Split-Path -Parent $java) 'javac.exe'
$jar = Join-Path $runtimeRoot 'Shared/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar'
$natives = Join-Path $runtimeRoot 'Shared/versions/legacyfabric-loader-0.18.2-1.6.4/natives'
$dll = Join-Path $natives 'OpenAL-amd64.dll'
$source = Join-Path $outputRoot 'InstalledAudioLoadProbe.java'
$profile = Join-Path $outputRoot 'profile'; $temp = Join-Path $outputRoot 'temp'; $classes = Join-Path $outputRoot 'classes'
$result = [ordered]@{status='FAIL_DIAGNOSTIC';phase='inventory';exitCode=$null;javaExecutable=$javaName;powerShellVersion=$PSVersionTable.PSVersion.ToString();win32LoadLibraryError=$null;win32LoadLibraryMessage=$null;nativeDirectoryCharacters=$natives.Length;actualInstalledFilesUsed=$true;nativeInventory=@();deviceSettingsChanged=$false;audioPlayed=$false;fullClientOrGuestGameplayClaimed=$false;errorMessage=$null}
$diagnosticError = $null
Write-Utf8 (Join-Path $outputRoot 'probe-private.log') "Private diagnostic log; no audio playback or device changes.`n"
try {
    $inventory = @(foreach ($path in @($java,$javac,$jar,$dll,(Join-Path $natives 'lwjgl-windows-amd64.dll'))) {
        $exists = Test-Path -LiteralPath $path -PathType Leaf
        [ordered]@{relativePath=(Get-InstalledRelativePath $path);exists=$exists;pathCharacters=$path.Length;nonAscii=($path -match '[^\x00-\x7F]');sizeBytes=$(if($exists){(Get-Item -LiteralPath $path).Length}else{$null});sha256=$(if($exists){Get-DiagnosticSha256 $path}else{$null})}
    })
    $result.nativeInventory=$inventory
    $result.phase='win32-loader'
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
    $result.win32LoadLibraryError=$nativeError
    $result.win32LoadLibraryMessage=if($nativeError){(New-Object ComponentModel.Win32Exception($nativeError)).Message}else{'Success'}
    $missing = @($inventory | Where-Object { -not $_.exists })
    if ($missing.Count) {
        $result.status='FAIL_MISSING_INSTALLED_FILE'
        throw ('Required installed files are missing: ' + (($missing | ForEach-Object { $_.relativePath }) -join ', '))
    }
    foreach ($directory in @($profile,$temp,$classes)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    # Explicit UTF-8 without BOM: Windows PowerShell's Set-Content -Encoding UTF8
    # otherwise adds a BOM rejected by javac at the start of a Java source file.
    Write-Utf8 $source @'
import org.lwjgl.openal.*;
public final class InstalledAudioLoadProbe {
 public static void main(String[] args)throws Exception {
  System.out.println("java.version="+System.getProperty("java.version")+";arch="+System.getProperty("os.arch"));
  System.out.println("java.library.path="+System.getProperty("java.library.path"));
  System.out.println("user.home="+System.getProperty("user.home"));
  System.out.println("java.io.tmpdir="+System.getProperty("java.io.tmpdir"));
  try { AL.create();System.out.println("OpenALCreated="+AL.isCreated());
   System.out.println("Vendor="+AL10.alGetString(AL10.AL_VENDOR));
   System.out.println("Renderer="+AL10.alGetString(AL10.AL_RENDERER));
   try {System.out.println("Device="+ALC10.alcGetString(AL.getDevice(),0x1013));}
   catch(RuntimeException warning){System.out.println("OptionalDeviceNameWarning="+warning);}
  } finally {AL.destroy();}
 }
}
'@
    $result.phase='compilation'
    $compile = Invoke-OwnedJavaTool $javac @('--release','17','-encoding','UTF-8','-J-Dfile.encoding=UTF-8',('-J-Duser.home='+$profile),('-J-Djava.io.tmpdir='+$temp),'-cp',$jar,'-d',$classes,$source) 30
    Write-Utf8 (Join-Path $outputRoot 'compile-private.log') ($compile.stdout + "`n" + $compile.stderr)
    if ($compile.exitCode -ne 0) { $result.status='FAIL_COMPILATION'; $result.exitCode=$compile.exitCode; throw ('Compilation failed: '+$compile.stderr) }
    $result.phase='openal-loader'
    $probe = Invoke-OwnedJavaTool $java @('-XX:-UsePerfData','-Dfile.encoding=UTF-8',('-Duser.home='+$profile),('-Djava.io.tmpdir='+$temp),('-Djava.library.path='+$natives),('-Dorg.lwjgl.librarypath='+$natives),'-Dorg.lwjgl.util.Debug=true','-Dbtw.qa.audioLoad=true','-cp',($classes+';'+$jar),'InstalledAudioLoadProbe') 30
    Write-Utf8 (Join-Path $outputRoot 'probe-private.log') ($probe.stdout + "`n" + $probe.stderr)
    $result.exitCode=$probe.exitCode
    $result.status=if($probe.exitCode -eq 0){'PASS_ISOLATED_NATIVE_LOAD'}else{'FAIL_ISOLATED_NATIVE_LOAD'}
    if ($probe.exitCode -ne 0) { throw 'The installed native OpenAL load failed; see probe-private.log.' }
    $result.phase='complete'
} catch {
    $diagnosticError=$_
    $result.errorMessage=$_.Exception.ToString()
    [IO.File]::AppendAllText((Join-Path $outputRoot 'probe-private.log'),("`nDIAGNOSTIC ERROR at "+$result.phase+": "+$result.errorMessage+"`n"),$utf8)
} finally {
    $cleanupErrors = @()
    foreach ($generated in @($source,(Join-Path $classes 'InstalledAudioLoadProbe.class'))) {
        try { if (Test-Path -LiteralPath $generated -PathType Leaf) { Remove-Item -LiteralPath $generated } }
        catch { $cleanupErrors += $_.Exception.Message }
    }
    foreach ($generatedDirectory in @($classes,$profile,$temp)) {
        try { if ((Test-Path -LiteralPath $generatedDirectory) -and -not (Get-ChildItem -LiteralPath $generatedDirectory -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $generatedDirectory } }
        catch { $cleanupErrors += $_.Exception.Message }
    }
    $result.cleanupErrors=$cleanupErrors
    Write-Json (Join-Path $outputRoot 'audio-load.json') $result
    Write-Json (Join-Path $outputRoot 'inventory.json') ([ordered]@{files=$result.nativeInventory;win32LoadLibraryError=$result.win32LoadLibraryError;win32LoadLibraryMessage=$result.win32LoadLibraryMessage})
    $run.status='complete-evidence-only'; $run.deleteAfterUtc=$null
    Write-Json (Join-Path $outputRoot 'RUN.json') $run
}
Write-Output ($result | ConvertTo-Json -Depth 8)
if ($diagnosticError) { throw $diagnosticError }
