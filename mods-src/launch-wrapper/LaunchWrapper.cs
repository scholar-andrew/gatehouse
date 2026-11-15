// The launch wrapper for Windows (Prism Launcher's "wrapper command" for the October Journey instance).
//
// Prism starts the game as:  launch-wrapper.exe <java> <java's arguments...>
// Normally this just runs that command, untouched, and ends with its exit code.
// When the player pressed Quit in the launcher, the pre-launch script left a file called "launch-cancelled" beside this
// program: then nothing is started and this ends with exit code 0, so Prism sees a launch that ended normally instead of
// a failed one (a failed pre-launch step makes Prism open its log window).
//
// The command line is handed on exactly as received (no re-quoting), with this program's own input and output (the
// pipes Prism talks to the game through), and the game is tied to this program: if Prism kills the wrapper, the game
// goes with it.
//
// Build: tools\build_wrapper.ps1  (a window-less program, so no console flashes up)
using System;
using System.IO;
using System.Runtime.InteropServices;

static class LaunchWrapper
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct STARTUPINFO
    {
        public int cb; public string lpReserved, lpDesktop, lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit; public uint LimitFlags; public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit; public UIntPtr Affinity; public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct IO_COUNTERS { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount; }
    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation; public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CreateProcessW(string app, System.Text.StringBuilder commandLine, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int n);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr h, out uint code);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObjectW(IntPtr a, string name);
    [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int cls, ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION info, int len);
    [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    static int Main()
    {
        string marker = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "launch-cancelled");
        if (File.Exists(marker))
        {
            try { File.Delete(marker); } catch { }
            return 0;
        }
        // Everything after this program's own name, exactly as it came.
        string all = Environment.CommandLine;
        int i;
        if (all.StartsWith("\"")) { i = all.IndexOf('"', 1); i = i < 0 ? all.Length : i + 1; }
        else { i = all.IndexOf(' '); if (i < 0) i = all.Length; }
        string rest = all.Substring(i).TrimStart();
        if (rest.Length == 0) return 2;

        var si = new STARTUPINFO();
        si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        si.dwFlags = 0x100;   // STARTF_USESTDHANDLES: the game gets the same input and output as this program
        si.hStdInput = GetStdHandle(-10); si.hStdOutput = GetStdHandle(-11); si.hStdError = GetStdHandle(-12);
        PROCESS_INFORMATION pi;
        // Started suspended, tied to this program (a job that ends its processes when this one goes), then let run.
        if (!CreateProcessW(null, new System.Text.StringBuilder(rest), IntPtr.Zero, IntPtr.Zero, true, 0x4 /* CREATE_SUSPENDED */, IntPtr.Zero, null, ref si, out pi))
            return 3;
        try
        {
            IntPtr job = CreateJobObjectW(IntPtr.Zero, null);
            if (job != IntPtr.Zero)
            {
                var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                info.BasicLimitInformation.LimitFlags = 0x2000;   // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if (SetInformationJobObject(job, 9, ref info, Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION)))) AssignProcessToJobObject(job, pi.hProcess);
            }
        }
        catch { }
        ResumeThread(pi.hThread);
        WaitForSingleObject(pi.hProcess, 0xFFFFFFFF);
        uint code;
        return GetExitCodeProcess(pi.hProcess, out code) ? (int)code : 1;
    }
}
