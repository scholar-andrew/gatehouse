// The windows shown by OctoberJourneySetup.exe and OctoberJourneyUninstall.exe, compiled into both:
//   AskQuick    the first screen: a "quick" button (the usual choices) or one to choose item by item
//   ShowDialog  the item-by-item screen: a checkbox with a line of help per item, then any folders the player can pick
// Item dependencies:
//   needs     the item only makes sense when at least one of these is on (setup: art needs the shortcut)
//   forcedBy  the item is on whenever one of these is on (uninstall: removing the shortcut removes its art too)
// A folder is only editable while one of its "usedBy" items is on.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;

class Options
{
    class Item { public string Key, Label, Help; public string[] Needs = new string[0], ForcedBy = new string[0]; public bool On, Fixed; public CheckBox Box; }
    class Folder { public string Key, Label, Help, Value; public string[] UsedBy; public TextBox Box; public Button Browse; }
    readonly List<Item> items = new List<Item>();
    readonly List<Folder> folders = new List<Folder>();
    // Testing (--screenshot=FILE.png): save a picture of each window (the first screen as FILE-start.png) instead of
    // waiting for a click; the first screen then carries on to the item-by-item one, which closes as if cancelled.
    public static string ScreenshotPath;
    public string FixedPlaces;   // shown under the folders: where things go that can't be chosen

    // changeable false: shown but can't be changed (e.g. "already installed").
    public void Add(string key, string label, string help, bool on, bool changeable, string needs = null, string forcedBy = null)
    {
        items.Add(new Item
        {
            Key = key, Label = label, Help = help, On = on, Fixed = !changeable,
            Needs = needs == null ? new string[0] : needs.Split('|'),
            ForcedBy = forcedBy == null ? new string[0] : forcedBy.Split('|')
        });
    }

    public void AddFolder(string key, string label, string help, string value, string usedBy)
    {
        folders.Add(new Folder { Key = key, Label = label, Help = help, Value = value, UsedBy = usedBy.Split('|') });
    }

    public bool Has(string key) { return items.Any(i => i.Key == key); }
    public bool On(string key) { var i = items.FirstOrDefault(x => x.Key == key); return i != null && i.On; }
    public IEnumerable<string> Chosen() { return items.Where(i => i.On).Select(i => i.Key); }
    public string FolderOf(string key) { return folders.First(f => f.Key == key).Value; }
    public void SetFolder(string key, string value) { if (!string.IsNullOrEmpty(value)) folders.First(f => f.Key == key).Value = value; }

    public void ForceOn(string key) { var i = items.FirstOrDefault(x => x.Key == key); if (i != null) { i.On = true; Settle(); } }

    public void ApplySkips(ICollection<string> skip)
    {
        foreach (var i in items) if (skip.Contains(i.Key) && !i.Fixed) i.On = false;
        Settle();
    }

    // Applies needs and forcedBy until nothing changes.
    void Settle()
    {
        for (int pass = 0; pass < items.Count; pass++)
            foreach (var i in items)
            {
                if (i.Needs.Length > 0 && !i.Needs.Any(On)) i.On = false;
                if (i.ForcedBy.Any(On)) i.On = true;
            }
    }

    bool Locked(Item i) { return i.Fixed || (i.Needs.Length > 0 && !i.Needs.Any(On)) || i.ForcedBy.Any(On); }

    static Form NewForm(string title)
    {
        return new Form { Text = title, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, StartPosition = FormStartPosition.CenterScreen, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, Font = SystemFonts.MessageBoxFont, Padding = new Padding(12) };
    }

    static void Snap(Form form, string path, DialogResult result)
    {
        form.Shown += (s, e) =>
        {
            form.Refresh();
            using (var bmp = new Bitmap(form.Width, form.Height)) { form.DrawToBitmap(bmp, new Rectangle(0, 0, form.Width, form.Height)); bmp.Save(path); }
            form.DialogResult = result;
        };
    }

    // The first screen. Returns "quick", "custom" or null (cancelled).
    public static string AskQuick(string title, string intro, string quickLabel, string quickHelp, string customLabel, string customHelp)
    {
        var form = NewForm(title);
        var flow = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
        flow.Controls.Add(new Label { Text = intro, AutoSize = true, MaximumSize = new Size(480, 0), Margin = new Padding(0, 0, 0, 10) });
        Func<string, string, DialogResult, Button> big = (label, help, result) =>
        {
            string text = label + "\n" + help;
            int h = TextRenderer.MeasureText(text, form.Font, new Size(456, 0), TextFormatFlags.WordBreak).Height;
            var b = new Button { Text = text, TextAlign = ContentAlignment.MiddleLeft, Width = 480, Height = Math.Max(56, h + 22), DialogResult = result, Padding = new Padding(8, 0, 8, 0), Margin = new Padding(0, 0, 0, 8) };
            flow.Controls.Add(b);
            return b;
        };
        var quick = big(quickLabel, quickHelp, DialogResult.Yes);
        big(customLabel, customHelp, DialogResult.No);
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true, Anchor = AnchorStyles.Right };
        flow.Controls.Add(cancel);
        form.Controls.Add(flow);
        form.AcceptButton = quick; form.CancelButton = cancel;
        if (ScreenshotPath != null) Snap(form, Path.Combine(Path.GetDirectoryName(ScreenshotPath), Path.GetFileNameWithoutExtension(ScreenshotPath) + "-start.png"), DialogResult.No);
        var r = form.ShowDialog();
        return r == DialogResult.Yes ? "quick" : r == DialogResult.No ? "custom" : null;
    }

    public bool ShowDialog(string title, string intro, string okText)
    {
        Settle();
        var form = NewForm(title);
        var flow = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
        flow.Controls.Add(new Label { Text = intro, AutoSize = true, MaximumSize = new Size(480, 0), Margin = new Padding(0, 0, 0, 10) });
        bool updating = false;
        Action refresh = () =>
        {
            updating = true;
            Settle();
            foreach (var i in items) { i.Box.Checked = i.On; i.Box.Enabled = !Locked(i); }
            foreach (var f in folders) { bool on = f.UsedBy.Any(On); f.Box.Enabled = on; f.Browse.Enabled = on; }
            updating = false;
        };
        foreach (var it in items)
        {
            var i = it;
            i.Box = new CheckBox { Text = i.Label, AutoSize = true, Checked = i.On, Font = new Font(form.Font, FontStyle.Bold), Margin = new Padding(0, 4, 0, 0) };
            i.Box.CheckedChanged += (s, e) => { if (updating) return; i.On = i.Box.Checked; refresh(); };
            flow.Controls.Add(i.Box);
            flow.Controls.Add(new Label { Text = i.Help, AutoSize = true, MaximumSize = new Size(460, 0), ForeColor = SystemColors.GrayText, Margin = new Padding(20, 0, 0, 4) });
        }
        if (folders.Count > 0)
        {
            flow.Controls.Add(new Label { Text = "Where to put them", AutoSize = true, Font = new Font(form.Font, FontStyle.Bold), Margin = new Padding(0, 12, 0, 2) });
            foreach (var fo in folders)
            {
                var f = fo;
                flow.Controls.Add(new Label { Text = f.Label + ": " + f.Help, AutoSize = true, MaximumSize = new Size(480, 0), Margin = new Padding(0, 4, 0, 2) });
                var row = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = false, Margin = new Padding(0) };
                f.Box = new TextBox { Text = f.Value, Width = 390 };
                f.Box.TextChanged += (s, e) => f.Value = f.Box.Text.Trim();
                f.Browse = new Button { Text = "Browse...", AutoSize = true };
                f.Browse.Click += (s, e) =>
                {
                    using (var d = new FolderBrowserDialog { Description = f.Label, SelectedPath = Directory.Exists(f.Value) ? f.Value : "" })
                        if (d.ShowDialog(form) == DialogResult.OK) f.Box.Text = d.SelectedPath;
                };
                row.Controls.Add(f.Box); row.Controls.Add(f.Browse);
                flow.Controls.Add(row);
            }
            if (FixedPlaces != null)
                flow.Controls.Add(new Label { Text = FixedPlaces, AutoSize = true, MaximumSize = new Size(480, 0), ForeColor = SystemColors.GrayText, Margin = new Padding(0, 8, 0, 0) });
        }
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Anchor = AnchorStyles.Right, Margin = new Padding(0, 12, 0, 0) };
        var ok = new Button { Text = okText, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        ok.Click += (s, e) =>
        {
            var bad = folders.FirstOrDefault(f => f.UsedBy.Any(On) && !IsFolderPath(f.Value));
            if (bad != null) { MessageBox.Show(form, bad.Label + " has to be a full folder path, such as C:\\Games\\" + Path.GetFileName(bad.Value.TrimEnd('\\')) + ".", form.Text, MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
            form.DialogResult = DialogResult.OK;
        };
        buttons.Controls.Add(cancel); buttons.Controls.Add(ok);
        flow.Controls.Add(buttons);
        form.Controls.Add(flow);
        form.AcceptButton = ok; form.CancelButton = cancel;
        refresh();
        if (ScreenshotPath != null) Snap(form, ScreenshotPath, DialogResult.Cancel);
        return form.ShowDialog() == DialogResult.OK;
    }

    public static bool IsFolderPath(string p)
    {
        try { return !string.IsNullOrWhiteSpace(p) && Path.IsPathRooted(p) && Path.GetFullPath(p).Length > 3 && !File.Exists(p); }
        catch { return false; }
    }
}
