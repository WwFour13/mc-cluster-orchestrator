using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace MinecraftCluster
{
    internal static class Program
    {
        [DllImport("kernel32.dll")]
        private static extern bool AttachConsole(int dwProcessId);
        private const int ATTACH_PARENT_PROCESS = -1;

        [STAThread]
        private static int Main(string[] args)
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            Directory.SetCurrentDirectory(baseDir);

            // If command-line arguments are provided, attach to the caller's console
            if (args != null && args.Length > 0)
            {
                AttachConsole(ATTACH_PARENT_PROCESS);
            }

            while (true)
            {
                // 1. Apply pending runtime update if present
                ApplyPendingUpdate(baseDir);

                // 2. Locate ServerLauncher.jar
                string jarPath = Path.Combine(baseDir, "ServerLauncher.jar");
                if (!File.Exists(jarPath))
                {
                    MessageBox.Show(
                        "ServerLauncher.jar was not found in:\n" + baseDir + "\n\n" +
                        "Please ensure all cluster files were extracted properly.",
                        "Minecraft Cluster Error",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error
                    );
                    return 1;
                }

                // 3. Locate Java runtime
                string javaBin = FindJavaExecutable(args != null && args.Length > 0);
                if (string.IsNullOrEmpty(javaBin))
                {
                    DialogResult res = MessageBox.Show(
                        "A compatible Java runtime (Java 21 or 17 LTS) was not detected on your system.\n\n" +
                        "Java is required to run the Minecraft server and cluster orchestrator.\n\n" +
                        "Would you like to open the official Java download page now?",
                        "Java Runtime Required",
                        MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning
                    );
                    if (res == DialogResult.Yes)
                    {
                        try
                        {
                            Process.Start("https://adoptium.net/temurin/releases/?version=21");
                        }
                        catch { }
                    }
                    return 1;
                }

                // 4. Construct process launch info
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = javaBin,
                    WorkingDirectory = baseDir,
                    UseShellExecute = false
                };

                if (args != null && args.Length > 0)
                {
                    // CLI Mode: Attach to parent console and run with java.exe
                    psi.Arguments = "-jar \"" + jarPath + "\" " + string.Join(" ", args);
                }
                else
                {
                    // GUI Mode: Run silently with javaw.exe
                    psi.Arguments = "-jar \"" + jarPath + "\"";
                    psi.CreateNoWindow = true;
                    psi.WindowStyle = ProcessWindowStyle.Hidden;
                }

                try
                {
                    using (Process proc = Process.Start(psi))
                    {
                        if (proc == null) return 1;

                        proc.WaitForExit();
                        int exitCode = proc.ExitCode;

                        // Exit Code 100 is the orchestrator's designated self-update trigger
                        if (exitCode == 100 || File.Exists(Path.Combine(baseDir, "ServerLauncher.jar.update")))
                        {
                            Thread.Sleep(500); // Allow file handles to release
                            continue;          // Loop and apply the update
                        }

                        return exitCode;
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show(
                        "Failed to launch Minecraft Cluster Orchestrator:\n" + ex.Message,
                        "Launch Error",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error
                    );
                    return 1;
                }
            }
        }

        private static void ApplyPendingUpdate(string baseDir)
        {
            string updateJar = Path.Combine(baseDir, "ServerLauncher.jar.update");
            string targetJar = Path.Combine(baseDir, "ServerLauncher.jar");

            if (File.Exists(updateJar))
            {
                for (int attempt = 0; attempt < 5; attempt++)
                {
                    try
                    {
                        if (File.Exists(targetJar))
                        {
                            File.Delete(targetJar);
                        }
                        File.Move(updateJar, targetJar);
                        break;
                    }
                    catch
                    {
                        Thread.Sleep(250);
                    }
                }
            }
        }

        private static string FindJavaExecutable(bool preferConsole)
        {
            string preferredName = preferConsole ? "java.exe" : "javaw.exe";
            string fallbackName = preferConsole ? "javaw.exe" : "java.exe";

            // 1. Probe PATH
            string pathEnv = Environment.GetEnvironmentVariable("PATH");
            if (!string.IsNullOrEmpty(pathEnv))
            {
                string[] dirs = pathEnv.Split(Path.PathSeparator);
                foreach (string dir in dirs)
                {
                    if (string.IsNullOrEmpty(dir)) continue;
                    try
                    {
                        string candidate = Path.Combine(dir.Trim(), preferredName);
                        if (File.Exists(candidate)) return candidate;
                    }
                    catch { }
                }

                foreach (string dir in dirs)
                {
                    if (string.IsNullOrEmpty(dir)) continue;
                    try
                    {
                        string candidate = Path.Combine(dir.Trim(), fallbackName);
                        if (File.Exists(candidate)) return candidate;
                    }
                    catch { }
                }
            }

            // 2. Probe JAVA_HOME
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome))
            {
                string candidate = Path.Combine(javaHome, "bin", preferredName);
                if (File.Exists(candidate)) return candidate;
                candidate = Path.Combine(javaHome, "bin", fallbackName);
                if (File.Exists(candidate)) return candidate;
            }

            // 3. Probe common Windows JDK install locations
            string[] commonRoots = new string[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Eclipse Adoptium"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Eclipse Adoptium"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Java")
            };

            foreach (string root in commonRoots)
            {
                if (Directory.Exists(root))
                {
                    try
                    {
                        foreach (string subDir in Directory.GetDirectories(root))
                        {
                            string binPath = Path.Combine(subDir, "bin", preferredName);
                            if (File.Exists(binPath)) return binPath;
                        }
                    }
                    catch { }
                }
            }

            return null;
        }
    }
}
