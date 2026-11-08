using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

// Diagnostic-only. Process-loopback includes one validated QA Java process and
// its children. It never opens a microphone or endpoint-wide loopback stream.
internal static class Program
{
    const int SampleRate = 44100, Channels = 2, BytesPerFrame = 4;
    static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    [MTAThread]
    static int Main(string[] arguments)
    {
        string? output = null;
        object? result = null;
        int code;
        try
        {
            if (arguments.Length != 6 || arguments[0] != "--pid" || arguments[2] != "--seconds" || arguments[4] != "--out")
                throw new ArgumentException("Usage: AudioCapture --pid QA_JAVA_PID --seconds 1..20 --out OWNED_RUN/file.wav");
            int processId = int.Parse(arguments[1]), seconds = int.Parse(arguments[3]);
            if (processId <= 0 || seconds < 1 || seconds > 20) throw new ArgumentException("Invalid PID or duration (1..20 seconds required).");
            output = ValidateOutput(arguments[5]);
            if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 20348) || !Environment.Is64BitProcess)
                throw new PlatformNotSupportedException("Process-loopback requires Windows build 20348+ and this tool's x64 host.");
            ValidateTarget(processId);
            result = Capture(processId, seconds, output);
            code = 0;
        }
        catch (ArgumentException error)
        {
            result = new { status = "REFUSED", reason = error.Message, desktopFallbackPerformed = false, microphoneOpened = false };
            code = 2;
        }
        catch (Exception error)
        {
            result = new { status = "UNAVAILABLE", reason = error.GetType().Name + ": " + error.Message,
                hresult = "0x" + error.HResult.ToString("X8"), desktopFallbackPerformed = false, microphoneOpened = false };
            code = 5;
        }
        var json = JsonSerializer.Serialize(result, JsonOptions);
        if (output is not null) File.WriteAllText(Path.ChangeExtension(output, ".json"), json + "\n", new UTF8Encoding(false));
        Console.WriteLine(json);
        return code;
    }

    static string ValidateOutput(string supplied)
    {
        if (!Path.IsPathFullyQualified(supplied)) throw new ArgumentException("Output must be an absolute path in an existing owned RUN directory.");
        var path = Path.GetFullPath(supplied);
        if (!path.EndsWith(".wav", StringComparison.OrdinalIgnoreCase) || File.Exists(path))
            throw new ArgumentException("Use a new WAV filename.");
        var parent = new DirectoryInfo(Path.GetDirectoryName(path)!);
        if (!parent.Exists) throw new ArgumentException("Output directory must already exist.");
        bool owned = false;
        for (var current = parent; current is not null; current = current.Parent)
        {
            if ((current.Attributes & FileAttributes.ReparsePoint) != 0) throw new ArgumentException("Output ancestry cannot contain links.");
            if (current.Parent?.Name.Equals("agent-runs", StringComparison.OrdinalIgnoreCase) == true &&
                current.Parent.Parent?.Name.Equals("artifacts", StringComparison.OrdinalIgnoreCase) == true)
            {
                var marker = Path.Combine(current.FullName, "RUN.json");
                if (File.Exists(marker) && (File.GetAttributes(marker) & FileAttributes.ReparsePoint) == 0)
                {
                    using var run = JsonDocument.Parse(File.ReadAllText(marker));
                    owned = run.RootElement.TryGetProperty("owner", out var owner) && !string.IsNullOrWhiteSpace(owner.GetString());
                }
            }
        }
        if (!owned) throw new ArgumentException("Output must be inside a marked artifacts/agent-runs/<task> directory.");
        foreach (var sibling in new[] { Path.ChangeExtension(path, ".json"), path + ".ready" })
            if (File.Exists(sibling)) throw new ArgumentException("Use a new WAV/JSON/readiness basename.");
        return path;
    }

    static void ValidateTarget(int processId)
    {
        using var process = Process.GetProcessById(processId);
        if (process.ProcessName is not ("java" or "javaw")) throw new ArgumentException("Only a marked QA Java process may be captured.");
        IntPtr handle = Native.OpenProcess(0x1000, false, processId);
        if (handle == IntPtr.Zero) throw new ArgumentException("Cannot inspect target process command line.");
        IntPtr storage = IntPtr.Zero;
        try
        {
            Native.NtQueryInformationProcess(handle, 60, IntPtr.Zero, 0, out int required);
            if (required <= 0 || required > 128 * 1024) throw new ArgumentException("Invalid target command-line buffer.");
            storage = Marshal.AllocHGlobal(required);
            if (Native.NtQueryInformationProcess(handle, 60, storage, required, out _) < 0)
                throw new ArgumentException("Cannot inspect target process command line.");
            int length = (ushort)Marshal.ReadInt16(storage);
            IntPtr pointer = Marshal.ReadIntPtr(storage, 8);
            if (length < 2 || length > required - 16 || pointer.ToInt64() < storage.ToInt64() ||
                pointer.ToInt64() + length > storage.ToInt64() + required)
                throw new ArgumentException("Invalid target command-line string.");
            string command = Marshal.PtrToStringUni(pointer, length / 2) ?? "";
            if (!command.Contains("-Dbtw.qa.", StringComparison.Ordinal) && !command.Contains("LegacyAudioProbe", StringComparison.Ordinal))
                throw new ArgumentException("Target is not explicitly marked btw.qa.* or LegacyAudioProbe. Live player audio is refused.");
            if (process.HasExited) throw new ArgumentException("QA process has already exited.");
        }
        finally { if (storage != IntPtr.Zero) Marshal.FreeHGlobal(storage); Native.CloseHandle(handle); }
    }

    static object Capture(int processId, int seconds, string output)
    {
        Native.Check(Native.CoInitializeEx(IntPtr.Zero, 0));
        IAudioClient? client = null;
        IAudioCaptureClient? capture = null;
        IActivateAudioInterfaceAsyncOperation? operation = null;
        using var samplesReady = new AutoResetEvent(false);
        var activation = new ActivationCompletion();
        IntPtr parameters = Marshal.AllocHGlobal(12), variant = Marshal.AllocHGlobal(24);
        bool started = false, activated = false;
        long dataBytes = 0, sampleCount = 0, nonzeroSamples = 0;
        double squareSum = 0, peak = 0;
        int packets = 0, silentPackets = 0, discontinuities = 0;
        var began = DateTime.UtcNow;
        try
        {
            for (int i = 0; i < 24; i++) Marshal.WriteByte(variant, i, 0);
            Marshal.WriteInt32(parameters, 0, 1); // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
            Marshal.WriteInt32(parameters, 4, processId);
            Marshal.WriteInt32(parameters, 8, 0); // INCLUDE_TARGET_PROCESS_TREE, never EXCLUDE
            Marshal.WriteInt16(variant, 0, 65); // VT_BLOB
            Marshal.WriteInt32(variant, 8, 12);
            Marshal.WriteIntPtr(variant, 16, parameters);
            var audioClientId = typeof(IAudioClient).GUID;
            Native.Check(Native.ActivateAudioInterfaceAsync("VAD\\Process_Loopback", in audioClientId, variant, activation, out operation), "ActivateAudioInterfaceAsync");
            if (!activation.Complete.WaitOne(TimeSpan.FromSeconds(6))) throw new TimeoutException("Process-loopback activation did not complete within 6 seconds; no endpoint-wide fallback is allowed.");
            activated = true;
            Native.Check(activation.Result, "GetActivateResult");
            client = (IAudioClient)(activation.AudioInterface ?? throw new InvalidOperationException("No process-loopback audio client returned."));
            var format = new WaveFormat { FormatTag = 1, Channels = Channels, SamplesPerSecond = SampleRate,
                BitsPerSample = 16, BlockAlign = BytesPerFrame, BytesPerSecond = SampleRate * BytesPerFrame, ExtraBytes = 0 };
            const uint flags = 0x00020000 | 0x00040000 | 0x80000000; // LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM
            Native.Check(client.Initialize(0, flags, 0, 0, in format, IntPtr.Zero), "IAudioClient.Initialize");
            var captureId = typeof(IAudioCaptureClient).GUID;
            Native.Check(client.GetService(in captureId, out object service), "IAudioClient.GetService");
            capture = (IAudioCaptureClient)service;
            Native.Check(client.SetEventHandle(samplesReady.SafeWaitHandle.DangerousGetHandle()), "IAudioClient.SetEventHandle");
            using var file = new FileStream(output, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.Read);
            using var writer = new BinaryWriter(file, Encoding.ASCII, leaveOpen: true);
            WriteHeader(writer, 0);
            var clock = Stopwatch.StartNew();
            Native.Check(client.Start(), "IAudioClient.Start"); started = true;
            File.WriteAllText(output + ".ready", "READY_PROCESS_LOOPBACK\n", new UTF8Encoding(false));
            Console.WriteLine("READY_PROCESS_LOOPBACK pid=" + processId);
            long byteLimit = (long)seconds * SampleRate * BytesPerFrame;
            try
            {
                while (clock.Elapsed.TotalSeconds < seconds && dataBytes < byteLimit)
                {
                    samplesReady.WaitOne(50);
                    Native.Check(capture.GetNextPacketSize(out uint available));
                    while (available > 0 && dataBytes < byteLimit)
                    {
                        Native.Check(capture.GetBuffer(out var buffer, out uint frames, out uint packetFlags, out _, out _));
                        try
                        {
                            int bytes = (int)Math.Min((long)frames * BytesPerFrame, byteLimit - dataBytes);
                            if (bytes < 0 || bytes > 4 * 1024 * 1024) throw new InvalidDataException("Unexpected capture packet size.");
                            var data = new byte[bytes];
                            if ((packetFlags & 2) != 0) silentPackets++;
                            else if (bytes > 0) Marshal.Copy(buffer, data, 0, bytes);
                            if ((packetFlags & 1) != 0) discontinuities++;
                            writer.Write(data); dataBytes += bytes; packets++;
                            for (int i = 0; i + 1 < data.Length; i += 2)
                            {
                                short value = (short)(data[i] | data[i + 1] << 8);
                                double normalized = value / 32768.0;
                                peak = Math.Max(peak, Math.Abs(normalized)); squareSum += normalized * normalized;
                                if (value != 0) nonzeroSamples++; sampleCount++;
                            }
                        }
                        finally { Native.Check(capture.ReleaseBuffer(frames)); }
                        Native.Check(capture.GetNextPacketSize(out available));
                    }
                }
            }
            finally
            {
                Native.Check(client.Stop()); started = false;
                file.Position = 0; WriteHeader(writer, checked((int)dataBytes)); writer.Flush(); file.Flush(true);
            }
            file.Position = 0;
            string sha256 = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(file));
            return new { status = "CAPTURED_QA_PROCESS_ONLY", processId, includeTargetProcessTree = true,
                capturedAtUtc = began.ToString("o"), requestedSeconds = seconds, elapsedSeconds = clock.Elapsed.TotalSeconds,
                pcmSampleRate = SampleRate, channels = Channels, bitsPerSample = 16, dataBytes, packets, silentPackets,
                discontinuities, sampleCount, nonzeroSamples, peak, rms = sampleCount == 0 ? 0 : Math.Sqrt(squareSum / sampleCount),
                outputSha256 = sha256,
                microphoneOpened = false, desktopFallbackPerformed = false, endpointOrSessionSettingsChanged = false,
                audiblePlaybackClaimed = false };
        }
        finally
        {
            if (started) client?.Stop();
            if (capture is not null && Marshal.IsComObject(capture)) Marshal.ReleaseComObject(capture);
            if (client is not null && Marshal.IsComObject(client)) Marshal.ReleaseComObject(client);
            if (operation is not null && Marshal.IsComObject(operation)) Marshal.ReleaseComObject(operation);
            // On an activation timeout, retain the callback and its tiny input
            // buffers until process exit; freeing async callback state early is unsafe.
            if (activated) { activation.Complete.Dispose(); Marshal.FreeHGlobal(parameters); Marshal.FreeHGlobal(variant); }
            GC.KeepAlive(activation);
            Native.CoUninitialize();
        }
    }

    static void WriteHeader(BinaryWriter writer, int bytes)
    {
        writer.Write(Encoding.ASCII.GetBytes("RIFF")); writer.Write(36 + bytes); writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
        writer.Write(16); writer.Write((ushort)1); writer.Write((ushort)Channels); writer.Write(SampleRate);
        writer.Write(SampleRate * BytesPerFrame); writer.Write((ushort)BytesPerFrame); writer.Write((ushort)16);
        writer.Write(Encoding.ASCII.GetBytes("data")); writer.Write(bytes);
    }
}

[StructLayout(LayoutKind.Sequential, Pack = 2)]
internal struct WaveFormat { public ushort FormatTag, Channels; public uint SamplesPerSecond, BytesPerSecond; public ushort BlockAlign, BitsPerSample, ExtraBytes; }

[ComVisible(true), Guid("41d949ab-9862-444a-80f6-c261334da5eb"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IActivateAudioInterfaceCompletionHandler { [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation); }
[ComVisible(true), Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAgileObject { }
[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
public sealed class ActivationCompletion : IActivateAudioInterfaceCompletionHandler, IAgileObject
{
    internal readonly ManualResetEvent Complete = new(false);
    internal int Result = unchecked((int)0x80004005);
    internal object? AudioInterface;
    public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
    {
        try { int hr = operation.GetActivateResult(out Result, out AudioInterface); if (hr < 0) Result = hr; }
        catch (Exception error) { Result = error.HResult; }
        finally { Complete.Set(); }
        return 0;
    }
}
[ComImport, Guid("72a22d78-cde4-431d-b8cc-843a71199b6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IActivateAudioInterfaceAsyncOperation { [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object? activatedInterface); }
[ComImport, Guid("1cb9ad4c-dbfa-4c32-b178-c2f568a703b2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClient
{
    [PreserveSig] int Initialize(int shareMode, uint flags, long duration, long periodicity, in WaveFormat format, IntPtr sessionId);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint padding);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long normal, out long minimum);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(in Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}
[ComImport, Guid("c8adbd64-e71e-48a0-a4de-185c395cd317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioCaptureClient
{
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}
internal static class Native
{
    [DllImport("Mmdevapi.dll", ExactSpelling = true)] internal static extern int ActivateAudioInterfaceAsync(
        [MarshalAs(UnmanagedType.LPWStr)] string path, in Guid iid, IntPtr activation,
        IActivateAudioInterfaceCompletionHandler handler, out IActivateAudioInterfaceAsyncOperation operation);
    [DllImport("ole32.dll")] internal static extern int CoInitializeEx(IntPtr reserved, uint mode);
    [DllImport("ole32.dll")] internal static extern void CoUninitialize();
    [DllImport("kernel32.dll", SetLastError = true)] internal static extern IntPtr OpenProcess(uint access, [MarshalAs(UnmanagedType.Bool)] bool inherit, int processId);
    [DllImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)] internal static extern bool CloseHandle(IntPtr handle);
    [DllImport("ntdll.dll")] internal static extern int NtQueryInformationProcess(IntPtr process, int informationClass, IntPtr info, int length, out int returnedLength);
    internal static void Check(int result, string stage = "WASAPI") { if (result < 0) throw new COMException(stage + " failed", result); }
}
