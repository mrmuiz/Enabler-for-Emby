using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace DiscoverPatcher.Gui
{
    internal static class Program
    {
        [STAThread]
        static void Main()
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new MainForm());
        }
    }

    internal static class Theme
    {
        public static readonly Color Bg = Color.FromArgb(13, 18, 26);
        public static readonly Color Card = Color.FromArgb(23, 30, 41);
        public static readonly Color CardHover = Color.FromArgb(30, 39, 53);
        public static readonly Color Border = Color.FromArgb(45, 57, 75);
        public static readonly Color Accent = Color.FromArgb(76, 175, 80);
        public static readonly Color AccentDark = Color.FromArgb(56, 142, 60);
        public static readonly Color Text = Color.FromArgb(236, 240, 245);
        public static readonly Color Muted = Color.FromArgb(146, 158, 175);
        public static readonly Color Danger = Color.FromArgb(239, 98, 98);
        public static Font Font(float size, FontStyle style = FontStyle.Regular) => new Font("Segoe UI", size, style);

        public static GraphicsPath Round(Rectangle r, int radius)
        {
            var p = new GraphicsPath();
            int d = radius * 2;
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }

    /// <summary>Texts in Dutch or English, following the Windows language.</summary>
    internal static class T
    {
        static readonly bool Nl = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "nl";
        public static string S(string en, string nl) => Nl ? nl : en;
    }

    /// <summary>Selectable card for one Emby APK build (arm64, armv7, ...).</summary>
    internal sealed class ArchCard : Control
    {
        public string Key, Title, Sub, Badge;
        bool _selected, _hover;
        public bool Selected { get => _selected; set { _selected = value; Invalidate(); } }

        public ArchCard()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
            Size = new Size(196, 92);
            Margin = new Padding(0, 0, 12, 0);
            Cursor = Cursors.Hand;
            BackColor = Theme.Bg;
        }
        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
            float k = DeviceDpi / 96f;
            int P(float v) => (int)Math.Round(v * k);
            var r = new Rectangle(1, 1, Width - 3, Height - 3);
            using (var path = Theme.Round(r, P(10)))
            using (var fill = new SolidBrush(_hover || _selected ? Theme.CardHover : Theme.Card))
            using (var pen = new Pen(_selected ? Theme.Accent : Theme.Border, _selected ? 2f : 1f))
            {
                g.FillPath(fill, path);
                g.DrawPath(pen, path);
            }
            // radio dot
            var dot = new Rectangle(Width - P(30), P(14), P(16), P(16));
            using (var pen = new Pen(_selected ? Theme.Accent : Theme.Muted, 2f)) g.DrawEllipse(pen, dot);
            if (_selected) using (var b = new SolidBrush(Theme.Accent)) g.FillEllipse(b, Rectangle.Inflate(dot, -P(4), -P(4)));

            using (var f1 = Theme.Font(13f, FontStyle.Bold))
            using (var f2 = Theme.Font(8.5f))
            using (var fb = Theme.Font(7.5f, FontStyle.Bold))
            using (var tb = new SolidBrush(Theme.Text))
            using (var mb = new SolidBrush(Theme.Muted))
            {
                g.DrawString(Title, f1, tb, P(14), P(10));
                g.DrawString(Sub, f2, mb, new RectangleF(P(14), P(38), Width - P(24), Height - P(40)));
                if (!string.IsNullOrEmpty(Badge))
                {
                    var sz = g.MeasureString(Badge, fb);
                    var br = new Rectangle(P(14) + (int)g.MeasureString(Title, f1).Width + P(4), P(15), (int)sz.Width + P(10), P(18));
                    using (var bp = Theme.Round(br, br.Height / 2)) using (var bb = new SolidBrush(Theme.Accent)) g.FillPath(bb, bp);
                    using (var dark = new SolidBrush(Theme.Bg)) g.DrawString(Badge, fb, dark, br.X + P(5), br.Y + P(2));
                }
            }
        }
    }

    /// <summary>Flat rounded button (accent or secondary).</summary>
    internal sealed class FlatBtn : Button
    {
        public bool Primary;
        bool _hover;
        public FlatBtn(string text, bool primary)
        {
            Text = text; Primary = primary;
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            Font = Theme.Font(primary ? 11f : 9.5f, primary ? FontStyle.Bold : FontStyle.Regular);
            Cursor = Cursors.Hand;
            Height = primary ? 46 : 34;
            BackColor = Theme.Bg;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        }
        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Theme.Bg);
            var r = new Rectangle(0, 0, Width - 1, Height - 1);
            Color fill = !Enabled ? Theme.Border : Primary ? (_hover ? Theme.AccentDark : Theme.Accent) : (_hover ? Theme.CardHover : Theme.Card);
            using (var p = Theme.Round(r, 8))
            using (var b = new SolidBrush(fill))
            {
                g.FillPath(b, p);
                if (!Primary) using (var pen = new Pen(Theme.Border)) g.DrawPath(pen, p);
            }
            TextRenderer.DrawText(g, Text, Font, r, Primary ? Theme.Bg : Theme.Text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }

    /// <summary>Thin rounded progress bar; Value &lt; 0 = busy (moving stripe).</summary>
    internal sealed class Bar : Control
    {
        int _value; float _phase;
        readonly Timer _t = new Timer { Interval = 30 };
        public Bar()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
            Height = 8;
            _t.Tick += (s, e) => { _phase = (_phase + 0.02f) % 1.4f; Invalidate(); };
        }
        public int Value { get => _value; set { _value = value; _t.Enabled = value < 0; Invalidate(); } }
        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Theme.Bg);
            var r = new Rectangle(0, 0, Width - 1, Height - 1);
            using (var p = Theme.Round(r, 4)) using (var b = new SolidBrush(Theme.Card)) g.FillPath(b, p);
            Rectangle f;
            if (_value < 0) { int w = Width / 3; int x = (int)((_phase - 0.4f) * Width); f = Rectangle.Intersect(r, new Rectangle(x, 0, w, Height - 1)); }
            else f = new Rectangle(0, 0, (int)((Width - 1) * Math.Min(100, _value) / 100.0), Height - 1);
            if (f.Width > 8) using (var p = Theme.Round(f, 4)) using (var b = new SolidBrush(Theme.Accent)) g.FillPath(b, p);
        }
    }

    public sealed class MainForm : Form
    {
        const string ReleaseBase = "https://github.com/MediaBrowser/Emby.Releases/raw/master/android/";
        const string RepoUrl = "https://github.com/mrmuiz/Discover-Patcher";
        const string KofiUrl = "https://ko-fi.com/mrmuiz";

        readonly FlowLayoutPanel _cards = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Theme.Bg, Margin = new Padding(0) };
        readonly LinkLabel _own = new LinkLabel();
        readonly Label _ownFile = new Label { AutoSize = true, ForeColor = Theme.Accent, Font = Theme.Font(9f, FontStyle.Bold) };
        readonly TextBox _folder = new TextBox { BorderStyle = BorderStyle.FixedSingle, BackColor = Theme.Card, ForeColor = Theme.Text, Font = Theme.Font(10f) };
        readonly FlatBtn _browse = new FlatBtn(T.S("Browse...", "Bladeren..."), false) { Width = 110 };
        readonly FlatBtn _go = new FlatBtn("", true) { Width = 260 };
        readonly FlatBtn _openFolder = new FlatBtn(T.S("Open folder", "Map openen"), false) { Width = 130, Visible = false };
        readonly Bar _bar = new Bar { Visible = false };
        readonly Label _status = new Label { AutoSize = false, Height = 40, ForeColor = Theme.Muted, Font = Theme.Font(9.5f) };
        string _ownApk, _lastOutput;

        static readonly string SettingsPath = Path.Combine(Path.GetDirectoryName(ApkSigner.DefaultKeyPath()), "settings.txt");

        public MainForm()
        {
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "Discover Patcher";
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.5f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            ClientSize = new Size(900, 690);
            StartPosition = FormStartPosition.CenterScreen;
            AllowDrop = true;
            using (var ico = typeof(MainForm).Assembly.GetManifestResourceStream("DiscoverPatcher.ico"))
                if (ico != null) Icon = new Icon(ico);

            // banner
            var banner = new PictureBox { Dock = DockStyle.Top, Height = 214, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.FromArgb(14, 20, 30) };
            using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("banner.jpg"))
                if (s != null) banner.Image = Image.FromStream(s);

            var body = new Panel { Dock = DockStyle.Fill, Padding = new Padding(32, 20, 32, 16), BackColor = Theme.Bg };
            int y = 0;
            Control Add(Control c, int x, int top) { c.Location = new Point(32 + x, 22 + top); body.Controls.Add(c); return c; }

            Add(Head("1", T.S("Choose your device", "Kies je apparaat")), 0, y); y += 36;
            AddCard("arm64-v8a", "ARM64", T.S("Most phones and tablets", "De meeste telefoons en tablets"), T.S("most used", "meest"));
            AddCard("armeabi-v7a", "ARMv7", T.S("Android TV and TV boxes, older phones (32-bit)", "Android TV en tv-boxen, oudere telefoons (32-bit)"), null);
            AddCard("x86_64", "x64", T.S("PCs, Chromebooks, emulators (64-bit)", "Pc's, Chromebooks, emulators (64-bit)"), null);
            AddCard("x86", "x86", T.S("Old Intel devices (32-bit)", "Oude Intel-apparaten (32-bit)"), null);
            Add(_cards, 0, y); y += 104;

            _own.Text = T.S("Or patch an APK you already downloaded...", "Of patch een APK die je al hebt gedownload...");
            _own.AutoSize = true; _own.LinkColor = Theme.Muted; _own.ActiveLinkColor = Theme.Accent; _own.Font = Theme.Font(9f);
            _own.LinkBehavior = LinkBehavior.HoverUnderline;
            _own.LinkClicked += (s, e) => ChooseOwn();
            Add(_own, 0, y);
            Add(_ownFile, 300, y); y += 42;

            Add(Head("2", T.S("Save the patched app in", "Gepatchte app opslaan in")), 0, y); y += 36;
            _folder.Width = 836 - _browse.Width - 10;
            Add(_folder, 0, y + 4);
            Add(_browse, _folder.Right - 32 + 10, y); y += 56;

            Add(_go, 0, y);
            Add(_openFolder, _go.Right - 32 + 14, y + 6); y += 62;
            _bar.Width = 836 - 2; Add(_bar, 0, y); y += 16;
            _status.Width = 836 - 2; Add(_status, 0, y);

            // footer
            var footer = new Panel { Dock = DockStyle.Bottom, Height = 34, BackColor = Color.FromArgb(10, 14, 20), Padding = new Padding(32, 0, 32, 0) };
            var ver = new Label { Text = "Discover Patcher " + Application.ProductVersion.Split('+')[0] + "  ·  " + T.S("Emby Premiere is still required", "Emby Premiere blijft nodig"), ForeColor = Theme.Muted, AutoSize = true, Font = Theme.Font(8.5f), Location = new Point(32, 9) };
            var gh = FooterLink("GitHub", RepoUrl); gh.Location = new Point(900 - 32 - 130, 9);
            var kofi = FooterLink("Ko-fi", KofiUrl); kofi.Location = new Point(900 - 32 - 60, 9);
            footer.Controls.AddRange(new Control[] { ver, gh, kofi });

            Controls.Add(body);
            Controls.Add(footer);
            Controls.Add(banner);

            _browse.Click += (s, e) =>
            {
                using (var dlg = new FolderBrowserDialog { SelectedPath = _folder.Text, Description = T.S("Where should the patched APK go?", "Waar moet de gepatchte APK komen?"), UseDescriptionForTitle = true })
                    if (dlg.ShowDialog(this) == DialogResult.OK) _folder.Text = dlg.SelectedPath;
            };
            _go.Click += async (s, e) => await Run();
            _openFolder.Click += (s, e) =>
            {
                if (_lastOutput != null && File.Exists(_lastOutput))
                    Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + _lastOutput + "\"") { UseShellExecute = true });
            };
            DragEnter += (s, e) => { if (e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy; };
            DragDrop += (s, e) =>
            {
                var files = (string[])e.Data.GetData(DataFormats.FileDrop);
                if (files != null && files.Length > 0 && files[0].EndsWith(".apk", StringComparison.OrdinalIgnoreCase)) SetOwn(files[0]);
            };

            LoadSettings();
            UpdateGoText();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { int on = 1; DwmSetWindowAttribute(Handle, 20, ref on, 4); } catch { }   // dark title bar (Windows 10 20H1+ / 11)
        }
        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        static Label Head(string num, string text)
        {
            var l = new Label { AutoSize = true, Font = Theme.Font(12.5f, FontStyle.Bold), ForeColor = Theme.Text, Text = num + "   " + text };
            l.Paint += (s, e) => { };
            return l;
        }

        static LinkLabel FooterLink(string text, string url)
        {
            var l = new LinkLabel { Text = text, AutoSize = true, LinkColor = Theme.Muted, ActiveLinkColor = Theme.Accent, Font = Theme.Font(8.5f), LinkBehavior = LinkBehavior.HoverUnderline };
            l.LinkClicked += (s, e) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            return l;
        }

        void AddCard(string key, string title, string sub, string badge)
        {
            var c = new ArchCard { Key = key, Title = title, Sub = sub, Badge = badge };
            c.Click += (s, e) => SelectArch(c);
            _cards.Controls.Add(c);
        }

        ArchCard SelectedCard()
        {
            foreach (ArchCard c in _cards.Controls) if (c.Selected) return c;
            return null;
        }

        void SelectArch(ArchCard card)
        {
            foreach (ArchCard c in _cards.Controls) c.Selected = c == card;
            _ownApk = null;
            _ownFile.Text = "";
            UpdateGoText();
        }

        void ChooseOwn()
        {
            using (var dlg = new OpenFileDialog { Filter = "Emby for Android (*.apk)|*.apk", Title = T.S("Choose the Emby for Android APK", "Kies de APK van Emby for Android") })
                if (dlg.ShowDialog(this) == DialogResult.OK) SetOwn(dlg.FileName);
        }

        void SetOwn(string path)
        {
            _ownApk = path;
            foreach (ArchCard c in _cards.Controls) c.Selected = false;
            _ownFile.Text = Path.GetFileName(path);
            UpdateGoText();
        }

        void UpdateGoText()
        {
            _go.Text = _ownApk != null ? T.S("Patch", "Patchen") : T.S("Download and patch", "Downloaden en patchen");
            _go.Enabled = _ownApk != null || SelectedCard() != null;
            _go.Invalidate();
        }

        void Status(string text, bool error = false)
        {
            _status.ForeColor = error ? Theme.Danger : Theme.Muted;
            _status.Text = text;
        }

        async Task Run()
        {
            var folder = _folder.Text.Trim();
            if (string.IsNullOrEmpty(folder)) { Status(T.S("Choose a folder first.", "Kies eerst een map."), true); return; }
            try { Directory.CreateDirectory(folder); }
            catch (Exception ex) { Status(ex.Message, true); return; }

            var card = SelectedCard();
            string input = _ownApk, tempDownload = null;
            string output = Path.Combine(folder, _ownApk != null
                ? Path.GetFileNameWithoutExtension(_ownApk) + "-discover.apk"
                : "Emby-" + card.Title + "-Discover.apk");
            if (input != null && string.Equals(Path.GetFullPath(output), Path.GetFullPath(input), StringComparison.OrdinalIgnoreCase))
                output = Path.Combine(folder, Path.GetFileNameWithoutExtension(input) + "-patched.apk");

            SetBusy(true);
            _openFolder.Visible = false;
            try
            {
                if (input == null)
                {
                    tempDownload = Path.Combine(Path.GetTempPath(), "emby-android-google-" + card.Key + "-release.apk");
                    await Download(ReleaseBase + "emby-android-google-" + card.Key + "-release.apk", tempDownload);
                    input = tempDownload;
                }
                _bar.Value = -1;
                Status(T.S("Patching and signing...", "Patchen en ondertekenen..."));
                await Task.Run(() => EmbyApkPatcher.Patch(input, output, ApkSigner.DefaultKeyPath(), null));
                _bar.Value = 100;
                _lastOutput = output;
                _openFolder.Visible = true;
                Status(T.S("Done: ", "Klaar: ") + Path.GetFileName(output) + "\r\n" +
                       T.S("Uninstall the official Emby app once, then install this file on your device.",
                           "Verwijder eenmalig de officiële Emby-app en installeer dan dit bestand op je apparaat."));
                SaveSettings();
            }
            catch (Exception ex)
            {
                _bar.Value = 0;
                Status(T.S("Error: ", "Fout: ") + ex.Message, true);
            }
            finally
            {
                if (tempDownload != null) try { File.Delete(tempDownload); } catch { }
                SetBusy(false);
            }
        }

        async Task Download(string url, string path)
        {
            Status(T.S("Downloading from Emby (GitHub)...", "Downloaden van Emby (GitHub)..."));
            _bar.Value = 0;
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) })
            {
                http.DefaultRequestHeaders.UserAgent.ParseAdd("DiscoverPatcher/1.0");
                using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead))
                {
                    resp.EnsureSuccessStatusCode();
                    long total = resp.Content.Headers.ContentLength ?? -1, done = 0;
                    if (total < 0) _bar.Value = -1;
                    using (var src = await resp.Content.ReadAsStreamAsync())
                    using (var dst = File.Create(path))
                    {
                        var buf = new byte[81920];
                        int n;
                        while ((n = await src.ReadAsync(buf, 0, buf.Length)) > 0)
                        {
                            await dst.WriteAsync(buf, 0, n);
                            done += n;
                            if (total > 0)
                            {
                                _bar.Value = (int)(done * 100 / total);
                                Status(T.S("Downloading from Emby (GitHub)... ", "Downloaden van Emby (GitHub)... ") + (done / 1048576) + " / " + (total / 1048576) + " MB");
                            }
                        }
                    }
                }
            }
        }

        void SetBusy(bool busy)
        {
            _go.Enabled = !busy && (_ownApk != null || SelectedCard() != null);
            _browse.Enabled = _cards.Enabled = _own.Enabled = !busy;
            _bar.Visible = true;
            _go.Invalidate();
        }

        void LoadSettings()
        {
            string folder = null, arch = "arm64-v8a";
            try
            {
                if (File.Exists(SettingsPath))
                    foreach (var line in File.ReadAllLines(SettingsPath))
                    {
                        if (line.StartsWith("folder=")) folder = line.Substring(7);
                        if (line.StartsWith("arch=")) arch = line.Substring(5);
                    }
            }
            catch { }
            if (string.IsNullOrEmpty(folder))
                folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            _folder.Text = folder;
            foreach (ArchCard c in _cards.Controls) c.Selected = c.Key == arch;
        }

        void SaveSettings()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath));
                File.WriteAllLines(SettingsPath, new[] { "folder=" + _folder.Text, "arch=" + (SelectedCard()?.Key ?? "") });
            }
            catch { }
        }
    }
}
