import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

public class ClusterGUI extends JFrame {

    // Modern Dark Theme Palette
    public static final Color COLOR_BG_DARK = new Color(18, 18, 22);       // Deep charcoal
    public static final Color COLOR_BG_CARD = new Color(28, 28, 34);       // Card surface
    public static final Color COLOR_BG_INPUT = new Color(36, 36, 44);      // Text fields
    public static final Color COLOR_BORDER = new Color(50, 50, 62);        // Subtle outline
    public static final Color COLOR_TEXT_PRIMARY = new Color(245, 245, 247);// Pure text
    public static final Color COLOR_TEXT_MUTED = new Color(156, 163, 175); // Zinc-400
    public static final Color COLOR_EMERALD = new Color(16, 185, 129);     // Green #10b981
    public static final Color COLOR_BLUE = new Color(59, 130, 246);        // Blue #3b82f6
    public static final Color COLOR_ROSE = new Color(239, 68, 68);         // Crimson #ef4444
    public static final Color COLOR_AMBER = new Color(245, 158, 11);       // Amber #f59e0b
    public static final Color COLOR_PURPLE = new Color(139, 92, 246);      // Violet #8b5cf6

    private Path rootDir;
    private final ClusterRegistry clusterRegistry;
    private JComboBox<ClusterRegistry.ClusterProfile> clusterSelector;
    private java.awt.event.ActionListener clusterSelectorListener;

    // View Switching
    private final CardLayout masterLayout = new CardLayout();
    private final JPanel masterContainer = new JPanel(masterLayout);

    // Tab Navigation
    private final CardLayout tabLayout = new CardLayout();
    private final JPanel tabContainer = new JPanel(tabLayout);
    private ModernButton navDashboardBtn;
    private ModernButton navAdminBtn;
    private ModernButton navSettingsBtn;

    // Dashboard View Components
    private JLabel statusBadge;
    private JLabel hostSubtitle;
    private JTextField ipAddressField;
    private ModernButton copyIpBtn;
    private JSlider ramSlider;
    private JLabel ramValueLabel;
    private JLabel ramSystemInfoLabel;
    private ModernButton primaryActionBtn;
    private JPanel stepperPanel;
    private JLabel[] stepperLabels;
    private JProgressBar stepperProgressBar;
    private JTextArea liveConsoleArea;
    private JTextField commandInputField;
    private ModernButton sendCommandBtn;
    private JPanel updateBanner;
    private JLabel updateBannerText;
    private ModernButton updateDownloadBtn;

    // Admin Governance Components
    private JLabel adminClusterHeaderLabel;
    private JTextField adminInviteKeyField;
    private ModernButton adminCopyKeyBtn;
    private ModernButton adminRefreshKeyBtn;
    private ModernButton adminRemoteStopBtn;
    private ModernButton adminForceUnlockBtn;
    private JLabel adminGenLabel;
    private JLabel adminCloudLabel;

    // Settings Components
    private JTextField settingsStorageField;
    private JTextField settingsBucketField;
    private JTextField settingsKeyIdField;
    private JTextField settingsAppKeyField;
    private JTextField settingsBwLimitField;
    private JSpinner settingsHeartbeatSpinner;
    private JSpinner settingsAutosaveSpinner;

    // Runtime state
    private volatile boolean isServerRunning = false;
    private Process activeMcProcess = null;
    private Process activePlayitProcess = null;
    private BufferedWriter activeMcStdin = null;
    private ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService workerPool = Executors.newCachedThreadPool();

    public ClusterGUI(Path rootDir) {
        this.clusterRegistry = ClusterRegistry.getInstance(rootDir);
        ClusterRegistry.ClusterProfile active = clusterRegistry.getActiveCluster();
        if (active != null && !active.path.isEmpty() && Files.exists(Paths.get(active.path))) {
            this.rootDir = Paths.get(active.path);
        } else {
            this.rootDir = rootDir;
        }
        setTitle("Minecraft Cluster Orchestrator [v" + ServerLauncher.APP_VERSION + "]");
        setSize(1020, 750);
        setMinimumSize(new Dimension(880, 640));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        getContentPane().setBackground(COLOR_BG_DARK);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });

        masterContainer.setBackground(COLOR_BG_DARK);
        masterContainer.add(buildMainAppDeck(), "VIEW_MAIN_APP");
        masterContainer.add(buildOnboardingDeck(), "VIEW_ONBOARDING");
        add(masterContainer);

        if (isConfigured()) {
            masterLayout.show(masterContainer, "VIEW_MAIN_APP");
            refreshCloudLockStatus();
        } else {
            masterLayout.show(masterContainer, "VIEW_ONBOARDING");
        }

        initGitHubUpdatePolling();
    }

    private boolean isConfigured() {
        Path cfg = rootDir.resolve("config.json");
        if (!Files.exists(cfg)) return false;
        try {
            String json = Files.readString(cfg);
            String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
            String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
            String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
            return (bucket != null && !bucket.isEmpty() && !bucket.startsWith("YOUR_")
                    && keyId != null && !keyId.isEmpty() && !keyId.startsWith("YOUR_")
                    && appKey != null && !appKey.isEmpty() && !appKey.startsWith("YOUR_"));
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Master Application Deck (With Top Tabs: Dashboard, Admin, Settings)
    // =========================================================================
    private JPanel buildMainAppDeck() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(COLOR_BG_DARK);

        // Top Navigation Header
        JPanel navHeader = new JPanel(new BorderLayout(15, 0));
        navHeader.setBackground(new Color(22, 22, 28));
        navHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
                new EmptyBorder(12, 24, 12, 24)
        ));

        // Left Branding
        JPanel brandBlock = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brandBlock.setOpaque(false);
        JLabel brandTitle = new JLabel("MINECRAFT CLUSTER");
        brandTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        brandTitle.setForeground(COLOR_TEXT_PRIMARY);
        JLabel brandVer = new JLabel("v" + ServerLauncher.APP_VERSION);
        brandVer.setFont(new Font("Segoe UI", Font.BOLD, 11));
        brandVer.setForeground(COLOR_EMERALD);
        brandBlock.add(brandTitle);
        brandBlock.add(brandVer);

        // Center / Right Navigation Tabs
        JPanel tabsBlock = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tabsBlock.setOpaque(false);

        navDashboardBtn = new ModernButton("🎮 Dashboard", COLOR_EMERALD, Color.WHITE, 12, 10);
        navAdminBtn = new ModernButton("🛡️ Cluster Admin", COLOR_BG_CARD, COLOR_TEXT_MUTED, 12, 10);
        navSettingsBtn = new ModernButton("⚙️ Settings", COLOR_BG_CARD, COLOR_TEXT_MUTED, 12, 10);

        navDashboardBtn.setPreferredSize(new Dimension(135, 36));
        navAdminBtn.setPreferredSize(new Dimension(150, 36));
        navSettingsBtn.setPreferredSize(new Dimension(120, 36));

        navDashboardBtn.addActionListener(e -> switchTab("TAB_DASHBOARD"));
        navAdminBtn.addActionListener(e -> switchTab("TAB_ADMIN"));
        navSettingsBtn.addActionListener(e -> switchTab("TAB_SETTINGS"));

        tabsBlock.add(navDashboardBtn);
        tabsBlock.add(navAdminBtn);
        tabsBlock.add(navSettingsBtn);

        navHeader.add(brandBlock, BorderLayout.WEST);
        navHeader.add(tabsBlock, BorderLayout.EAST);

        // Tab Container
        tabContainer.setBackground(COLOR_BG_DARK);
        tabContainer.add(buildDashboardTab(), "TAB_DASHBOARD");
        tabContainer.add(buildClusterAdminTab(), "TAB_ADMIN");
        tabContainer.add(buildSettingsTab(), "TAB_SETTINGS");

        panel.add(navHeader, BorderLayout.NORTH);
        panel.add(tabContainer, BorderLayout.CENTER);

        return panel;
    }

    private void switchTab(String tabKey) {
        tabLayout.show(tabContainer, tabKey);

        navDashboardBtn.setBackgroundColor("TAB_DASHBOARD".equals(tabKey) ? COLOR_EMERALD : COLOR_BG_CARD);
        navDashboardBtn.setForeground("TAB_DASHBOARD".equals(tabKey) ? Color.WHITE : COLOR_TEXT_MUTED);

        navAdminBtn.setBackgroundColor("TAB_ADMIN".equals(tabKey) ? COLOR_BLUE : COLOR_BG_CARD);
        navAdminBtn.setForeground("TAB_ADMIN".equals(tabKey) ? Color.WHITE : COLOR_TEXT_MUTED);

        navSettingsBtn.setBackgroundColor("TAB_SETTINGS".equals(tabKey) ? new Color(107, 114, 128) : COLOR_BG_CARD);
        navSettingsBtn.setForeground("TAB_SETTINGS".equals(tabKey) ? Color.WHITE : COLOR_TEXT_MUTED);

        if ("TAB_ADMIN".equals(tabKey)) {
            refreshAdminTabState();
        } else if ("TAB_DASHBOARD".equals(tabKey)) {
            refreshCloudLockStatus();
        }
    }

    // =========================================================================
    // TAB 1: Dashboard (Pre-Launch & Live Runtime Observation)
    // =========================================================================
    private JPanel buildDashboardTab() {
        JPanel dashboard = new JPanel(new BorderLayout(0, 12));
        dashboard.setBackground(COLOR_BG_DARK);
        dashboard.setBorder(new EmptyBorder(16, 22, 16, 22));

        // Subheader: Host info and status radar
        JPanel subHeader = new JPanel(new BorderLayout(10, 0));
        subHeader.setOpaque(false);

        JPanel hostInfo = new JPanel(new GridLayout(2, 1, 0, 4));
        hostInfo.setOpaque(false);

        JPanel clusterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        clusterRow.setOpaque(false);

        JLabel iconLbl = new JLabel("🎮");
        iconLbl.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));

        clusterSelector = new JComboBox<>();
        clusterSelector.setFont(new Font("Segoe UI", Font.BOLD, 14));
        clusterSelector.setPreferredSize(new Dimension(280, 32));
        styleDarkComboBox(clusterSelector);

        clusterSelectorListener = e -> onClusterSelected();
        refreshClusterSelectorItems();

        ModernButton addClusterBtn = new ModernButton("➕", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 12, 4);
        addClusterBtn.setToolTipText("Import or Create New Cluster Profile");
        addClusterBtn.setPreferredSize(new Dimension(36, 32));
        addClusterBtn.addActionListener(e -> showImportClusterDialog());

        clusterRow.add(iconLbl);
        clusterRow.add(clusterSelector);
        clusterRow.add(addClusterBtn);

        hostSubtitle = new JLabel("Host: " + ServerLauncher.getHostName() + " | Local Storage: " + rootDir.toString());
        hostSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hostSubtitle.setForeground(COLOR_TEXT_MUTED);

        hostInfo.add(clusterRow);
        hostInfo.add(hostSubtitle);

        JPanel radarBlock = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        radarBlock.setOpaque(false);
        statusBadge = new JLabel("● Checking Cluster Availability...");
        statusBadge.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusBadge.setForeground(COLOR_TEXT_MUTED);

        ModernButton refreshBtn = new ModernButton("⟳ Refresh", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 8);
        refreshBtn.setPreferredSize(new Dimension(90, 30));
        refreshBtn.addActionListener(e -> refreshCloudLockStatus());

        radarBlock.add(statusBadge);
        radarBlock.add(refreshBtn);

        subHeader.add(hostInfo, BorderLayout.WEST);
        subHeader.add(radarBlock, BorderLayout.EAST);

        // Main Center Workspace
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);

        // Control & Memory Card
        ModernCard controlCard = new ModernCard();
        controlCard.setLayout(new BorderLayout(20, 12));
        controlCard.setBorder(new EmptyBorder(16, 20, 16, 20));

        // Left: Big Primary Action Button
        primaryActionBtn = new ModernButton("▶  START CLUSTER SERVER", COLOR_EMERALD, Color.WHITE, 15, 12);
        primaryActionBtn.setPreferredSize(new Dimension(320, 60));
        primaryActionBtn.addActionListener(e -> toggleServerAction());

        // Center: Dynamic RAM Allocation Slider
        JPanel ramBlock = new JPanel(new BorderLayout(0, 6));
        ramBlock.setOpaque(false);

        int totalSysRam = detectTotalPhysicalRamGb();
        int maxSlider = Math.max(4, totalSysRam - 3);
        int defaultRam = Math.min(8, Math.max(2, totalSysRam / 2));

        ramValueLabel = new JLabel("Memory Allocation: " + defaultRam + " GB RAM");
        ramValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        ramValueLabel.setForeground(COLOR_TEXT_PRIMARY);

        ramSystemInfoLabel = new JLabel("System Memory: " + totalSysRam + " GB Total | Recommended: 6 GB - 12 GB");
        ramSystemInfoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ramSystemInfoLabel.setForeground(COLOR_TEXT_MUTED);

        ramSlider = new JSlider(2, maxSlider, defaultRam);
        ramSlider.setOpaque(false);
        ramSlider.setMajorTickSpacing(2);
        ramSlider.setPaintTicks(true);
        ramSlider.addChangeListener(e -> {
            int val = ramSlider.getValue();
            ramValueLabel.setText("Memory Allocation: " + val + ".0 GB RAM");
            persistConfigRam(val + "G");
        });

        JPanel ramLabelsStack = new JPanel(new GridLayout(2, 1, 0, 2));
        ramLabelsStack.setOpaque(false);
        ramLabelsStack.add(ramValueLabel);
        ramLabelsStack.add(ramSystemInfoLabel);

        ramBlock.add(ramLabelsStack, BorderLayout.NORTH);
        ramBlock.add(ramSlider, BorderLayout.CENTER);

        // Right: Connection Address / Copy IP
        JPanel ipBlock = new JPanel(new GridLayout(2, 1, 0, 4));
        ipBlock.setOpaque(false);
        ipBlock.setPreferredSize(new Dimension(220, 56));

        JLabel addrLabel = new JLabel("Connection Address (PlayIt):");
        addrLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        addrLabel.setForeground(COLOR_TEXT_MUTED);

        JPanel copyIpRow = new JPanel(new BorderLayout(6, 0));
        copyIpRow.setOpaque(false);
        ipAddressField = createDarkTextFieldWithText(resolveDynamicServerAddress());
        ipAddressField.setEditable(false);
        copyIpBtn = new ModernButton("COPY IP", COLOR_BLUE, Color.WHITE, 11, 6);
        copyIpBtn.setPreferredSize(new Dimension(85, 28));
        copyIpBtn.addActionListener(e -> {
            copyToClipboard(ipAddressField.getText().trim());
            copyIpBtn.setText("COPIED!");
            Timer t = new Timer(1500, ev -> copyIpBtn.setText("COPY IP"));
            t.setRepeats(false);
            t.start();
        });
        copyIpRow.add(ipAddressField, BorderLayout.CENTER);
        copyIpRow.add(copyIpBtn, BorderLayout.EAST);

        ipBlock.add(addrLabel);
        ipBlock.add(copyIpRow);

        controlCard.add(primaryActionBtn, BorderLayout.WEST);
        controlCard.add(ramBlock, BorderLayout.CENTER);
        controlCard.add(ipBlock, BorderLayout.EAST);

        // Milestone Progress Stepper Card
        stepperPanel = buildMilestoneStepperCard();

        // Live Console and Command Deck
        ModernCard consoleCard = new ModernCard();
        consoleCard.setLayout(new BorderLayout(0, 8));
        consoleCard.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel consoleHeader = new JLabel("Live Server Console & Command Stream");
        consoleHeader.setFont(new Font("Segoe UI", Font.BOLD, 12));
        consoleHeader.setForeground(COLOR_TEXT_MUTED);

        liveConsoleArea = new JTextArea();
        liveConsoleArea.setBackground(new Color(12, 12, 15));
        liveConsoleArea.setForeground(new Color(228, 228, 231));
        liveConsoleArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        liveConsoleArea.setEditable(false);
        liveConsoleArea.setLineWrap(true);
        liveConsoleArea.setWrapStyleWord(true);
        JScrollPane consoleScroll = new JScrollPane(liveConsoleArea);
        consoleScroll.setBorder(new LineBorder(COLOR_BORDER, 1));
        consoleScroll.setPreferredSize(new Dimension(800, 240));

        JPanel cmdRow = new JPanel(new BorderLayout(8, 0));
        cmdRow.setOpaque(false);
        JLabel promptSymbol = new JLabel("Console: ");
        promptSymbol.setFont(new Font("Consolas", Font.BOLD, 13));
        promptSymbol.setForeground(COLOR_EMERALD);

        commandInputField = createDarkTextField("Type command (e.g. op Player, whitelist add Player, stop)...");
        commandInputField.setFont(new Font("Consolas", Font.PLAIN, 13));
        commandInputField.addActionListener(e -> dispatchConsoleCommand());

        sendCommandBtn = new ModernButton("EXECUTE", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        sendCommandBtn.setPreferredSize(new Dimension(95, 32));
        sendCommandBtn.addActionListener(e -> dispatchConsoleCommand());

        cmdRow.add(promptSymbol, BorderLayout.WEST);
        cmdRow.add(commandInputField, BorderLayout.CENTER);
        cmdRow.add(sendCommandBtn, BorderLayout.EAST);

        consoleCard.add(consoleHeader, BorderLayout.NORTH);
        consoleCard.add(consoleScroll, BorderLayout.CENTER);
        consoleCard.add(cmdRow, BorderLayout.SOUTH);

        // Assemble Upper Deck
        JPanel upperDeck = new JPanel(new BorderLayout(0, 10));
        upperDeck.setOpaque(false);
        upperDeck.add(controlCard, BorderLayout.NORTH);
        upperDeck.add(stepperPanel, BorderLayout.SOUTH);

        center.add(upperDeck, BorderLayout.NORTH);
        center.add(consoleCard, BorderLayout.CENTER);

        // Bottom: Update Notification Banner
        updateBanner = buildUpdateBannerPanel();

        dashboard.add(subHeader, BorderLayout.NORTH);
        dashboard.add(center, BorderLayout.CENTER);
        dashboard.add(updateBanner, BorderLayout.SOUTH);

        return dashboard;
    }

    private JPanel buildMilestoneStepperCard() {
        ModernCard card = new ModernCard();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 16, 10, 16));

        String[] titles = {
                "1. Update Check",
                "2. Cloud Lock",
                "3. Cloud Sync",
                "4. Engine Boot",
                "5. Server Ready"
        };

        JPanel stepsRow = new JPanel(new GridLayout(1, 5, 10, 0));
        stepsRow.setOpaque(false);
        stepperLabels = new JLabel[5];

        for (int i = 0; i < 5; i++) {
            JLabel lbl = new JLabel(titles[i], SwingConstants.CENTER);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
            lbl.setForeground(COLOR_TEXT_MUTED);
            stepperLabels[i] = lbl;
            stepsRow.add(lbl);
        }

        stepperProgressBar = new JProgressBar(0, 100);
        stepperProgressBar.setValue(0);
        stepperProgressBar.setPreferredSize(new Dimension(800, 6));
        stepperProgressBar.setForeground(COLOR_EMERALD);
        stepperProgressBar.setBackground(COLOR_BG_INPUT);
        stepperProgressBar.setBorderPainted(false);

        card.add(stepsRow, BorderLayout.NORTH);
        card.add(stepperProgressBar, BorderLayout.SOUTH);
        return card;
    }

    private void updateMilestoneStep(int index, String text, Color color, int progressPct) {
        SwingUtilities.invokeLater(() -> {
            if (index >= 0 && index < stepperLabels.length) {
                stepperLabels[index].setText(text);
                stepperLabels[index].setForeground(color);
            }
            stepperProgressBar.setValue(progressPct);
        });
    }

    private void resetMilestones() {
        String[] titles = {
                "1. Update Check",
                "2. Cloud Lock",
                "3. Cloud Sync",
                "4. Engine Boot",
                "5. Server Ready"
        };
        for (int i = 0; i < stepperLabels.length; i++) {
            updateMilestoneStep(i, titles[i], COLOR_TEXT_MUTED, 0);
        }
    }

    private JPanel buildUpdateBannerPanel() {
        JPanel banner = new JPanel(new BorderLayout(12, 0));
        banner.setBackground(new Color(24, 38, 54));
        banner.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(COLOR_BLUE, 1, true),
                new EmptyBorder(8, 16, 8, 16)
        ));
        banner.setVisible(false);

        updateBannerText = new JLabel("🚀 A new platform release is available on GitHub!");
        updateBannerText.setFont(new Font("Segoe UI", Font.BOLD, 12));
        updateBannerText.setForeground(COLOR_TEXT_PRIMARY);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        updateDownloadBtn = new ModernButton("Update & Restart", COLOR_BLUE, Color.WHITE, 12, 6);
        ModernButton dismissBtn = new ModernButton("Dismiss", COLOR_BG_CARD, COLOR_TEXT_MUTED, 12, 6);
        dismissBtn.addActionListener(e -> banner.setVisible(false));

        actions.add(updateDownloadBtn);
        actions.add(dismissBtn);

        banner.add(updateBannerText, BorderLayout.CENTER);
        banner.add(actions, BorderLayout.EAST);
        return banner;
    }

    // =========================================================================
    // TAB 2: Cluster Admin (The Exact Governance Deck)
    // =========================================================================
    private JPanel buildClusterAdminTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(COLOR_BG_DARK);
        panel.setBorder(new EmptyBorder(20, 30, 20, 30));

        // Header Title
        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setOpaque(false);
        adminClusterHeaderLabel = new JLabel("CLUSTER GOVERNANCE (Dorm Craft SMP)");
        adminClusterHeaderLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        adminClusterHeaderLabel.setForeground(COLOR_TEXT_PRIMARY);

        JLabel headerSubtitle = new JLabel("Administrative operational controls, security unlock routines, and co-host invite key management.");
        headerSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        headerSubtitle.setForeground(COLOR_TEXT_MUTED);
        header.add(adminClusterHeaderLabel);
        header.add(headerSubtitle);

        // Content Body
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        // Section 1: Cohost Access & Invite Key
        ModernCard inviteCard = new ModernCard();
        inviteCard.setLayout(new BorderLayout(10, 8));
        inviteCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        inviteCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 115));

        JLabel inviteTitle = new JLabel("COHOST ACCESS & INVITE KEY");
        inviteTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        inviteTitle.setForeground(COLOR_TEXT_PRIMARY);

        JPanel keyRow = new JPanel(new BorderLayout(8, 0));
        keyRow.setOpaque(false);
        keyRow.setPreferredSize(new Dimension(0, 34));
        keyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        adminInviteKeyField = createDarkTextFieldWithText("mc-cluster://...");
        adminInviteKeyField.setFont(new Font("Consolas", Font.PLAIN, 12));
        adminInviteKeyField.setEditable(false);
        adminInviteKeyField.setPreferredSize(new Dimension(0, 34));

        JPanel keyActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        keyActions.setOpaque(false);
        adminCopyKeyBtn = new ModernButton("COPY KEY", COLOR_BLUE, Color.WHITE, 12, 6);
        adminCopyKeyBtn.setPreferredSize(new Dimension(100, 32));
        adminCopyKeyBtn.addActionListener(e -> {
            copyToClipboard(adminInviteKeyField.getText().trim());
            adminCopyKeyBtn.setText("COPIED!");
            Timer t = new Timer(1500, ev -> adminCopyKeyBtn.setText("COPY KEY"));
            t.setRepeats(false);
            t.start();
        });

        adminRefreshKeyBtn = new ModernButton("REFRESH", COLOR_BG_CARD, COLOR_TEXT_MUTED, 11, 6);
        adminRefreshKeyBtn.setPreferredSize(new Dimension(90, 32));
        adminRefreshKeyBtn.addActionListener(e -> generateAndDisplayInviteToken());

        keyActions.add(adminCopyKeyBtn);
        keyActions.add(adminRefreshKeyBtn);

        keyRow.add(adminInviteKeyField, BorderLayout.CENTER);
        keyRow.add(keyActions, BorderLayout.EAST);

        JLabel shareNote = new JLabel("Share this key with trusted friends so they can import it and co-host anytime with zero setup.");
        shareNote.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        shareNote.setForeground(COLOR_TEXT_MUTED);

        inviteCard.add(inviteTitle, BorderLayout.NORTH);
        inviteCard.add(keyRow, BorderLayout.CENTER);
        inviteCard.add(shareNote, BorderLayout.SOUTH);

        // Section 1.5: Zero-Bandwidth Cohost Starter Pack (Export & Import)
        ModernCard starterPackCard = new ModernCard();
        starterPackCard.setLayout(new BorderLayout(10, 8));
        starterPackCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        starterPackCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 135));

        JLabel starterTitle = new JLabel("COHOST STARTER PACK (ZERO CLOUD EGRESS)");
        starterTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        starterTitle.setForeground(COLOR_EMERALD);

        JLabel starterDesc = new JLabel("Package world, plugins, configs, and runtime into cohost-starterpack.zip to invite co-hosts without using Backblaze download quota.");
        starterDesc.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        starterDesc.setForeground(COLOR_TEXT_MUTED);

        JPanel starterActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        starterActions.setOpaque(false);

        ModernButton exportZipBtn = new ModernButton("📦 EXPORT STARTER PACK (.zip)", COLOR_EMERALD, Color.WHITE, 12, 8);
        exportZipBtn.setPreferredSize(new Dimension(240, 42));
        exportZipBtn.addActionListener(e -> exportStarterPackAction());

        ModernButton importZipBtn = new ModernButton("📁 IMPORT STARTER PACK (.zip)", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 12, 8);
        importZipBtn.setPreferredSize(new Dimension(240, 42));
        importZipBtn.addActionListener(e -> showImportClusterDialog());

        starterActions.add(exportZipBtn);
        starterActions.add(Box.createHorizontalStrut(12));
        starterActions.add(importZipBtn);

        starterPackCard.add(starterTitle, BorderLayout.NORTH);
        starterPackCard.add(starterDesc, BorderLayout.CENTER);
        starterPackCard.add(starterActions, BorderLayout.SOUTH);

        // Section 2: Operational Controls (Remote Stop, Force Unlock, & Cloud Config Sync)
        ModernCard opsCard = new ModernCard();
        opsCard.setLayout(new GridLayout(4, 1, 0, 10));
        opsCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        opsCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));

        // Sub-Card A: Remote Stop
        JPanel stopRow = new JPanel(new BorderLayout(12, 0));
        stopRow.setOpaque(false);
        adminRemoteStopBtn = new ModernButton("⛔ REQUEST REMOTE STOP", COLOR_ROSE, Color.WHITE, 12, 8);
        adminRemoteStopBtn.setPreferredSize(new Dimension(240, 42));
        adminRemoteStopBtn.addActionListener(e -> handleRemoteStopRequest());

        JPanel stopInfo = new JPanel(new GridLayout(2, 1, 0, 2));
        stopInfo.setOpaque(false);
        JLabel stopTitle = new JLabel("Order Graceful Remote Shutdown");
        stopTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        stopTitle.setForeground(COLOR_TEXT_PRIMARY);
        JLabel stopDesc = new JLabel("Broadcasts a 30s countdown to the active host and orders a graceful save and cloud sync.");
        stopDesc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        stopDesc.setForeground(COLOR_TEXT_MUTED);
        stopInfo.add(stopTitle);
        stopInfo.add(stopDesc);

        stopRow.add(adminRemoteStopBtn, BorderLayout.WEST);
        stopRow.add(stopInfo, BorderLayout.CENTER);

        // Sub-Card B: Emergency Force Unlock
        JPanel unlockRow = new JPanel(new BorderLayout(12, 0));
        unlockRow.setOpaque(false);
        adminForceUnlockBtn = new ModernButton("🔓 EMERGENCY FORCE UNLOCK", COLOR_AMBER, Color.WHITE, 12, 8);
        adminForceUnlockBtn.setPreferredSize(new Dimension(240, 42));
        adminForceUnlockBtn.addActionListener(e -> handleEmergencyUnlockRequest());

        JPanel unlockInfo = new JPanel(new GridLayout(2, 1, 0, 2));
        unlockInfo.setOpaque(false);
        JLabel unlockTitle = new JLabel("Release Stuck Distributed Cloud Lock");
        unlockTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        unlockTitle.setForeground(COLOR_TEXT_PRIMARY);
        JLabel unlockDesc = new JLabel("Releases stuck cloud lock if a hoster suffered a hard crash or power outage.");
        unlockDesc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        unlockDesc.setForeground(COLOR_TEXT_MUTED);
        unlockInfo.add(unlockTitle);
        unlockInfo.add(unlockDesc);

        unlockRow.add(adminForceUnlockBtn, BorderLayout.WEST);
        unlockRow.add(unlockInfo, BorderLayout.CENTER);

        // Sub-Card C: Cloud Runtime & Config Distribution
        JPanel syncDistRow = new JPanel(new BorderLayout(12, 0));
        syncDistRow.setOpaque(false);
        ModernButton adminSyncDistBtn = new ModernButton("☁️ SYNC CONFIGS & PLUGINS", COLOR_BLUE, Color.WHITE, 12, 8);
        adminSyncDistBtn.setPreferredSize(new Dimension(240, 42));
        adminSyncDistBtn.addActionListener(e -> handlePublishAdminDistribution(adminSyncDistBtn));

        JPanel syncDistInfo = new JPanel(new GridLayout(2, 1, 0, 2));
        syncDistInfo.setOpaque(false);
        JLabel syncDistTitle = new JLabel("Push Server Runtime, Plugins & Configurations");
        syncDistTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        syncDistTitle.setForeground(COLOR_TEXT_PRIMARY);
        JLabel syncDistDesc = new JLabel("Uploads plugins/, server.properties, spigot/bukkit configs, and binaries to the cluster cloud.");
        syncDistDesc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        syncDistDesc.setForeground(COLOR_TEXT_MUTED);
        syncDistInfo.add(syncDistTitle);
        syncDistInfo.add(syncDistDesc);

        syncDistRow.add(adminSyncDistBtn, BorderLayout.WEST);
        syncDistRow.add(syncDistInfo, BorderLayout.CENTER);

        // Sub-Card D: Cloud Storage Cleanup
        JPanel cleanStorageRow = new JPanel(new BorderLayout(12, 0));
        cleanStorageRow.setOpaque(false);
        ModernButton adminCleanStorageBtn = new ModernButton("🧹 CLEAN CLOUD STORAGE", COLOR_PURPLE, Color.WHITE, 12, 8);
        adminCleanStorageBtn.setPreferredSize(new Dimension(240, 42));
        adminCleanStorageBtn.addActionListener(e -> handleCleanStorage(adminCleanStorageBtn));

        JPanel cleanStorageInfo = new JPanel(new GridLayout(2, 1, 0, 2));
        cleanStorageInfo.setOpaque(false);
        JLabel cleanStorageTitle = new JLabel("Purge Dead Chunk Revisions & Free Storage");
        cleanStorageTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        cleanStorageTitle.setForeground(COLOR_TEXT_PRIMARY);
        JLabel cleanStorageDesc = new JLabel("Deletes hidden superseded chunk revisions on B2, preserving the latest active save.");
        cleanStorageDesc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        cleanStorageDesc.setForeground(COLOR_TEXT_MUTED);
        cleanStorageInfo.add(cleanStorageTitle);
        cleanStorageInfo.add(cleanStorageDesc);

        cleanStorageRow.add(adminCleanStorageBtn, BorderLayout.WEST);
        cleanStorageRow.add(cleanStorageInfo, BorderLayout.CENTER);

        opsCard.add(stopRow);
        opsCard.add(unlockRow);
        opsCard.add(syncDistRow);
        opsCard.add(cleanStorageRow);

        // Section 3: World State & Storage Shortcuts
        ModernCard stateCard = new ModernCard();
        stateCard.setLayout(new BorderLayout(12, 0));
        stateCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        stateCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 85));

        JPanel statsStack = new JPanel(new GridLayout(2, 1, 0, 4));
        statsStack.setOpaque(false);
        adminGenLabel = new JLabel("Active World Generation: #14 (Latest Verified)");
        adminGenLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        adminGenLabel.setForeground(COLOR_TEXT_PRIMARY);

        adminCloudLabel = new JLabel("Cloud Provider: Backblaze B2 (Isolated Bucket) | Anti-Collision OCC Active");
        adminCloudLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        adminCloudLabel.setForeground(COLOR_TEXT_MUTED);
        statsStack.add(adminGenLabel);
        statsStack.add(adminCloudLabel);

        JPanel folderActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        folderActions.setOpaque(false);

        ModernButton openDirBtn = new ModernButton("Open Server Folder", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        openDirBtn.addActionListener(e -> openPathInExplorer(rootDir));

        ModernButton openPluginsBtn = new ModernButton("Open Plugins Folder", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        openPluginsBtn.addActionListener(e -> openPathInExplorer(rootDir.resolve("plugins")));

        folderActions.add(openDirBtn);
        folderActions.add(openPluginsBtn);

        stateCard.add(statsStack, BorderLayout.CENTER);
        stateCard.add(folderActions, BorderLayout.EAST);

        body.add(inviteCard);
        body.add(Box.createVerticalStrut(14));
        body.add(starterPackCard);
        body.add(Box.createVerticalStrut(14));
        body.add(opsCard);
        body.add(Box.createVerticalStrut(14));
        body.add(stateCard);
        body.add(Box.createVerticalGlue());

        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);

        return panel;
    }

    private void refreshAdminTabState() {
        generateAndDisplayInviteToken();
        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                if (!Files.exists(cfg)) return;
                String json = Files.readString(cfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);

                int cloudGen = ServerLauncher.readCloudGeneration(rootDir, rclone, b2Target);
                int localGen = ServerLauncher.readLocalGeneration(rootDir);

                SwingUtilities.invokeLater(() -> {
                    adminGenLabel.setText("Active World Generation: #" + cloudGen + " (Local Base: #" + localGen + ")");
                    adminCloudLabel.setText("Cloud Provider: Backblaze B2 (" + bucket + ") | Anti-Collision Active");
                });
            } catch (Exception ignored) {}
        });
    }

    private void generateAndDisplayInviteToken() {
        try {
            Path cfg = rootDir.resolve("config.json");
            if (!Files.exists(cfg)) return;
            String json = Files.readString(cfg);
            String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
            String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
            String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
            int stale = ServerLauncher.extractJsonInt(json, "stale_timeout_minutes", 10);
            String bw = ServerLauncher.extractJsonString(json, "b2_bandwidth_limit");
            if (bw == null || bw.isEmpty()) bw = "10M";

            ClusterToken.ClusterConfig config = new ClusterToken.ClusterConfig(bucket, keyId, appKey, stale, bw, null, null);
            String token = ClusterToken.encode(config);
            adminInviteKeyField.setText(token);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // TAB 3: Settings (Directory Selection, Backblaze B2 Keys & Throttle Limits)
    // =========================================================================
    private JPanel buildSettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(COLOR_BG_DARK);
        panel.setBorder(new EmptyBorder(20, 30, 20, 30));

        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setOpaque(false);
        JLabel title = new JLabel("Cluster Settings & Configuration");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(COLOR_TEXT_PRIMARY);
        JLabel sub = new JLabel("Adjust local storage pathways, Backblaze B2 credentials, and sync throttle limits.");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(COLOR_TEXT_MUTED);
        header.add(title);
        header.add(sub);

        ModernCard formCard = new ModernCard();
        formCard.setLayout(new BoxLayout(formCard, BoxLayout.Y_AXIS));
        formCard.setBorder(new EmptyBorder(20, 24, 20, 24));

        // Row 1: Storage directory
        JPanel pathRow = new JPanel(new BorderLayout(10, 0));
        pathRow.setOpaque(false);
        pathRow.setPreferredSize(new Dimension(0, 36));
        pathRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        settingsStorageField = createDarkTextFieldWithText(rootDir.toString());
        settingsStorageField.setPreferredSize(new Dimension(0, 36));
        settingsStorageField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        ModernButton browseBtn = new ModernButton("Browse...", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        browseBtn.setPreferredSize(new Dimension(90, 36));
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(rootDir.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                settingsStorageField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JLabel pathLbl = new JLabel("Storage Directory:");
        pathLbl.setPreferredSize(new Dimension(180, 36));
        pathLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        pathLbl.setForeground(COLOR_TEXT_MUTED);

        pathRow.add(pathLbl, BorderLayout.WEST);
        pathRow.add(settingsStorageField, BorderLayout.CENTER);
        pathRow.add(browseBtn, BorderLayout.EAST);

        // Row 2: Bucket Name
        settingsBucketField = createDarkTextField("e.g. my-minecraft-cluster");
        // Row 3: Key ID
        settingsKeyIdField = createDarkTextField("e.g. 0046c0c9ef987000000000001");
        // Row 4: Application Key
        settingsAppKeyField = createDarkTextField("e.g. K004XKwcFTHgMQFL64OGZixY0MniwLI");
        // Row 5: Bandwidth Cap
        settingsBwLimitField = createDarkTextField("e.g. 10M (optional)");

        // Row 6: Heartbeat and Autosave spinners
        JPanel timingRow = new JPanel(new GridLayout(1, 2, 20, 0));
        timingRow.setOpaque(false);
        timingRow.setPreferredSize(new Dimension(0, 36));
        timingRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JPanel hbBlock = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        hbBlock.setOpaque(false);
        JLabel hbLbl = new JLabel("Heartbeat Interval (sec):");
        hbLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        hbLbl.setForeground(COLOR_TEXT_MUTED);
        hbBlock.add(hbLbl);
        settingsHeartbeatSpinner = new JSpinner(new SpinnerNumberModel(120, 30, 600, 10));
        styleDarkSpinner(settingsHeartbeatSpinner, 85, 32);
        hbBlock.add(settingsHeartbeatSpinner);

        JPanel asBlock = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        asBlock.setOpaque(false);
        JLabel asLbl = new JLabel("Autosave Snapshot (min):");
        asLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        asLbl.setForeground(COLOR_TEXT_MUTED);
        asBlock.add(asLbl);
        settingsAutosaveSpinner = new JSpinner(new SpinnerNumberModel(15, 5, 120, 5));
        styleDarkSpinner(settingsAutosaveSpinner, 85, 32);
        asBlock.add(settingsAutosaveSpinner);

        timingRow.add(hbBlock);
        timingRow.add(asBlock);

        formCard.add(pathRow);
        formCard.add(Box.createVerticalStrut(12));
        formCard.add(createLabeledRow("Backblaze Bucket:", settingsBucketField));
        formCard.add(Box.createVerticalStrut(12));
        formCard.add(createLabeledRow("Application Key ID:", settingsKeyIdField));
        formCard.add(Box.createVerticalStrut(12));
        formCard.add(createLabeledRow("Application Key Secret:", settingsAppKeyField));
        formCard.add(Box.createVerticalStrut(12));
        formCard.add(createLabeledRow("Upload Bandwidth Limit:", settingsBwLimitField));
        formCard.add(Box.createVerticalStrut(12));
        formCard.add(timingRow);

        // Populate initial values
        loadSettingsIntoFields();

        // Footer Actions
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footer.setOpaque(false);

        ModernButton saveBtn = new ModernButton("💾  Save Configuration", COLOR_EMERALD, Color.WHITE, 13, 8);
        saveBtn.setPreferredSize(new Dimension(180, 40));
        saveBtn.addActionListener(e -> saveSettingsFromFields());

        footer.add(saveBtn);

        JPanel contentWrap = new JPanel(new BorderLayout());
        contentWrap.setOpaque(false);
        contentWrap.add(formCard, BorderLayout.NORTH);

        panel.add(header, BorderLayout.NORTH);
        panel.add(contentWrap, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createLabeledRow(String label, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(0, 36));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(180, 36));
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(COLOR_TEXT_MUTED);

        comp.setPreferredSize(new Dimension(0, 36));
        comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        p.add(l, BorderLayout.WEST);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    private void loadSettingsIntoFields() {
        try {
            Path cfg = rootDir.resolve("config.json");
            if (!Files.exists(cfg)) return;
            String json = Files.readString(cfg);
            String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
            String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
            String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
            String bw = ServerLauncher.extractJsonString(json, "b2_bandwidth_limit");

            settingsBucketField.setText(bucket != null ? bucket : "");
            settingsKeyIdField.setText(keyId != null ? keyId : "");
            settingsAppKeyField.setText(appKey != null ? appKey : "");
            settingsBwLimitField.setText(bw != null ? bw : "");

            settingsHeartbeatSpinner.setValue(ServerLauncher.extractJsonInt(json, "heartbeat_seconds", 120));
            settingsAutosaveSpinner.setValue(ServerLauncher.extractJsonInt(json, "save_interval_minutes", 15));
        } catch (Exception ignored) {}
    }

    private void saveSettingsFromFields() {
        try {
            Path cfg = rootDir.resolve("config.json");
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("  \"b2_bucket\": \"").append(settingsBucketField.getText().trim()).append("\",\n");
            sb.append("  \"b2_key_id\": \"").append(settingsKeyIdField.getText().trim()).append("\",\n");
            sb.append("  \"b2_application_key\": \"").append(settingsAppKeyField.getText().trim()).append("\",\n");
            sb.append("  \"java_memory\": \"").append(ramSlider.getValue()).append("G\",\n");
            sb.append("  \"stale_timeout_minutes\": 10,\n");
            sb.append("  \"b2_bandwidth_limit\": \"").append(settingsBwLimitField.getText().trim()).append("\",\n");
            sb.append("  \"heartbeat_seconds\": ").append(settingsHeartbeatSpinner.getValue()).append(",\n");
            sb.append("  \"save_interval_minutes\": ").append(settingsAutosaveSpinner.getValue()).append("\n");
            sb.append("}\n");
            Files.writeString(cfg, sb.toString());
            JOptionPane.showMessageDialog(this, "Configuration successfully saved!", "Settings Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to save settings: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Onboarding Deck (The First-Run Wizard Matching Wireframe Phase 1 & 4)
    // =========================================================================
    private JPanel buildOnboardingDeck() {
        JPanel deck = new JPanel(new BorderLayout());
        deck.setBackground(COLOR_BG_DARK);
        deck.setBorder(new EmptyBorder(30, 40, 30, 40));

        CardLayout wizardCards = new CardLayout();
        JPanel wizardContainer = new JPanel(wizardCards);
        wizardContainer.setOpaque(false);

        // Screen 1: Welcome Choice
        JPanel choiceScreen = new JPanel(new BorderLayout(0, 24));
        choiceScreen.setOpaque(false);

        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setOpaque(false);
        JLabel title = new JLabel("Welcome to Minecraft Cluster Orchestrator", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(COLOR_TEXT_PRIMARY);

        JLabel sub = new JLabel("Distributed, 24/7 multiplayer hosting across multiple friends' PCs with zero server rental costs.", SwingConstants.CENTER);
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        sub.setForeground(COLOR_TEXT_MUTED);
        header.add(title);
        header.add(sub);

        JPanel choiceRow = new JPanel(new GridLayout(1, 3, 20, 0));
        choiceRow.setOpaque(false);
        choiceRow.setBorder(new EmptyBorder(20, 20, 30, 20));

        // Card A: Create a Cluster (Admin)
        ModernCard createCard = new ModernCard();
        createCard.setLayout(new BorderLayout(0, 14));
        createCard.setBorder(new EmptyBorder(26, 24, 26, 24));

        JLabel createIconTitle = new JLabel("👑 CREATE A CLUSTER", SwingConstants.CENTER);
        createIconTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        createIconTitle.setForeground(COLOR_EMERALD);

        JTextArea createText = new JTextArea("Host your own shared world with friends using free Backblaze B2. You will generate an invite key to share with your co-hosts and choose whether to adopt an existing server or create a fresh Paper server.");
        createText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        createText.setForeground(COLOR_TEXT_MUTED);
        createText.setLineWrap(true);
        createText.setWrapStyleWord(true);
        createText.setOpaque(false);
        createText.setEditable(false);

        ModernButton createAction = new ModernButton("Set Up Cluster (Admin)", COLOR_EMERALD, Color.WHITE, 13, 8);
        createAction.setPreferredSize(new Dimension(200, 44));
        createAction.addActionListener(e -> wizardCards.show(wizardContainer, "WIZARD_CREATE"));

        createCard.add(createIconTitle, BorderLayout.NORTH);
        createCard.add(createText, BorderLayout.CENTER);
        createCard.add(createAction, BorderLayout.SOUTH);

        // Card B: Join a Cluster (Cohost)
        ModernCard joinCard = new ModernCard();
        joinCard.setLayout(new BorderLayout(0, 14));
        joinCard.setBorder(new EmptyBorder(26, 24, 26, 24));

        JLabel joinIconTitle = new JLabel("👥 JOIN A CLUSTER", SwingConstants.CENTER);
        joinIconTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        joinIconTitle.setForeground(COLOR_BLUE);

        JTextArea joinText = new JTextArea("Connect to an existing cluster hosted by a friend. Simply paste the invite key (mc-cluster://) they gave you, select where on your PC to store files, and start hosting sessions with zero hassle.");
        joinText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        joinText.setForeground(COLOR_TEXT_MUTED);
        joinText.setLineWrap(true);
        joinText.setWrapStyleWord(true);
        joinText.setOpaque(false);
        joinText.setEditable(false);

        ModernButton joinAction = new ModernButton("Enter Invite Key (Cohost)", COLOR_BLUE, Color.WHITE, 13, 8);
        joinAction.setPreferredSize(new Dimension(200, 44));
        joinAction.addActionListener(e -> wizardCards.show(wizardContainer, "WIZARD_JOIN"));

        joinCard.add(joinIconTitle, BorderLayout.NORTH);
        joinCard.add(joinText, BorderLayout.CENTER);
        joinCard.add(joinAction, BorderLayout.SOUTH);

        // Card C: Import Starter Pack (Zero Egress)
        ModernCard starterCard = new ModernCard();
        starterCard.setLayout(new BorderLayout(0, 14));
        starterCard.setBorder(new EmptyBorder(26, 24, 26, 24));

        JLabel starterIconTitle = new JLabel("📦 STARTER PACK", SwingConstants.CENTER);
        starterIconTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        starterIconTitle.setForeground(COLOR_AMBER);

        JTextArea starterText = new JTextArea("Received a cohost-starterpack.zip from your admin? Import it directly to set up world saves, plugins, and configs in seconds with zero Backblaze download bandwidth.");
        starterText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        starterText.setForeground(COLOR_TEXT_MUTED);
        starterText.setLineWrap(true);
        starterText.setWrapStyleWord(true);
        starterText.setOpaque(false);
        starterText.setEditable(false);

        ModernButton starterAction = new ModernButton("Import Starter Pack (.zip)", COLOR_AMBER, Color.WHITE, 13, 8);
        starterAction.setPreferredSize(new Dimension(200, 44));
        starterAction.addActionListener(e -> {
            showImportClusterDialog();
            if (isConfigured()) {
                masterLayout.show(masterContainer, "VIEW_MAIN_APP");
            }
        });

        starterCard.add(starterIconTitle, BorderLayout.NORTH);
        starterCard.add(starterText, BorderLayout.CENTER);
        starterCard.add(starterAction, BorderLayout.SOUTH);

        choiceRow.add(createCard);
        choiceRow.add(joinCard);
        choiceRow.add(starterCard);

        choiceScreen.add(header, BorderLayout.NORTH);
        choiceScreen.add(choiceRow, BorderLayout.CENTER);

        // Screen 2: Admin Creation Form (Matching Wireframe Phase 4)
        JPanel adminScreen = buildAdminCreationWizard(wizardCards, wizardContainer);

        // Screen 3: Cohost Join Form
        JPanel cohostScreen = buildCohostJoinWizard(wizardCards, wizardContainer);

        wizardContainer.add(choiceScreen, "WIZARD_CHOICE");
        wizardContainer.add(adminScreen, "WIZARD_CREATE");
        wizardContainer.add(cohostScreen, "WIZARD_JOIN");

        deck.add(wizardContainer, BorderLayout.CENTER);
        return deck;
    }

    private JPanel buildAdminCreationWizard(CardLayout wizardCards, JPanel wizardContainer) {
        JPanel p = new JPanel(new BorderLayout(0, 16));
        p.setOpaque(false);

        JLabel title = new JLabel("INITIALIZE YOUR CLUSTER (ADMINISTRATOR)");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(COLOR_TEXT_PRIMARY);

        JPanel content = new JPanel(new GridLayout(2, 1, 0, 12));
        content.setOpaque(false);

        // Sub-deck 1: Server Source (Adopt vs Fresh)
        ModernCard srcCard = new ModernCard();
        srcCard.setLayout(new BorderLayout(0, 8));
        srcCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel srcTitle = new JLabel("How would you like to initialize the Minecraft server?");
        srcTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        srcTitle.setForeground(COLOR_TEXT_PRIMARY);

        JRadioButton freshRadio = new JRadioButton("✨  CREATE A FRESH SERVER FROM SCRATCH (Auto-Download Official Paper)", true);
        freshRadio.setForeground(COLOR_TEXT_PRIMARY);
        freshRadio.setOpaque(false);

        JComboBox<String> versionCombo = new JComboBox<>(PaperDownloader.SUPPORTED_VERSIONS);
        styleDarkComboBox(versionCombo);

        JRadioButton adoptRadio = new JRadioButton("📁  ADOPT AN EXISTING SERVER (Convert local world, Paper server, and plugins)");
        adoptRadio.setForeground(COLOR_TEXT_PRIMARY);
        adoptRadio.setOpaque(false);

        ButtonGroup group = new ButtonGroup();
        group.add(freshRadio);
        group.add(adoptRadio);

        JPanel adoptRow = new JPanel(new BorderLayout(8, 0));
        adoptRow.setOpaque(false);
        JTextField adoptPathField = createDarkTextField("Select existing server folder...");
        ModernButton adoptBrowse = new ModernButton("Browse...", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        adoptBrowse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                adoptPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        adoptRow.add(adoptPathField, BorderLayout.CENTER);
        adoptRow.add(adoptBrowse, BorderLayout.EAST);

        JPanel stack = new JPanel(new GridLayout(4, 1, 0, 4));
        stack.setOpaque(false);
        stack.add(freshRadio);
        stack.add(versionCombo);
        stack.add(adoptRadio);
        stack.add(adoptRow);

        srcCard.add(srcTitle, BorderLayout.NORTH);
        srcCard.add(stack, BorderLayout.CENTER);

        // Sub-deck 2: Backblaze B2 Config
        ModernCard b2Card = new ModernCard();
        b2Card.setLayout(new GridLayout(5, 1, 0, 8));
        b2Card.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel b2Title = new JLabel("Cloud Storage Credentials (Backblaze B2)");
        b2Title.setFont(new Font("Segoe UI", Font.BOLD, 13));
        b2Title.setForeground(COLOR_TEXT_PRIMARY);

        JTextField bucketField = createDarkTextField("Backblaze B2 Bucket Name (e.g. my-minecraft-cluster)");
        JTextField keyIdField = createDarkTextField("B2 Application Key ID");
        JTextField appKeyField = createDarkTextField("B2 Application Key Secret");
        JTextField hostField = createDarkTextFieldWithText("Admin-" + ServerLauncher.getHostName());

        b2Card.add(b2Title);
        b2Card.add(bucketField);
        b2Card.add(keyIdField);
        b2Card.add(appKeyField);
        b2Card.add(hostField);

        content.add(srcCard);
        content.add(b2Card);

        // Footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel note = new JLabel("Ready to configure.");
        note.setForeground(COLOR_TEXT_MUTED);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btns.setOpaque(false);

        ModernButton backBtn = new ModernButton("Back", COLOR_BG_CARD, COLOR_TEXT_MUTED, 12, 6);
        backBtn.addActionListener(e -> wizardCards.show(wizardContainer, "WIZARD_CHOICE"));

        ModernButton initBtn = new ModernButton("Initialize Cluster", COLOR_EMERALD, Color.WHITE, 13, 8);
        initBtn.setPreferredSize(new Dimension(170, 40));
        initBtn.addActionListener(e -> {
            String bucket = bucketField.getText().trim();
            String keyId = keyIdField.getText().trim();
            String appKey = appKeyField.getText().trim();
            String host = hostField.getText().trim();

            if (bucket.isEmpty() || keyId.isEmpty() || appKey.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter your Backblaze B2 credentials.", "Required Fields", JOptionPane.WARNING_MESSAGE);
                return;
            }

            initBtn.setEnabled(false);
            note.setText("Initializing cluster runtime...");

            workerPool.submit(() -> {
                try {
                    if (freshRadio.isSelected()) {
                        String ver = (String) versionCombo.getSelectedItem();
                        PaperDownloader.setupFreshServer(rootDir, ver, msg -> SwingUtilities.invokeLater(() -> note.setText(msg)));
                    } else {
                        Path existing = Paths.get(adoptPathField.getText().trim());
                        PaperDownloader.adoptExistingServer(rootDir, existing, msg -> SwingUtilities.invokeLater(() -> note.setText(msg)));
                    }

                    saveInitialConfig(bucket, keyId, appKey, host, "8G");
                    ClusterToken.ClusterConfig tokenCfg = new ClusterToken.ClusterConfig(bucket, keyId, appKey, 10, "10M", null, null);
                    String token = ClusterToken.encode(tokenCfg);

                    SwingUtilities.invokeLater(() -> {
                        initBtn.setEnabled(true);
                        copyToClipboard(token);
                        JOptionPane.showMessageDialog(this,
                                "Cluster successfully initialized!\n\nHere is your Cluster Invite Token for co-hosts:\n" + token + "\n\n(Copied to your clipboard).",
                                "Cluster Initialized", JOptionPane.INFORMATION_MESSAGE);
                        masterLayout.show(masterContainer, "VIEW_MAIN_APP");
                        refreshCloudLockStatus();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        initBtn.setEnabled(true);
                        note.setText("Initialization error: " + ex.getMessage());
                        JOptionPane.showMessageDialog(this, "Failed to initialize: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        });

        btns.add(backBtn);
        btns.add(initBtn);
        footer.add(note, BorderLayout.WEST);
        footer.add(btns, BorderLayout.EAST);

        p.add(title, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        p.add(footer, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildCohostJoinWizard(CardLayout wizardCards, JPanel wizardContainer) {
        JPanel p = new JPanel(new BorderLayout(0, 16));
        p.setOpaque(false);

        JLabel title = new JLabel("CONNECT TO AN EXISTING CLUSTER (COHOST)");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(COLOR_TEXT_PRIMARY);

        ModernCard formCard = new ModernCard();
        formCard.setLayout(new GridLayout(6, 1, 0, 10));
        formCard.setBorder(new EmptyBorder(16, 20, 16, 20));

        // Storage path picker
        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.setOpaque(false);
        JTextField pathField = createDarkTextFieldWithText(rootDir.toString());
        ModernButton browseBtn = new ModernButton("Browse...", COLOR_BG_CARD, COLOR_TEXT_PRIMARY, 11, 6);
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(rootDir.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        pathRow.add(new JLabel("Storage Directory: ") {{ setForeground(COLOR_TEXT_MUTED); }}, BorderLayout.WEST);
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.add(browseBtn, BorderLayout.EAST);

        // Invite Token Field
        JTextField tokenField = createDarkTextField("Paste your mc-cluster:// invite token here...");

        JTextField bucketField = createDarkTextField("B2 Bucket Name");
        JTextField keyIdField = createDarkTextField("B2 Application Key ID");
        JTextField appKeyField = createDarkTextField("B2 Application Key Secret");
        JTextField hostField = createDarkTextFieldWithText(ServerLauncher.getHostName());

        tokenField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                String raw = tokenField.getText().trim();
                if (raw.startsWith(ClusterToken.PREFIX)) {
                    ClusterToken.ClusterConfig cfg = ClusterToken.decode(raw);
                    if (cfg != null) {
                        bucketField.setText(cfg.bucket());
                        keyIdField.setText(cfg.keyId());
                        appKeyField.setText(cfg.appKey());
                        tokenField.setForeground(COLOR_EMERALD);
                    }
                }
            }
        });

        formCard.add(pathRow);
        formCard.add(createLabeledRow("Cluster Invite Key:", tokenField));
        formCard.add(createLabeledRow("B2 Bucket Name:", bucketField));
        formCard.add(createLabeledRow("Key ID:", keyIdField));
        formCard.add(createLabeledRow("Application Secret:", appKeyField));
        formCard.add(createLabeledRow("My Host Nickname:", hostField));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footer.setOpaque(false);

        ModernButton backBtn = new ModernButton("Back", COLOR_BG_CARD, COLOR_TEXT_MUTED, 12, 6);
        backBtn.addActionListener(e -> wizardCards.show(wizardContainer, "WIZARD_CHOICE"));

        ModernButton connectBtn = new ModernButton("Connect & Save", COLOR_BLUE, Color.WHITE, 13, 8);
        connectBtn.setPreferredSize(new Dimension(160, 40));
        connectBtn.addActionListener(e -> {
            String bucket = bucketField.getText().trim();
            String keyId = keyIdField.getText().trim();
            String appKey = appKeyField.getText().trim();
            String host = hostField.getText().trim();

            if (bucket.isEmpty() || keyId.isEmpty() || appKey.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter your cluster details or paste a valid invite key.", "Incomplete", JOptionPane.WARNING_MESSAGE);
                return;
            }

            saveInitialConfig(bucket, keyId, appKey, host, "6G");
            masterLayout.show(masterContainer, "VIEW_MAIN_APP");
            refreshCloudLockStatus();
        });

        footer.add(backBtn);
        footer.add(connectBtn);

        p.add(title, BorderLayout.NORTH);
        p.add(formCard, BorderLayout.CENTER);
        p.add(footer, BorderLayout.SOUTH);
        return p;
    }

    private void saveInitialConfig(String bucket, String keyId, String appKey, String host, String javaMem) {
        Path cfg = rootDir.resolve("config.json");
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"b2_bucket\": \"").append(bucket).append("\",\n");
        sb.append("  \"b2_key_id\": \"").append(keyId).append("\",\n");
        sb.append("  \"b2_application_key\": \"").append(appKey).append("\",\n");
        sb.append("  \"java_memory\": \"").append(javaMem).append("\",\n");
        sb.append("  \"stale_timeout_minutes\": 10,\n");
        sb.append("  \"b2_bandwidth_limit\": \"10M\",\n");
        sb.append("  \"heartbeat_seconds\": 120,\n");
        sb.append("  \"save_interval_minutes\": 15\n");
        sb.append("}\n");
        try {
            Files.writeString(cfg, sb.toString());
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Runtime Execution Engine & Lifecycle Management
    // =========================================================================
    private void toggleServerAction() {
        if (!isServerRunning) {
            launchServerSession();
        } else {
            terminateServerSession();
        }
    }

    private void launchServerSession() {
        if (isServerRunning) return;
        isServerRunning = true;
        if (clusterSelector != null) clusterSelector.setEnabled(false);

        primaryActionBtn.setText("IGNITING ENGINE...");
        primaryActionBtn.setBackgroundColor(COLOR_AMBER);
        primaryActionBtn.setEnabled(false);
        resetMilestones();

        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                String json = Files.readString(cfg);

                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String duckDomain = ServerLauncher.extractJsonString(json, "domain");
                String duckToken = ServerLauncher.extractJsonString(json, "token");
                String javaMemory = ServerLauncher.extractJsonString(json, "java_memory");
                if (javaMemory == null || javaMemory.isEmpty()) javaMemory = "6G";

                int staleMin = ServerLauncher.extractJsonInt(json, "stale_timeout_minutes", 10);
                int heartbeatSec = ServerLauncher.extractJsonInt(json, "heartbeat_seconds", 120);
                int saveIntervalMin = ServerLauncher.extractJsonInt(json, "save_interval_minutes", 15);
                String bwLimit = ServerLauncher.extractJsonString(json, "b2_bandwidth_limit");
                if (bwLimit == null || bwLimit.isEmpty()) bwLimit = "10M";

                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);
                String localHost = ServerLauncher.getHostName();
                Path lockPath = rootDir.resolve(".server_active.lock");
                Path coreDat = rootDir.resolve("bin").resolve("core.dat");

                // Milestone 1: Version Compatibility Check
                updateMilestoneStep(0, "1. Version Check...", COLOR_AMBER, 15);
                appendConsoleLog("[Step 1/5] Verifying cluster orchestrator compatibility...");
                String minCloudVer = ServerLauncher.readCloudMinVersion(rootDir, rclone, b2Target);
                if (minCloudVer != null && !minCloudVer.isEmpty()) {
                    if (ServerLauncher.compareVersions(ServerLauncher.APP_VERSION, minCloudVer) < 0) {
                        appendConsoleLog("[ABORT] Outdated orchestrator! Cluster requires v" + minCloudVer + " (Local is v" + ServerLauncher.APP_VERSION + ").");
                        abortLaunch("Cluster requires orchestrator v" + minCloudVer + ".\nYour local version is v" + ServerLauncher.APP_VERSION + ".\nPlease update your launcher before hosting to ensure world integrity.");
                        return;
                    }
                }
                updateMilestoneStep(0, "1. Up to Date", COLOR_EMERALD, 20);

                // Port Check
                if (!ServerLauncher.isPortAvailable(25565)) {
                    appendConsoleLog("[ERROR] Port 25565 is currently occupied.");
                    abortLaunch("Port 25565 is bound by another application.");
                    return;
                }

                // Active Host Priority Gate: If a host is already live and newcomer has no local world, prevent redundant downloads
                if (ServerLauncher.isWorldEmpty(rootDir) && ServerLauncher.isCloudLocked(rootDir, rclone, b2Target, staleMin)) {
                    String lockHost = ServerLauncher.readLockHost(rootDir, rclone, b2Target);
                    appendConsoleLog("[Active Host Priority Gate] Cluster is actively hosted by '" + lockHost + "'!");
                    abortLaunch("ACTIVE HOST PRIORITY GATE\n\n" +
                            "This cluster is actively hosted live by: " + lockHost + "\n\n" +
                            "Initial cloud downloads are paused to guarantee live gameplay backup bandwidth.\n" +
                            "Please connect directly to the active session at:\n" +
                            (duckDomain != null && !duckDomain.isEmpty() ? duckDomain + ".duckdns.org" : "jensen-edited.tun.ply.gg"));
                    return;
                }

                // Dual-Layer Tunnel Ping Guard: Prevent collisions if a peer is already live on the tunnel
                String sAddr = resolveDynamicServerAddress();
                ServerLauncher.PingResult pingRes = ServerLauncher.pingMinecraftServer(sAddr);
                if (pingRes.isOnline) {
                    appendConsoleLog(String.format("[COLLISION GUARD] Active Minecraft session detected on tunnel '%s' (%d players online)!", sAddr, pingRes.onlinePlayers));
                    abortLaunch(String.format("ACTIVE MINECRAFT SESSION DETECTED ON TUNNEL!\n\n" +
                            "Endpoint: %s\nPlayers Online: %d/%d\nMOTD: %s\n\n" +
                            "Launch blocked to prevent session conflict and world corruption.\n" +
                            "Please connect directly to the active server!", sAddr, pingRes.onlinePlayers, pingRes.maxPlayers, pingRes.motd));
                    return;
                }

                // Milestone 2: Cloud Lock
                updateMilestoneStep(1, "2. Cloud Lock...", COLOR_AMBER, 35);
                appendConsoleLog("[Step 2/5] Acquiring cluster cloud lock...");
                if (!ServerLauncher.acquireCloudLock(rootDir, rclone, b2Target, localHost, staleMin)) {
                    appendConsoleLog("[ABORT] Lock acquisition refused (Cluster is active on another host).");
                    abortLaunch("Cluster lock is currently held by another host.");
                    return;
                }
                ServerLauncher.cancelRemoteStop(rootDir, rclone, b2Target);
                ServerLauncher.writeLockFile(lockPath, localHost);
                updateMilestoneStep(1, "2. Lock Acquired", COLOR_EMERALD, 40);

                // Milestone 3: World Sync
                updateMilestoneStep(2, "3. Syncing Cloud...", COLOR_AMBER, 55);
                appendConsoleLog("[Step 3/5] Pulling latest world generation & master runtime assets...");
                ServerLauncher.syncAdminDistribution(rootDir, rclone, b2Target);
                boolean syncOk = ServerLauncher.pullFromB2(rootDir, rclone, b2Target, staleMin, true);
                if (!syncOk) {
                    appendConsoleLog("[WARN] World sync did not complete cleanly.");
                    if (ServerLauncher.isWorldEmpty(rootDir)) {
                        String countdown = ServerLauncher.getTimeUntilUtcMidnightFormatted();
                        abortLaunch("Backblaze B2 Daily Download Cap Exceeded (403 Cap)\n\n" +
                                "The free tier daily bandwidth limit has been reached.\n" +
                                "Quota resets automatically at 00:00 UTC (in " + countdown + ").\n\n" +
                                "Workaround: Ask the server admin for a 'cohost-starterpack.zip' to onboard without cloud egress.");
                        return;
                    }
                }
                updateMilestoneStep(2, "3. Synchronized", COLOR_EMERALD, 65);

                // Playit Sidecar Daemon
                String playitBin = ServerLauncher.findPlayit(rootDir);
                if (playitBin != null) {
                    appendConsoleLog("[Playit Sidecar] Launching Anycast tunnel agent...");
                    try {
                        ProcessBuilder pb = new ProcessBuilder(playitBin, "run");
                        pb.directory(rootDir.toFile());
                        pb.redirectErrorStream(true);
                        activePlayitProcess = pb.start();
                        workerPool.submit(() -> {
                            try (BufferedReader pr = new BufferedReader(new InputStreamReader(activePlayitProcess.getInputStream(), StandardCharsets.UTF_8))) {
                                String pline;
                                while ((pline = pr.readLine()) != null) {
                                    appendConsoleLog("[PlayIt] " + pline);
                                    checkLineForServerAddress(pline);
                                }
                            } catch (Exception ignored) {}
                        });
                    } catch (Exception e) {
                        appendConsoleLog("[WARN] PlayIt daemon launch warning: " + e.getMessage());
                    }
                }

                // Dynamic DNS
                if (duckToken != null && !duckToken.isEmpty() && !duckToken.startsWith("YOUR_")) {
                    String ip = ServerLauncher.getTailscaleIp();
                    if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
                    ServerLauncher.updateDuckDns(duckDomain, duckToken, ip);
                }

                // Heartbeat and Autosave Scheduler
                heartbeatScheduler = Executors.newScheduledThreadPool(2);
                final String finalB2 = b2Target;
                final String finalRclone = rclone;
                final String finalBw = bwLimit;

                heartbeatScheduler.scheduleAtFixedRate(() -> {
                    try {
                        ServerLauncher.writeLockFile(lockPath, localHost);
                        ServerLauncher.writeCloudHeartbeat(rootDir, finalRclone, finalB2, localHost);
                        ServerLauncher.checkRemoteStopSignal(rootDir, finalRclone, finalB2, activeMcStdin);
                    } catch (Exception ignored) {}
                }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);

                if (saveIntervalMin > 0) {
                    heartbeatScheduler.scheduleAtFixedRate(() -> {
                        ServerLauncher.triggerIncrementalPush(rootDir, finalRclone, finalB2, finalBw, activeMcStdin);
                    }, saveIntervalMin, saveIntervalMin, TimeUnit.MINUTES);
                }

                // Milestone 4: Paper Boot
                updateMilestoneStep(3, "4. Booting Paper...", COLOR_AMBER, 80);
                appendConsoleLog("[Step 4/5] Executing PaperMC engine (" + javaMemory + " heap)...");

                if (!Files.exists(coreDat)) {
                    abortLaunch("Server binary missing: bin/core.dat");
                    return;
                }

                // Ensure Mojang EULA is accepted
                Path eulaFile = rootDir.resolve("eula.txt");
                try {
                    if (!Files.exists(eulaFile) || Files.readString(eulaFile).contains("eula=false")) {
                        Files.writeString(eulaFile, "# Accepted automatically by Minecraft Cluster Orchestrator\neula=true\n");
                        appendConsoleLog("[EULA] Mojang EULA automatically accepted.");
                    }
                } catch (Exception ignored) {}

                ProcessBuilder mcPb = new ProcessBuilder(
                        "java",
                        "-Dcluster.safe_launch=true",
                        "-Xms" + javaMemory,
                        "-Xmx" + javaMemory,
                        "-jar", coreDat.toString(),
                        "nogui"
                );
                mcPb.directory(rootDir.toFile());
                mcPb.redirectErrorStream(true);

                activeMcProcess = mcPb.start();
                activeMcStdin = new BufferedWriter(new OutputStreamWriter(activeMcProcess.getOutputStream(), StandardCharsets.UTF_8));

                SwingUtilities.invokeLater(() -> {
                    primaryActionBtn.setText("■  SAFE SHUTDOWN & CLOUD PUSH");
                    primaryActionBtn.setBackgroundColor(COLOR_ROSE);
                    primaryActionBtn.setEnabled(true);
                    statusBadge.setText("● Hosting Session (Local)");
                    statusBadge.setForeground(COLOR_BLUE);
                });

                // Stream stdout/stderr to GUI console
                try (BufferedReader r = new BufferedReader(new InputStreamReader(activeMcProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendConsoleLog(line);
                        checkLineForServerAddress(line);
                        if (line.contains("Done (") && line.contains("For help, type")) {
                            updateMilestoneStep(3, "4. Boot Complete", COLOR_EMERALD, 90);
                            updateMilestoneStep(4, "5. SERVER ONLINE", COLOR_EMERALD, 100);
                        }
                    }
                }

                // Normal Process Exit
                activeMcProcess.waitFor();
                cleanupPostShutdown(lockPath, rclone, b2Target, localHost, staleMin, bwLimit);

            } catch (Exception ex) {
                appendConsoleLog("[ERROR] Engine failure: " + ex.getMessage());
                abortLaunch(ex.getMessage());
            }
        });
    }

    private void terminateServerSession() {
        if (!isServerRunning) return;
        primaryActionBtn.setText("SHUTTING DOWN...");
        primaryActionBtn.setEnabled(false);
        appendConsoleLog("[GUI] Initiating graceful server shutdown and world save...");

        workerPool.submit(() -> {
            try {
                if (activeMcStdin != null) {
                    activeMcStdin.write("stop\n");
                    activeMcStdin.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    private void cleanupPostShutdown(Path lockPath, String rclone, String b2Target, String localHost, int staleMin, String bwLimit) {
        appendConsoleLog("[Step 5/5] Backing up final world state and releasing cloud lock...");
        if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();

        if (activePlayitProcess != null && activePlayitProcess.isAlive()) {
            try {
                activePlayitProcess.destroy();
                activePlayitProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        ServerLauncher.cleanup(rootDir, lockPath, rclone, b2Target, localHost, staleMin, bwLimit,
                null, null);

        isServerRunning = false;
        SwingUtilities.invokeLater(() -> {
            if (clusterSelector != null) clusterSelector.setEnabled(true);
            primaryActionBtn.setText("▶  START CLUSTER SERVER");
            primaryActionBtn.setBackgroundColor(COLOR_EMERALD);
            primaryActionBtn.setEnabled(true);
            resetMilestones();
            refreshCloudLockStatus();
            appendConsoleLog("[SUCCESS] Server stopped cleanly. Progress preserved in cloud.");
        });
    }

    private void abortLaunch(String reason) {
        isServerRunning = false;
        SwingUtilities.invokeLater(() -> {
            if (clusterSelector != null) clusterSelector.setEnabled(true);
            primaryActionBtn.setText("▶  START CLUSTER SERVER");
            primaryActionBtn.setBackgroundColor(COLOR_EMERALD);
            primaryActionBtn.setEnabled(true);
            resetMilestones();
            refreshCloudLockStatus();
            JOptionPane.showMessageDialog(this, "Could not start server:\n" + reason, "Launch Aborted", JOptionPane.WARNING_MESSAGE);
        });
    }

    private void dispatchConsoleCommand() {
        String cmd = commandInputField.getText().trim();
        if (cmd.isEmpty()) return;
        commandInputField.setText("");

        if (!isServerRunning || activeMcStdin == null) {
            appendConsoleLog("[LOCAL] Server offline. Ignored command: " + cmd);
            return;
        }

        String lower = cmd.toLowerCase().trim();
        if (lower.equals("stop")) {
            appendConsoleLog("[GUI] Intercepted 'stop' command. Initiating safe cluster teardown & cloud push...");
            terminateServerSession();
            return;
        }

        if (lower.equals("restart") || lower.equals("reset") || lower.equals("reload")) {
            appendConsoleLog("[CLUSTER GUARD] Command '" + cmd + "' intercepted!");
            appendConsoleLog("[CLUSTER GUARD] In a distributed cluster, uncoordinated in-engine restarts are blocked.");
            appendConsoleLog("[CLUSTER GUARD] Use '■ SAFE SHUTDOWN & CLOUD PUSH' to conclude the session cleanly.");
            return;
        }

        try {
            appendConsoleLog("> " + cmd);
            activeMcStdin.write(cmd + "\n");
            activeMcStdin.flush();
        } catch (IOException e) {
            appendConsoleLog("[ERROR] Failed to dispatch command: " + e.getMessage());
        }
    }

    private void appendConsoleLog(String text) {
        SwingUtilities.invokeLater(() -> {
            liveConsoleArea.append(text + "\n");
            liveConsoleArea.setCaretPosition(liveConsoleArea.getDocument().getLength());
        });
    }

    // =========================================================================
    // Utility & Cloud Actions
    // =========================================================================
    private void refreshCloudLockStatus() {
        statusBadge.setText("● Polling Cloud...");
        statusBadge.setForeground(COLOR_TEXT_MUTED);

        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                if (!Files.exists(cfg)) return;
                String json = Files.readString(cfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);

                ServerLauncher.CommandResult catRes = ServerLauncher.runCommand(rootDir, rclone, "cat", b2Target + "/cluster.lock");
                String minCloudVer = ServerLauncher.readCloudMinVersion(rootDir, rclone, b2Target);

                SwingUtilities.invokeLater(() -> {
                    if (minCloudVer != null && ServerLauncher.compareVersions(ServerLauncher.APP_VERSION, minCloudVer) < 0) {
                        updateBannerText.setText("⚠️ Cloud World Requires Orchestrator v" + minCloudVer + " (Local is v" + ServerLauncher.APP_VERSION + "). Please update!");
                        updateBanner.setVisible(true);
                    }
                    if (isServerRunning) {
                        statusBadge.setText("● Hosting Session (Local)");
                        statusBadge.setForeground(COLOR_BLUE);
                    } else if (catRes.exitCode() == 0 && !catRes.stdout().isBlank()) {
                        String activeHost = ServerLauncher.extractJsonString(catRes.stdout(), "host");
                        statusBadge.setText("● Active on " + (activeHost != null ? activeHost : "Remote Host"));
                        statusBadge.setForeground(COLOR_AMBER);
                    } else {
                        // Dual-Layer Tunnel Ping Guard: Probe the live server address directly
                        String sAddr = resolveDynamicServerAddress();
                        ServerLauncher.PingResult pr = ServerLauncher.pingMinecraftServer(sAddr);
                        if (pr.isOnline) {
                            statusBadge.setText(String.format("● Live on Tunnel (%d Online)", pr.onlinePlayers));
                            statusBadge.setForeground(COLOR_AMBER);
                        } else {
                            statusBadge.setText("● Cluster Available");
                            statusBadge.setForeground(COLOR_EMERALD);
                        }
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusBadge.setText("● Status Check Failed");
                    statusBadge.setForeground(COLOR_TEXT_MUTED);
                });
            }
        });
    }

    private String resolveDynamicServerAddress() {
        try {
            Path cfg = rootDir.resolve("config.json");
            if (Files.exists(cfg)) {
                String json = Files.readString(cfg);
                String addr = ServerLauncher.extractJsonString(json, "server_address");
                if (addr == null || addr.isEmpty()) {
                    addr = ServerLauncher.extractJsonString(json, "playit_address");
                }
                if (addr != null && !addr.isEmpty() && !addr.contains("auto.tun")) {
                    return addr;
                }
            }
        } catch (Exception ignored) {}

        try {
            Path logFile = rootDir.resolve("logs").resolve("latest.log");
            if (Files.exists(logFile)) {
                java.util.List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "(?:found minecraft java tunnel:\\s*|playit\\.gg:\\s*)([a-zA-Z0-9.-]+\\.(?:ply\\.gg|duckdns\\.org|tun\\.ply\\.gg))",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                );
                for (int i = lines.size() - 1; i >= 0; i--) {
                    java.util.regex.Matcher m = p.matcher(lines.get(i));
                    if (m.find()) {
                        String detected = m.group(1).trim();
                        saveServerAddressToConfig(detected);
                        return detected;
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Path cfg = rootDir.resolve("config.json");
            if (Files.exists(cfg)) {
                String json = Files.readString(cfg);
                String domain = ServerLauncher.extractJsonString(json, "domain");
                if (domain != null && !domain.isEmpty() && !domain.startsWith("YOUR_")) {
                    return domain + ".duckdns.org:25565";
                }
            }
        } catch (Exception ignored) {}

        return "Connecting...";
    }

    private void checkLineForServerAddress(String line) {
        if (line == null) return;
        if (line.contains("ply.gg") || line.contains("duckdns.org") || line.contains("tunnel")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?:found minecraft java tunnel:\\s*|playit\\.gg:\\s*|tunnel:\\s*|address:\\s*)([a-zA-Z0-9.-]+\\.(?:ply\\.gg|duckdns\\.org|tun\\.ply\\.gg))",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                String detected = m.group(1).trim();
                SwingUtilities.invokeLater(() -> {
                    if (ipAddressField != null) {
                        ipAddressField.setText(detected);
                    }
                });
                saveServerAddressToConfig(detected);
            }
        }
    }

    private void saveServerAddressToConfig(String address) {
        if (address == null || address.isEmpty()) return;
        try {
            Path cfg = rootDir.resolve("config.json");
            if (Files.exists(cfg)) {
                String json = Files.readString(cfg);
                if (json.contains("\"server_address\"")) {
                    json = json.replaceAll("\"server_address\"\\s*:\\s*\"[^\"]*\"", "\"server_address\": \"" + address + "\"");
                } else {
                    int lastBrace = json.lastIndexOf('}');
                    if (lastBrace != -1) {
                        String prefix = json.substring(0, lastBrace).trim();
                        if (prefix.endsWith(",")) {
                            json = prefix + "\n  \"server_address\": \"" + address + "\"\n}";
                        } else {
                            json = prefix + ",\n  \"server_address\": \"" + address + "\"\n}";
                        }
                    }
                }
                Files.writeString(cfg, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception ignored) {}
    }

    private void handleRemoteStopRequest() {
        String reason = JOptionPane.showInputDialog(this, "Enter reason for remote stop request:", "Host Maintenance");
        if (reason == null || reason.isBlank()) return;

        appendConsoleLog("[REMOTE STOP] Dispatching remote stop signal to cloud...");
        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                String json = Files.readString(cfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);

                ServerLauncher.dispatchRemoteStop(rootDir, rclone, b2Target, ServerLauncher.getHostName(), reason);
                appendConsoleLog("[REMOTE STOP] Stop signal published to B2. Host will shut down in 30 seconds.");
                refreshCloudLockStatus();
            } catch (Exception ex) {
                appendConsoleLog("[ERROR] Failed to send remote stop: " + ex.getMessage());
            }
        });
    }

    private void handleEmergencyUnlockRequest() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Force unlock will clear all distributed cluster locks.\nOnly perform this if a hoster hard-crashed or lost power.\n\nProceed?",
                "Confirm Emergency Force Unlock", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        appendConsoleLog("[UNLOCK] Wiping local and distributed cloud locks...");
        workerPool.submit(() -> {
            try {
                Files.deleteIfExists(rootDir.resolve(".server_active.lock"));
                Path cfg = rootDir.resolve("config.json");
                if (Files.exists(cfg)) {
                    String json = Files.readString(cfg);
                    String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                    String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                    String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                    String rclone = ServerLauncher.findRclone(rootDir);
                    String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);
                    ServerLauncher.releaseCloudLock(rootDir, rclone, b2Target);
                    ServerLauncher.cancelRemoteStop(rootDir, rclone, b2Target);
                }
                appendConsoleLog("[UNLOCK] Cluster lock released successfully.");
                refreshCloudLockStatus();
            } catch (Exception ex) {
                appendConsoleLog("[ERROR] Unlock failed: " + ex.getMessage());
            }
        });
    }

    private void handlePublishAdminDistribution(ModernButton btn) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "This will upload your local plugins/, server.properties, spigot/bukkit configs,\n" +
                "and runtime binaries to the cluster cloud distribution.\n\n" +
                "All co-hosts will automatically synchronize these files prior to their next boot.\n\n" +
                "Proceed with Cloud Synchronization?",
                "Publish Runtime & Configurations", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        btn.setEnabled(false);
        btn.setText("SYNCING...");
        appendConsoleLog("[CLOUD SYNC] Starting administrative distribution push to Backblaze B2...");

        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                if (!Files.exists(cfg)) {
                    appendConsoleLog("[ERROR] config.json missing. Cannot determine cluster target.");
                    return;
                }
                String json = Files.readString(cfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);

                ServerLauncher.publishAdminUpdate(rootDir, rclone, b2Target);
                appendConsoleLog("[CLOUD SYNC] Successfully published runtime configurations & plugins to B2!");
                SwingUtilities.invokeLater(() -> {
                    btn.setText("SYNCED!");
                    JOptionPane.showMessageDialog(this,
                            "Server configurations and plugins were published to the cloud successfully!\nAll co-hosts will automatically receive them on their next session.",
                            "Cloud Sync Complete", JOptionPane.INFORMATION_MESSAGE);
                    Timer t = new Timer(2000, ev -> {
                        btn.setText("☁️ SYNC CONFIGS & PLUGINS");
                        btn.setEnabled(true);
                    });
                    t.setRepeats(false);
                    t.start();
                });
            } catch (Exception ex) {
                appendConsoleLog("[ERROR] Cloud sync failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    btn.setText("RETRY SYNC");
                    btn.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Failed to publish distribution:\n" + ex.getMessage(), "Sync Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void handleCleanStorage(ModernButton btn) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "This will purge all hidden and superseded chunk revisions from Backblaze B2.\n" +
                "The current live save state and world files remain completely safe and untouched.\n\n" +
                "Proceed with Cloud Storage Cleanup?",
                "Purge Dead Revisions", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        btn.setEnabled(false);
        btn.setText("CLEANING...");
        appendConsoleLog("[B2 MAINTENANCE] Starting storage cleanup on Backblaze B2...");

        workerPool.submit(() -> {
            try {
                Path cfg = rootDir.resolve("config.json");
                if (!Files.exists(cfg)) {
                    appendConsoleLog("[ERROR] config.json missing. Cannot determine cluster target.");
                    return;
                }
                String json = Files.readString(cfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
                String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
                String rclone = ServerLauncher.findRclone(rootDir);
                String b2Target = ServerLauncher.getB2Remote(bucket, keyId, appKey);

                ServerLauncher.CommandResult res = ServerLauncher.purgeDeadRevisions(rootDir, rclone, b2Target);
                if (res.exitCode() == 0) {
                    appendConsoleLog("[B2 MAINTENANCE] Bucket cleaned successfully! Dead revisions purged.");
                    SwingUtilities.invokeLater(() -> {
                        btn.setText("CLEANED!");
                        JOptionPane.showMessageDialog(this,
                                "Storage cleanup complete!\nAll hidden chunk revisions have been purged while preserving the latest save.",
                                "Storage Cleaned", JOptionPane.INFORMATION_MESSAGE);
                        Timer t = new Timer(2000, ev -> {
                            btn.setText("🧹 CLEAN CLOUD STORAGE");
                            btn.setEnabled(true);
                        });
                        t.setRepeats(false);
                        t.start();
                    });
                } else {
                    appendConsoleLog("[ERROR] Cleanup failed (code " + res.exitCode() + "): " + res.stderr());
                    SwingUtilities.invokeLater(() -> {
                        btn.setText("RETRY CLEAN");
                        btn.setEnabled(true);
                    });
                }
            } catch (Exception ex) {
                appendConsoleLog("[ERROR] Cleanup failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    btn.setText("RETRY CLEAN");
                    btn.setEnabled(true);
                });
            }
        });
    }

    private void initGitHubUpdatePolling() {
        GitHubUpdateChecker.checkAsync(ServerLauncher.APP_VERSION, GitHubUpdateChecker.DEFAULT_REPO)
                .thenAccept(info -> {
                    if (info.hasUpdate() && info.downloadUrl() != null) {
                        SwingUtilities.invokeLater(() -> {
                            updateBannerText.setText("🚀 New Release Available: v" + info.latestVersion() + "! Click to update.");
                            updateBanner.setVisible(true);

                            updateDownloadBtn.addActionListener(e -> {
                                updateDownloadBtn.setEnabled(false);
                                updateDownloadBtn.setText("Downloading...");
                                workerPool.submit(() -> {
                                    try {
                                        GitHubUpdateChecker.downloadUpdate(info.downloadUrl(), rootDir, pct ->
                                                SwingUtilities.invokeLater(() -> updateDownloadBtn.setText("Downloading " + pct + "%"))
                                        );
                                        SwingUtilities.invokeLater(() -> {
                                            JOptionPane.showMessageDialog(this, "Update downloaded successfully!\nRelaunching...", "Update Ready", JOptionPane.INFORMATION_MESSAGE);
                                            System.exit(100);
                                        });
                                    } catch (Exception ex) {
                                        SwingUtilities.invokeLater(() -> {
                                            updateDownloadBtn.setEnabled(true);
                                            updateDownloadBtn.setText("Retry Update");
                                            JOptionPane.showMessageDialog(this, "Failed to download update: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                        });
                                    }
                                });
                            });
                        });
                    }
                });
    }

    private void persistConfigRam(String memStr) {
        Path cfg = rootDir.resolve("config.json");
        if (!Files.exists(cfg)) return;
        try {
            String json = Files.readString(cfg);
            String updated = json.replaceAll("\"java_memory\":\\s*\"[^\"]+\"", "\"java_memory\": \"" + memStr + "\"");
            Files.writeString(cfg, updated);
        } catch (Exception ignored) {}
    }

    private int detectTotalPhysicalRamGb() {
        try {
            long bytes = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
            return (int) (bytes / (1024L * 1024L * 1024L));
        } catch (Throwable t) {
            return 16;
        }
    }

    private void handleWindowClose() {
        if (isServerRunning) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Minecraft server is actively running!\nClosing the window will shut down the server and sync your world to the cloud.\n\nExit now?",
                    "Server Active", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                terminateServerSession();
            }
        } else {
            dispose();
            System.exit(0);
        }
    }

    private void copyToClipboard(String str) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(str), null);
        } catch (Exception ignored) {}
    }

    private void openPathInExplorer(Path p) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception ignored) {}
    }

    private PlaceholderTextField createDarkTextField(String placeholder) {
        return new PlaceholderTextField("", placeholder);
    }

    private PlaceholderTextField createDarkTextFieldWithText(String text) {
        return new PlaceholderTextField(text, "");
    }

    private void styleDarkComboBox(JComboBox<?> box) {
        box.setBackground(COLOR_BG_CARD);
        box.setForeground(COLOR_TEXT_PRIMARY);
        box.setFocusable(false);
        box.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(COLOR_BORDER, 1),
                new EmptyBorder(2, 8, 2, 8)
        ));

        box.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = new JButton("▼");
                btn.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                btn.setForeground(COLOR_TEXT_MUTED);
                btn.setBackground(COLOR_BG_CARD);
                btn.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                btn.setContentAreaFilled(false);
                btn.setFocusPainted(false);
                btn.setOpaque(false);
                return btn;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(COLOR_BG_CARD);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        });

        box.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
                lbl.setBorder(new EmptyBorder(6, 12, 6, 12));
                if (isSelected) {
                    lbl.setBackground(COLOR_BLUE);
                    lbl.setForeground(Color.WHITE);
                } else {
                    lbl.setBackground(COLOR_BG_CARD);
                    lbl.setForeground(COLOR_TEXT_PRIMARY);
                }
                if (value instanceof ClusterRegistry.ClusterProfile cp) {
                    lbl.setText(cp.name);
                } else if (value != null) {
                    lbl.setText(value.toString());
                }
                return lbl;
            }
        });
    }

    private void styleDarkSpinner(JSpinner spinner, int width, int height) {
        spinner.setBackground(COLOR_BG_INPUT);
        spinner.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(COLOR_BORDER, 1),
                new EmptyBorder(1, 4, 1, 1)
        ));
        spinner.setPreferredSize(new Dimension(width, height));
        spinner.setMaximumSize(new Dimension(width, height));

        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            JFormattedTextField tf = defaultEditor.getTextField();
            tf.setBackground(COLOR_BG_INPUT);
            tf.setForeground(COLOR_TEXT_PRIMARY);
            tf.setCaretColor(COLOR_TEXT_PRIMARY);
            tf.setFont(new Font("Segoe UI", Font.BOLD, 12));
            tf.setHorizontalAlignment(JTextField.CENTER);
            tf.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        }

        for (Component c : spinner.getComponents()) {
            if (c instanceof JButton btn) {
                btn.setBackground(COLOR_BG_CARD);
                btn.setForeground(COLOR_TEXT_MUTED);
                btn.setBorder(new LineBorder(COLOR_BORDER, 1));
                btn.setFocusPainted(false);
                btn.setFocusable(false);
            }
        }
    }

    private void onClusterSelected() {
        Object item = clusterSelector.getSelectedItem();
        if (item instanceof ClusterRegistry.ClusterProfile profile) {
            if (isServerRunning) {
                JOptionPane.showMessageDialog(this,
                        "Cannot switch cluster while a server is currently running!\nPlease shut down the server first.",
                        "Cluster Switcher Locked", JOptionPane.WARNING_MESSAGE);
                refreshClusterSelectorItems();
                return;
            }
            if (!profile.path.equals(rootDir.toAbsolutePath().toString())) {
                Path newPath = Paths.get(profile.path);
                if (Files.exists(newPath)) {
                    this.rootDir = newPath;
                    clusterRegistry.setActiveClusterId(profile.id);
                    hostSubtitle.setText("Host: " + ServerLauncher.getHostName() + " | Local Storage: " + rootDir.toString());
                    appendConsoleLog("[Cluster Switcher] Active cluster switched to: " + profile.name);
                    loadSettingsIntoFields();
                    refreshCloudLockStatus();
                    resetMilestones();
                } else {
                    JOptionPane.showMessageDialog(this,
                            "Directory for cluster '" + profile.name + "' not found at:\n" + profile.path,
                            "Cluster Directory Missing", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void refreshClusterSelectorItems() {
        if (clusterSelector == null) return;
        clusterSelector.removeActionListener(clusterSelectorListener);
        clusterSelector.removeAllItems();
        java.util.List<ClusterRegistry.ClusterProfile> list = clusterRegistry.getClusters();
        ClusterRegistry.ClusterProfile active = clusterRegistry.getActiveCluster();
        ClusterRegistry.ClusterProfile toSelect = null;
        for (ClusterRegistry.ClusterProfile cp : list) {
            clusterSelector.addItem(cp);
            if (active != null && cp.id.equalsIgnoreCase(active.id)) {
                toSelect = cp;
            }
        }
        if (toSelect != null) {
            clusterSelector.setSelectedItem(toSelect);
        }
        clusterSelector.addActionListener(clusterSelectorListener);
    }

    private void exportStarterPackAction() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export Cohost Starter Pack");
        fc.setSelectedFile(new File(rootDir.toFile(), "cohost-starterpack.zip"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Zip Archives (*.zip)", "zip"));

        int res = fc.showSaveDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File dest = fc.getSelectedFile();
            if (!dest.getName().toLowerCase().endsWith(".zip")) {
                dest = new File(dest.getParentFile(), dest.getName() + ".zip");
            }
            final File finalDest = dest;
            appendConsoleLog("[Starter Pack] Packaging cohost-starterpack.zip to " + finalDest.getAbsolutePath() + "...");
            workerPool.submit(() -> {
                try {
                    ServerLauncher.exportStarterPack(rootDir, finalDest.toPath(), msg -> SwingUtilities.invokeLater(() -> appendConsoleLog(msg)));
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Starter Pack successfully exported!\n\n" +
                                "Saved to: " + finalDest.getAbsolutePath() + "\nSize: " + String.format("%.2f MB", finalDest.length() / (1024.0 * 1024.0)) + "\n\n" +
                                "Share this file with your co-hosts via Google Drive, OneDrive, or Discord.\n" +
                                "When they import it, they will be 100% onboarded with 0 MB of Backblaze download quota spent!",
                                "Starter Pack Ready", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Export failed:\n" + ex.getMessage(),
                                "Export Error", JOptionPane.ERROR_MESSAGE);
                        appendConsoleLog("[ERROR] Failed to export starter pack: " + ex.getMessage());
                    });
                }
            });
        }
    }

    private void showImportClusterDialog() {
        if (isServerRunning) {
            JOptionPane.showMessageDialog(this,
                    "Cannot import or switch clusters while a server is currently running!\nPlease shut down the server first.",
                    "Cluster Switcher Locked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select Cohost Starter Pack Zip");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Cohost Starter Pack (*.zip)", "zip"));

        int res = fc.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File zipFile = fc.getSelectedFile();
            String defaultName = zipFile.getName().replace(".zip", "");
            if (defaultName.equalsIgnoreCase("cohost-starterpack")) {
                defaultName = "Dorm-Craft-SMP";
            }

            String clusterName = JOptionPane.showInputDialog(this,
                    "Enter a name or identifier for this cluster workspace:",
                    defaultName);
            if (clusterName == null || clusterName.trim().isEmpty()) {
                return;
            }
            clusterName = clusterName.trim();
            String sanitizedFolder = clusterName.toLowerCase().replaceAll("[^a-z0-9_-]", "-");

            Path storageRoot = Paths.get(clusterRegistry.getStorageRoot());
            Path targetDir = storageRoot.resolve(sanitizedFolder);

            if (Files.exists(targetDir)) {
                int overwrite = JOptionPane.showConfirmDialog(this,
                        "A cluster folder already exists at:\n" + targetDir + "\n\nDo you want to overwrite it?",
                        "Cluster Already Exists", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (overwrite != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            final String finalClusterName = clusterName;
            appendConsoleLog("[Starter Pack] Importing cluster into " + targetDir + "...");

            workerPool.submit(() -> {
                try {
                    String clusterId = ServerLauncher.importStarterPack(zipFile.toPath(), targetDir, clusterRegistry,
                            msg -> SwingUtilities.invokeLater(() -> appendConsoleLog(msg)));
                    appendConsoleLog("[Starter Pack] Successfully registered cluster profile: " + clusterId);

                    SwingUtilities.invokeLater(() -> {
                        this.rootDir = targetDir;
                        refreshClusterSelectorItems();
                        hostSubtitle.setText("Host: " + ServerLauncher.getHostName() + " | Local Storage: " + rootDir.toString());
                        loadSettingsIntoFields();
                        refreshCloudLockStatus();
                        resetMilestones();
                        masterLayout.show(masterContainer, "VIEW_MAIN_APP");
                        JOptionPane.showMessageDialog(this,
                                "Cluster '" + finalClusterName + "' successfully imported!\n" +
                                "Workspace: " + targetDir.toString() + "\n\n" +
                                "All world files, plugins, and configurations are ready with 0 MB cloud egress spent.",
                                "Import Complete", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Failed to import starter pack:\n" + ex.getMessage(),
                                "Import Error", JOptionPane.ERROR_MESSAGE);
                        appendConsoleLog("[ERROR] Import failed: " + ex.getMessage());
                    });
                }
            });
        }
    }

    public static void launch(Path rootDir) {
        SwingUtilities.invokeLater(() -> {
            ClusterGUI gui = new ClusterGUI(rootDir);
            gui.setVisible(true);
        });
    }

    // =========================================================================
    // Custom Modern UI Components (Bypasses Windows Native Button Washing)
    // =========================================================================

    public static class PlaceholderTextField extends JTextField {
        private static final long serialVersionUID = 1L;
        private String placeholder;

        public PlaceholderTextField() {
            this("", "");
        }

        public PlaceholderTextField(String placeholder) {
            this("", placeholder);
        }

        public PlaceholderTextField(String text, String placeholder) {
            super(text != null ? text : "");
            this.placeholder = placeholder != null ? placeholder : "";
            setBackground(COLOR_BG_INPUT);
            setForeground(COLOR_TEXT_PRIMARY);
            setCaretColor(COLOR_TEXT_PRIMARY);
            setFont(new Font("Segoe UI", Font.PLAIN, 12));
            setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(COLOR_BORDER, 1),
                    new EmptyBorder(6, 10, 6, 10)
            ));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        public String getPlaceholder() {
            return placeholder;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if ((getText() == null || getText().isEmpty()) && placeholder != null && !placeholder.isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(COLOR_TEXT_MUTED);
                g2.setFont(getFont());
                Insets insets = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                int x = insets.left;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(placeholder, x, y);
                g2.dispose();
            }
        }
    }

    public static class ModernCard extends JPanel {
        private final int radius;

        public ModernCard() {
            this(12);
        }

        public ModernCard(int radius) {
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(COLOR_BG_CARD);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.setColor(COLOR_BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static class ModernButton extends JButton {
        private Color bgColor;
        private Color hoverColor;
        private final int radius;
        private boolean isHovered = false;
        private String icon = "";
        private String label = "";

        public ModernButton(String text, Color bg, Color fg, int fontSize, int radius) {
            super(text);
            this.bgColor = bg;
            this.hoverColor = lighten(bg, 1.15f);
            this.radius = radius;
            parseText(text);

            setFont(new Font("Segoe UI", Font.BOLD, fontSize));
            setForeground(fg);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    repaint();
                }
            });
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            parseText(text);
            repaint();
        }

        private void parseText(String raw) {
            if (raw == null || raw.isEmpty()) {
                this.icon = "";
                this.label = "";
                return;
            }
            int firstCp = raw.codePointAt(0);
            int charCount = Character.charCount(firstCp);
            if (firstCp >= 0x2000 || Character.isSurrogate(raw.charAt(0))) {
                String iconStr = new String(Character.toChars(firstCp));
                int nextIdx = charCount;
                if (nextIdx < raw.length() && raw.charAt(nextIdx) == '\uFE0F') {
                    iconStr += "\uFE0F";
                    nextIdx++;
                }
                this.icon = iconStr;
                this.label = raw.substring(nextIdx).trim();
            } else {
                this.icon = "";
                this.label = raw;
            }
        }

        public void setBackgroundColor(Color c) {
            this.bgColor = c;
            this.hoverColor = lighten(c, 1.15f);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color currentBg = !isEnabled() ? new Color(60, 60, 68) : (isHovered ? hoverColor : bgColor);
            g2.setColor(currentBg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);

            g2.setColor(isEnabled() ? getForeground() : COLOR_TEXT_MUTED);

            Font textFont = getFont();
            Font emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, textFont.getSize() + 1);
            FontMetrics fmText = g2.getFontMetrics(textFont);
            FontMetrics fmEmoji = g2.getFontMetrics(emojiFont);

            boolean hasIcon = (icon != null && !icon.isEmpty());
            boolean hasLabel = (label != null && !label.isEmpty());

            if (hasIcon && hasLabel) {
                int iconW = fmEmoji.stringWidth(icon);
                int labelW = fmText.stringWidth(label);
                int spacing = 8;
                int totalW = iconW + spacing + labelW;
                int startX = Math.max(4, (getWidth() - totalW) / 2);

                GlyphVector gv = textFont.createGlyphVector(g2.getFontRenderContext(), label);
                Rectangle2D vb = gv.getVisualBounds();
                int textY = (int) Math.round((getHeight() - vb.getHeight()) / 2.0 - vb.getY());

                g2.setFont(emojiFont);
                g2.drawString(icon, startX, textY);

                g2.setFont(textFont);
                g2.drawString(label, startX + iconW + spacing, textY);
            } else if (hasLabel) {
                GlyphVector gv = textFont.createGlyphVector(g2.getFontRenderContext(), label);
                Rectangle2D vb = gv.getVisualBounds();
                int startX = Math.max(4, (int) Math.round((getWidth() - vb.getWidth()) / 2.0 - vb.getX()));
                int textY = (int) Math.round((getHeight() - vb.getHeight()) / 2.0 - vb.getY());

                g2.setFont(textFont);
                g2.drawString(label, startX, textY);
            } else if (hasIcon) {
                int iconW = fmEmoji.stringWidth(icon);
                int startX = (getWidth() - iconW) / 2;
                int textY = (getHeight() + fmEmoji.getAscent() - fmEmoji.getDescent()) / 2;
                g2.setFont(emojiFont);
                g2.drawString(icon, startX, textY);
            }

            g2.dispose();
        }

        private static Color lighten(Color c, float factor) {
            int r = Math.min(255, (int)(c.getRed() * factor));
            int g = Math.min(255, (int)(c.getGreen() * factor));
            int b = Math.min(255, (int)(c.getBlue() * factor));
            return new Color(r, g, b, c.getAlpha());
        }
    }
}
