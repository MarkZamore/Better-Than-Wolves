<#
.SYNOPSIS
Read-only Windows render endpoint and existing audio session snapshot.
.DESCRIPTION
Requires Windows and PowerShell 7. Enumerates active output endpoints, default
roles, and existing sessions with process ID, state, volume and mute. The output
is private diagnostic evidence: it contains device names and unrelated process
IDs. No audio samples or capture endpoints are read, and no settings are changed.
.EXAMPLE
./InspectWindowsAudio.ps1 -OutputPath ABSOLUTE_OWNED_RUN/windows-audio.json -FocusProcessId @(1234,5678)
#>
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [int[]]$FocusProcessId = @()
)

$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or $PSVersionTable.PSVersion.Major -lt 7) {
    throw 'This read-only diagnostic requires Windows and PowerShell 7.'
}
$destination = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $destination
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    throw 'The diagnostic output directory must already exist.'
}
if ((Get-Item -LiteralPath $outputDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'The diagnostic output directory cannot be a link.'
}
if ((Test-Path -LiteralPath $destination) -and
    ((Get-Item -LiteralPath $destination).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'The diagnostic output cannot be a link.'
}

# All COM calls below are reads: render endpoint enumeration, default role,
# property store, existing sessions, volume and mute getters. No capture client,
# endpoint/session setter, notification registration or audio sample API is used.
# Interface identities and vtable order follow Windows SDK 10 mmdeviceapi.h,
# audiopolicy.h, audioclient.h and endpointvolume.h.
if (-not ('LanMinecraft.Diagnostics.WindowsAudioSnapshot' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace LanMinecraft.Diagnostics {
public sealed class AudioSessionSnapshot {
    public uint ProcessId { get; set; }
    public string ProcessName { get; set; }
    public bool FocusProcess { get; set; }
    public string State { get; set; }
    public float? Volume { get; set; }
    public bool? Muted { get; set; }
    public string Error { get; set; }
}
public sealed class AudioEndpointSnapshot {
    public string Id { get; set; }
    public string FriendlyName { get; set; }
    public List<string> DefaultRoles { get; set; } = new List<string>();
    public float? MasterVolume { get; set; }
    public bool? MasterMuted { get; set; }
    public List<AudioSessionSnapshot> Sessions { get; set; } = new List<AudioSessionSnapshot>();
    public string Error { get; set; }
}
public sealed class WindowsAudioReport {
    public int SchemaVersion { get; set; } = 1;
    public string CapturedAtUtc { get; set; }
    public bool ReadOnly { get; set; } = true;
    public bool CaptureDevicesEnumerated { get; set; } = false;
    public bool AudioSamplesCaptured { get; set; } = false;
    public Dictionary<string,string> DefaultRenderEndpointIds { get; set; } = new Dictionary<string,string>();
    public List<AudioEndpointSnapshot> ActiveRenderEndpoints { get; set; } = new List<AudioEndpointSnapshot>();
    public List<string> Errors { get; set; } = new List<string>();
}
public static class WindowsAudioSnapshot {
    [StructLayout(LayoutKind.Sequential)] struct PropertyKey { public Guid Format; public uint Id; }
    [DllImport("ole32.dll")] static extern int CoInitializeEx(IntPtr reserved, uint mode);
    [DllImport("ole32.dll")] static extern void CoUninitialize();
    [DllImport("ole32.dll")] static extern int CoCreateInstance(ref Guid clsid, IntPtr outer, uint context, ref Guid iid, out IntPtr result);
    [DllImport("ole32.dll")] static extern int PropVariantClear(IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int EnumEndpoints(IntPtr self, int flow, uint state, out IntPtr collection);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int DefaultEndpoint(IntPtr self, int flow, int role, out IntPtr endpoint);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Count(IntPtr self, out uint count);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Item(IntPtr self, uint index, out IntPtr item);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int StringValue(IntPtr self, out IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Activate(IntPtr self, ref Guid iid, uint context, IntPtr activation, out IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int OpenStore(IntPtr self, uint access, out IntPtr store);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PropertyValue(IntPtr self, ref PropertyKey key, IntPtr variant);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int ObjectValue(IntPtr self, out IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int UIntValue(IntPtr self, out uint value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int FloatValue(IntPtr self, out float value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int IntValue(IntPtr self, out int value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int QueryInterface(IntPtr self, ref Guid iid, out IntPtr value);

    static T Method<T>(IntPtr self, int slot) where T : Delegate {
        return Marshal.GetDelegateForFunctionPointer<T>(Marshal.ReadIntPtr(Marshal.ReadIntPtr(self), slot * IntPtr.Size));
    }
    static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }
    static void Release(ref IntPtr value) { if (value != IntPtr.Zero) { Marshal.Release(value); value = IntPtr.Zero; } }
    static string GetString(IntPtr self, int slot) {
        IntPtr text = IntPtr.Zero;
        try { Check(Method<StringValue>(self,slot)(self,out text)); return Marshal.PtrToStringUni(text); }
        finally { if (text != IntPtr.Zero) Marshal.FreeCoTaskMem(text); }
    }
    static string FriendlyName(IntPtr endpoint) {
        IntPtr store = IntPtr.Zero, variant = Marshal.AllocHGlobal(32);
        for (int i=0;i<32;i++) Marshal.WriteByte(variant,i,0);
        try {
            Check(Method<OpenStore>(endpoint,4)(endpoint,0,out store));
            var key = new PropertyKey { Format = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), Id = 14 };
            Check(Method<PropertyValue>(store,5)(store,ref key,variant));
            return Marshal.ReadInt16(variant)==31 ? Marshal.PtrToStringUni(Marshal.ReadIntPtr(variant,8)) : "<non-string friendly name>";
        } finally { PropVariantClear(variant); Marshal.FreeHGlobal(variant); Release(ref store); }
    }
    static IntPtr ActivateInterface(IntPtr endpoint, string identity) {
        var iid = new Guid(identity); IntPtr value;
        Check(Method<Activate>(endpoint,3)(endpoint,ref iid,23,IntPtr.Zero,out value)); return value;
    }
    static AudioSessionSnapshot Session(IntPtr control, HashSet<uint> focus) {
        var result = new AudioSessionSnapshot(); IntPtr control2 = IntPtr.Zero, volume = IntPtr.Zero;
        try {
            var iid = new Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d");
            Check(Method<QueryInterface>(control,0)(control,ref iid,out control2));
            uint pid; Check(Method<UIntValue>(control2,14)(control2,out pid));
            result.ProcessId=pid; result.FocusProcess=focus.Contains(pid);
            if(pid==0) result.ProcessName="SystemSounds";
            else try { using(var process=Process.GetProcessById((int)pid)) result.ProcessName=process.ProcessName; }
                 catch(ArgumentException) { result.ProcessName="<process exited>"; }
            int state; Check(Method<IntValue>(control2,3)(control2,out state));
            result.State=state==0?"Inactive":state==1?"Active":state==2?"Expired":"Unknown:"+state;
            iid=new Guid("87ce5498-68d6-44e5-9215-6da47ef883d8");
            Check(Method<QueryInterface>(control,0)(control,ref iid,out volume));
            float scalar; Check(Method<FloatValue>(volume,4)(volume,out scalar)); result.Volume=scalar;
            int mute; Check(Method<IntValue>(volume,6)(volume,out mute)); result.Muted=mute!=0;
        } catch(Exception error) { result.Error=error.GetType().Name+": "+error.Message; }
        finally { Release(ref volume); Release(ref control2); }
        return result;
    }
    static void ReadSessions(IntPtr endpoint, AudioEndpointSnapshot result, HashSet<uint> focus) {
        IntPtr manager=IntPtr.Zero, sessions=IntPtr.Zero;
        try {
            manager=ActivateInterface(endpoint,"77aa99a0-1bd6-484f-8bc7-2c654c9a9b6f");
            Check(Method<ObjectValue>(manager,5)(manager,out sessions));
            uint count; Check(Method<Count>(sessions,3)(sessions,out count));
            for(uint i=0;i<count;i++) {
                IntPtr control=IntPtr.Zero;
                try { Check(Method<Item>(sessions,4)(sessions,i,out control)); result.Sessions.Add(Session(control,focus)); }
                finally { Release(ref control); }
            }
        } finally { Release(ref sessions); Release(ref manager); }
    }
    static void ReadMasterVolume(IntPtr endpoint, AudioEndpointSnapshot result) {
        IntPtr volume=IntPtr.Zero;
        try {
            volume=ActivateInterface(endpoint,"5cdf2c82-841e-4546-9722-0cf74078229a");
            float scalar; Check(Method<FloatValue>(volume,9)(volume,out scalar)); result.MasterVolume=scalar;
            int mute; Check(Method<IntValue>(volume,15)(volume,out mute)); result.MasterMuted=mute!=0;
        } finally { Release(ref volume); }
    }
    static WindowsAudioReport CaptureOnMta(int[] focusIds) {
        var report=new WindowsAudioReport { CapturedAtUtc=DateTime.UtcNow.ToString("o") };
        var focus=new HashSet<uint>(); foreach(int pid in focusIds) if(pid>0) focus.Add((uint)pid);
        IntPtr enumerator=IntPtr.Zero, collection=IntPtr.Zero;
        int init=CoInitializeEx(IntPtr.Zero,0); Check(init);
        try {
            var clsid=new Guid("bcde0395-e52f-467c-8e3d-c4579291692e");
            var iid=new Guid("a95664d2-9614-4f35-a746-de8db63617e6");
            Check(CoCreateInstance(ref clsid,IntPtr.Zero,1,ref iid,out enumerator));
            string[] roles={"eConsole","eMultimedia","eCommunications"};
            for(int role=0;role<roles.Length;role++) {
                IntPtr endpoint=IntPtr.Zero;
                try { Check(Method<DefaultEndpoint>(enumerator,4)(enumerator,0,role,out endpoint)); report.DefaultRenderEndpointIds[roles[role]]=GetString(endpoint,5); }
                catch(Exception error) { report.Errors.Add(roles[role]+": "+error.Message); }
                finally { Release(ref endpoint); }
            }
            Check(Method<EnumEndpoints>(enumerator,3)(enumerator,0,1,out collection));
            uint count; Check(Method<Count>(collection,3)(collection,out count));
            for(uint i=0;i<count;i++) {
                IntPtr endpoint=IntPtr.Zero;var result=new AudioEndpointSnapshot();
                try {
                    Check(Method<Item>(collection,4)(collection,i,out endpoint)); result.Id=GetString(endpoint,5);result.FriendlyName=FriendlyName(endpoint);
                    foreach(var role in report.DefaultRenderEndpointIds) if(role.Value==result.Id) result.DefaultRoles.Add(role.Key);
                    ReadMasterVolume(endpoint,result);ReadSessions(endpoint,result,focus);
                } catch(Exception error) { result.Error=error.GetType().Name+": "+error.Message; }
                finally { Release(ref endpoint); }
                report.ActiveRenderEndpoints.Add(result);
            }
        } finally { Release(ref collection); Release(ref enumerator); CoUninitialize(); }
        return report;
    }
    public static WindowsAudioReport Capture(int[] focusIds) {
        WindowsAudioReport report=null;Exception failure=null;
        var thread=new Thread(()=>{try { report=CaptureOnMta(focusIds); } catch(Exception error) { failure=error; }});
        thread.SetApartmentState(ApartmentState.MTA);thread.Start();thread.Join();
        if(failure!=null) throw new InvalidOperationException("Core Audio read-only snapshot failed",failure);
        return report;
    }
}
}
'@
}

$snapshot = [LanMinecraft.Diagnostics.WindowsAudioSnapshot]::Capture($FocusProcessId)
$snapshot | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "READ_ONLY_RENDER_AUDIO_SNAPSHOT_WRITTEN endpoints=$($snapshot.ActiveRenderEndpoints.Count)"
