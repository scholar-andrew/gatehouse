// Gatehouse uninstaller for Windows: undoes what GatehouseSetup.exe (once OctoberJourneySetup.exe) did, item by item.
// It still knows one pack, October Journey (its instance, shortcut names and server below, and the folder, file names
// and Settings > Apps key setup saves under that name): it works offline, without a profile to read. Removing another
// profile's pack needs setup to save that profile beside it first (docs/profiles.md, still to do). Built like the installer:
//   csc /target:exe /out:GatehouseUninstall.exe /r:System.Windows.Forms.dll GatehouseUninstall.cs Options.cs Nbt.cs
// The first screen offers a quick uninstall (everything it finds except Prism Launcher) or choosing item by item. It
// finds Prism Launcher, the instance and the saved installer and uninstaller wherever setup put them (Prism's own
// records and instances setting, the Settings > Apps entry). The items (all ticked except Prism Launcher):
//   shortcut     Steam shortcuts "Minecraft (Prism Launcher) - ..." and "October Journey - ..." that start Prism (every
//                Steam account; shortcuts.vdf is backed up first as shortcuts.vdf.bak-uninstall). Their art and
//                controller layouts go with them.
//   art          the shortcuts' library art (and the .before-pack copies the pack sync kept)
//   controller   the shortcuts' controller layout folders and their entries in Steam's layout list
//   server       the October Journey server in the instance's server list
//   instance     the Prism instance <Prism's instances folder>\october-journey (screenshots and any single-player
//                worlds in it go too). Removing it removes the server entry with it.
//   prism        Prism Launcher itself, by its own uninstaller (default off; shared with any other Prism instances)
//   installer    the installer saved by setup (default %LOCALAPPDATA%\OctoberJourney)
//   uninstaller  this uninstaller's saved copy and its Settings > Apps entry
// What's kept stays as it is; with the instance kept, the pack sync is told not to put back what was removed
// (pack-options.json). Steam must be closed while its files change; it asks. Nothing is downloaded.
// Log: %TEMP%\OctoberJourneyUninstall.log
// Flags: --yes (no questions: everything found except Prism; stops if Steam is running)
//        --skip=item,item (keep those)  --keep-instance  --remove-prism
//        --prism-root=DIR --steam-root=DIR (testing: use these folders instead of the real ones; Steam may stay open)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

static class Uninstall
{
    // Setup's two shortcuts (each followed by " - <computer name>"): the one that opens Prism Launcher, and the one that
    // starts the game and joins the server.
    static readonly string[] ShortcutName = { "Minecraft (Prism Launcher)", "October Journey" };
    const string InstanceId = "october-journey";
    const string Title = "Gatehouse uninstall";
    const string AppsKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney";
    static bool quiet;
    static StreamWriter log;

    static string Arg(string[] a, string k) { var x = a.FirstOrDefault(s => s.StartsWith(k + "=")); return x == null ? null : x.Substring(k.Length + 1); }
    static void Say(string s) { Console.WriteLine(s); log.WriteLine(DateTime.Now.ToString("s") + "  " + s); }

    class Found { public string Acct, Name; public uint AppId; }

    [STAThread]
    static int Main(string[] args)
    {
        quiet = args.Contains("--yes");
        string prismRootArg = Arg(args, "--prism-root"), steamRoot = Arg(args, "--steam-root");
        bool testing = prismRootArg != null;
        Options.ScreenshotPath = Arg(args, "--screenshot");
        var skip = new HashSet<string>((Arg(args, "--skip") ?? "").Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries).Select(s => s.Trim().ToLowerInvariant()));
        if (args.Contains("--keep-instance")) skip.Add("instance");
        string logPath = Path.Combine(Path.GetTempPath(), "GatehouseUninstall.log");
        log = new StreamWriter(logPath, false, Encoding.UTF8) { AutoFlush = true };
        try
        {
            Say("Gatehouse uninstall (October Journey)");

            // What's here.
            string steam = steamRoot ?? SteamPath();
            var found = new List<Found>();
            if (steam != null && Directory.Exists(Path.Combine(steam, "userdata")))
                foreach (string acctDir in Directory.GetDirectories(Path.Combine(steam, "userdata")))
                    foreach (var s in Shortcuts.Remove(Path.Combine(acctDir, "config", "shortcuts.vdf"), ShortcutName, false))
                        found.Add(new Found { Acct = Path.GetFileName(acctDir), Name = s.Name, AppId = s.AppId });
            Func<Found, string> grid = f => Path.Combine(steam, "userdata", f.Acct, "config", "grid");
            Func<Found, string> layoutRoot = f => Path.Combine(steam, "steamapps", "common", "Steam Controller Configs", f.Acct, "config");
            bool hasArt = found.Any(f => ArtFiles(grid(f), f.AppId).Any(File.Exists));
            bool hasLayout = found.Any(f => Directory.Exists(Path.Combine(layoutRoot(f), f.Name.ToLowerInvariant())));
            string inst = Path.Combine(InstancesDir(prismRootArg), InstanceId);
            string mc = Path.Combine(inst, "minecraft");
            bool hasInstance = Directory.Exists(inst);
            var addresses = new List<string>(); var names = new List<string> { "October Journey" };
            try
            {
                foreach (Dictionary<string, object> s in (object[])new JavaScriptSerializer().DeserializeObject(File.ReadAllText(Path.Combine(mc, "extras", "servers.json"))))
                { addresses.Add((string)s["address"]); names.Add((string)s["name"]); }
            }
            catch { addresses.Add("100.91.134.102:25565"); }
            bool hasServer = hasInstance && File.Exists(Path.Combine(mc, "servers.dat"));
            string prismDir = PrismDir();
            string prismUninstaller = prismDir == null ? null : Path.Combine(prismDir, "uninstall.exe");
            bool hasPrism = !testing && prismUninstaller != null && File.Exists(prismUninstaller);
            string toolsDir = ToolsDir(prismRootArg);
            bool hasSetup = toolsDir != null && File.Exists(Path.Combine(toolsDir, "OctoberJourneySetup.exe"));
            bool hasSelf = (toolsDir != null && File.Exists(Path.Combine(toolsDir, "OctoberJourneyUninstall.exe"))) || (!testing && Registry.CurrentUser.OpenSubKey(AppsKey) != null);

            // What to remove.
            var opts = new Options();
            string shortcutNames = string.Join(", ", found.Select(f => "\"" + f.Name + "\"").Distinct());
            if (found.Count > 0) opts.Add("shortcut", found.Count > 1 ? "Steam library shortcuts" : "Steam library shortcut", shortcutNames + ". Their art and controller layouts go with them.", true, true);
            if (hasArt) opts.Add("art", "Steam library art", "The shortcuts' cover, banner, logo and icon.", true, true, null, found.Count > 0 ? "shortcut" : null);
            if (hasLayout) opts.Add("controller", "Steam Deck controller layout", "The shortcuts' controller layouts and their entries in Steam's list.", true, true, null, found.Count > 0 ? "shortcut" : null);
            if (hasInstance) opts.Add("instance", "Game instance (the modpack)", inst + ". Its screenshots and any single-player worlds are deleted with it.", true, true);
            if (hasServer) opts.Add("server", "October Journey server", "The server's entry in the game's server list.", true, true, null, "instance");
            if (hasPrism) opts.Add("prism", "Prism Launcher", "The launcher itself, by its own uninstaller. Leave this off if you use Prism for anything else.", false, true);
            if (hasSetup) opts.Add("installer", "Saved installer", "The copy of setup in " + toolsDir + ".", true, true);
            if (hasSelf) opts.Add("uninstaller", "This uninstaller", "Its saved copy and its Settings > Apps entry.", true, true);
            if (!opts.Chosen().Any() && !opts.Has("prism"))
            {
                Say("Nothing of October Journey was found on this computer.");
                if (!quiet) MessageBox.Show("Nothing of October Journey was found on this computer.", Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
            if (args.Contains("--remove-prism") && opts.Has("prism")) skip.Remove("prism");
            opts.ApplySkips(skip);
            if (args.Contains("--remove-prism") && hasPrism) opts.ForceOn("prism");
            if (!quiet)
            {
                string mode = Options.AskQuick(Title, "Removes October Journey from this computer.",
                    "Quick uninstall (recommended)", "Everything October Journey added that's here: " + string.Join(", ", opts.Chosen().Select(Describe)) + ". Prism Launcher stays.",
                    "Choose what to remove...", "Pick the items; anything left unticked stays as it is.");
                if (mode == null || (mode == "custom" && !opts.ShowDialog(Title, "Choose what to remove from this computer. Anything left unticked stays as it is.", "Remove"))) { Say("Cancelled."); return 1; }
                Say("Uninstall mode: " + mode);
            }
            Say("Removing: " + string.Join(", ", opts.Chosen()));

            // Steam: shortcut, art, controller layout.
            bool steamWork = opts.On("shortcut") || opts.On("art") || opts.On("controller");
            if (steamWork && steamRoot == null) WaitForSteamClosed();
            if (opts.On("shortcut"))
                foreach (string acct in found.Select(f => f.Acct).Distinct())
                    foreach (var s in Shortcuts.Remove(Path.Combine(steam, "userdata", acct, "config", "shortcuts.vdf"), ShortcutName, true))
                        Say("Removed Steam shortcut \"" + s.Name + "\" (account " + acct + ").");
            foreach (var f in found)
            {
                if (opts.On("art")) RemoveArt(grid(f), f.AppId);
                if (opts.On("controller")) RemoveLayout(layoutRoot(f), f.Name.ToLowerInvariant());
            }

            // The instance, or just its server entry.
            if (opts.On("instance"))
            {
                Directory.Delete(inst, true);
                Say("Deleted the instance " + inst + ".");
            }
            else if (hasInstance)
            {
                if (opts.On("server")) Say("Removed " + Nbt.RemoveServers(Path.Combine(mc, "servers.dat"), addresses, names) + " server list entr(y/ies).");
                // Tell the pack sync not to put back what was removed.
                var po = new Dictionary<string, object> { { "steam_art", true }, { "steam_controller", true }, { "server_entry", true } };
                string poPath = Path.Combine(mc, "pack-options.json");
                try { foreach (var kv in (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(File.ReadAllText(poPath))) po[kv.Key] = kv.Value; } catch { }
                if (opts.On("art") || opts.On("shortcut")) po["steam_art"] = false;
                if (opts.On("controller") || opts.On("shortcut")) po["steam_controller"] = false;
                if (opts.On("server")) po["server_entry"] = false;
                File.WriteAllText(poPath, new JavaScriptSerializer().Serialize(po));
                if (opts.On("shortcut")) File.Delete(Path.Combine(mc, "steam-target.json"));
                Say("Kept the instance; updates on launch won't put back what was removed.");
            }

            // Prism Launcher.
            if (opts.On("prism"))
            {
                var p = Process.Start(prismUninstaller, "/S _?=" + prismDir);
                p.WaitForExit();
                Say("Prism Launcher uninstalled (exit " + p.ExitCode + ").");
            }

            // The saved installer and this uninstaller.
            RemoveTools(toolsDir, testing, opts.On("installer"), opts.On("uninstaller"));

            string done = "Done. " + (steamWork ? "Start Steam again whenever you like." : "");
            Say(done);
            if (!quiet) MessageBox.Show(done, Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
            // Only now, with its last window closed: the deletion of this program's own saved copy.
            if (afterExit != null) afterExit();
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

    static void RemoveTools(string dir, bool testing, bool installer, bool uninstaller)
    {
        if (dir == null) dir = Path.Combine(Path.GetTempPath(), "OctoberJourney-none");   // nothing saved: only the registry entry
        // The whole folder is only deleted when it is our own (named OctoberJourney) and holds nothing else; a folder the
        // player picked for these files may have been theirs already.
        string setup = Path.Combine(dir, "OctoberJourneySetup.exe");
        if (installer && File.Exists(setup)) { File.Delete(setup); Say("Removed the saved installer."); }
        if (!testing)
            using (var k = Registry.CurrentUser.OpenSubKey(AppsKey, true))
                if (k != null)
                {
                    if (uninstaller) { k.Close(); Registry.CurrentUser.DeleteSubKeyTree(AppsKey, false); Say("Removed the Settings > Apps entry."); }
                    else if (installer) { k.DeleteValue("ModifyPath", false); k.SetValue("NoModify", 1, RegistryValueKind.DWord); }
                }
        if (!uninstaller || !Directory.Exists(dir)) return;
        // With both gone, the marker goes too.
        if (!File.Exists(setup)) { var marker = Path.Combine(dir, "OctoberJourney.txt"); if (File.Exists(marker)) File.Delete(marker); }
        if (Directory.GetFileSystemEntries(dir).Any(f => !Path.GetFileName(f).Equals("OctoberJourneyUninstall.exe", StringComparison.OrdinalIgnoreCase)))
        {
            // The installer is being kept: delete only this program.
            DeleteLater(Path.Combine(dir, "OctoberJourneyUninstall.exe"), false);
            return;
        }
        DeleteLater(Path.GetFileName(dir.TrimEnd('\\')).Equals("OctoberJourney", StringComparison.OrdinalIgnoreCase) ? dir : Path.Combine(dir, "OctoberJourneyUninstall.exe"), Path.GetFileName(dir.TrimEnd('\\')).Equals("OctoberJourney", StringComparison.OrdinalIgnoreCase));
    }

    // A running program can't delete itself: when it is the one running, a hidden command deletes it a few seconds after
    // this exits.
    static void DeleteLater(string path, bool isDir)
    {
        string me = Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName);
        bool running = isDir ? me.StartsWith(Path.GetFullPath(path) + "\\", StringComparison.OrdinalIgnoreCase) : me.Equals(Path.GetFullPath(path), StringComparison.OrdinalIgnoreCase);
        if (running)
        {
            string cmd = isDir ? "rmdir /s /q \"" + path + "\"" : "del /f /q \"" + path + "\" & rmdir \"" + Path.GetDirectoryName(path) + "\" 2>nul";
            // Started once this program's last window has closed (see Main), then a few seconds' wait for it to exit.
            afterExit = () => Process.Start(new ProcessStartInfo("cmd.exe", "/c ping 127.0.0.1 -n 4 >nul & " + cmd) { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            Say("The saved uninstaller is deleted once this window closes.");
        }
        else
        {
            if (isDir) Directory.Delete(path, true);
            else
            {
                if (File.Exists(path)) File.Delete(path);
                string parent = Path.GetDirectoryName(path);
                if (Directory.Exists(parent) && !Directory.EnumerateFileSystemEntries(parent).Any()) Directory.Delete(parent);   // only when empty
            }
            Say("Removed the saved uninstaller.");
        }
    }

    static string Describe(string key)
    {
        switch (key)
        {
            case "shortcut": return "the Steam shortcut";
            case "art": return "its art";
            case "controller": return "its controller layout";
            case "instance": return "the game instance";
            case "server": return "the server entry";
            case "installer": return "the saved installer";
            case "uninstaller": return "this uninstaller";
            default: return key;
        }
    }

    // Prism Launcher's folder: the usual one, or wherever its Windows installer recorded it. Null when not installed.
    static string PrismDir()
    {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "PrismLauncher");
        if (File.Exists(Path.Combine(dir, "uninstall.exe"))) return dir;
        foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine })
            using (var root = hive.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall"))
                if (root != null)
                    foreach (var name in root.GetSubKeyNames())
                        using (var k = root.OpenSubKey(name))
                        {
                            if (k == null || !((k.GetValue("DisplayName") as string) ?? "").StartsWith("Prism Launcher")) continue;
                            string d = k.GetValue("InstallLocation") as string;
                            if (string.IsNullOrEmpty(d)) { var u = ((k.GetValue("UninstallString") as string) ?? "").Trim(); if (u.StartsWith("\"")) u = u.Substring(1, u.IndexOf('"', 1) - 1); d = Path.GetDirectoryName(u); }
                            if (!string.IsNullOrEmpty(d) && File.Exists(Path.Combine(d, "uninstall.exe"))) return d;
                        }
        return null;
    }

    // Prism Launcher's instances folder: its data folder's "instances", unless Prism's own settings name another.
    static string InstancesDir(string prismRootArg)
    {
        string data = prismRootArg ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PrismLauncher");
        string cfg = Path.Combine(data, "prismlauncher.cfg");
        if (File.Exists(cfg))
            foreach (var line in File.ReadAllLines(cfg))
                if (line.StartsWith("InstanceDir="))
                {
                    string v = line.Substring("InstanceDir=".Length).Trim().Trim('"').Replace('/', '\\');
                    if (v.Length > 0) return Path.IsPathRooted(v) ? v : Path.Combine(data, v);
                }
        return Path.Combine(data, "instances");
    }

    // Where setup saved the uninstaller and installer: the Settings > Apps entry says; failing that, this program's own
    // folder if setup marked it (OctoberJourney.txt); failing that, the default folder if setup marked it. Null when there
    // is none: a copy run from Downloads, say, never treats Downloads as its own.
    static string ToolsDir(string prismRootArg)
    {
        if (prismRootArg == null)
            using (var k = Registry.CurrentUser.OpenSubKey(AppsKey))
            {
                string d = k == null ? null : k.GetValue("InstallLocation") as string;
                if (!string.IsNullOrEmpty(d) && Directory.Exists(d)) return d;
            }
        string me = Path.GetDirectoryName(Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName));
        if (File.Exists(Path.Combine(me, "OctoberJourney.txt"))) return me;
        string dflt = prismRootArg != null ? Path.Combine(prismRootArg, "OctoberJourney") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OctoberJourney");
        return File.Exists(Path.Combine(dflt, "OctoberJourney.txt")) ? dflt : null;
    }

    static Action afterExit;

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
            var r = MessageBox.Show("Please quit Steam (Steam menu, then Exit), then click OK.\n\nSteam has to be closed while its files are changed.", Title, MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
            if (r == DialogResult.Cancel) throw new Exception("cancelled while waiting for Steam to close");
            System.Threading.Thread.Sleep(1500);
        }
    }

    // The five art files setup and the pack sync put there, and the copies the sync kept of what was there before.
    static IEnumerable<string> ArtFiles(string grid, uint appid)
    {
        foreach (var f in new[] { appid + "p.png", appid + "_hero.png", appid + "_logo.png", appid + "_icon.png", appid + ".png" })
        { yield return Path.Combine(grid, f); yield return Path.Combine(grid, f + ".before-pack"); }
    }

    static void RemoveArt(string grid, uint appid)
    {
        int n = 0;
        foreach (var p in ArtFiles(grid, appid)) if (File.Exists(p)) { File.Delete(p); n++; }
        if (n > 0) Say("Removed " + n + " library art file(s).");
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

    // Finds (write: false) or removes (write: true) every shortcut whose name starts with one of namePrefixes and that
    // starts Prism Launcher; returns them.
    public static List<Removed> Remove(string path, string[] namePrefixes, bool write)
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
            if (prism && namePrefixes.Any(p => name.StartsWith(p, StringComparison.Ordinal)))
            {
                var id = e.FirstOrDefault(x => string.Equals(x.Key, "appid", StringComparison.OrdinalIgnoreCase));
                removed.Add(new Removed { Name = name, AppId = id == null ? 0 : unchecked((uint)(int)id.Value) });
            }
            else keep.Add(n);
        }
        if (removed.Count == 0 || !write) return removed;
        for (int i = 0; i < keep.Count; i++) keep[i].Key = i.ToString();
        top.Value = keep;
        File.Copy(path, path + ".bak-uninstall", true);
        using (var w = new BinaryWriter(File.Create(path))) Write(w, root);
        return removed;
    }
}
