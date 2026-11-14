// October Journey setup for Windows. Built with the C# compiler that ships with Windows (.NET Framework 4.x):
//   csc /target:exe /out:OctoberJourneySetup.exe /r:System.Web.Extensions.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll OctoberJourneySetup.cs
// What it does, reading installer/config.json from the GitHub repo each run:
//   1. Prism Launcher (installs it if missing, checking the installer's SHA-256), the October Journey instance with the
//      pack sync, and the server list entry.
//   2. A Steam shortcut "Minecraft (Prism Launcher) - <this computer>" (Steam must be closed; it asks).
//   3. Steam library art (original to this pack, from the repo).
//   4. The Steam Deck controller layout for the shortcut.
//   5. The uninstaller, saved to %LOCALAPPDATA%\OctoberJourney and listed in Settings > Apps (current user only).
//   6. Every launch syncs the pack (this owner's PC over Tailscale first, then GitHub), and with it any new library art or
//      controller layout.
// Flags: --yes  (no questions; stops if Steam is running)
//        --prism-root=DIR --steam-root=DIR  (testing: use these folders instead of the real ones; Steam may stay open)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

static class Setup
{
    const string ConfigUrl = "https://raw.githubusercontent.com/scholar-andrew/october-journey-pack/main/installer/config.json";
    static bool quiet;
    static string prismRoot, steamRoot;
    static string Arg(string[] a, string k) { var x = a.FirstOrDefault(s => s.StartsWith(k + "=")); return x == null ? null : x.Substring(k.Length + 1); }
    static StreamWriter log;

    [STAThread]
    static int Main(string[] args)
    {
        quiet = args.Contains("--yes");
        prismRoot = Arg(args, "--prism-root");
        steamRoot = Arg(args, "--steam-root");
        string logPath = Path.Combine(Path.GetTempPath(), "OctoberJourneySetup.log");
        log = new StreamWriter(logPath, false, Encoding.UTF8);
        log.AutoFlush = true;
        try
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Say("October Journey setup");
            var cfg = (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(Text(ConfigUrl));
            string repo = (string)cfg["repo_raw"];

            // 1. Prism, the instance, the server list.
            string prism = EnsurePrism((Dictionary<string, object>)cfg["prism_windows"]);
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            string inst = Path.Combine(prismRoot ?? Path.Combine(appData, "PrismLauncher"), "instances", (string)cfg["instance_id"]);
            string mc = Path.Combine(inst, "minecraft");
            Directory.CreateDirectory(mc);
            File.WriteAllBytes(Path.Combine(inst, "mmc-pack.json"), Bytes(repo + "/clients/mmc-pack.json"));
            WriteInstanceCfg(Path.Combine(inst, "instance.cfg"), (string)cfg["instance_name"]);
            File.WriteAllBytes(Path.Combine(mc, "pack-sync.ps1"), Bytes(repo + "/clients/pack-sync.ps1"));
            var jar = Bytes(repo + "/clients/packwiz-installer-bootstrap.jar");
            if (!Sha256(jar).Equals((string)cfg["bootstrap_sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("packwiz-installer-bootstrap.jar does not match its checksum");
            File.WriteAllBytes(Path.Combine(mc, "packwiz-installer-bootstrap.jar"), jar);
            var sources = new StringBuilder("# Tried in order; the first that answers is used.\n");
            foreach (var s in (object[])cfg["pack_sources"]) sources.Append((string)s).Append('\n');
            File.WriteAllText(Path.Combine(mc, "pack-sources.txt"), sources.ToString());
            foreach (Dictionary<string, object> srv in (object[])cfg["servers"]) Nbt.AddServer(Path.Combine(mc, "servers.dat"), (string)srv["name"], (string)srv["address"]);
            Say("Prism instance ready: " + inst);
            AddUninstaller(cfg, prism);

            // 2-4. Steam.
            string steam = steamRoot ?? SteamPath();
            if (steam == null) { Say("Steam is not installed, so no Steam shortcut was added. Open Prism Launcher to play."); Done(); return 0; }
            string acct = SteamAccount(steam);
            if (steamRoot == null) WaitForSteamClosed();
            string name = (string)cfg["shortcut_name"] + " - " + DeviceName();
            string cfgDir = Path.Combine(steam, "userdata", acct, "config");
            Directory.CreateDirectory(cfgDir);
            string iconPath = Path.Combine(cfgDir, "grid", "{0}_icon.png");
            uint appid = Shortcuts.Upsert(Path.Combine(cfgDir, "shortcuts.vdf"), name, prism, iconPath);
            Say("Steam shortcut: " + name + " (app " + appid + ")");
            Art(Path.Combine(cfgDir, "grid"), repo, appid, (Dictionary<string, object>)cfg["art"]);
            Controller(steam, acct, name.ToLowerInvariant(), repo);
            // Where the pack sync puts later art and layout updates for this shortcut.
            string ctrl = Path.Combine(steam, "steamapps", "common", "Steam Controller Configs", acct, "config", name.ToLowerInvariant());
            File.WriteAllText(Path.Combine(mc, "steam-target.json"), new JavaScriptSerializer().Serialize(new Dictionary<string, object> { { "grid", Path.Combine(cfgDir, "grid") }, { "appid", appid }, { "controller", ctrl } }));
            Done();
            return 0;
        }
        catch (Exception e)
        {
            Say("Setup stopped: " + e.Message);
            log.WriteLine(e.ToString());
            if (!quiet) MessageBox.Show("Setup stopped: " + e.Message + "\n\nDetails are in " + logPath, "October Journey setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    // The uninstaller, kept on this computer and listed in Settings > Apps for this user. Checked against config.json's
    // SHA-256; if it can't be fetched or doesn't match, setup carries on without it (it's also on the release page).
    static void AddUninstaller(Dictionary<string, object> cfg, string prismExe)
    {
        try
        {
            var u = (Dictionary<string, object>)cfg["uninstall_windows"];
            var b = Bytes((string)u["url"]);
            if (!Sha256(b).Equals((string)u["sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("it does not match its checksum");
            bool testing = prismRoot != null;
            string dir = testing ? Path.Combine(prismRoot, "OctoberJourney") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OctoberJourney");
            Directory.CreateDirectory(dir);
            string exe = Path.Combine(dir, "OctoberJourneyUninstall.exe");
            File.WriteAllBytes(exe, b);
            if (testing) { Say("Uninstaller saved: " + exe + " (testing: not listed in Settings > Apps)."); return; }
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney"))
            {
                k.SetValue("DisplayName", "October Journey (Minecraft modpack)");
                k.SetValue("Publisher", "October Journey");
                k.SetValue("DisplayIcon", prismExe);
                k.SetValue("InstallLocation", dir);
                k.SetValue("UninstallString", "\"" + exe + "\"");
                k.SetValue("QuietUninstallString", "\"" + exe + "\" --yes");
                k.SetValue("URLInfoAbout", "https://github.com/scholar-andrew/october-journey-pack");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
            Say("Uninstaller saved: " + exe + ", listed in Settings > Apps as \"October Journey (Minecraft modpack)\".");
        }
        catch (Exception e) { Say("Uninstaller not added (" + e.Message + "); it can be downloaded from the release page."); }
    }

    static void Done()
    {
        string msg = "All set.\n\nStart Steam and play \"Minecraft (Prism Launcher) - " + DeviceName() + "\". The first time Prism opens, sign in with your Microsoft account. Every launch updates the mods.";
        Say(msg.Replace("\n\n", " "));
        if (!quiet) MessageBox.Show(msg, "October Journey setup", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    static void Say(string s) { Console.WriteLine(s); log.WriteLine(DateTime.Now.ToString("s") + "  " + s); }

    // ------------------------------------------------------------------ downloads
    static byte[] Bytes(string url)
    {
        using (var wc = new WebClient())
        {
            wc.Headers[HttpRequestHeader.UserAgent] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) OctoberJourneySetup/1.0";
            return wc.DownloadData(url);
        }
    }
    static string Text(string url) { return Encoding.UTF8.GetString(Bytes(url)); }
    static string Sha256(byte[] b) { using (var h = SHA256.Create()) return BitConverter.ToString(h.ComputeHash(b)).Replace("-", ""); }

    // ------------------------------------------------------------------ Prism
    static string EnsurePrism(Dictionary<string, object> p)
    {
        string exe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "PrismLauncher", "prismlauncher.exe");
        if (File.Exists(exe)) { Say("Prism Launcher is already installed."); return exe; }
        Say("Downloading Prism Launcher " + p["version"] + "...");
        var b = Bytes((string)p["url"]);
        if (!Sha256(b).Equals((string)p["sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("the Prism Launcher installer does not match its checksum");
        string setup = Path.Combine(Path.GetTempPath(), "PrismLauncher-Setup.exe");
        File.WriteAllBytes(setup, b);
        var proc = Process.Start(setup, "/S");
        proc.WaitForExit();
        if (!File.Exists(exe)) throw new Exception("Prism Launcher did not install (exit " + proc.ExitCode + ")");
        Say("Prism Launcher installed.");
        return exe;
    }

    static void WriteInstanceCfg(string path, string name)
    {
        var lines = File.Exists(path) ? File.ReadAllLines(path).ToList() : new List<string> { "[General]", "ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default" };
        lines.RemoveAll(l => l.StartsWith("name=") || l.StartsWith("OverrideCommands=") || l.StartsWith("PreLaunchCommand="));
        int at = lines.IndexOf("[General]");
        if (at < 0) { lines.Insert(0, "[General]"); at = 0; }
        lines.Insert(at + 1, "name=" + name);
        lines.Insert(at + 2, "OverrideCommands=true");
        lines.Insert(at + 3, "PreLaunchCommand=\"powershell -NoProfile -ExecutionPolicy Bypass -File \\\"$INST_MC_DIR\\\\pack-sync.ps1\\\"\"");
        File.WriteAllLines(path, lines);
    }

    // ------------------------------------------------------------------ Steam
    static string SteamPath()
    {
        using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"))
        {
            var p = k == null ? null : k.GetValue("SteamPath") as string;
            return p != null && Directory.Exists(p) ? p.Replace('/', '\\') : null;
        }
    }

    static string SteamAccount(string steam)
    {
        string f = Path.Combine(steam, "config", "loginusers.vdf");
        string best = null;
        if (File.Exists(f))
        {
            foreach (Match m in Regex.Matches(File.ReadAllText(f), "\"(7656\\d{13})\"\\s*\\{(.*?)\\}", RegexOptions.Singleline))
                if (best == null || Regex.IsMatch(m.Groups[2].Value, "\"MostRecent\"\\s*\"1\"")) best = m.Groups[1].Value;
        }
        if (best == null) throw new Exception("could not find your Steam account; sign in to Steam once, quit it, and run setup again");
        return (ulong.Parse(best) - 76561197960265728UL).ToString();
    }

    static void WaitForSteamClosed()
    {
        while (Process.GetProcessesByName("steam").Length > 0)
        {
            if (quiet) throw new Exception("Steam is running; quit Steam and run setup again");
            var r = MessageBox.Show("Please quit Steam (Steam menu, then Exit), then click OK.\n\nSteam has to be closed while the shortcut, art and controller layout are added.", "October Journey setup", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
            if (r == DialogResult.Cancel) throw new Exception("cancelled while waiting for Steam to close");
            System.Threading.Thread.Sleep(1500);
        }
    }

    static string DeviceName()
    {
        string n = System.Net.Dns.GetHostName();
        return string.IsNullOrEmpty(n) ? Environment.MachineName : n;
    }

    // The library art, original to this pack and kept in the repo: cover, hero, logo, icon and wide capsule.
    static void Art(string grid, string repo, uint appid, Dictionary<string, object> art)
    {
        Directory.CreateDirectory(grid);
        try
        {
            var files = new Dictionary<string, string> { { "cover", appid + "p.png" }, { "hero", appid + "_hero.png" }, { "logo", appid + "_logo.png" }, { "icon", appid + "_icon.png" }, { "wide", appid + ".png" } };
            foreach (var f in files) File.WriteAllBytes(Path.Combine(grid, f.Value), Bytes(repo + "/" + (string)art[f.Key]));
            Say("Library art added.");
        }
        catch (Exception e) { Say("Library art skipped: " + e.Message); }
    }

    static void Controller(string steam, string acct, string lowerName, string repo)
    {
        try
        {
            string root = Path.Combine(steam, "steamapps", "common", "Steam Controller Configs", acct, "config");
            string dir = Path.Combine(root, lowerName);
            Directory.CreateDirectory(dir);
            string target = Path.Combine(dir, "controller_neptune.vdf");
            string layout = Text(repo + "/installer/steam/controller_neptune.vdf");
            layout = Regex.Replace(layout, "\"url\"\\s*\"[^\"]*\"", "\"url\"\t\t\"autosave://" + target.Replace("\\", "/") + "\"");
            if (File.Exists(target)) File.Copy(target, target + ".bak-setup", true);
            File.WriteAllText(target, layout);
            string set = Path.Combine(root, "configset_controller_neptune.vdf");
            string text = File.Exists(set) ? File.ReadAllText(set) : "\"controller_config\"\n{\n}\n";
            if (text.IndexOf("\"" + lowerName + "\"", StringComparison.OrdinalIgnoreCase) < 0)
            {
                int end = text.LastIndexOf('}');
                text = text.Substring(0, end) + "\t\"" + lowerName + "\"\n\t{\n\t\t\"autosave\"\t\t\"1\"\n\t}\n" + text.Substring(end);
                File.WriteAllText(set, text);
            }
            Say("Steam Deck controller layout added (it applies to Steam Deck controls; other controllers work through the Controlify mod).");
        }
        catch (Exception e) { Say("Controller layout skipped: " + e.Message); }
    }
}

// Steam's binary shortcuts.vdf.
static class Shortcuts
{
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
    static Node Get(List<Node> e, string key) { return e.FirstOrDefault(n => string.Equals(n.Key, key, StringComparison.OrdinalIgnoreCase)); }
    static void Set(List<Node> e, string key, byte type, object value) { var n = Get(e, key); if (n == null) e.Add(new Node { Type = type, Key = key, Value = value }); else { n.Type = type; n.Value = value; } }

    // Adds the shortcut, or updates the one that already runs Prism; returns its app id (as Steam names the art files).
    public static uint Upsert(string path, string name, string exe, string iconPattern)
    {
        List<Node> root;
        if (File.Exists(path)) { File.Copy(path, path + ".bak-setup", true); using (var r = new BinaryReader(File.OpenRead(path))) root = Read(r); }
        else root = new List<Node> { new Node { Type = 0, Key = "shortcuts", Value = new List<Node>() } };
        var list = (List<Node>)Get(root, "shortcuts").Value;
        string quoted = "\"" + exe + "\"";
        List<Node> entry = null;
        foreach (var n in list)
        {
            var e = (List<Node>)n.Value;
            var x = Get(e, "Exe"); var a = Get(e, "AppName");
            if ((x != null && ((string)x.Value).IndexOf("prismlauncher.exe", StringComparison.OrdinalIgnoreCase) >= 0) || (a != null && (string)a.Value == name)) { entry = e; break; }
        }
        uint appid = Crc32.Of(quoted + name) | 0x80000000;
        if (entry == null)
        {
            entry = new List<Node>();
            list.Add(new Node { Type = 0, Key = list.Count.ToString(), Value = entry });
            Set(entry, "appid", 2, unchecked((int)appid));
            foreach (var k in new[] { "ShortcutPath", "LaunchOptions", "DevkitGameID", "FlatpakAppID" }) Set(entry, k, 1, "");
            foreach (var k in new[] { "IsHidden", "OpenVR", "Devkit", "DevkitOverrideAppID", "LastPlayTime" }) Set(entry, k, 2, 0);
            Set(entry, "AllowDesktopConfig", 2, 1); Set(entry, "AllowOverlay", 2, 1);
            Set(entry, "tags", 0, new List<Node>());
        }
        else
        {
            var id = Get(entry, "appid");
            if (id != null) appid = unchecked((uint)(int)id.Value); else Set(entry, "appid", 2, unchecked((int)appid));
        }
        Set(entry, "AppName", 1, name);
        Set(entry, "Exe", 1, quoted);
        Set(entry, "StartDir", 1, "\"" + Path.GetDirectoryName(exe) + "\\\"");
        Set(entry, "icon", 1, string.Format(iconPattern, appid));
        using (var w = new BinaryWriter(File.Create(path))) Write(w, root);
        return appid;
    }
}

static class Crc32
{
    static readonly uint[] T = Enumerable.Range(0, 256).Select(i => { uint c = (uint)i; for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1; return c; }).ToArray();
    public static uint Of(string s) { uint c = 0xFFFFFFFF; foreach (var b in Encoding.UTF8.GetBytes(s)) c = T[(c ^ b) & 0xFF] ^ (c >> 8); return c ^ 0xFFFFFFFF; }
}

// Minecraft's servers.dat (uncompressed NBT): adds a server unless one with that address is already listed.
static class Nbt
{
    class Tag { public byte Type; public string Name; public object Value; public byte ListType; }

    public static void AddServer(string path, string name, string address)
    {
        Tag root;
        if (File.Exists(path)) using (var r = new Be(File.OpenRead(path))) { byte t = r.ReadByte(); root = new Tag { Type = t, Name = r.Str() }; root.Value = ReadPayload(r, t, root); }
        else root = new Tag { Type = 10, Name = "", Value = new List<Tag>() };
        var top = (List<Tag>)root.Value;
        var servers = top.FirstOrDefault(t => t.Name == "servers");
        if (servers == null) { servers = new Tag { Type = 9, Name = "servers", ListType = 10, Value = new List<object>() }; top.Add(servers); }
        var list = (List<object>)servers.Value;
        servers.ListType = 10;
        foreach (List<Tag> s in list) if (s.Any(t => t.Name == "ip" && string.Equals((string)t.Value, address, StringComparison.OrdinalIgnoreCase))) return;
        list.Add(new List<Tag> { new Tag { Type = 8, Name = "name", Value = name }, new Tag { Type = 8, Name = "ip", Value = address }, new Tag { Type = 1, Name = "acceptTextures", Value = (byte)1 } });
        using (var w = new Be(File.Create(path), true)) { w.Write((byte)10); w.Str(""); WritePayload(w, root); }
    }

    static object ReadPayload(Be r, byte t, Tag owner)
    {
        switch (t)
        {
            case 1: return r.ReadByte();
            case 2: return r.I16();
            case 3: return r.I32();
            case 4: return r.I64();
            case 5: return r.I32();
            case 6: return r.I64();
            case 7: return r.ReadBytes(r.I32());
            case 8: return r.Str();
            case 9: { byte lt = r.ReadByte(); int n = r.I32(); var l = new List<object>(); if (owner != null) owner.ListType = lt; for (int i = 0; i < n; i++) l.Add(ReadPayload(r, lt, null)); return l; }
            case 10: { var l = new List<Tag>(); while (true) { byte ct = r.ReadByte(); if (ct == 0) return l; var c = new Tag { Type = ct, Name = r.Str() }; c.Value = ReadPayload(r, ct, c); l.Add(c); } }
            case 11: { int n = r.I32(); var a = new int[n]; for (int i = 0; i < n; i++) a[i] = r.I32(); return a; }
            case 12: { int n = r.I32(); var a = new long[n]; for (int i = 0; i < n; i++) a[i] = r.I64(); return a; }
        }
        throw new Exception("unexpected NBT tag " + t);
    }

    static void WritePayload(Be w, Tag tag) { WriteValue(w, tag.Type, tag.Value, tag.ListType); }
    static void WriteValue(Be w, byte t, object v, byte listType)
    {
        switch (t)
        {
            case 1: w.Write((byte)v); break;
            case 2: w.I16((short)v); break;
            case 3: case 5: w.I32((int)v); break;
            case 4: case 6: w.I64((long)v); break;
            case 7: { var b = (byte[])v; w.I32(b.Length); w.Write(b); break; }
            case 8: w.Str((string)v); break;
            case 9: { var l = (List<object>)v; w.Write(l.Count == 0 ? (byte)0 : listType); w.I32(l.Count); foreach (var x in l) WriteValue(w, listType, x, 10); break; }
            case 10: { foreach (var c in (List<Tag>)v) { w.Write(c.Type); w.Str(c.Name); WriteValue(w, c.Type, c.Value, c.ListType); } w.Write((byte)0); break; }
            case 11: { var a = (int[])v; w.I32(a.Length); foreach (var x in a) w.I32(x); break; }
            case 12: { var a = (long[])v; w.I32(a.Length); foreach (var x in a) w.I64(x); break; }
        }
    }

    // Big-endian reading and writing.
    class Be : IDisposable
    {
        readonly Stream s; readonly bool writing;
        public Be(Stream s, bool writing = false) { this.s = s; this.writing = writing; }
        public byte ReadByte() { int b = s.ReadByte(); if (b < 0) throw new EndOfStreamException(); return (byte)b; }
        public byte[] ReadBytes(int n) { var b = new byte[n]; int o = 0; while (o < n) { int k = s.Read(b, o, n - o); if (k <= 0) throw new EndOfStreamException(); o += k; } return b; }
        public short I16() { var b = ReadBytes(2); return (short)(b[0] << 8 | b[1]); }
        public int I32() { var b = ReadBytes(4); return b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]; }
        public long I64() { return (long)(uint)I32() << 32 | (uint)I32(); }
        public string Str() { int n = (ushort)I16(); return Encoding.UTF8.GetString(ReadBytes(n)); }
        public void Write(byte b) { s.WriteByte(b); }
        public void Write(byte[] b) { s.Write(b, 0, b.Length); }
        public void I16(short v) { Write(new[] { (byte)(v >> 8), (byte)v }); }
        public void I32(int v) { Write(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v }); }
        public void I64(long v) { I32((int)(v >> 32)); I32((int)v); }
        public void Str(string v) { var b = Encoding.UTF8.GetBytes(v); I16((short)b.Length); Write(b); }
        public void Dispose() { s.Dispose(); }
    }
}
