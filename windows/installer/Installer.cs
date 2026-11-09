using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace MinecraftClusterInstaller
{
    public class InstallerForm : Form
    {
        private Label titleLabel;
        private Label subtitleLabel;
        private Label statusLabel;
        private ProgressBar progressBar;
        private Label pathLabel;
        private TextBox pathBox;
        private Button browseBtn;
        private Button installBtn;
        private Button cancelBtn;

        private const string GITHUB_REPO = "tavgu/mc-cluster-orchestrator";
        private string targetDirectory;

        [STAThread]
        public static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)3072;
            Application.Run(new InstallerForm());
        }

        public InstallerForm()
        {
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.Text = "Minecraft Cluster Server Setup";
            this.Size = new Size(580, 360);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.BackColor = Color.FromArgb(24, 24, 27);
            this.ForeColor = Color.FromArgb(244, 244, 245);
            this.Font = new Font("Segoe UI", 9F, FontStyle.Regular);

            // Default target directory: %LOCALAPPDATA%\MinecraftCluster
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            targetDirectory = Path.Combine(localAppData, "MinecraftCluster");

            // Header Title
            titleLabel = new Label
            {
                Text = "Minecraft Cluster Orchestrator",
                Font = new Font("Segoe UI", 16F, FontStyle.Bold),
                ForeColor = Color.FromArgb(16, 185, 129), // Emerald
                Location = new Point(24, 20),
                AutoSize = true
            };

            subtitleLabel = new Label
            {
                Text = "Distributed Peer-to-Peer Minecraft Server Platform",
                Font = new Font("Segoe UI", 10F, FontStyle.Regular),
                ForeColor = Color.FromArgb(161, 161, 170), // Muted
                Location = new Point(26, 52),
                AutoSize = true
            };

            // Path Selector
            pathLabel = new Label
            {
                Text = "Install Location:",
                Location = new Point(26, 95),
                AutoSize = true,
                ForeColor = Color.FromArgb(244, 244, 245)
            };

            pathBox = new TextBox
            {
                Text = targetDirectory,
                Location = new Point(28, 118),
                Size = new Size(410, 26),
                BackColor = Color.FromArgb(39, 39, 42),
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle
            };

            browseBtn = new Button
            {
                Text = "Browse...",
                Location = new Point(448, 116),
                Size = new Size(92, 28),
                BackColor = Color.FromArgb(39, 39, 42),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            browseBtn.FlatAppearance.BorderColor = Color.FromArgb(63, 63, 70);
            browseBtn.Click += BrowseBtn_Click;

            // Status Label
            statusLabel = new Label
            {
                Text = "Ready to install. Click 'Install' to begin.",
                Location = new Point(26, 175),
                Size = new Size(514, 38),
                ForeColor = Color.FromArgb(228, 228, 231)
            };

            // Progress Bar
            progressBar = new ProgressBar
            {
                Location = new Point(28, 220),
                Size = new Size(512, 22),
                Style = ProgressBarStyle.Continuous
            };

            // Buttons
            installBtn = new Button
            {
                Text = "Install & Launch",
                Location = new Point(300, 270),
                Size = new Size(130, 36),
                BackColor = Color.FromArgb(16, 185, 129),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 10F, FontStyle.Bold)
            };
            installBtn.FlatAppearance.BorderSize = 0;
            installBtn.Click += InstallBtn_Click;

            cancelBtn = new Button
            {
                Text = "Cancel",
                Location = new Point(442, 270),
                Size = new Size(98, 36),
                BackColor = Color.FromArgb(39, 39, 42),
                ForeColor = Color.FromArgb(161, 161, 170),
                FlatStyle = FlatStyle.Flat
            };
            cancelBtn.FlatAppearance.BorderColor = Color.FromArgb(63, 63, 70);
            cancelBtn.Click += (s, e) => this.Close();

            this.Controls.Add(titleLabel);
            this.Controls.Add(subtitleLabel);
            this.Controls.Add(pathLabel);
            this.Controls.Add(pathBox);
            this.Controls.Add(browseBtn);
            this.Controls.Add(statusLabel);
            this.Controls.Add(progressBar);
            this.Controls.Add(installBtn);
            this.Controls.Add(cancelBtn);
        }

        private void BrowseBtn_Click(object sender, EventArgs e)
        {
            using (FolderBrowserDialog fbd = new FolderBrowserDialog())
            {
                fbd.SelectedPath = pathBox.Text;
                if (fbd.ShowDialog() == DialogResult.OK)
                {
                    pathBox.Text = Path.Combine(fbd.SelectedPath, "MinecraftCluster");
                }
            }
        }

        private void InstallBtn_Click(object sender, EventArgs e)
        {
            installBtn.Enabled = false;
            browseBtn.Enabled = false;
            pathBox.Enabled = false;
            targetDirectory = pathBox.Text.Trim();

            Thread worker = new Thread(RunInstallation) { IsBackground = true };
            worker.Start();
        }

        private void UpdateStatus(string message, int progressPercent)
        {
            if (this.InvokeRequired)
            {
                this.Invoke(new Action(() => UpdateStatus(message, progressPercent)));
                return;
            }
            statusLabel.Text = message;
            if (progressPercent >= 0 && progressPercent <= 100)
            {
                progressBar.Value = progressPercent;
            }
        }

        private void RunInstallation()
        {
            try
            {
                // Step 1: Java 21 Check & Auto-Install
                UpdateStatus("[1/4] Verifying Java 21 runtime...", 15);
                EnsureJava21();

                // Step 2: Prepare Target Directory
                UpdateStatus("[2/4] Preparing installation directory...", 35);
                if (!Directory.Exists(targetDirectory))
                {
                    Directory.CreateDirectory(targetDirectory);
                }

                // Step 3: Deploy or Download Application Bundle
                UpdateStatus("[3/4] Deploying cluster platform assets...", 50);
                DeployApplicationBundle();

                // Step 4: Register Desktop and Start Menu Shortcuts
                UpdateStatus("[4/4] Configuring Desktop and Windows Search shortcuts...", 85);
                CreateShortcuts();

                // Step 5: Launch Application
                UpdateStatus("Installation complete! Launching Minecraft Cluster...", 100);
                Thread.Sleep(1000);

                string launcherBat = Path.Combine(targetDirectory, "START_SERVER.bat");
                if (File.Exists(launcherBat))
                {
                    ProcessStartInfo psi = new ProcessStartInfo
                    {
                        FileName = launcherBat,
                        WorkingDirectory = targetDirectory,
                        UseShellExecute = true,
                        WindowStyle = ProcessWindowStyle.Hidden
                    };
                    Process.Start(psi);
                }

                this.Invoke(new Action(() => this.Close()));
            }
            catch (Exception ex)
            {
                UpdateStatus("Error: " + ex.Message, 0);
                MessageBox.Show("Installation failed:\n" + ex.Message, "Setup Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                if (this.InvokeRequired)
                {
                    this.Invoke(new Action(() => { installBtn.Enabled = true; }));
                }
            }
        }

        private void EnsureJava21()
        {
            int javaMajor = GetInstalledJavaMajorVersion();
            if (javaMajor >= 17)
            {
                UpdateStatus("[1/4] Detected compatible Java " + javaMajor + " LTS.", 25);
                return;
            }

            UpdateStatus("[1/4] Java 21 not found. Installing Eclipse Adoptium Temurin 21 via winget...", 20);
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "winget",
                    Arguments = "install EclipseAdoptium.Temurin.21.JDK -e --silent --accept-source-agreements --accept-package-agreements",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };
                Process p = Process.Start(psi);
                if (p != null)
                {
                    p.WaitForExit(180000); // Wait up to 3 mins
                }
            }
            catch
            {
                // Fallback to direct download page
                Process.Start("https://adoptium.net/temurin/releases/?version=21");
                MessageBox.Show("Please complete the Java 21 installation from your browser, then click OK.", "Java 21 Required", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        private int GetInstalledJavaMajorVersion()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "java",
                    Arguments = "-version",
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using (Process p = Process.Start(psi))
                {
                    if (p == null) return 0;
                    string output = p.StandardError.ReadToEnd();
                    p.WaitForExit(4000);

                    Match m = Regex.Match(output, "version \"([^\"]+)\"");
                    if (m.Success)
                    {
                        string ver = m.Groups[1].Value;
                        string[] parts = ver.Split('.');
                        if (parts.Length > 0)
                        {
                            if (parts[0] == "1" && parts.Length > 1) return int.Parse(parts[1]);
                            return int.Parse(parts[0]);
                        }
                    }
                }
            }
            catch { }
            return 0;
        }

        private void DeployApplicationBundle()
        {
            string currentDir = AppDomain.CurrentDomain.BaseDirectory;
            string localJar = Path.Combine(currentDir, "ServerLauncher.jar");
            string localBat = Path.Combine(currentDir, "START_SERVER.bat");

            // Local Mode: If installer is adjacent to ServerLauncher.jar, copy local files directly
            if (File.Exists(localJar) && File.Exists(localBat))
            {
                UpdateStatus("[3/4] Copying files from local package...", 60);
                CopyDirectoryRecursive(currentDir, targetDirectory);
                return;
            }

            // Standalone Mode: Fetch from GitHub Releases
            UpdateStatus("[3/4] Downloading latest release from GitHub...", 55);
            string tempZip = Path.Combine(Path.GetTempPath(), "cohost-windows-" + Guid.NewGuid().ToString().Substring(0, 8) + ".zip");

            try
            {
                string downloadUrl = ResolveGitHubReleaseAssetUrl();
                using (WebClient wc = new WebClient())
                {
                    wc.Headers.Add("User-Agent", "MC-Cluster-Installer");
                    wc.DownloadProgressChanged += (s, e) =>
                    {
                        int mappedProgress = 55 + (int)(e.ProgressPercentage * 0.25);
                        UpdateStatus(string.Format("[3/4] Downloading platform assets ({0}%)...", e.ProgressPercentage), mappedProgress);
                    };
                    wc.DownloadFileTaskAsync(new Uri(downloadUrl), tempZip).Wait();
                }

                UpdateStatus("[3/4] Extracting platform assets...", 80);
                ZipFile.ExtractToDirectory(tempZip, targetDirectory);
            }
            finally
            {
                if (File.Exists(tempZip))
                {
                    try { File.Delete(tempZip); } catch { }
                }
            }
        }

        private string ResolveGitHubReleaseAssetUrl()
        {
            try
            {
                string apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(apiUrl);
                req.UserAgent = "MC-Cluster-Installer";
                req.Accept = "application/vnd.github.v3+json";

                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (StreamReader sr = new StreamReader(resp.GetResponseStream()))
                {
                    string json = sr.ReadToEnd();
                    Match m = Regex.Match(json, "\"browser_download_url\":\\s*\"(https://[^\"]+cohost-windows\\.zip)\"");
                    if (m.Success)
                    {
                        return m.Groups[1].Value;
                    }
                }
            }
            catch { }

            // Direct fallback URL
            return "https://github.com/" + GITHUB_REPO + "/releases/latest/download/cohost-windows.zip";
        }

        private void CreateShortcuts()
        {
            string desktopDir = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string startMenuDir = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
            string targetBat = Path.Combine(targetDirectory, "START_SERVER.bat");
            string iconPath = Path.Combine(targetDirectory, "bin\\icon.ico");

            string script = string.Format(@"
$wsh = New-Object -ComObject WScript.Shell
$desktop = '{0}'
$programs = '{1}'
$target = '{2}'
$dir = '{3}'
$icon = '{4}'

# 1. Desktop
$s1 = $wsh.CreateShortcut((Join-Path $desktop 'Minecraft Cluster Server.lnk'))
$s1.TargetPath = $env:ComSpec
$s1.Arguments = '/c `""' + $target + '`""'
$s1.WorkingDirectory = $dir
$s1.WindowStyle = 7
if (Test-Path $icon) {{ $s1.IconLocation = ""$icon,0"" }}
$s1.Description = 'Start Minecraft Cluster Server'
$s1.Save()

# 2. Start Menu
$s2 = $wsh.CreateShortcut((Join-Path $programs 'Minecraft Cluster Server.lnk'))
$s2.TargetPath = $env:ComSpec
$s2.Arguments = '/c `""' + $target + '`""'
$s2.WorkingDirectory = $dir
$s2.WindowStyle = 7
if (Test-Path $icon) {{ $s2.IconLocation = ""$icon,0"" }}
$s2.Description = 'Start Minecraft Cluster Server'
$s2.Save()
", desktopDir.Replace("'", "''"), startMenuDir.Replace("'", "''"), targetBat.Replace("'", "''"), targetDirectory.Replace("'", "''"), iconPath.Replace("'", "''"));

            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -ExecutionPolicy Bypass -Command \"" + script.Replace("\"", "\\\"") + "\"",
                CreateNoWindow = true,
                UseShellExecute = false
            };
            Process p = Process.Start(psi);
            if (p != null) p.WaitForExit(5000);
        }

        private void CopyDirectoryRecursive(string source, string target)
        {
            foreach (string dir in Directory.GetDirectories(source, "*", SearchOption.AllDirectories))
            {
                string rel = dir.Substring(source.Length).TrimStart('\\');
                string destDir = Path.Combine(target, rel);
                if (!Directory.Exists(destDir)) Directory.CreateDirectory(destDir);
            }
            foreach (string file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
            {
                string name = Path.GetFileName(file);
                if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && name.Contains("Setup")) continue;
                string rel = file.Substring(source.Length).TrimStart('\\');
                string destFile = Path.Combine(target, rel);
                File.Copy(file, destFile, true);
            }
        }
    }
}
