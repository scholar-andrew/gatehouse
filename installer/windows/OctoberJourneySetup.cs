// October Journey setup for Windows. Built with the C# compiler that ships with Windows (.NET Framework 4.x):
//   csc /target:exe /out:OctoberJourneySetup.exe /r:System.Web.Extensions.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll OctoberJourneySetup.cs Options.cs Nbt.cs
// Reads installer/config.json from the GitHub repo each run. Always installs the October Journey modpack: a Prism
// Launcher instance whose mods update on every launch, in Prism's own instances folder. The first screen offers a quick
// install (everything below, in the usual folders) or choosing item by item (all on by default) and where to put Prism
// Launcher and the saved uninstaller and installer:
//   prism        Prism Launcher, if it isn't installed (official installer from GitHub, SHA-256 checked)
//   server       the October Journey server in the instance's server list
//   game         a Steam shortcut "October Journey - <this computer>" that starts the game and joins the server
//                (Prism's --launch and --server; the pack update and launch pop-up still run first)
//   shortcut     a Steam shortcut "Minecraft (Prism Launcher) - <this computer>" that opens Prism Launcher
//                (Steam must be closed for either shortcut; it asks)
//   art          Steam library art: October Journey's for its shortcut, Prism Launcher's logo for Prism's
//   controller   the Steam Deck controller layout for both shortcuts
//   updates      keep the server entry, art and layout up to date on each launch (pack-options.json)
//   uninstaller  save the uninstaller to %LOCALAPPDATA%\OctoberJourney and list it in Settings > Apps
//   installer    save this installer there too, to change these choices later (Settings > Apps > Modify)
// When the pack is already here but the October Journey shortcut isn't (set up before it existed), the first screen
// also offers adding just that shortcut, with the art and layouts of both shortcuts and a refreshed saved uninstaller.
// Flags: --yes  (no questions: everything above; stops if Steam is running)
//        --add-shortcut  (with --yes: only add the October Journey shortcut, as that first-screen choice does)
//        --skip=item,item  (leave those out, e.g. --skip=prism,art)
//        --prism-dir=DIR  (where to install Prism Launcher; default %LOCALAPPDATA%\Programs\PrismLauncher)
//        --tools-dir=DIR  (where to save the uninstaller and installer; default %LOCALAPPDATA%\OctoberJourney)
//        --prism-root=DIR --steam-root=DIR  (testing: use these folders instead of the real ones; Steam may stay open)
//        --config=URL  (testing: read config.json from there, e.g. file:///C:/.../installer/config.json)
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
    const string Title = "October Journey setup";
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
        Options.ScreenshotPath = Arg(args, "--screenshot");
        var skip = new HashSet<string>((Arg(args, "--skip") ?? "").Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries).Select(s => s.Trim().ToLowerInvariant()));
        string logPath = Path.Combine(Path.GetTempPath(), "OctoberJourneySetup.log");
        log = new StreamWriter(logPath, false, Encoding.UTF8);
        log.AutoFlush = true;
        try
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Say("October Journey setup");
            var cfg = (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(Text(Arg(args, "--config") ?? ConfigUrl));
            string repo = (string)cfg["repo_raw"];
            string steam = steamRoot ?? SteamPath();
            string prism = PrismExe();
            string device = DeviceName();
            string instId = (string)cfg["instance_id"];
            string prismName = (string)cfg["shortcut_name"] + " - " + device;
            string gameName = (cfg.ContainsKey("game_shortcut_name") ? (string)cfg["game_shortcut_name"] : "October Journey") + " - " + device;
            string server = (string)((Dictionary<string, object>)((object[])cfg["servers"])[0])["address"];
            string instances = InstancesDir();
            string inst = Path.Combine(instances, instId);
            string mc = Path.Combine(inst, "minecraft");

            // What's here already: the pack, and which of the two Steam shortcuts.
            string acct = null;
            bool hasPrismShortcut = false, hasGameShortcut = false;
            if (steam != null)
                try { acct = SteamAccount(steam); var f = Shortcuts.Find(Path.Combine(steam, "userdata", acct, "config", "shortcuts.vdf"), instId, prismName, gameName); hasPrismShortcut = f[0]; hasGameShortcut = f[1]; }
                catch { }
            bool packHere = File.Exists(Path.Combine(inst, "instance.cfg"));
            string localApp = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string savedTools = prismRoot == null ? SavedToolsDir() : null;
            string toolsDefault = savedTools ?? (prismRoot != null ? Path.Combine(prismRoot, "OctoberJourney") : Path.Combine(localApp, "OctoberJourney"));

            // What to install.
            var opts = new Options();
            opts.Add("prism", "Prism Launcher", prism != null ? "Already installed." : "The game launcher, from Prism's official GitHub release (checked against its SHA-256).", true, prism == null);
            opts.Add("server", "October Journey server", "Adds the server to the game's server list.", true, true);
            opts.Add("game", "October Journey shortcut", steam == null ? "Steam isn't installed." : "\"" + gameName + "\" in your Steam library: updates the pack, then starts the game and joins the server. Steam has to be closed for this.", steam != null, steam != null, "prism");
            opts.Add("shortcut", "Prism Launcher shortcut", steam == null ? "Steam isn't installed." : "\"" + prismName + "\" in your Steam library: opens Prism Launcher. Steam has to be closed for this.", steam != null, steam != null, "prism");
            opts.Add("art", "Steam library art", "October Journey's own art for its shortcut, and Prism Launcher's logo for Prism's.", steam != null, steam != null, "game|shortcut");
            opts.Add("controller", "Steam Deck controller layout", "The pack's layout (R4 jump, L4 crouch, trackpad mouse) for both shortcuts on Steam Deck controls.", steam != null, steam != null, "game|shortcut");
            opts.Add("updates", "Keep these up to date when you play", "Each launch brings the server entry, art and controller layouts up to date with the pack. Changes you make yourself are kept.", true, true, "server|art|controller");
            opts.Add("uninstaller", "Save the uninstaller", "Listed in Settings > Apps, to remove October Journey later.", true, true);
            opts.Add("installer", "Save this installer", "Beside the uninstaller, to change these choices later (Settings > Apps > Modify).", true, true);
            if (prism == null) opts.AddFolder("prismdir", "Prism Launcher", "where to install it.", Path.Combine(localApp, "Programs", "PrismLauncher"), "prism");
            opts.AddFolder("toolsdir", "Uninstaller and installer", "where to save them.", toolsDefault, "uninstaller|installer");
            opts.FixedPlaces = "Set by other programs: the game instance goes in Prism Launcher's instances folder (" + instances + "), and the Steam shortcuts, art and layouts go where Steam keeps them" + (steam != null ? " (" + steam + ")." : ".");
            if (prism == null) opts.SetFolder("prismdir", Arg(args, "--prism-dir"));
            opts.SetFolder("toolsdir", Arg(args, "--tools-dir"));
            opts.ApplySkips(skip);

            // Setup from before the October Journey shortcut existed: offer to add just that (and the art and layouts that
            // go with the two shortcuts), refreshing a saved uninstaller and installer so they know about it too.
            bool offerAdd = packHere && steam != null && prism != null && !hasGameShortcut;
            bool addOnly = args.Contains("--add-shortcut") && offerAdd;
            Action onlyAdd = () =>
            {
                var keep = new List<string> { "game", "art", "controller" };
                if (hasPrismShortcut) keep.Add("shortcut");
                if (savedTools != null && File.Exists(Path.Combine(savedTools, "OctoberJourneyUninstall.exe"))) keep.Add("uninstaller");
                if (savedTools != null && File.Exists(Path.Combine(savedTools, "OctoberJourneySetup.exe"))) keep.Add("installer");
                opts.Only(keep.ToArray());
            };
            if (addOnly) onlyAdd();
            if (!quiet)
            {
                string mode = Options.AskQuick(Title, "Installs the October Journey modpack: a Prism Launcher instance whose mods update every time you play.",
                    "Quick install" + (offerAdd ? "" : " (recommended)"), "Prism Launcher" + (prism != null ? " (already installed)" : "") + ", the server, " + (steam != null ? "two Steam shortcuts (October Journey and Prism Launcher) with their art and controller layout, " : "") + "updates, and the uninstaller, in the usual folders.",
                    "Choose what to install...", "Pick the items and where Prism Launcher and the uninstaller go.",
                    offerAdd ? "Add the October Journey shortcut (new, recommended)" : null,
                    offerAdd ? "October Journey is already set up here. This adds only the new \"" + gameName + "\" shortcut, which starts the game and joins the server, gives " + (hasPrismShortcut ? "the Prism Launcher shortcut Prism's own art, adds the controller layout to both" : "it the art and controller layout") + ", and updates the saved uninstaller. Nothing else changes." : null);
                if (mode == null || (mode == "custom" && !opts.ShowDialog(Title, "The October Journey modpack is always installed. Choose what else to add:", "Install"))) { Say("Cancelled."); return 1; }
                if (mode == "extra") { onlyAdd(); addOnly = true; }
                Say("Install mode: " + (addOnly ? "add the October Journey shortcut" : mode));
            }
            Say("Chosen: " + string.Join(", ", opts.Chosen()));
            foreach (var key in new[] { "prismdir", "toolsdir" })
                if ((key != "prismdir" || prism == null) && !Options.IsFolderPath(opts.FolderOf(key))) throw new Exception("not a full folder path: " + opts.FolderOf(key));

            // Prism Launcher.
            if (opts.On("prism") && prism == null) prism = InstallPrism((Dictionary<string, object>)cfg["prism_windows"], opts.FolderOf("prismdir"));
            if (prism == null) Say("Prism Launcher isn't installed: install it before playing, then the instance shows up in it.");

            // The instance (always), in Prism's instances folder.
            Directory.CreateDirectory(mc);
            File.WriteAllBytes(Path.Combine(inst, "mmc-pack.json"), Bytes(repo + "/clients/mmc-pack.json"));
            WriteInstanceCfg(Path.Combine(inst, "instance.cfg"), (string)cfg["instance_name"]);
            File.WriteAllBytes(Path.Combine(mc, "pack-sync.ps1"), Bytes(repo + "/clients/pack-sync.ps1"));
            File.WriteAllBytes(Path.Combine(mc, "launch-wrapper.exe"), Bytes(repo + "/clients/launch-wrapper.exe"));
            var jar = Bytes(repo + "/clients/packwiz-installer-bootstrap.jar");
            if (!Sha256(jar).Equals((string)cfg["bootstrap_sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("packwiz-installer-bootstrap.jar does not match its checksum");
            File.WriteAllBytes(Path.Combine(mc, "packwiz-installer-bootstrap.jar"), jar);
            var sources = new StringBuilder("# Tried in order; the first that answers is used.\n");
            foreach (var s in (object[])cfg["pack_sources"]) sources.Append((string)s).Append('\n');
            File.WriteAllText(Path.Combine(mc, "pack-sources.txt"), sources.ToString());
            Say("Prism instance ready: " + inst);

            // The server entry.
            if (opts.On("server"))
            {
                foreach (Dictionary<string, object> srv in (object[])cfg["servers"]) Nbt.AddServer(Path.Combine(mc, "servers.dat"), (string)srv["name"], (string)srv["address"]);
                Say("Server added to the server list.");
            }

            // What each launch keeps up to date (read by pack-sync.ps1), kept beside the player's own settings in the same
            // file (mod switches, pop-ups). Adding only the new shortcut leaves it as it is.
            if (!addOnly)
            {
                bool upd = opts.On("updates");
                string poPath = Path.Combine(mc, "pack-options.json");
                Dictionary<string, object> po = null;
                try { po = new JavaScriptSerializer().DeserializeObject(File.ReadAllText(poPath)) as Dictionary<string, object>; } catch { }
                if (po == null) po = new Dictionary<string, object>();
                po["steam_art"] = upd && opts.On("art"); po["steam_controller"] = upd && opts.On("controller"); po["server_entry"] = upd && opts.On("server");
                File.WriteAllText(poPath, new JavaScriptSerializer().Serialize(po));
            }

            // Steam: the October Journey shortcut (starts the game and joins the server) and the Prism Launcher one.
            string play = null;
            if ((opts.On("game") || opts.On("shortcut")) && steam != null && prism != null)
            {
                if (acct == null) acct = SteamAccount(steam);
                if (steamRoot == null) WaitForSteamClosed();
                string cfgDir = Path.Combine(steam, "userdata", acct, "config");
                Directory.CreateDirectory(cfgDir);
                string grid = Path.Combine(cfgDir, "grid");
                string layouts = Path.Combine(steam, "steamapps", "common", "Steam Controller Configs", acct, "config");
                var targets = new List<object>();
                Dictionary<string, object> gameTarget = null;
                foreach (bool game in new[] { true, false })
                {
                    if (!opts.On(game ? "game" : "shortcut")) continue;
                    string name = game ? gameName : prismName;
                    uint appid = Shortcuts.Upsert(Path.Combine(cfgDir, "shortcuts.vdf"), name, prism, game ? "--launch " + instId + " --server " + server : "", Path.Combine(grid, "{0}_icon.png"), game, instId);
                    Say("Steam shortcut: " + name + " (app " + appid + ")");
                    object art = game ? cfg["art"] : (cfg.ContainsKey("art_prism") ? cfg["art_prism"] : null);
                    if (opts.On("art")) { if (art != null) Art(grid, repo, appid, (Dictionary<string, object>)art); else Say("Prism Launcher art skipped: not in setup's settings yet."); }
                    if (opts.On("controller")) Controller(steam, acct, name.ToLowerInvariant(), repo);
                    var t = new Dictionary<string, object> { { "grid", grid }, { "appid", appid }, { "controller", Path.Combine(layouts, name.ToLowerInvariant()) }, { "art", game ? "pack" : "prism" } };
                    targets.Add(t);
                    if (game) gameTarget = t;
                    if (play == null) play = name;
                }
                // Where the pack sync puts later art and layout updates: "targets", one per shortcut. The single grid /
                // appid / controller beside it is for pack syncs from before there were two shortcuts.
                var st = new Dictionary<string, object> { { "targets", targets } };
                if (gameTarget != null) foreach (var k in new[] { "grid", "appid", "controller" }) st[k] = gameTarget[k];
                File.WriteAllText(Path.Combine(mc, "steam-target.json"), new JavaScriptSerializer().Serialize(st));
            }
            else if (opts.On("game") || opts.On("shortcut")) Say("Steam shortcuts skipped: " + (steam == null ? "Steam isn't installed." : "Prism Launcher isn't installed."));

            // The saved uninstaller and installer.
            SaveTools(cfg, prism, opts.FolderOf("toolsdir"), opts.On("uninstaller"), opts.On("installer"));
            Done(play, play != null && play == gameName);
            return 0;
        }
        catch (Exception e)
        {
            Say("Setup stopped: " + e.Message);
            log.WriteLine(e.ToString());
            if (!quiet) MessageBox.Show("Setup stopped: " + e.Message + "\n\nDetails are in " + logPath, Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    // The uninstaller and this installer, kept in %LOCALAPPDATA%\OctoberJourney. With the uninstaller saved, October
    // Journey is listed in Settings > Apps for this user (Uninstall, and Modify when the installer is saved too). The
    // uninstaller is checked against config.json's SHA-256; if it can't be fetched or doesn't match, setup carries on
    // without it (it's also on the release page).
    static void SaveTools(Dictionary<string, object> cfg, string prismExe, string dir, bool uninstaller, bool installer)
    {
        bool testing = prismRoot != null;
        string un = Path.Combine(dir, "OctoberJourneyUninstall.exe"), setup = Path.Combine(dir, "OctoberJourneySetup.exe");
        // The uninstaller only treats a folder as its own when this file is there (or Settings > Apps names it).
        if (uninstaller || installer)
            try
            {
                Directory.CreateDirectory(dir);
                File.WriteAllText(Path.Combine(dir, "OctoberJourney.txt"), "Saved by October Journey setup: its installer and uninstaller.\r\n" +
                    "Run OctoberJourneyUninstall.exe (or Settings > Apps > October Journey) to remove October Journey, or\r\n" +
                    "OctoberJourneySetup.exe to add items. https://github.com/scholar-andrew/october-journey-pack\r\n");
            }
            catch { }
        if (installer)
        {
            try
            {
                Directory.CreateDirectory(dir);
                string me = Path.GetFullPath(Application.ExecutablePath);
                if (!me.Equals(Path.GetFullPath(setup), StringComparison.OrdinalIgnoreCase)) File.Copy(me, setup, true);
                Say("Installer saved: " + setup);
            }
            catch (Exception e) { Say("Installer not saved (" + e.Message + ")."); installer = false; }
        }
        if (uninstaller)
        {
            try
            {
                var u = (Dictionary<string, object>)cfg["uninstall_windows"];
                var b = Bytes((string)u["url"]);
                if (!Sha256(b).Equals((string)u["sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("it does not match its checksum");
                Directory.CreateDirectory(dir);
                File.WriteAllBytes(un, b);
                Say("Uninstaller saved: " + un);
            }
            catch (Exception e) { Say("Uninstaller not added (" + e.Message + "); it can be downloaded from the release page."); uninstaller = false; }
        }
        if (testing) { if (uninstaller) Say("Testing: not listed in Settings > Apps."); return; }
        const string key = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney";
        if (!uninstaller) return;
        using (var k = Registry.CurrentUser.CreateSubKey(key))
        {
            k.SetValue("DisplayName", "October Journey (Minecraft modpack)");
            k.SetValue("Publisher", "October Journey");
            if (prismExe != null) k.SetValue("DisplayIcon", prismExe);
            k.SetValue("InstallLocation", dir);
            k.SetValue("UninstallString", "\"" + un + "\"");
            k.SetValue("QuietUninstallString", "\"" + un + "\" --yes");
            k.SetValue("URLInfoAbout", "https://github.com/scholar-andrew/october-journey-pack");
            k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            if (installer) { k.SetValue("ModifyPath", "\"" + setup + "\""); k.SetValue("NoModify", 0, RegistryValueKind.DWord); }
            else { k.DeleteValue("ModifyPath", false); k.SetValue("NoModify", 1, RegistryValueKind.DWord); }
        }
        Say("Listed in Settings > Apps as \"October Journey (Minecraft modpack)\".");
    }

    static void Done(string shortcut, bool joins)
    {
        string msg = "All set.\n\n" + (shortcut == null ? "Open Prism Launcher and play \"October Journey 26.3 (Fabric)\"." :
            "Start Steam and play \"" + shortcut + "\"" + (joins ? ": it updates the pack, starts the game and joins the server." : ".")) +
            " The first time, sign in to Prism Launcher with your Microsoft account. Every launch updates the mods.";
        Say(msg.Replace("\n\n", " "));
        if (!quiet) MessageBox.Show(msg, Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    static void Say(string s) { Console.WriteLine(s); log.WriteLine(DateTime.Now.ToString("s") + "  " + s); }

    // Where an earlier setup saved the uninstaller and installer (its Settings > Apps entry), if it did.
    static string SavedToolsDir()
    {
        using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney"))
        {
            var d = k == null ? null : k.GetValue("InstallLocation") as string;
            return !string.IsNullOrEmpty(d) && Directory.Exists(d) ? d : null;
        }
    }

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
    // Prism Launcher's prismlauncher.exe: the usual folder, or wherever its Windows installer recorded it.
    static string PrismExe()
    {
        string exe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "PrismLauncher", "prismlauncher.exe");
        if (File.Exists(exe)) return exe;
        foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine })
            using (var root = hive.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall"))
                if (root != null)
                    foreach (var name in root.GetSubKeyNames())
                        using (var k = root.OpenSubKey(name))
                        {
                            if (k == null || !((k.GetValue("DisplayName") as string) ?? "").StartsWith("Prism Launcher")) continue;
                            string dir = k.GetValue("InstallLocation") as string;
                            if (string.IsNullOrEmpty(dir)) { var u = ((k.GetValue("UninstallString") as string) ?? "").Trim(); if (u.StartsWith("\"")) u = u.Substring(1, u.IndexOf('"', 1) - 1); dir = Path.GetDirectoryName(u); }
                            if (!string.IsNullOrEmpty(dir) && File.Exists(Path.Combine(dir, "prismlauncher.exe"))) return Path.Combine(dir, "prismlauncher.exe");
                        }
        return null;
    }

    // Prism Launcher's instances folder: its data folder's "instances", unless Prism's own settings name another.
    static string InstancesDir()
    {
        string data = prismRoot ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PrismLauncher");
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

    static string InstallPrism(Dictionary<string, object> p, string dir)
    {
        string exe = Path.Combine(dir, "prismlauncher.exe");
        Say("Downloading Prism Launcher " + p["version"] + "...");
        var b = Bytes((string)p["url"]);
        if (!Sha256(b).Equals((string)p["sha256"], StringComparison.OrdinalIgnoreCase)) throw new Exception("the Prism Launcher installer does not match its checksum");
        string setup = Path.Combine(Path.GetTempPath(), "PrismLauncher-Setup.exe");
        File.WriteAllBytes(setup, b);
        // The installer takes its folder as /D=..., last and unquoted.
        var proc = Process.Start(setup, "/S /D=" + dir);
        proc.WaitForExit();
        if (!File.Exists(exe)) throw new Exception("Prism Launcher did not install (exit " + proc.ExitCode + ")");
        Say("Prism Launcher installed in " + dir + ".");
        return exe;
    }

    static void WriteInstanceCfg(string path, string name)
    {
        var lines = File.Exists(path) ? File.ReadAllLines(path).ToList() : new List<string> { "[General]", "ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default" };
        lines.RemoveAll(l => l.StartsWith("name=") || l.StartsWith("OverrideCommands=") || l.StartsWith("PreLaunchCommand=") || l.StartsWith("WrapperCommand="));
        int at = lines.IndexOf("[General]");
        if (at < 0) { lines.Insert(0, "[General]"); at = 0; }
        lines.Insert(at + 1, "name=" + name);
        lines.Insert(at + 2, "OverrideCommands=true");
        lines.Insert(at + 3, "PreLaunchCommand=\"powershell -NoProfile -ExecutionPolicy Bypass -File \\\"$INST_MC_DIR\\\\pack-sync.ps1\\\"\"");
        // The launch wrapper: lets Quit in the launcher cancel a launch without Prism treating it as a failure.
        lines.Insert(at + 4, "WrapperCommand=\"\\\"$INST_MC_DIR\\\\launch-wrapper.exe\\\"\"");
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

    // The October Journey shortcut launches the instance (or has its name); the Prism Launcher one runs Prism without
    // launching an instance (or has its name).
    static string Text(List<Node> e, string key) { var n = Get(e, key); return n == null ? "" : (n.Value as string) ?? ""; }
    static bool IsGame(List<Node> e, string instId, string name)
    {
        return Text(e, "LaunchOptions").IndexOf("--launch " + instId, StringComparison.OrdinalIgnoreCase) >= 0 || Text(e, "AppName") == name;
    }
    static bool IsPrism(List<Node> e, string name)
    {
        return (Text(e, "Exe").IndexOf("prismlauncher.exe", StringComparison.OrdinalIgnoreCase) >= 0 && Text(e, "LaunchOptions").IndexOf("--launch", StringComparison.OrdinalIgnoreCase) < 0) || Text(e, "AppName") == name;
    }

    // Which of the two shortcuts are there: { Prism Launcher, October Journey }.
    public static bool[] Find(string path, string instId, string prismName, string gameName)
    {
        var found = new bool[2];
        if (!File.Exists(path)) return found;
        List<Node> root;
        using (var r = new BinaryReader(File.OpenRead(path))) root = Read(r);
        var top = Get(root, "shortcuts");
        if (top == null) return found;
        foreach (var n in (List<Node>)top.Value)
        {
            var e = (List<Node>)n.Value;
            if (IsGame(e, instId, gameName)) found[1] = true;
            else if (IsPrism(e, prismName)) found[0] = true;
        }
        return found;
    }

    // Adds a shortcut, or updates the matching one; returns its app id (as Steam names the art files).
    public static uint Upsert(string path, string name, string exe, string launchOptions, string iconPattern, bool game, string instId)
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
            if (game ? IsGame(e, instId, name) : (!IsGame(e, instId, "\0") && IsPrism(e, name))) { entry = e; break; }
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
        Set(entry, "LaunchOptions", 1, launchOptions);
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
