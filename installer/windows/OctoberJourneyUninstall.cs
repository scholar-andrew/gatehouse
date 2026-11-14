// October Journey uninstaller for Windows: undoes what OctoberJourneySetup.exe did. Built like the installer:
//   csc /target:exe /out:OctoberJourneyUninstall.exe /r:System.Windows.Forms.dll OctoberJourneyUninstall.cs
// It removes, for every Steam account on this computer:
//   - Steam shortcuts named "Minecraft (Prism Launcher)..." that start Prism Launcher (shortcuts.vdf is backed up first
//     as shortcuts.vdf.bak-uninstall), with their library art and their controller layout folder and list entry;
// and then, after asking:
//   - the Prism instance %APPDATA%\PrismLauncher\instances\october-journey (screenshots and any single-player worlds
//     in it go too; answer No to keep it);
//   - Prism Launcher itself (default No; it is shared with any other Prism instances you have);
// and last, its own entry in Settings > Apps and its copy in %LOCALAPPDATA%\OctoberJourney (put there by setup).
// Steam must be closed while its files are changed; it asks. Nothing is downloaded. Log: %TEMP%\OctoberJourneyUninstall.log
// Flags: --yes (no questions: removes the shortcut, art, layout and instance, keeps Prism; stops if Steam is running)
//        --keep-instance  --remove-prism
//        --prism-root=DIR --steam-root=DIR (testing: use these folders instead of the real ones; Steam may stay open)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

static class Uninstall
{
    const string ShortcutName = "Minecraft (Prism Launcher)";
    const string InstanceId = "october-journey";
    const string Title = "October Journey uninstall";
    static bool quiet;
    static StreamWriter log;

    static string Arg(string[] a, string k) { var x = a.FirstOrDefault(s => s.StartsWith(k + "=")); return x == null ? null : x.Substring(k.Length + 1); }
    static void Say(string s) { Console.WriteLine(s); log.WriteLine(DateTime.Now.ToString("s") + "  " + s); }
    static bool Ask(string q, bool dflt)
    {
        if (quiet) return dflt;
        return MessageBox.Show(q, Title, MessageBoxButtons.YesNo, MessageBoxIcon.Question, dflt ? MessageBoxDefaultButton.Button1 : MessageBoxDefaultButton.Button2) == DialogResult.Yes;
    }

    [STAThread]
    static int Main(string[] args)
    {
        quiet = args.Contains("--yes");
        string prismRootArg = Arg(args, "--prism-root"), steamRoot = Arg(args, "--steam-root");
        string logPath = Path.Combine(Path.GetTempPath(), "OctoberJourneyUninstall.log");
        log = new StreamWriter(logPath, false, Encoding.UTF8) { AutoFlush = true };
        try
        {
            Say("October Journey uninstall");
            if (!quiet && MessageBox.Show("This removes October Journey from this computer:\n\n" +
                    "- the Steam shortcut \"" + ShortcutName + " - ...\", its library art and its controller layout\n" +
                    "- the Prism Launcher instance \"October Journey\" (you'll be asked first)\n" +
                    "- Prism Launcher itself, only if you say so\n\nContinue?", Title, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK)
            { Say("Cancelled."); return 1; }

            // 1. Steam: shortcut, art, controller layout.
            string steam = steamRoot ?? SteamPath();
            if (steam == null) Say("Steam is not installed; nothing to remove there.");
            else
            {
                if (steamRoot == null) WaitForSteamClosed();
                string userdata = Path.Combine(steam, "userdata");
                foreach (string acctDir in Directory.Exists(userdata) ? Directory.GetDirectories(userdata) : new string[0])
                {
                    string acct = Path.GetFileName(acctDir);
                    string cfgDir = Path.Combine(acctDir, "config");
                    foreach (var s in Shortcuts.Remove(Path.Combine(cfgDir, "shortcuts.vdf"), ShortcutName))
                    {
                        Say("Removed Steam shortcut \"" + s.Name + "\" (account " + acct + ").");
                        RemoveArt(Path.Combine(cfgDir, "grid"), s.AppId);
                        RemoveLayout(Path.Combine(steam, "steamapps", "common", "Steam Controller Configs", acct, "config"), s.Name.ToLowerInvariant());
                    }
                }
            }

            // 2. The Prism instance.
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            string inst = Path.Combine(prismRootArg ?? Path.Combine(appData, "PrismLauncher"), "instances", InstanceId);
            if (!Directory.Exists(inst)) Say("No October Journey instance at " + inst + ".");
            else if (!args.Contains("--keep-instance") && Ask("Delete the October Journey instance?\n\n" + inst + "\n\nIts screenshots and any single-player worlds are deleted with it. Choose No to keep the folder.", true))
            {
                Directory.Delete(inst, true);
                Say("Deleted the instance " + inst + ".");
            }
            else Say("Kept the instance " + inst + ".");

            // 3. Prism Launcher itself.
            string prismDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "PrismLauncher");
            string uninstaller = Path.Combine(prismDir, "uninstall.exe");
            if (prismRootArg != null) Say("Testing: Prism Launcher left installed.");
            else if (!File.Exists(uninstaller)) Say("Prism Launcher's own uninstaller was not found; Prism (if installed elsewhere) is left alone.");
            else if (args.Contains("--remove-prism") || Ask("Also uninstall Prism Launcher?\n\nChoose No if you use Prism for anything else. This runs Prism's own uninstaller.", false))
            {
                var p = Process.Start(uninstaller, "/S _?=" + prismDir);
                p.WaitForExit();
                Say("Prism Launcher uninstalled (exit " + p.ExitCode + ").");
            }
            else Say("Kept Prism Launcher.");

            // 4. Its own Settings > Apps entry and saved copy.
            RemoveSelf(prismRootArg);

            string done = "October Journey has been removed. Start Steam again whenever you like.";
            Say(done);
            if (!quiet) MessageBox.Show(done, Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 0;
        }
        catch (Exception e)
        {
            Say("Uninstall stopped: " + e.Message);
            log.WriteLine(e.ToString());
            if (!quiet) MessageBox.Show("Uninstall stopped: " + e.Message + "\n\nDetails are in " + logPath, Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    static void RemoveSelf(string prismRootArg)
    {
        string dir = prismRootArg != null ? Path.Combine(prismRootArg, "OctoberJourney") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OctoberJourney");
        if (prismRootArg == null)
        {
            Registry.CurrentUser.DeleteSubKeyTree(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney", false);
            Say("Removed the Settings > Apps entry.");
        }
        if (!Directory.Exists(dir)) return;
        string me = Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName);
        if (me.StartsWith(Path.GetFullPath(dir) + "\\", StringComparison.OrdinalIgnoreCase))
        {
            // A running program can't delete itself: a hidden command deletes the folder a few seconds after this exits.
            Process.Start(new ProcessStartInfo("cmd.exe", "/c ping 127.0.0.1 -n 4 >nul & rmdir /s /q \"" + dir + "\"") { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            Say("The saved uninstaller (" + dir + ") is deleted once this window closes.");
        }
        else { Directory.Delete(dir, true); Say("Removed the saved uninstaller " + dir + "."); }
    }

    static string SteamPath()
    {
        using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"))
        {
            var p = k == null ? null : k.GetValue("SteamPath") as string;
            return p != null && Directory.Exists(p) ? p.Replace('/', '\\') : null;
        }
    }

    static void WaitForSteamClosed()
    {
        while (Process.GetProcessesByName("steam").Length > 0)
        {
            if (quiet) throw new Exception("Steam is running; quit Steam and run the uninstaller again");
            var r = MessageBox.Show("Please quit Steam (Steam menu, then Exit), then click OK.\n\nSteam has to be closed while its shortcut list is changed.", Title, MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
            if (r == DialogResult.Cancel) throw new Exception("cancelled while waiting for Steam to close");
            System.Threading.Thread.Sleep(1500);
        }
    }

    // The five art files setup and the pack sync put there, and the copies the sync kept of what was there before.
    static void RemoveArt(string grid, uint appid)
    {
        int n = 0;
        foreach (var f in new[] { appid + "p.png", appid + "_hero.png", appid + "_logo.png", appid + "_icon.png", appid + ".png" })
            foreach (var p in new[] { Path.Combine(grid, f), Path.Combine(grid, f + ".before-pack") })
                if (File.Exists(p)) { File.Delete(p); n++; }
        Say("Removed " + n + " library art file(s).");
    }

    static void RemoveLayout(string root, string lowerName)
    {
        string dir = Path.Combine(root, lowerName);
        if (Directory.Exists(dir)) { Directory.Delete(dir, true); Say("Removed the controller layout folder."); }
        string set = Path.Combine(root, "configset_controller_neptune.vdf");
        if (!File.Exists(set)) return;
        string text = File.ReadAllText(set);
        string updated = Regex.Replace(text, "[ \\t]*\"" + Regex.Escape(lowerName) + "\"\\s*\\{[^{}]*\\}[ \\t]*\\r?\\n?", "", RegexOptions.IgnoreCase);
        if (updated != text) { File.WriteAllText(set, updated); Say("Removed it from Steam's controller layout list."); }
    }
}

// Steam's binary shortcuts.vdf.
static class Shortcuts
{
    public class Removed { public string Name; public uint AppId; }
    class Node { public byte Type; public string Key; public object Value; }

    static List<Node> Read(BinaryReader r)
    {
        var list = new List<Node>();
        while (true)
        {
            byte t = r.ReadByte();
            if (t == 8) return list;
            var n = new Node { Type = t, Key = Str(r) };
            if (t == 0) n.Value = Read(r);
            else if (t == 1) n.Value = Str(r);
            else if (t == 2) n.Value = r.ReadInt32();
            else throw new Exception("unexpected shortcuts.vdf entry type " + t);
            list.Add(n);
        }
    }
    static string Str(BinaryReader r) { var b = new List<byte>(); byte c; while ((c = r.ReadByte()) != 0) b.Add(c); return Encoding.UTF8.GetString(b.ToArray()); }
    static void Write(BinaryWriter w, List<Node> list)
    {
        foreach (var n in list)
        {
            w.Write(n.Type); w.Write(Encoding.UTF8.GetBytes(n.Key)); w.Write((byte)0);
            if (n.Type == 0) Write(w, (List<Node>)n.Value);
            else if (n.Type == 1) { w.Write(Encoding.UTF8.GetBytes((string)n.Value)); w.Write((byte)0); }
            else w.Write((int)n.Value);
        }
        w.Write((byte)8);
    }
    static string Field(List<Node> e, string key) { var n = e.FirstOrDefault(x => string.Equals(x.Key, key, StringComparison.OrdinalIgnoreCase)); return n == null ? null : n.Value as string; }

    // Removes every shortcut whose name starts with namePrefix and that starts Prism Launcher; returns what was removed.
    public static List<Removed> Remove(string path, string namePrefix)
    {
        var removed = new List<Removed>();
        if (!File.Exists(path)) return removed;
        List<Node> root;
        using (var r = new BinaryReader(File.OpenRead(path))) root = Read(r);
        var top = root.FirstOrDefault(n => string.Equals(n.Key, "shortcuts", StringComparison.OrdinalIgnoreCase));
        if (top == null) return removed;
        var list = (List<Node>)top.Value;
        var keep = new List<Node>();
        foreach (var n in list)
        {
            var e = (List<Node>)n.Value;
            string name = Field(e, "AppName") ?? "", exe = Field(e, "Exe") ?? "", opts = Field(e, "LaunchOptions") ?? "";
            bool prism = exe.IndexOf("prismlauncher", StringComparison.OrdinalIgnoreCase) >= 0 || opts.IndexOf("org.prismlauncher.PrismLauncher", StringComparison.OrdinalIgnoreCase) >= 0;
            if (prism && name.StartsWith(namePrefix, StringComparison.Ordinal))
            {
                var id = e.FirstOrDefault(x => string.Equals(x.Key, "appid", StringComparison.OrdinalIgnoreCase));
                removed.Add(new Removed { Name = name, AppId = id == null ? 0 : unchecked((uint)(int)id.Value) });
            }
            else keep.Add(n);
        }
        if (removed.Count == 0) return removed;
        for (int i = 0; i < keep.Count; i++) keep[i].Key = i.ToString();
        top.Value = keep;
        File.Copy(path, path + ".bak-uninstall", true);
        using (var w = new BinaryWriter(File.Create(path))) Write(w, root);
        return removed;
    }
}
