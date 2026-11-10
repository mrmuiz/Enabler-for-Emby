using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
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

    public sealed class MainForm : Form
    {
        const string DownloadUrl = "https://emby.media/emby-for-android.html";
        readonly TextBox _file = new TextBox { ReadOnly = true, Dock = DockStyle.Fill };
        readonly Button _browse = new Button { Text = "Choose APK...", AutoSize = true };
        readonly Button _patch = new Button { Text = "Patch", AutoSize = true, Enabled = false };
        readonly TextBox _log = new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, Font = new Font(FontFamily.GenericMonospace, 9f) };

        public MainForm()
        {
            Text = "Discover Patcher - Emby for Android";
            MinimumSize = new Size(640, 420);
            Size = new Size(760, 480);
            StartPosition = FormStartPosition.CenterScreen;
            using (var ico = typeof(MainForm).Assembly.GetManifestResourceStream("DiscoverPatcher.ico"))
                if (ico != null) Icon = new Icon(ico);
            AllowDrop = true;

            var intro = new Label
            {
                AutoSize = true,
                MaximumSize = new Size(720, 0),
                Text = "Makes the official Emby for Android app load the Discover Media plugin: Discover in the menu and the " +
                       "Discover tile opening the Discover page. Nothing else changes; Emby Premiere is still needed.\r\n\r\n" +
                       "1. Download the APK for your device from emby.media (Emby for Android).\r\n" +
                       "2. Choose it here (or drop it on this window), click Patch and choose where to save the new APK.\r\n" +
                       "3. Uninstall the official Emby app once, then install the new \"-discover.apk\"."
            };
            var link = new LinkLabel { Text = DownloadUrl, AutoSize = true };
            link.LinkClicked += (s, e) => Process.Start(new ProcessStartInfo(DownloadUrl) { UseShellExecute = true });

            var row = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3 };
            row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            row.Controls.Add(_file, 0, 0);
            row.Controls.Add(_browse, 1, 0);
            row.Controls.Add(_patch, 2, 0);

            var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 1, RowCount = 4 };
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            layout.Controls.Add(intro, 0, 0);
            layout.Controls.Add(link, 0, 1);
            layout.Controls.Add(row, 0, 2);
            layout.Controls.Add(_log, 0, 3);
            Controls.Add(layout);

            _browse.Click += (s, e) =>
            {
                using (var dlg = new OpenFileDialog { Filter = "Android app (*.apk)|*.apk", Title = "Choose the Emby for Android APK" })
                    if (dlg.ShowDialog(this) == DialogResult.OK) SetFile(dlg.FileName);
            };
            _patch.Click += async (s, e) => await RunPatch();
            DragEnter += (s, e) => { if (e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy; };
            DragDrop += (s, e) =>
            {
                var files = (string[])e.Data.GetData(DataFormats.FileDrop);
                if (files != null && files.Length > 0) SetFile(files[0]);
            };
        }

        void SetFile(string path)
        {
            _file.Text = path;
            _patch.Enabled = File.Exists(path);
            _log.Clear();
        }

        async Task RunPatch()
        {
            var input = _file.Text;
            string output;
            using (var dlg = new SaveFileDialog
            {
                Title = "Save the patched APK as",
                Filter = "Android app (*.apk)|*.apk",
                InitialDirectory = Path.GetDirectoryName(Path.GetFullPath(input)),
                FileName = Path.GetFileName(EmbyApkPatcher.DefaultOutputPath(input)),
                OverwritePrompt = true
            })
            {
                if (dlg.ShowDialog(this) != DialogResult.OK) return;
                output = dlg.FileName;
            }
            if (string.Equals(Path.GetFullPath(output), Path.GetFullPath(input), StringComparison.OrdinalIgnoreCase))
            {
                MessageBox.Show(this, "Choose a different file name than the original APK.", Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            _patch.Enabled = _browse.Enabled = false;
            _log.Clear();
            try
            {
                await Task.Run(() => EmbyApkPatcher.Patch(input, output, ApkSigner.DefaultKeyPath(),
                    m => BeginInvoke(new Action(() => _log.AppendText(m + Environment.NewLine)))));
                _log.AppendText(Environment.NewLine + "Ready. Install " + Path.GetFileName(output) + " on your device." + Environment.NewLine +
                    "Keep using this computer for later updates: it signs with the same key, so updates install over it." + Environment.NewLine);
                Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + output + "\"") { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                _log.AppendText(Environment.NewLine + "Error: " + ex.Message + Environment.NewLine);
            }
            finally
            {
                _patch.Enabled = _browse.Enabled = true;
            }
        }
    }
}
