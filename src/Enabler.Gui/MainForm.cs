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

namespace DiscoverEnabler.Gui
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

    /// <summary>UI texts following the Windows display language: English (default), Dutch, German, Norwegian.
    /// Log and error details from the patcher itself stay in English.</summary>
    internal static class T
    {
        static readonly string Lang = Detect();
        static string Detect()
        {
            var l = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.ToLowerInvariant();
            if (l == "nl" || l == "de") return l;
            if (l == "nb" || l == "no" || l == "nn") return "nb";
            return "en";
        }
        public static string S(string en, string nl, string de, string nb) =>
            Lang == "nl" ? nl : Lang == "de" ? de : Lang == "nb" ? nb : en;

        /// <summary>Card description for an Emby build, translated.</summary>
        public static string Build(EmbyBuild b)
        {
            switch (b.Id)
            {
                case "arm64": return S("Most phones and tablets", "De meeste telefoons en tablets", "Die meisten Smartphones und Tablets", "De fleste telefoner og nettbrett");
                case "armv7": return S("Android TV, TV boxes and older phones", "Android TV, tv-boxen en oudere telefoons", "Android TV, TV-Boxen und ältere Smartphones", "Android TV, TV-bokser og eldre telefoner");
                case "firetv": return S("Amazon Fire TV and Fire tablets", "Amazon Fire TV en Fire-tablets", "Amazon Fire TV und Fire-Tablets", "Amazon Fire TV og Fire-nettbrett");
                case "x64": return S("PCs, Chromebooks and emulators", "Pc's, Chromebooks en emulators", "PCs, Chromebooks und Emulatoren", "PC-er, Chromebooks og emulatorer");
                case "x86": return S("Older Intel devices", "Oudere Intel-apparaten", "Ältere Intel-Geräte", "Eldre Intel-enheter");
                default: return b.Description;
            }
        }
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
            Size = new Size(159, 96);
            Margin = new Padding(0, 0, 10, 0);
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

            using (var f1 = Theme.Font(12.5f, FontStyle.Bold))
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
        const string RepoUrl = "https://github.com/mrmuiz/Enabler-for-Emby";
        const string KofiUrl = "https://ko-fi.com/mrmuiz";
        const string PremiereUrl = "https://emby.media/premiere.html";

        readonly FlowLayoutPanel _cards = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Theme.Bg, Margin = new Padding(0) };
        readonly LinkLabel _own = new LinkLabel();
        readonly Label _ownFile = new Label { AutoSize = true, ForeColor = Theme.Accent, Font = Theme.Font(9f, FontStyle.Bold) };
        readonly TextBox _folder = new TextBox { BorderStyle = BorderStyle.FixedSingle, BackColor = Theme.Card, ForeColor = Theme.Text, Font = Theme.Font(10f) };
        readonly FlatBtn _browse = new FlatBtn(T.S("Browse...", "Bladeren...", "Durchsuchen...", "Bla gjennom..."), false) { Width = 110 };
        readonly FlatBtn _go = new FlatBtn("", true) { Width = 260 };
        readonly FlatBtn _openFolder = new FlatBtn(T.S("Open folder", "Map openen", "Ordner öffnen", "Åpne mappe"), false) { Width = 130, Visible = false };
        readonly Bar _bar = new Bar { Visible = false };
        readonly Label _status = new Label { AutoSize = false, Height = 40, ForeColor = Theme.Muted, Font = Theme.Font(9.5f) };
        readonly CheckBox _serverMenu = new CheckBox
        {
            AutoSize = true, Checked = true, ForeColor = Theme.Text, BackColor = Theme.Bg, Font = Theme.Font(10f),
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            Text = T.S("Show the server menu (server settings) in the app",
                       "Servermenu (serverinstellingen) in de app tonen",
                       "Servermenü (Servereinstellungen) in der App anzeigen",
                       "Vis servermenyen (serverinnstillinger) i appen")
        };
        readonly Label _serverMenuHint = new Label
        {
            AutoSize = true, ForeColor = Theme.Muted, Font = Theme.Font(8.5f),
            Text = T.S("Only visible to administrators; the server decides the permissions.",
                       "Alleen zichtbaar voor beheerders; de server bepaalt de rechten.",
                       "Nur für Administratoren sichtbar; der Server entscheidet über die Rechte.",
                       "Bare synlig for administratorer; serveren bestemmer rettighetene.")
        };
        string _ownApk, _lastOutput;

        static readonly string SettingsPath = Path.Combine(Path.GetDirectoryName(ApkSigner.DefaultKeyPath()), "settings.txt");

        public MainForm()
        {
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "Discover Enabler";
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.5f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            ClientSize = new Size(900, 760);
            StartPosition = FormStartPosition.CenterScreen;
            AllowDrop = true;
            using (var ico = typeof(MainForm).Assembly.GetManifestResourceStream("DiscoverEnabler.ico"))
                if (ico != null) Icon = new Icon(ico);

            // banner
            var banner = new PictureBox { Dock = DockStyle.Top, Height = 214, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.FromArgb(14, 20, 30) };
            using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("banner.jpg"))
                if (s != null) banner.Image = Image.FromStream(s);

            var body = new Panel { Dock = DockStyle.Fill, Padding = new Padding(32, 20, 32, 16), BackColor = Theme.Bg };
            int y = 0;
            Control Add(Control c, int x, int top) { c.Location = new Point(32 + x, 22 + top); body.Controls.Add(c); return c; }

            Add(Head("1", T.S("Choose your device", "Kies je apparaat", "Wähle dein Gerät", "Velg enheten din")), 0, y); y += 36;
            foreach (var b in EmbyDownloads.Builds) AddCard(b.Id, b.Title, T.Build(b), null);
            Add(_cards, 0, y); y += 106;

            _own.Text = T.S("Or patch an APK you already downloaded...", "Of patch een APK die je al hebt gedownload...", "Oder eine bereits heruntergeladene APK patchen...", "Eller patch en APK du allerede har lastet ned...");
            _own.AutoSize = true; _own.LinkColor = Theme.Muted; _own.ActiveLinkColor = Theme.Accent; _own.Font = Theme.Font(9f);
            _own.LinkBehavior = LinkBehavior.HoverUnderline;
            _own.LinkClicked += (s, e) => ChooseOwn();
            Add(_own, 0, y);
            Add(_ownFile, 300, y); y += 42;

            Add(Head("2", T.S("Save the patched app in", "Gepatchte app opslaan in", "Gepatchte App speichern in", "Lagre den patchede appen i")), 0, y); y += 36;
            _folder.Width = 836 - _browse.Width - 10;
            Add(_folder, 0, y + 4);
            Add(_browse, _folder.Right - 32 + 10, y); y += 52;

            Add(_serverMenu, 0, y); y += 28;
            Add(_serverMenuHint, 22, y); y += 34;

            Add(_go, 0, y);
            Add(_openFolder, _go.Right - 32 + 14, y + 6); y += 62;
            _bar.Width = 836 - 2; Add(_bar, 0, y); y += 16;
            _status.Width = 836 - 2; Add(_status, 0, y);

            // footer
            var footer = new Panel { Dock = DockStyle.Bottom, Height = 34, BackColor = Color.FromArgb(10, 14, 20), Padding = new Padding(32, 0, 32, 0) };
            string premiere = T.S("Emby is free to browse your media, while playback requires Emby Premiere.",
                                  "Bladeren door je media is gratis in Emby; afspelen vereist Emby Premiere.",
                                  "Mit Emby kannst du deine Medien kostenlos durchsuchen; die Wiedergabe erfordert Emby Premiere.",
                                  "Emby er gratis for å bla i mediene dine, mens avspilling krever Emby Premiere.");
            var prem = new LinkLabel { Text = premiere, AutoSize = true, ForeColor = Theme.Muted, LinkColor = Theme.Accent, ActiveLinkColor = Theme.Text, Font = Theme.Font(8.5f), LinkBehavior = LinkBehavior.HoverUnderline, Location = new Point(32, 9) };
            prem.LinkArea = new LinkArea(premiere.IndexOf("Emby Premiere"), "Emby Premiere".Length);
            prem.LinkClicked += (s, e) => Process.Start(new ProcessStartInfo(PremiereUrl) { UseShellExecute = true });
            var ver = new Label { Text = "v" + Application.ProductVersion.Split('+')[0], ForeColor = Theme.Muted, AutoSize = true, Font = Theme.Font(8.5f), Location = new Point(900 - 32 - 190, 9) };
            var gh = FooterLink("GitHub", RepoUrl); gh.Location = new Point(900 - 32 - 130, 9);
            var kofi = FooterLink("Ko-fi", KofiUrl); kofi.Location = new Point(900 - 32 - 60, 9);
            footer.Controls.AddRange(new Control[] { prem, ver, gh, kofi });

            Controls.Add(body);
            Controls.Add(footer);
            Controls.Add(banner);

            _browse.Click += (s, e) =>
            {
                using (var dlg = new FolderBrowserDialog { SelectedPath = _folder.Text, Description = T.S("Where should the patched APK go?", "Waar moet de gepatchte APK komen?", "Wohin soll die gepatchte APK?", "Hvor skal den patchede APK-en lagres?"), UseDescriptionForTitle = true })
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

            _serverMenu.FlatAppearance.BorderColor = Theme.Border;
            _serverMenu.FlatAppearance.CheckedBackColor = Theme.Accent;
            _serverMenu.FlatAppearance.MouseOverBackColor = Theme.CardHover;

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
            using (var dlg = new OpenFileDialog { Filter = "Emby for Android (*.apk)|*.apk", Title = T.S("Choose the Emby for Android APK", "Kies de APK van Emby for Android", "APK von Emby for Android auswählen", "Velg APK-en for Emby for Android") })
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
            _go.Text = _ownApk != null ? T.S("Patch", "Patchen", "Patchen", "Patch") : T.S("Download and patch", "Downloaden en patchen", "Herunterladen und patchen", "Last ned og patch");
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
            if (string.IsNullOrEmpty(folder)) { Status(T.S("Choose a folder first.", "Kies eerst een map.", "Wähle zuerst einen Ordner.", "Velg en mappe først."), true); return; }
            try { Directory.CreateDirectory(folder); }
            catch (Exception ex) { Status(ex.Message, true); return; }

            var card = SelectedCard();
            string input = _ownApk, tempDownload = null;
            string output = Path.Combine(folder, _ownApk != null
                ? Path.GetFileNameWithoutExtension(_ownApk) + "-discover.apk"
                : EmbyDownloads.OutputName(EmbyDownloads.Find(card.Key)));
            if (input != null && string.Equals(Path.GetFullPath(output), Path.GetFullPath(input), StringComparison.OrdinalIgnoreCase))
                output = Path.Combine(folder, Path.GetFileNameWithoutExtension(input) + "-patched.apk");

            SetBusy(true);
            _openFolder.Visible = false;
            try
            {
                if (input == null)
                {
                    var build = EmbyDownloads.Find(card.Key);
                    tempDownload = Path.Combine(Path.GetTempPath(), build.File);
                    await Download(build, tempDownload);
                    input = tempDownload;
                }
                _bar.Value = -1;
                Status(T.S("Patching and signing...", "Patchen en ondertekenen...", "Patchen und signieren...", "Patcher og signerer..."));
                var options = new EmbyApkPatcher.Options { ServerMenu = _serverMenu.Checked };
                await Task.Run(() => EmbyApkPatcher.Patch(input, output, ApkSigner.DefaultKeyPath(), null, EmbyApkPatcher.Scope.All, options));
                _bar.Value = 100;
                _lastOutput = output;
                _openFolder.Visible = true;
                Status(T.S("Done: ", "Klaar: ", "Fertig: ", "Ferdig: ") + Path.GetFileName(output) + "\r\n" +
                       T.S("Uninstall the official Emby app once, then install this file on your device.",
                           "Verwijder eenmalig de officiële Emby-app en installeer dan dit bestand op je apparaat.",
                           "Deinstalliere einmalig die offizielle Emby-App und installiere dann diese Datei auf deinem Gerät.",
                           "Avinstaller den offisielle Emby-appen én gang, og installer deretter denne filen på enheten."));
                SaveSettings();
            }
            catch (Exception ex)
            {
                _bar.Value = 0;
                Status(T.S("Error: ", "Fout: ", "Fehler: ", "Feil: ") + ex.Message, true);
            }
            finally
            {
                if (tempDownload != null) try { File.Delete(tempDownload); } catch { }
                SetBusy(false);
            }
        }

        async Task Download(EmbyBuild build, string path)
        {
            Status(T.S("Downloading ", "Downloaden: ", "Herunterladen: ", "Laster ned ") + build.File + T.S(" from Emby (GitHub)...", " van Emby (GitHub)...", " von Emby (GitHub)...", " fra Emby (GitHub)..."));
            _bar.Value = -1;
            await EmbyDownloads.Download(build, path, (done, total) =>
            {
                if (total <= 0) return;
                BeginInvoke(new Action(() =>
                {
                    _bar.Value = (int)(done * 100 / total);
                    Status(T.S("Downloading from Emby (GitHub)... ", "Downloaden van Emby (GitHub)... ", "Herunterladen von Emby (GitHub)... ", "Laster ned fra Emby (GitHub)... ") + (done / 1048576) + " / " + (total / 1048576) + " MB");
                }));
            });
        }

        void SetBusy(bool busy)
        {
            _go.Enabled = !busy && (_ownApk != null || SelectedCard() != null);
            _browse.Enabled = _cards.Enabled = _own.Enabled = _serverMenu.Enabled = !busy;
            _bar.Visible = true;
            _go.Invalidate();
        }

        void LoadSettings()
        {
            string folder = null, arch = "arm64";
            bool serverMenu = true;
            try
            {
                if (File.Exists(SettingsPath))
                    foreach (var line in File.ReadAllLines(SettingsPath))
                    {
                        if (line.StartsWith("folder=")) folder = line.Substring(7);
                        if (line.StartsWith("arch=")) arch = line.Substring(5);
                        if (line.StartsWith("servermenu=")) serverMenu = line.Substring(11) != "0";
                    }
            }
            catch { }
            if (string.IsNullOrEmpty(folder))
                folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            _folder.Text = folder;
            foreach (ArchCard c in _cards.Controls) c.Selected = c.Key == arch;
            _serverMenu.Checked = serverMenu;
        }

        void SaveSettings()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath));
                File.WriteAllLines(SettingsPath, new[] { "folder=" + _folder.Text, "arch=" + (SelectedCard()?.Key ?? ""), "servermenu=" + (_serverMenu.Checked ? "1" : "0") });
            }
            catch { }
        }
    }
}
