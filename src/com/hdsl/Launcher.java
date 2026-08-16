package com.hdsl;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Launcher {
    private static final Color INK = new Color(20, 25, 32);
    private static final Color PAPER = new Color(247, 249, 252);
    private static final Color ACCENT = new Color(55, 132, 255);
    private static final Color GREEN = new Color(43, 181, 111);
    private static final String UI_FONT = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "Microsoft YaHei UI" : "Dialog";
    private final Path root;
    private final Properties settings = new Properties();
    private final DefaultListModel<Instance> instances = new DefaultListModel<>();
    private final DefaultListModel<PluginInfo> plugins = new DefaultListModel<>();
    private final Map<String, List<PluginInfo>> pluginCache = new ConcurrentHashMap<>();
    private final Set<String> pluginRefreshInFlight = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> pluginRefreshPending = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Process> processes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, InstanceState> instanceStates = new ConcurrentHashMap<>();
    private final AtomicBoolean stateRefreshInFlight = new AtomicBoolean(false);
    private final Map<String, Map<String, String>> words = translations();
    private JFrame frame;
    private BackgroundPanel canvas;
    private CardLayout cards;
    private JPanel pages;
    private HeroPanel heroPanel;
    private JTextArea terminal;
    private JTextField commandField;
    private JLabel selectedName;
    private JLabel selectedStatus;
    private JLabel pluginInstanceLabel;
    private JList<PluginInfo> pluginList;
    private JTextField pluginSearchField;
    private JCheckBox hideOfficialPlugins;
    private JList<Instance> instanceList;
    private JButton heroLaunchButton;
    private JButton consoleStartButton;
    private JButton consoleStopButton;
    private JButton consoleSendButton;
    private JTextField consoleInputField;
    private JPanel runtimeGrid;
    private Instance currentInstance;
    private javax.swing.Timer stateTimer;
    private final Object runtimeInstallLock = new Object();

    public enum InstanceState {
        STOPPED,
        STARTING,
        RUNNING,
        PORT_OCCUPIED
    }

    private Launcher() {
        root = portableRoot();
        createFolders();
        loadSettings();
        loadInstances();
        currentInstance = instances.get(0);
        for (int i = 0; i < instances.size(); i++) {
            Instance inst = instances.get(i);
            ensureInstanceFolders(inst);
            loadPluginCache(inst);
        }
    }

    private Path portableRoot() {
        String configured = System.getenv("HDSL_PORTABLE_ROOT");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) return Paths.get(appPath).toAbsolutePath().getParent();
        return Paths.get(System.getProperty("user.home"), ".hdsl").toAbsolutePath();
    }

    private void createFolders() {
        for (String name : new String[]{"config", "plugins", "background", "backups", "data", "logs", "cache", "runtimes", "instances"}) {
            try { Files.createDirectories(root.resolve(name)); } catch (IOException ignored) { }
        }
    }

    private String tr(String key) {
        String language = settings.getProperty("language", "zh");
        return words.getOrDefault(language, words.get("zh")).getOrDefault(key, key);
    }

    private void loadSettings() {
        Path file = root.resolve("config/settings.properties");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                settings.load(r);
            } catch (IOException ignored) { }
        }
        settings.putIfAbsent("language", "zh");
        settings.putIfAbsent("source", "china");
        settings.putIfAbsent("harnessVersion", "0.1.0-rc.6");
    }

    private void saveSettings() {
        try (Writer w = Files.newBufferedWriter(root.resolve("config/settings.properties"), StandardCharsets.UTF_8)) {
            settings.store(w, "HDSL desktop settings");
        } catch (IOException ignored) { }
    }

    private void loadInstances() {
        Path file = root.resolve("config/instances.properties");
        Set<Integer> usedPorts = new HashSet<>();
        if (Files.exists(file)) {
            try {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                int count = Integer.parseInt(p.getProperty("count", "0"));
                for (int i = 0; i < count; i++) {
                    int port = parsePort(p.getProperty("port." + i), usedPorts);
                    usedPorts.add(port);
                    instances.addElement(new Instance(
                            p.getProperty("id." + i, "legacy-" + i),
                            p.getProperty("name." + i, "Instance " + (i + 1)),
                            p.getProperty("version." + i, settings.getProperty("harnessVersion", "0.1.0-rc.6")),
                            p.getProperty("profile." + i, "web"),
                            p.getProperty("command." + i, ""),
                            port));
                }
            } catch (Exception ignored) { }
        }
        if (instances.isEmpty()) {
            instances.addElement(new Instance("default", "DeepSeek Harness", settings.getProperty("harnessVersion", "0.1.0-rc.6"), "web", "", 3080));
        }
    }

    private int parsePort(String value, Set<Integer> used) {
        if (value != null && !value.isBlank()) {
            try {
                int port = Integer.parseInt(value.trim());
                if (port >= 1024 && port <= 65535 && !used.contains(port)) {
                    return port;
                }
            } catch (Exception ignored) { }
        }
        int port = 3080;
        while (used.contains(port) && port < 65535) {
            port++;
        }
        return port;
    }

    private int nextInstancePort() {
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < instances.size(); i++) {
            used.add(instances.get(i).port);
        }
        return parsePort(null, used);
    }

    private Path instanceDir(Instance item) { return root.resolve("instances").resolve(item.id); }
    private Path instanceHome(Instance item) { return instanceDir(item).resolve("dsh-home"); }
    private Path instanceWorkspace(Instance item) { return instanceDir(item).resolve("workspace"); }
    private Path runtimeDir(Instance item) { return root.resolve("runtimes").resolve(item.runtimeVersion); }
    private Path runtimeExecutable(Instance item) { return runtimeDir(item).resolve("node_modules/.bin").resolve(isWindows() ? "dsh.cmd" : "dsh"); }
    private Path bundledNodeDir() { return root.resolve("tools/node"); }
    private String npmExecutable() {
        Path local = bundledNodeDir().resolve(isWindows() ? "npm.cmd" : "bin/npm");
        return Files.isRegularFile(local) ? local.toString() : (isWindows() ? "npm.cmd" : "npm");
    }

    private String instanceCommand(Instance item) {
        String command = (item.customCommand == null || item.customCommand.isBlank())
                ? quote(runtimeExecutable(item).toString()) + " --profile " + item.profile
                : item.customCommand;
        return ensurePortArgument(command, item.port);
    }

    public static String ensurePortArgument(String command, int port) {
        if (command == null || command.isBlank()) return "--port " + port;
        String trimmed = command.trim();
        Pattern p = Pattern.compile("(?<=^|\\s)--port(=|\\s+)\\d+");
        Matcher m = p.matcher(trimmed);
        if (m.find()) {
            String sep = m.group(1);
            return m.replaceAll("--port" + sep + port);
        }
        return trimmed + " --port " + port;
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\\\"") + "\""; }
    private void ensureInstanceFolders(Instance item) {
        try {
            Files.createDirectories(instanceHome(item));
            Files.createDirectories(instanceWorkspace(item));
            Files.createDirectories(instanceDir(item).resolve("logs"));
        } catch (IOException ignored) { }
    }
    private boolean runtimeInstalled(Instance item) { return Files.isRegularFile(runtimeExecutable(item)); }

    private Map<String, String> isolatedEnvironment(Instance item) {
        Map<String, String> env = new HashMap<>();
        env.put("DSH_HOME", instanceHome(item).toString());
        env.put("HDSL_NPM_REGISTRY", registry());
        env.put("NPM_CONFIG_CACHE", root.resolve("cache/npm").toString());
        env.put("PNPM_HOME", root.resolve("data/pnpm").toString());
        env.put("PNPM_STORE_DIR", root.resolve("cache/pnpm-store").toString());
        env.put("XDG_CACHE_HOME", root.resolve("cache").toString());
        if (Files.isDirectory(bundledNodeDir())) {
            env.put("PATH", bundledNodeDir() + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        }
        return env;
    }

    private String installRuntime(Instance item) {
        synchronized (runtimeInstallLock) {
            if (!item.runtimeVersion.matches("[0-9A-Za-z._+-]+")) return "Invalid Harness version";
            if (runtimeInstalled(item)) return "Runtime already installed: " + item.runtimeVersion;
            try {
                Files.createDirectories(runtimeDir(item));
                Files.createDirectories(root.resolve("cache/npm"));
            } catch (IOException e) {
                return null;
            }
            String packageName = "@deepseek-ai/dsh@" + item.runtimeVersion;
            String npm = npmExecutable();
            List<String> args = isWindows()
                    ? Arrays.asList("cmd.exe", "/d", "/s", "/c", quote(npm) + " install --prefix " + quote(runtimeDir(item).toString()) + " " + packageName + " --omit=dev --registry=" + registry())
                    : Arrays.asList(npm, "install", "--prefix", runtimeDir(item).toString(), packageName, "--omit=dev", "--registry=" + registry());
            return capture(args, 900, root, isolatedEnvironment(item));
        }
    }

    private String initializeInstance(Instance item) {
        ensureInstanceFolders(item);
        if (!runtimeInstalled(item)) return null;
        List<String> args = isWindows()
                ? Arrays.asList("cmd.exe", "/d", "/s", "/c", quote(runtimeExecutable(item).toString()) + " --profile " + item.profile + " --dump-default-config")
                : Arrays.asList(runtimeExecutable(item).toString(), "--profile", item.profile, "--dump-default-config");
        return capture(args, 120, instanceWorkspace(item), isolatedEnvironment(item));
    }

    private void saveInstances() {
        Properties p = new Properties();
        p.setProperty("count", String.valueOf(instances.size()));
        for (int i = 0; i < instances.size(); i++) {
            Instance item = instances.get(i);
            p.setProperty("id." + i, item.id);
            p.setProperty("name." + i, item.name);
            p.setProperty("version." + i, item.runtimeVersion);
            p.setProperty("profile." + i, item.profile);
            p.setProperty("command." + i, item.customCommand);
            p.setProperty("port." + i, String.valueOf(item.port));
        }
        try (Writer w = Files.newBufferedWriter(root.resolve("config/instances.properties"), StandardCharsets.UTF_8)) {
            p.store(w, "HDSL instances");
        } catch (IOException ignored) { }
    }

    private void show() {
        applyTheme();
        frame = new JFrame("HDSL · Hello DeepSeek Harness Launcher");
        Path iconPath = root.resolve("app-icon.png");
        if (Files.isRegularFile(iconPath)) {
            try { frame.setIconImage(ImageIO.read(iconPath.toFile())); } catch (IOException ignored) { }
        }
        frame.setUndecorated(false);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if (stateTimer != null) stateTimer.stop();
                stopAllProcesses();
                saveInstances();
                frame.dispose();
            }
        });
        frame.setMinimumSize(new Dimension(1100, 700));
        frame.setSize(1320, 820);
        frame.setLocationRelativeTo(null);
        canvas = new BackgroundPanel(null);
        canvas.setLayout(new BorderLayout());
        canvas.add(mainArea(), BorderLayout.CENTER);
        frame.setContentPane(canvas);
        frame.setVisible(true);
        refreshRuntimeStates();
        stateTimer = new javax.swing.Timer(2000, e -> refreshRuntimeStates());
        stateTimer.start();
        refreshPlugins(currentInstance);
    }

    private void rebuild() {
        if (stateTimer != null) stateTimer.stop();
        stopAllProcesses();
        if (frame != null) frame.dispose();
        SwingUtilities.invokeLater(this::show);
    }

    private void applyTheme() {
        UIManager.put("Button.font", new Font(UI_FONT, Font.BOLD, 13));
        UIManager.put("Label.font", new Font(UI_FONT, Font.PLAIN, 13));
        UIManager.put("TextField.font", new Font(UI_FONT, Font.PLAIN, 13));
        UIManager.put("ComboBox.font", new Font(UI_FONT, Font.PLAIN, 13));
        UIManager.put("Panel.background", PAPER);
        UIManager.put("OptionPane.background", PAPER);
    }

    private JComponent topBar() {
        FrostPanel bar = new FrostPanel(new Color(255, 255, 255, 246), 0);
        bar.setLayout(new BorderLayout());
        bar.setBorder(new EmptyBorder(12, 20, 12, 20));
        JLabel logo = label("HDSL", 25, Font.BOLD, ACCENT);
        JLabel subtitle = label("  Hello DeepSeek Harness Launcher", 13, Font.PLAIN, new Color(75, 88, 105));
        JPanel brand = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        brand.add(logo);
        brand.add(subtitle);
        JLabel location = label(root.toString(), 12, Font.PLAIN, new Color(123, 137, 155));
        bar.add(brand, BorderLayout.WEST);
        bar.add(location, BorderLayout.EAST);
        return bar;
    }

    private JComponent mainArea() {
        JPanel area = transparent(new BorderLayout(12, 12));
        area.setBorder(new EmptyBorder(12, 12, 12, 12));
        area.add(sidebar(), BorderLayout.WEST);
        cards = new CardLayout();
        pages = transparent(cards);
        pages.add(homePage(), "home");
        pages.add(runtimePage(), "runtime");
        pages.add(pluginPage(), "plugins");
        pages.add(settingsPage(), "settings");
        area.add(pages, BorderLayout.CENTER);
        return area;
    }

    private JComponent sidebar() {
        FrostPanel side = new FrostPanel(new Color(255, 255, 255, 248), 24);
        side.setPreferredSize(new Dimension(220, 0));
        side.setLayout(new GridBagLayout());
        side.setBorder(new EmptyBorder(24, 16, 18, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
        JPanel brand = transparent(new FlowLayout(FlowLayout.CENTER, 8, 0));
        brand.add(new JLabel(new WhaleIcon(48, ACCENT)));
        brand.add(label("HDSL", 29, Font.BOLD, ACCENT));
        c.insets = new Insets(0, 0, 0, 0);
        side.add(brand, c);
        c.gridy++;
        c.insets = new Insets(7, 0, 26, 0);
        JLabel slogan = label("<html><div style='text-align:center'>Hello DeepSeek<br>Harness Launcher</div></html>", 12, Font.PLAIN, new Color(103, 117, 139));
        slogan.setHorizontalAlignment(SwingConstants.CENTER);
        side.add(slogan, c);
        c.insets = new Insets(0, 0, 8, 0);
        c.gridy++; side.add(navButton(tr("home"), "home"), c);
        c.gridy++; side.add(navButton(tr("runtimes"), "runtime"), c);
        c.gridy++; side.add(navButton(tr("plugins"), "plugins"), c);
        c.gridy++; c.insets = new Insets(0, 0, 18, 0); side.add(navButton(tr("settings"), "settings"), c);
        c.gridy++; c.insets = new Insets(0, 0, 16, 0); side.add(sideInfoCard(), c);
        c.gridy++; c.weighty = 1; c.fill = GridBagConstraints.BOTH; c.insets = new Insets(0, 0, 0, 0); side.add(transparent(), c);
        JPanel footer = transparent(new GridLayout(2, 1, 0, 5));
        footer.add(label("HDSL Desktop 0.6.0", 11, Font.PLAIN, new Color(119, 133, 153)));
        footer.add(label("●  " + tr("ready"), 11, Font.PLAIN, GREEN));
        c.gridy++; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL; side.add(footer, c);
        return side;
    }

    private JButton navButton(String text, String page) {
        JButton b = flatButton(text, new Color(248, 250, 254), new Color(40, 55, 77));
        b.setIcon(new LineIcon(page, 22, new Color(65, 87, 121)));
        b.setIconTextGap(15);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setPreferredSize(new Dimension(188, 56));
        b.addActionListener(e -> {
            cards.show(pages, page);
            if (page.equals("plugins")) renderPluginCache();
            if (page.equals("runtime")) refreshRuntimes();
        });
        return b;
    }

    private JComponent homePage() {
        JPanel page = transparent(new BorderLayout(14, 14));
        HeroPanel hero = new HeroPanel(loadBackground());
        heroPanel = hero;
        hero.setLayout(new BorderLayout());
        hero.setBorder(new EmptyBorder(28, 30, 25, 30));
        hero.setPreferredSize(new Dimension(0, 235));
        JPanel heroText = transparent();
        heroText.setLayout(new BoxLayout(heroText, BoxLayout.Y_AXIS));
        selectedName = label(instances.get(0).name, 31, Font.BOLD, INK);
        selectedName.setAlignmentX(Component.LEFT_ALIGNMENT);
        selectedStatus = label("", 12, Font.BOLD, new Color(94, 109, 130));
        selectedStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = label(tr("hero_caption"), 14, Font.PLAIN, new Color(82, 96, 114));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel chips = label("DeepSeek Harness   ·   Desktop   ·   Local", 12, Font.BOLD, ACCENT);
        chips.setAlignmentX(Component.LEFT_ALIGNMENT);
        heroText.add(chips);
        heroText.add(Box.createVerticalStrut(10));
        heroText.add(selectedName);
        heroText.add(Box.createVerticalStrut(4));
        heroText.add(selectedStatus);
        heroText.add(Box.createVerticalStrut(8));
        heroText.add(caption);
        JPanel featureRow = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        featureRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        featureRow.add(featureChip("rocket", tr("easy"), tr("efficient")));
        featureRow.add(featureChip("shield", tr("safe"), tr("local_run")));
        featureRow.add(featureChip("bolt", tr("flexible"), tr("plugin_support")));
        heroText.add(Box.createVerticalStrut(18));
        heroText.add(featureRow);
        heroLaunchButton = flatButton(tr("launch"), ACCENT, Color.WHITE);
        heroLaunchButton.setIcon(new LineIcon("play", 18, Color.WHITE));
        heroLaunchButton.setIconTextGap(10);
        heroLaunchButton.setPreferredSize(new Dimension(155, 58));
        heroLaunchButton.addActionListener(e -> launchProcess());
        JPanel launchWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 62));
        launchWrap.add(heroLaunchButton);
        hero.add(heroText, BorderLayout.CENTER);
        hero.add(launchWrap, BorderLayout.EAST);
        page.add(hero, BorderLayout.NORTH);
        JPanel workspace = transparent(new GridBagLayout());
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0; left.gridy = 0; left.weightx = .30; left.weighty = 1; left.fill = GridBagConstraints.BOTH; left.insets = new Insets(0, 0, 0, 10);
        workspace.add(instanceLibrary(), left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1; right.gridy = 0; right.weightx = .70; right.weighty = 1; right.fill = GridBagConstraints.BOTH;
        workspace.add(consolePanel(), right);
        page.add(workspace, BorderLayout.CENTER);
        return page;
    }

    private JComponent instanceLibrary() {
        FrostPanel panel = new FrostPanel(new Color(255, 255, 255, 250), 22);
        panel.setLayout(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JLabel title = label(tr("instances"), 17, Font.BOLD, INK);
        title.setIcon(new LineIcon("runtime", 18, new Color(65, 87, 121)));
        title.setIconTextGap(9);
        JList<Instance> list = new JList<>(instances);
        instanceList = list;
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new InstanceRenderer());
        list.setSelectedIndex(0);
        list.addListSelectionListener(e -> {
            Instance i = list.getSelectedValue();
            if (!e.getValueIsAdjusting() && i != null && i != currentInstance) {
                currentInstance = i;
                if (commandField != null) commandField.setText(instanceCommand(i));
                if (selectedName != null) selectedName.setText(i.name);
                renderPluginCache();
                refreshPlugins(i);
                updateRuntimeUi();
            }
        });
        JPanel actions = transparent(new GridLayout(1, 3, 7, 0));
        JButton add = flatButton(tr("create"), ACCENT, Color.WHITE);
        add.setIcon(new LineIcon("add", 15, Color.WHITE));
        add.setIconTextGap(7);
        add.addActionListener(e -> createInstance(list));
        JButton copy = flatButton(tr("copy"), new Color(238, 243, 249), INK);
        copy.setIcon(new LineIcon("copy", 15, new Color(65, 87, 121)));
        copy.setIconTextGap(6);
        copy.addActionListener(e -> copyInstance(list));
        JButton delete = flatButton(tr("delete"), new Color(255, 242, 242), new Color(184, 56, 56));
        delete.setIcon(new LineIcon("delete", 15, new Color(184, 56, 56)));
        delete.setIconTextGap(6);
        delete.addActionListener(e -> deleteInstance(list));
        actions.add(add); actions.add(copy); actions.add(delete);
        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent consolePanel() {
        FrostPanel panel = new FrostPanel(new Color(255, 255, 255, 250), 22);
        panel.setLayout(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        JPanel commandBar = transparent(new BorderLayout(8, 0));
        commandField = new JTextField(instanceCommand(instances.get(0)));
        commandField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(211, 221, 235)), new EmptyBorder(10, 12, 10, 12)));
        commandField.setBackground(Color.WHITE);
        commandField.setForeground(INK);
        commandField.setCaretColor(INK);
        consoleStartButton = flatButton(tr("launch"), new Color(231, 248, 240), new Color(31, 145, 85));
        consoleStartButton.setIcon(new LineIcon("play", 14, new Color(31, 145, 85)));
        consoleStartButton.setIconTextGap(6);
        consoleStartButton.addActionListener(e -> launchProcess());
        consoleStopButton = flatButton(tr("stop"), new Color(255, 239, 239), new Color(184, 56, 56));
        consoleStopButton.setIcon(new LineIcon("stop", 13, new Color(184, 56, 56)));
        consoleStopButton.setIconTextGap(6);
        consoleStopButton.addActionListener(e -> stopProcess());
        JPanel buttons = transparent(new GridLayout(1, 2, 6, 0));
        buttons.add(consoleStartButton);
        buttons.add(consoleStopButton);
        commandBar.add(commandField, BorderLayout.CENTER);
        commandBar.add(buttons, BorderLayout.EAST);
        terminal = new JTextArea();
        terminal.setEditable(false);
        terminal.setText("\n\n\n\n\n\n\n\n                         " + tr("no_output") + "\n                  " + tr("launch_for_logs"));
        terminal.setBackground(new Color(250, 252, 255));
        terminal.setForeground(new Color(122, 137, 157));
        terminal.setCaretColor(INK);
        terminal.setFont(new Font(UI_FONT, Font.PLAIN, 13));
        terminal.setBorder(new EmptyBorder(10, 10, 10, 10));
        consoleInputField = new JTextField();
        consoleInputField.addActionListener(e -> sendInput(consoleInputField));
        consoleSendButton = flatButton(tr("send"), ACCENT, Color.WHITE);
        consoleSendButton.setIcon(new LineIcon("send", 14, Color.WHITE));
        consoleSendButton.setIconTextGap(6);
        consoleSendButton.addActionListener(e -> sendInput(consoleInputField));
        JPanel inputBar = transparent(new BorderLayout(7, 0));
        inputBar.add(consoleInputField, BorderLayout.CENTER);
        inputBar.add(consoleSendButton, BorderLayout.EAST);
        panel.add(commandBar, BorderLayout.NORTH);
        panel.add(new JScrollPane(terminal), BorderLayout.CENTER);
        panel.add(inputBar, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent runtimePage() {
        JPanel content = sectionPage(tr("runtimes"), tr("runtime_help"));
        runtimeGrid = (JPanel) content.getClientProperty("grid");
        refreshRuntimes();
        return content;
    }

    private void refreshRuntimes() {
        if (runtimeGrid == null) return;
        runtimeGrid.removeAll();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.resolve("runtimes"))) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    Path executable = dir.resolve("node_modules/.bin").resolve(isWindows() ? "dsh.cmd" : "dsh");
                    runtimeGrid.add(infoCard("DeepSeek Harness " + dir.getFileName(), Files.isRegularFile(executable) ? tr("installed") : tr("installing"), dir.toString()));
                }
            }
        } catch (IOException ignored) { }
        if (runtimeGrid.getComponentCount() == 0) {
            runtimeGrid.add(infoCard(tr("no_runtimes"), tr("runtime_help"), root.resolve("runtimes").toString()));
        }
        runtimeGrid.revalidate();
        runtimeGrid.repaint();
    }

    private JComponent pluginPage() {
        FrostPanel content = new FrostPanel(new Color(247, 249, 252, 245), 24);
        content.setLayout(new BorderLayout(14, 14));
        content.setBorder(new EmptyBorder(24, 26, 24, 26));
        JPanel heading = transparent(new BorderLayout());
        JPanel titles = transparent();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(label(tr("plugins"), 27, Font.BOLD, INK));
        pluginInstanceLabel = label("", 12, Font.PLAIN, new Color(91, 105, 123));
        titles.add(Box.createVerticalStrut(5));
        titles.add(pluginInstanceLabel);
        JButton add = flatButton(tr("install_plugin"), ACCENT, Color.WHITE);
        add.addActionListener(e -> installPlugin());
        JButton update = flatButton(tr("update_plugin"), new Color(238, 243, 249), INK);
        update.addActionListener(e -> updatePlugins());
        JButton remove = flatButton(tr("remove_plugin"), new Color(255, 239, 239), new Color(184, 56, 56));
        remove.addActionListener(e -> removePlugin());
        JButton refresh = flatButton(tr("refresh"), new Color(238, 243, 249), INK);
        refresh.addActionListener(e -> refreshPlugins(selectedInstance()));
        JPanel actions = transparent(new GridLayout(1, 4, 7, 0));
        actions.add(add); actions.add(update); actions.add(remove); actions.add(refresh);
        heading.add(titles, BorderLayout.WEST);
        heading.add(actions, BorderLayout.EAST);
        pluginSearchField = new JTextField();
        pluginSearchField.setToolTipText(tr("search_plugins"));
        pluginSearchField.addActionListener(e -> renderPluginCache());
        JButton search = flatButton(tr("search"), new Color(238, 243, 249), INK);
        search.addActionListener(e -> renderPluginCache());
        hideOfficialPlugins = new JCheckBox(tr("hide_official_plugins"));
        hideOfficialPlugins.setOpaque(false);
        hideOfficialPlugins.setForeground(new Color(70, 86, 108));
        hideOfficialPlugins.addActionListener(e -> renderPluginCache());
        JPanel searchBox = transparent(new BorderLayout(7, 0));
        searchBox.add(pluginSearchField, BorderLayout.CENTER);
        searchBox.add(search, BorderLayout.EAST);
        JPanel filters = transparent(new BorderLayout(12, 0));
        filters.add(searchBox, BorderLayout.CENTER);
        filters.add(hideOfficialPlugins, BorderLayout.EAST);
        JPanel top = transparent(new BorderLayout(0, 12));
        top.add(heading, BorderLayout.NORTH);
        top.add(filters, BorderLayout.SOUTH);
        pluginList = new JList<>(plugins);
        pluginList.setCellRenderer(new PluginRenderer());
        pluginList.setBackground(Color.WHITE);
        pluginList.setFixedCellHeight(54);
        JScrollPane scroll = new JScrollPane(pluginList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(218, 227, 239)));
        JLabel hint = label(tr("plugin_detect_hint"), 11, Font.PLAIN, new Color(107, 121, 141));
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(hint, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(this::renderPluginCache);
        return content;
    }

    private void renderPluginCache() {
        if (pluginInstanceLabel == null) return;
        Instance item = selectedInstance();
        if (item == null) return;
        List<PluginInfo> source = pluginCache.getOrDefault(item.id, Collections.emptyList());
        String query = pluginSearchField == null ? "" : pluginSearchField.getText().trim();
        boolean hide = hideOfficialPlugins != null && hideOfficialPlugins.isSelected();
        plugins.clear();
        for (PluginInfo info : source) {
            if (pluginMatches(info, query) && (!hide || !isOfficialPlugin(info))) {
                plugins.addElement(info);
            }
        }
        if (source.isEmpty()) {
            plugins.addElement(new PluginInfo("", pluginRefreshInFlight.contains(item.id) ? tr("loading_plugins") : tr("no_cached_plugins"), "", false, false));
        }
        int visible = source.isEmpty() ? 0 : plugins.size();
        int total = source.size();
        String count = (visible == total) ? total + " " + tr("plugin_count") : visible + " / " + total + " " + tr("plugin_count");
        pluginInstanceLabel.setText(tr("current_instance") + ": " + item.name + "  ·  " + count);
    }

    public static boolean isOfficialPlugin(PluginInfo info) {
        if (info == null) return false;
        String name = info.name != null ? info.name.trim() : "";
        String id = info.id != null ? info.id.trim() : "";
        return name.startsWith("@deepseek-ai/") || id.startsWith("@deepseek-ai/");
    }

    public static boolean pluginMatches(PluginInfo info, String query) {
        if (info == null) return false;
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return (info.id != null && info.id.toLowerCase(Locale.ROOT).contains(q))
                || (info.name != null && info.name.toLowerCase(Locale.ROOT).contains(q))
                || (info.version != null && info.version.toLowerCase(Locale.ROOT).contains(q));
    }

    private void refreshPlugins(Instance item) {
        if (item == null) return;
        if (!pluginRefreshInFlight.add(item.id)) {
            pluginRefreshPending.add(item.id);
            return;
        }
        if (item == currentInstance) renderPluginCache();
        new SwingWorker<List<PluginInfo>, Void>() {
            protected List<PluginInfo> doInBackground() {
                return detectPlugins(item);
            }
            protected void done() {
                try {
                    List<PluginInfo> result = get();
                    if (result != null) {
                        pluginCache.put(item.id, new ArrayList<>(result));
                        writePluginCache(item, result);
                    }
                } catch (Exception e) {
                    if (!pluginCache.containsKey(item.id)) {
                        pluginCache.put(item.id, List.of(new PluginInfo("", tr("plugin_read_failed"), e.getMessage(), false, false)));
                    }
                } finally {
                    pluginRefreshInFlight.remove(item.id);
                    boolean pending = pluginRefreshPending.remove(item.id);
                    if (item == currentInstance) renderPluginCache();
                    if (pending) refreshPlugins(item);
                }
            }
        }.execute();
    }

    private List<PluginInfo> detectPlugins(Instance item) {
        List<PluginInfo> result = new ArrayList<>();
        Path manifest = instanceHome(item).resolve("profiles").resolve(item.profile).resolve("package.json");
        Map<String, String> dependencies = new LinkedHashMap<>();
        Set<String> bundles = new LinkedHashSet<>();
        try {
            if (Files.isRegularFile(manifest)) {
                String json = Files.readString(manifest, StandardCharsets.UTF_8);
                dependencies.putAll(jsonObjectPairs(json, "dependencies"));
                bundles.addAll(jsonArrayValues(json, "bundles"));
            }
        } catch (IOException ignored) { }
        String listed = runDsh(item, "plugin --profile " + item.profile + " list --depth 0 --json", 120);
        if (listed != null) {
            try {
                String json = Files.readString(manifest, StandardCharsets.UTF_8);
                dependencies.clear();
                dependencies.putAll(jsonObjectPairs(json, "dependencies"));
                bundles.clear();
                bundles.addAll(jsonArrayValues(json, "bundles"));
            } catch (IOException ignored) { }
        }
        String dump = runDsh(item, "--profile " + item.profile + " --dump-config", 120);
        if (dump != null) result.addAll(parseConfigPlugins(item, dump));
        if (result.isEmpty()) {
            for (String bundle : bundles) {
                result.add(new PluginInfo("bundle", bundle, dependencies.getOrDefault(bundle, tr("built_in")), true, dependencies.containsKey(bundle)));
            }
        }
        Set<String> managedNames = new HashSet<>();
        for (PluginInfo info : result) {
            if (info.managed) managedNames.add(info.name);
        }
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            if (!managedNames.contains(entry.getKey())) {
                result.add(new PluginInfo("package", entry.getKey(), entry.getValue(), bundles.contains(entry.getKey()), true));
            }
        }
        if (result.isEmpty()) {
            result.add(new PluginInfo("", Files.isRegularFile(manifest) ? tr("no_plugins") : tr("profile_not_initialized"), "", false, false));
        }
        return result;
    }

    private Path pluginCacheFile(Instance item) { return root.resolve("cache/plugins").resolve(item.id + ".tsv"); }
    private static String cacheEncode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
    private static String cacheDecode(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
    private void writePluginCache(Instance item, List<PluginInfo> values) {
        if (item == null || values == null) return;
        try {
            Files.createDirectories(root.resolve("cache/plugins"));
            List<String> lines = new ArrayList<>();
            for (PluginInfo info : values) {
                lines.add(cacheEncode(info.id) + "\t" + cacheEncode(info.name) + "\t" + cacheEncode(info.version) + "\t" + info.enabled + "\t" + info.managed);
            }
            Files.write(pluginCacheFile(item), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }
    private void loadPluginCache(Instance item) {
        if (item == null) return;
        Path file = pluginCacheFile(item);
        if (!Files.isRegularFile(file)) return;
        List<PluginInfo> values = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 5) {
                    values.add(new PluginInfo(
                            cacheDecode(parts[0]),
                            cacheDecode(parts[1]),
                            cacheDecode(parts[2]),
                            Boolean.parseBoolean(parts[3]),
                            Boolean.parseBoolean(parts[4])));
                }
            }
            if (!values.isEmpty()) {
                pluginCache.put(item.id, values);
            }
        } catch (Exception ignored) { }
    }

    private String runDsh(Instance item, String arguments, int timeout) {
        if (!runtimeInstalled(item)) return null;
        return capture(shell(quote(runtimeExecutable(item).toString()) + " " + arguments), timeout, instanceWorkspace(item), isolatedEnvironment(item));
    }

    private List<PluginInfo> parseConfigPlugins(Instance item, String yaml) {
        List<PluginInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String id = null;
        for (String line : yaml.split("\\R")) {
            Matcher idMatch = Pattern.compile("^\\s*- id:\\s*['\\\"]?([^'\\\"#]+)").matcher(line);
            if (idMatch.find()) {
                id = idMatch.group(1).trim();
                continue;
            }
            if (id != null) {
                Matcher nameMatch = Pattern.compile("^\\s+name:\\s*['\\\"]?([^'\\\"#]+)").matcher(line);
                if (nameMatch.find()) {
                    String name = nameMatch.group(1).trim(), key = id + "|" + name;
                    if (seen.add(key)) result.add(new PluginInfo(id, name, packageVersion(item, name), true, false));
                    id = null;
                }
            }
        }
        return result;
    }

    private String packageVersion(Instance item, String packageName) {
        for (Path base : Arrays.asList(instanceHome(item).resolve("profiles").resolve(item.profile).resolve("node_modules"), runtimeDir(item).resolve("node_modules"))) {
            Path manifest = base.resolve(packageName).resolve("package.json");
            if (Files.isRegularFile(manifest)) {
                try {
                    Matcher m = Pattern.compile("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(Files.readString(manifest, StandardCharsets.UTF_8));
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) { }
            }
        }
        return tr("configured");
    }

    private void installPlugin() {
        String spec = JOptionPane.showInputDialog(frame, tr("plugin_package"));
        if (spec == null || spec.isBlank()) return;
        if (!spec.matches("[0-9A-Za-z@._/+:#=~-]+")) {
            JOptionPane.showMessageDialog(frame, tr("invalid_plugin_spec"));
            return;
        }
        runPluginMutation("add " + quote(spec), tr("install_plugin"));
    }
    private void updatePlugins() { runPluginMutation("update", tr("update_plugin")); }
    private void removePlugin() {
        PluginInfo selected = pluginList == null ? null : pluginList.getSelectedValue();
        if (selected == null || !selected.managed) {
            JOptionPane.showMessageDialog(frame, tr("select_managed_plugin"));
            return;
        }
        if (JOptionPane.showConfirmDialog(frame, tr("remove_confirm") + "\n" + selected.name, "HDSL", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runPluginMutation("remove " + quote(selected.name), tr("remove_plugin"));
    }
    private void runPluginMutation(String args, String label) {
        Instance item = selectedInstance();
        if (item == null) return;
        if (pluginInstanceLabel != null) pluginInstanceLabel.setText(label + "…");
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                return runDsh(item, "plugin --profile " + item.profile + " " + args, 900);
            }
            protected void done() {
                boolean success = false;
                try {
                    success = get() != null;
                    if (!success) JOptionPane.showMessageDialog(frame, tr("plugin_command_failed") + "\n" + root.resolve("logs"), "HDSL", JOptionPane.ERROR_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(frame, e.toString(), "HDSL", JOptionPane.ERROR_MESSAGE);
                }
                if (success) refreshPlugins(item); else renderPluginCache();
            }
        }.execute();
    }

    private Map<String, String> jsonObjectPairs(String json, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher section = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(json);
        if (!section.find()) return result;
        Matcher pair = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(section.group(1));
        while (pair.find()) result.put(pair.group(1), pair.group(2));
        return result;
    }

    private Set<String> jsonArrayValues(String json, String key) {
        Set<String> result = new LinkedHashSet<>();
        Matcher section = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\[([^]]*)]", Pattern.DOTALL).matcher(json);
        if (!section.find()) return result;
        Matcher value = Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(section.group(1));
        while (value.find()) result.add(value.group(1));
        return result;
    }

    private JPanel sectionPage(String title, String help) {
        FrostPanel outer = new FrostPanel(new Color(247, 249, 252, 242), 24);
        outer.setLayout(new BorderLayout(14, 14));
        outer.setBorder(new EmptyBorder(24, 26, 24, 26));
        JPanel heading = transparent();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(label(title, 27, Font.BOLD, INK));
        heading.add(Box.createVerticalStrut(5));
        heading.add(label(help, 13, Font.PLAIN, new Color(91, 105, 123)));
        outer.add(heading, BorderLayout.NORTH);
        JPanel grid = transparent(new GridLayout(0, 2, 14, 14));
        outer.add(grid, BorderLayout.CENTER);
        outer.putClientProperty("grid", grid);
        return outer;
    }

    private JComponent infoCard(String title, String subtitle, String detail) {
        FrostPanel card = new FrostPanel(Color.WHITE, 18);
        card.setLayout(new BorderLayout(8, 8));
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        card.add(label(title, 17, Font.BOLD, INK), BorderLayout.NORTH);
        JTextArea text = new JTextArea(subtitle + "\n\n" + detail);
        text.setEditable(false);
        text.setOpaque(false);
        text.setForeground(new Color(93, 105, 121));
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private JComponent sideInfoCard() {
        FrostPanel card = new FrostPanel(new Color(244, 248, 255), 16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(13, 13, 13, 13));
        card.setMaximumSize(new Dimension(188, 104));
        card.setPreferredSize(new Dimension(188, 104));
        card.setMinimumSize(new Dimension(188, 104));
        JLabel title = label(tr("workspace"), 12, Font.BOLD, new Color(55, 75, 104));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel count = label(instances.size() + "  " + tr("instance_count"), 11, Font.PLAIN, new Color(106, 121, 141));
        count.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel source = label(settings.getProperty("source", "china").equals("china") ? tr("china_short") : tr("official"), 10, Font.PLAIN, ACCENT);
        source.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(8));
        card.add(count);
        card.add(Box.createVerticalStrut(5));
        card.add(source);
        return card;
    }

    private JComponent featureChip(String icon, String title, String subtitle) {
        FrostPanel chip = new FrostPanel(new Color(255, 255, 255, 218), 16);
        chip.setLayout(new BorderLayout(8, 0));
        chip.setBorder(new EmptyBorder(8, 11, 8, 11));
        JLabel mark = new JLabel(new LineIcon(icon, 18, ACCENT));
        JPanel text = transparent();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(label(title, 11, Font.BOLD, INK));
        text.add(label(subtitle, 9, Font.PLAIN, new Color(107, 121, 141)));
        chip.add(mark, BorderLayout.WEST);
        chip.add(text, BorderLayout.CENTER);
        return chip;
    }

    private JComponent settingsPage() {
        FrostPanel outer = new FrostPanel(new Color(247, 249, 252, 245), 24);
        outer.setLayout(new BorderLayout(16, 16));
        outer.setBorder(new EmptyBorder(22, 24, 22, 24));
        JPanel top = transparent(new GridLayout(1, 2, 14, 0));
        top.add(appearanceSettings());
        top.add(sourceSettings());
        JPanel dependencies = new JPanel();
        dependencies.setOpaque(false);
        dependencies.setLayout(new BoxLayout(dependencies, BoxLayout.Y_AXIS));
        JLabel depTitle = label(tr("dependencies"), 21, Font.BOLD, INK);
        dependencies.add(depTitle);
        dependencies.add(Box.createVerticalStrut(10));
        JTextArea statusLog = new JTextArea(6, 40);
        statusLog.setEditable(false);
        statusLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (Dependency d : dependencyList()) {
            dependencies.add(dependencyRow(d, statusLog));
            dependencies.add(Box.createVerticalStrut(7));
        }
        JScrollPane scroll = new JScrollPane(dependencies);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        outer.add(top, BorderLayout.NORTH);
        outer.add(scroll, BorderLayout.CENTER);
        outer.add(new JScrollPane(statusLog), BorderLayout.SOUTH);
        return outer;
    }

    private JComponent appearanceSettings() {
        FrostPanel card = new FrostPanel(Color.WHITE, 18);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(16, 16, 16, 16));
        GridBagConstraints c = constraints();
        c.gridwidth = 2; card.add(label(tr("appearance"), 18, Font.BOLD, INK), c);
        c.gridy++; c.gridwidth = 1;
        card.add(label(tr("language"), 13, Font.PLAIN, INK), c);
        c.gridx = 1;
        JComboBox<String> language = new JComboBox<>(new String[]{"中文", "English"});
        language.setSelectedIndex(settings.getProperty("language", "zh").equals("zh") ? 0 : 1);
        card.add(language, c);
        c.gridy++; c.gridx = 0;
        card.add(label(tr("background"), 13, Font.PLAIN, INK), c);
        c.gridx = 1;
        JButton choose = flatButton(tr("choose_image"), ACCENT, Color.WHITE);
        choose.addActionListener(e -> chooseBackground());
        card.add(choose, c);
        language.addActionListener(e -> {
            settings.setProperty("language", language.getSelectedIndex() == 0 ? "zh" : "en");
            saveSettings();
            rebuild();
        });
        return card;
    }

    private JComponent sourceSettings() {
        FrostPanel card = new FrostPanel(Color.WHITE, 18);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(16, 16, 16, 16));
        GridBagConstraints c = constraints();
        c.gridwidth = 2; card.add(label(tr("download_source"), 18, Font.BOLD, INK), c);
        c.gridy++; c.gridwidth = 1;
        card.add(label(tr("source"), 13, Font.PLAIN, INK), c);
        c.gridx = 1;
        JComboBox<String> source = new JComboBox<>(new String[]{tr("china_mirror"), tr("official")});
        source.setSelectedIndex(settings.getProperty("source", "china").equals("china") ? 0 : 1);
        card.add(source, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        JLabel registry = label(registry(), 11, Font.PLAIN, new Color(87, 104, 124));
        card.add(registry, c);
        source.addActionListener(e -> {
            settings.setProperty("source", source.getSelectedIndex() == 0 ? "china" : "official");
            saveSettings();
            registry.setText(registry());
        });
        return card;
    }

    private JComponent dependencyRow(Dependency d, JTextArea log) {
        FrostPanel row = new FrostPanel(Color.WHITE, 15);
        row.setLayout(new BorderLayout(10, 0));
        row.setBorder(new EmptyBorder(10, 13, 10, 13));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        JPanel text = transparent();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(label(d.name, 14, Font.BOLD, INK));
        JLabel state = label(tr("not_checked"), 11, Font.PLAIN, new Color(111, 124, 141));
        text.add(state);
        JButton detect = flatButton(tr("detect"), new Color(226, 233, 242), INK);
        detect.addActionListener(e -> detectDependency(d, state, log));
        JButton install = flatButton(tr("install"), ACCENT, Color.WHITE);
        install.addActionListener(e -> installDependency(d, state, log));
        JPanel actions = transparent(new GridLayout(1, 2, 7, 0));
        actions.add(detect); actions.add(install);
        row.add(text, BorderLayout.CENTER);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private void detectDependency(Dependency d, JLabel state, JTextArea log) {
        state.setText(tr("checking"));
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return capture(shell(d.detect), 12); }
            protected void done() {
                try {
                    String result = get();
                    boolean ok = result != null && !result.isBlank();
                    state.setText(ok ? "✓ " + firstLine(result) : "✕ " + tr("missing"));
                    state.setForeground(ok ? new Color(31, 145, 85) : new Color(184, 56, 56));
                    log.append("[detect] " + d.name + ": " + (ok ? result : tr("missing")) + "\n");
                } catch (Exception e) {
                    state.setText("✕ " + tr("missing"));
                }
            }
        }.execute();
    }

    private void installDependency(Dependency d, JLabel state, JTextArea log) {
        String cmd = d.install(settings.getProperty("source", "china"), registry());
        int answer = JOptionPane.showConfirmDialog(frame, tr("install_confirm") + "\n\n" + cmd, d.name, JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        state.setText(tr("installing"));
        log.append("$ " + cmd + "\n");
        new SwingWorker<String, String>() {
            protected String doInBackground() { return capture(shell(cmd), 900); }
            protected void done() {
                try {
                    String result = get();
                    log.append((result == null ? tr("install_failed") : result) + "\n");
                    state.setText(result == null ? "✕ " + tr("install_failed") : "✓ " + tr("installed"));
                } catch (Exception e) {
                    state.setText("✕ " + tr("install_failed"));
                    log.append(e + "\n");
                }
            }
        }.execute();
    }

    private List<Dependency> dependencyList() {
        String version = settings.getProperty("harnessVersion", "0.1.0-rc.6");
        Path node = bundledNodeDir().resolve(isWindows() ? "node.exe" : "bin/node");
        Path npm = bundledNodeDir().resolve(isWindows() ? "npm.cmd" : "bin/npm");
        Path runtime = root.resolve("runtimes").resolve(version);
        Path dsh = runtime.resolve("node_modules/@deepseek-ai/dsh/lib/bin.js");
        String nodeDetect = Files.isRegularFile(node) ? quote(node.toString()) + " --version" : "node --version";
        String npmDetect = Files.isRegularFile(npm) ? quote(npm.toString()) + " --version" : "npm --version";
        String dshDetect = Files.isRegularFile(node) && Files.isRegularFile(dsh)
                ? quote(node.toString()) + " " + quote(dsh.toString()) + " --version"
                : quote(runtime.resolve("node_modules/.bin").resolve(isWindows() ? "dsh.cmd" : "dsh").toString()) + " --version";
        return Arrays.asList(
                new Dependency("Java", "java -version", (s, r) -> "winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements"),
                new Dependency("Node.js", nodeDetect, (s, r) -> "winget install --id OpenJS.NodeJS.LTS -e --accept-source-agreements --accept-package-agreements"),
                new Dependency("npm", npmDetect, (s, r) -> quote(npmExecutable()) + " config set registry " + r),
                new Dependency("Git", "git --version", (s, r) -> "winget install --id Git.Git -e --accept-source-agreements --accept-package-agreements"),
                new Dependency("DeepSeek Harness", dshDetect, (s, r) -> quote(npmExecutable()) + " install --prefix " + quote(runtime.toString()) + " @deepseek-ai/dsh@" + version + " --omit=dev --cache=" + quote(root.resolve("cache/npm").toString()) + " --registry=" + r)
        );
    }

    private String registry() { return settings.getProperty("source", "china").equals("china") ? "https://registry.npmmirror.com" : "https://registry.npmjs.org"; }

    private void chooseBackground() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "webp"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        String extension = extension(source.getFileName().toString());
        Path target = root.resolve("background/custom" + extension);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            settings.setProperty("background", target.toString());
            saveSettings();
            if (heroPanel != null) {
                heroPanel.setImage(loadBackground());
                heroPanel.repaint();
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "HDSL", JOptionPane.ERROR_MESSAGE);
        }
    }

    private BufferedImage loadBackground() {
        List<Path> candidates = new ArrayList<>();
        String saved = settings.getProperty("background");
        if (saved != null) candidates.add(Paths.get(saved));
        candidates.add(root.resolve("background/hero-banner.png"));
        candidates.add(root.resolve("background/background1.jpg"));
        candidates.add(root.resolve("background/default.jpg"));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.resolve("background"), "*.{jpg,jpeg,png}")) {
            for (Path p : stream) candidates.add(p);
        } catch (IOException ignored) { }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try { return ImageIO.read(p.toFile()); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private Path pidFile(Instance item) {
        return instanceDir(item).resolve("launcher.pid");
    }

    private void writePid(Instance item, long pid) {
        try {
            Files.writeString(pidFile(item), String.valueOf(pid), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private void clearPid(Instance item) {
        try {
            Files.deleteIfExists(pidFile(item));
        } catch (IOException ignored) { }
    }

    private Long readSavedPid(Instance item) {
        Path file = pidFile(item);
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!text.isBlank()) {
                    return Long.parseLong(text);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static boolean matchesInstanceOwnershipEvidence(String commandLine, String instanceId, String workspacePath, String homePath, String runtimePath, int port) {
        if (commandLine == null || commandLine.isBlank()) return false;
        String cmd = commandLine.toLowerCase(Locale.ROOT);

        Pattern portPattern = Pattern.compile("(?:^|\\s)--port(?:=|\\s+)" + port + "(?:\\s|$)");
        if (!portPattern.matcher(cmd).find()) {
            return false;
        }

        boolean hasInstanceId = instanceId != null && !instanceId.isBlank() && cmd.contains(instanceId.toLowerCase(Locale.ROOT));
        boolean hasWorkspace = workspacePath != null && !workspacePath.isBlank() && cmd.contains(workspacePath.toLowerCase(Locale.ROOT));
        boolean hasHome = homePath != null && !homePath.isBlank() && cmd.contains(homePath.toLowerCase(Locale.ROOT));
        boolean hasRuntime = runtimePath != null && !runtimePath.isBlank() && cmd.contains(runtimePath.toLowerCase(Locale.ROOT));

        return hasInstanceId || hasWorkspace || hasHome || hasRuntime;
    }

    private boolean isProcessOwnedByInstance(Instance item, ProcessHandle handle) {
        if (item == null || handle == null || !handle.isAlive()) return false;
        try {
            ProcessHandle.Info info = handle.info();
            String commandLine = info.commandLine().orElse("");
            String command = info.command().orElse("");
            String[] arguments = info.arguments().orElse(new String[0]);
            StringBuilder allArgs = new StringBuilder(commandLine).append(" ").append(command);
            for (String arg : arguments) {
                allArgs.append(" ").append(arg);
            }
            String combined = allArgs.toString();

            String instId = item.id;
            String wsPath = instanceWorkspace(item).toString();
            String homePath = instanceHome(item).toString();
            String runtimePath = runtimeDir(item).toString();

            if (matchesInstanceOwnershipEvidence(combined, instId, wsPath, homePath, runtimePath, item.port)) {
                return true;
            }
            for (ProcessHandle child : handle.children().toList()) {
                if (isProcessOwnedByInstance(item, child)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    public static boolean isLoopbackPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 150);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private InstanceState computeInstanceState(Instance item) {
        if (item == null) return InstanceState.STOPPED;
        Process liveProcess = processes.get(item.id);
        boolean hasLiveProcess = (liveProcess != null && liveProcess.isAlive());
        boolean hasRecoveredProcess = false;
        if (!hasLiveProcess) {
            Long pid = readSavedPid(item);
            if (pid != null) {
                Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
                if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                    if (isProcessOwnedByInstance(item, handleOpt.get())) {
                        hasRecoveredProcess = true;
                    }
                } else {
                    clearPid(item);
                }
            }
        }

        boolean isOwned = hasLiveProcess || hasRecoveredProcess;
        boolean portOpen = isLoopbackPortOpen(item.port);

        if (isOwned) {
            return portOpen ? InstanceState.RUNNING : InstanceState.STARTING;
        } else {
            return portOpen ? InstanceState.PORT_OCCUPIED : InstanceState.STOPPED;
        }
    }

    private void refreshRuntimeStates() {
        if (!stateRefreshInFlight.compareAndSet(false, true)) return;
        List<Instance> snapshot = new ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            snapshot.add(instances.get(i));
        }
        new SwingWorker<Map<String, InstanceState>, Void>() {
            protected Map<String, InstanceState> doInBackground() {
                Map<String, InstanceState> map = new HashMap<>();
                for (Instance item : snapshot) {
                    map.put(item.id, computeInstanceState(item));
                }
                return map;
            }
            protected void done() {
                try {
                    Map<String, InstanceState> map = get();
                    if (map != null) {
                        instanceStates.putAll(map);
                        updateRuntimeUi();
                    }
                } catch (Exception ignored) {
                } finally {
                    stateRefreshInFlight.set(false);
                }
            }
        }.execute();
    }

    private void updateRuntimeUi() {
        if (currentInstance == null) return;
        InstanceState state = instanceStates.getOrDefault(currentInstance.id, InstanceState.STOPPED);
        if (selectedStatus != null) {
            int port = currentInstance.port;
            if (state == InstanceState.RUNNING) {
                selectedStatus.setText("● " + tr("running") + " (" + tr("port") + ": " + port + ")");
                selectedStatus.setForeground(GREEN);
            } else if (state == InstanceState.STARTING) {
                selectedStatus.setText("● " + tr("starting") + " (" + tr("port") + ": " + port + ")");
                selectedStatus.setForeground(ACCENT);
            } else if (state == InstanceState.PORT_OCCUPIED) {
                selectedStatus.setText("● " + tr("port_occupied") + " (" + tr("port") + ": " + port + ")");
                selectedStatus.setForeground(new Color(184, 56, 56));
            } else {
                selectedStatus.setText("● " + tr("stopped") + " (" + tr("port") + ": " + port + ")");
                selectedStatus.setForeground(new Color(122, 137, 157));
            }
        }
        boolean canLaunch = (state == InstanceState.STOPPED);
        boolean canStop = (state == InstanceState.RUNNING || state == InstanceState.STARTING);
        boolean canSend = (processes.get(currentInstance.id) != null && processes.get(currentInstance.id).isAlive());

        if (heroLaunchButton != null) heroLaunchButton.setEnabled(canLaunch);
        if (consoleStartButton != null) consoleStartButton.setEnabled(canLaunch);
        if (consoleStopButton != null) consoleStopButton.setEnabled(canStop);
        if (consoleSendButton != null) consoleSendButton.setEnabled(canSend);
        if (consoleInputField != null) consoleInputField.setEnabled(canSend);
        if (instanceList != null) instanceList.repaint();
    }

    private void launchProcess() {
        Instance selected = selectedInstance();
        if (selected == null) return;
        InstanceState currentState = instanceStates.getOrDefault(selected.id, InstanceState.STOPPED);
        if (currentState == InstanceState.RUNNING || currentState == InstanceState.STARTING) {
            return;
        }
        if (currentState == InstanceState.PORT_OCCUPIED) {
            JOptionPane.showMessageDialog(frame, tr("port_conflict_warn") + " (:" + selected.port + ")", "HDSL", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String generated = instanceCommand(selected);
        String entered = commandField != null ? commandField.getText().trim() : "";
        if (!entered.isBlank()) {
            entered = ensurePortArgument(entered, selected.port);
            if (!entered.equals(generated)) {
                selected.customCommand = entered;
            }
        }
        saveInstances();
        String actual = instanceCommand(selected);
        append("\n[" + selected.name + " · " + selected.runtimeVersion + " (:" + selected.port + ")]\n> " + actual + "\n");

        if (!runtimeInstalled(selected)) {
            append(tr("runtime_missing_create") + "\n");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(shell(actual));
            pb.directory(instanceWorkspace(selected).toFile());
            pb.redirectErrorStream(true);
            pb.environment().putAll(isolatedEnvironment(selected));
            Process p = pb.start();
            processes.put(selected.id, p);
            writePid(selected, p.pid());
            instanceStates.put(selected.id, InstanceState.STARTING);
            updateRuntimeUi();

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        append(line + "\n");
                    }
                } catch (IOException ignored) { }
                processes.remove(selected.id);
                clearPid(selected);
                SwingUtilities.invokeLater(() -> {
                    instanceStates.put(selected.id, InstanceState.STOPPED);
                    updateRuntimeUi();
                    refreshRuntimeStates();
                });
            }, "hdsl-output-" + selected.id);
            reader.setDaemon(true);
            reader.start();

            refreshPlugins(selected);
            refreshRuntimeStates();
        } catch (IOException e) {
            append(tr("launch_failed") + ": " + e + "\n");
        }
    }

    private Instance selectedInstance() {
        return currentInstance == null ? (instances.isEmpty() ? null : instances.get(0)) : currentInstance;
    }

    private void stopProcess() {
        stopProcess(selectedInstance());
    }

    private void stopProcess(Instance item) {
        if (item == null) return;
        InstanceState state = instanceStates.getOrDefault(item.id, InstanceState.STOPPED);
        if (state == InstanceState.PORT_OCCUPIED) {
            append("\n[" + tr("port_conflict_warn") + ": " + item.port + "]\n");
            return;
        }

        Process liveProcess = processes.get(item.id);
        if (liveProcess != null && liveProcess.isAlive()) {
            terminateProcessTree(liveProcess.toHandle());
        }

        Long savedPid = readSavedPid(item);
        boolean savedPidVerified = false;
        if (savedPid != null) {
            Optional<ProcessHandle> handleOpt = ProcessHandle.of(savedPid);
            if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                if (isProcessOwnedByInstance(item, handleOpt.get())) {
                    savedPidVerified = true;
                    terminateProcessTree(handleOpt.get());
                }
            } else {
                clearPid(item);
            }
        }

        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            boolean alive = (liveProcess != null && liveProcess.isAlive())
                    || (savedPid != null && savedPidVerified && ProcessHandle.of(savedPid).map(ProcessHandle::isAlive).orElse(false));
            boolean portOpen = isLoopbackPortOpen(item.port);
            if (!alive && !portOpen) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean stillAlive = (liveProcess != null && liveProcess.isAlive())
                || (savedPid != null && savedPidVerified && ProcessHandle.of(savedPid).map(ProcessHandle::isAlive).orElse(false));
        boolean stillPortOpen = isLoopbackPortOpen(item.port);

        if (!stillAlive) {
            processes.remove(item.id);
            if (savedPidVerified) clearPid(item);
        }

        if (!stillAlive && !stillPortOpen) {
            instanceStates.put(item.id, InstanceState.STOPPED);
            append("\n[" + tr("stopped") + "]\n");
        } else {
            instanceStates.put(item.id, computeInstanceState(item));
            append("\n[" + tr("stop_pending_or_port_held") + "]\n");
        }

        updateRuntimeUi();
        refreshRuntimeStates();
    }

    private static void terminateProcessTree(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) return;
        long rootPid = handle.pid();
        if (isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(rootPid), "/T", "/F").start().waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) { }

            long deadline = System.currentTimeMillis() + 1500;
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (handle.isAlive()) {
                try {
                    handle.descendants().forEach(h -> {
                        try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) { }
                    });
                    if (handle.isAlive()) handle.destroyForcibly();
                } catch (Exception ignored) { }
            }
        } else {
            List<ProcessHandle> descendants = handle.descendants().toList();
            for (int i = descendants.size() - 1; i >= 0; i--) {
                ProcessHandle h = descendants.get(i);
                try { if (h.isAlive()) h.destroy(); } catch (Exception ignored) { }
            }
            try { if (handle.isAlive()) handle.destroy(); } catch (Exception ignored) { }

            long deadline = System.currentTimeMillis() + 1500;
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (handle.isAlive()) {
                for (int i = descendants.size() - 1; i >= 0; i--) {
                    ProcessHandle h = descendants.get(i);
                    try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) { }
                }
                try { if (handle.isAlive()) handle.destroyForcibly(); } catch (Exception ignored) { }
            }
        }
    }

    private void stopAllProcesses() {
        for (int i = 0; i < instances.size(); i++) {
            Instance item = instances.get(i);
            Process live = processes.remove(item.id);
            if (live != null && live.isAlive()) {
                terminateProcessTree(live.toHandle());
            }
            Long savedPid = readSavedPid(item);
            if (savedPid != null) {
                Optional<ProcessHandle> handleOpt=ProcessHandle.of(savedPid);
                if(handleOpt.isEmpty()||!handleOpt.get().isAlive())clearPid(item);
                else if(isProcessOwnedByInstance(item,handleOpt.get())){
                    terminateProcessTree(handleOpt.get());
                    if(!handleOpt.get().isAlive())clearPid(item);
                }
            }
        }
    }

    private void sendInput(JTextField input) {
        Instance selected = selectedInstance();
        if (selected == null) return;
        Process p = processes.get(selected.id);
        if (p != null && p.isAlive()) {
            try {
                p.getOutputStream().write((input.getText() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
                input.setText("");
            } catch (IOException ignored) { }
        }
    }

    private void append(String text) {
        if (terminal != null) {
            SwingUtilities.invokeLater(() -> {
                terminal.append(text);
                terminal.setCaretPosition(terminal.getDocument().getLength());
            });
        }
    }

    private void createInstance(JList<Instance> list) {
        JTextField nameField = new JTextField(tr("new_harness"));
        JTextField versionField = new JTextField(settings.getProperty("harnessVersion", "0.1.0-rc.6"));
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel(tr("instance_name"))); form.add(nameField);
        form.add(new JLabel(tr("harness_version"))); form.add(versionField);
        if (JOptionPane.showConfirmDialog(frame, form, tr("create_harness"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String name = nameField.getText().trim(), version = versionField.getText().trim();
        if (name.isBlank() || !version.matches("[0-9A-Za-z._+-]+")) {
            JOptionPane.showMessageDialog(frame, tr("invalid_name_version"));
            return;
        }
        int port = nextInstancePort();
        Instance item = new Instance(UUID.randomUUID().toString(), name, version, "web", "", port);
        ensureInstanceFolders(item);
        instances.addElement(item);
        currentInstance = item;
        list.setSelectedIndex(instances.size() - 1);
        settings.setProperty("harnessVersion", version);
        saveSettings();
        saveInstances();
        if (commandField != null) commandField.setText(instanceCommand(item));
        append("\n[HDSL] " + tr("creating_harness") + " " + name + " @ " + version + " (:" + port + ")\n");
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                String installed = installRuntime(item);
                if (installed == null) return null;
                String initialized = initializeInstance(item);
                return initialized == null ? null : installed;
            }
            protected void done() {
                try {
                    String result = get();
                    if (result == null) {
                        append("[HDSL] " + tr("create_failed") + "\n");
                        JOptionPane.showMessageDialog(frame, tr("create_failed"), "HDSL", JOptionPane.ERROR_MESSAGE);
                    } else {
                        append("[HDSL] " + tr("create_complete") + "\n");
                        refreshPlugins(item);
                        refreshRuntimeStates();
                    }
                } catch (Exception e) {
                    append("[HDSL] " + e + "\n");
                }
            }
        }.execute();
    }

    private void copyInstance(JList<Instance> list) {
        Instance i = list.getSelectedValue();
        if (i != null) {
            int port = nextInstancePort();
            Instance copy = new Instance(UUID.randomUUID().toString(), i.name + " Copy", i.runtimeVersion, i.profile, "", port);
            ensureInstanceFolders(copy);
            instances.addElement(copy);
            currentInstance = copy;
            list.setSelectedIndex(instances.size() - 1);
            saveInstances();
            refreshRuntimeStates();
        }
    }

    private void deleteInstance(JList<Instance> list) {
        if (instances.size() <= 1) return;
        int index = list.getSelectedIndex();
        if (index >= 0) {
            Instance item = instances.get(index);
            stopProcess(item);
            instances.remove(index);
            pluginCache.remove(item.id);
            instanceStates.remove(item.id);
            try { Files.deleteIfExists(pluginCacheFile(item)); } catch (IOException ignored) { }
            list.setSelectedIndex(Math.max(0, index - 1));
            saveInstances();
            refreshRuntimeStates();
        }
    }

    private static List<String> shell(String command) { return isWindows() ? Arrays.asList("cmd.exe", "/d", "/s", "/c", command) : Arrays.asList("sh", "-lc", command); }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private String capture(List<String> args, int seconds) { return capture(args, seconds, root, Collections.emptyMap()); }
    private String capture(List<String> args, int seconds, Path cwd, Map<String, String> environment) {
        try {
            Files.createDirectories(root.resolve("logs"));
            Path log = root.resolve("logs/task-" + UUID.randomUUID() + ".log");
            ProcessBuilder builder = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(environment);
            Process p = builder.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String output = Files.readString(log, StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String value) { int n = value.indexOf('\n'); return n < 0 ? value : value.substring(0, n).trim(); }
    private static String extension(String value) { int n = value.lastIndexOf('.'); return n >= 0 ? value.substring(n).toLowerCase(Locale.ROOT) : ".jpg"; }
    private static JLabel label(String text, float size, int style, Color color) { JLabel l = new JLabel(text); l.setFont(new Font(UI_FONT, style, Math.round(size))); l.setForeground(color); return l; }
    private static JPanel transparent() { JPanel p = new JPanel(); p.setOpaque(false); return p; }
    private static JPanel transparent(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JButton flatButton(String text, Color background, Color foreground) { return new SoftButton(text, background, foreground); }
    private static GridBagConstraints constraints() { GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 7, 4); return c; }
    private static String escapeHtml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static Map<String, Map<String, String>> translations() {
        Map<String, String> zh = new HashMap<>(), en = new HashMap<>();
        String[][] rows = {
                {"home","首页","Home"},{"runtimes","运行时","Runtimes"},{"plugins","插件","Plugins"},{"settings","设置","Settings"},{"hero_caption","像管理 Minecraft 实例一样管理 DeepSeek Harness","Manage DeepSeek Harness like Minecraft instances"},
                {"launch","启动","Launch"},{"stop","停止","Stop"},{"send","发送","Send"},{"instances","实例库","Instances"},{"copy","复制","Copy"},{"delete","删除","Delete"},{"runtime_help","安装并管理多个 DeepSeek Harness 运行时版本","Install and manage multiple DeepSeek Harness runtimes"},
                {"plugin_help","浏览、安装并为每个实例启用独立插件组合","Browse and manage plugins per instance"},{"managed_dependency","由 HDSL 依赖管理器检测","Managed by HDSL dependency manager"},{"local_plugins","本地插件","Local plugins"},{"per_instance","每个实例独立启用","Enabled per instance"},
                {"appearance","外观与语言","Appearance & language"},{"language","语言","Language"},{"background","背景图","Background"},{"choose_image","选择图片","Choose image"},{"download_source","依赖下载源","Dependency source"},{"source","源","Source"},{"china_mirror","中国镜像（阿里/npmmirror）","China mirror (Ali/npmmirror)"},{"official","官方源","Official source"},
                {"dependencies","依赖检测与安装","Dependency detection & installation"},{"not_checked","尚未检测","Not checked"},{"detect","检测","Detect"},{"install","安装","Install"},{"checking","检测中…","Checking…"},{"missing","未安装或不在 PATH","Missing or not in PATH"},{"install_confirm","将执行以下安装命令：","The following command will run:"},{"installing","安装中…","Installing…"},{"installed","安装完成","Installed"},{"install_failed","安装失败","Installation failed"},
                {"launch_failed","启动失败","Launch failed"},{"stopped","已停止","Stopped"},{"running","运行中","Running"},{"starting","启动中","Starting"},{"port","端口","Port"},{"port_occupied","端口被占用","Port occupied"},{"port_conflict_warn","端口已被其他程序占用","Port is occupied by another process"},
                {"stop_pending_or_port_held","正在停止或端口仍被占用…","Stopping or port is still held…"},
                {"instance_name","实例名称","Instance name"},{"ready","已就绪","Ready"},{"create","新建","Create"},{"workspace","当前工作区","Workspace"},{"instance_count","个实例","instances"},{"china_short","中国镜像源","China mirror"},
                {"easy","强大易用","Powerful"},{"efficient","高效启动与管理","Fast launch & management"},{"safe","安全可控","Safe & controlled"},{"local_run","本地运行更安心","Local execution"},{"flexible","灵活扩展","Flexible"},{"plugin_support","插件系统支持","Plugin support"},{"no_output","暂无输出内容","No output yet"},{"launch_for_logs","启动实例以查看日志输出…","Launch an instance to view logs…"},
                {"refresh","刷新","Refresh"},{"current_instance","当前实例","Current instance"},{"profile_not_initialized","该实例尚未初始化 Profile","Profile is not initialized"},{"built_in","内置","Built in"},{"no_plugins","未识别到插件","No plugins detected"},{"no_cached_plugins","暂无缓存的插件信息","No cached plugins"},{"plugin_read_failed","插件读取失败","Plugin read failed"},{"plugin_detect_hint","列表由 plugin list 与 dump-config 合并生成；普通切换仅展示缓存，不会重复调用 dsh。","List merges plugin list and dump-config; ordinary navigation renders cache only without invoking dsh."},
                {"loading_plugins","正在后台读取完整插件树…","Loading complete plugin tree in background…"},{"plugin_count","项插件","plugins"},{"configured","已配置","Configured"},{"managed_package","可管理包","Managed package"},{"install_plugin","安装插件","Install"},{"update_plugin","更新插件","Update"},{"remove_plugin","移除插件","Remove"},{"plugin_package","输入 npm、Git 或本地插件包名","Enter an npm, Git, or local plugin spec"},{"invalid_plugin_spec","插件包名格式无效","Invalid plugin spec"},{"select_managed_plugin","请选择标记为“可管理包”的外部插件；内置运行时插件不能直接移除。","Select an external managed package; built-in runtime entries cannot be removed."},{"remove_confirm","确定移除当前实例的插件包？","Remove this plugin package from the current instance?"},{"plugin_command_failed","插件命令执行失败，请查看日志目录","Plugin command failed; see the logs folder"},
                {"new_harness","新的 Harness","New Harness"},{"harness_version","Harness 版本","Harness version"},{"create_harness","新建独立 Harness","Create isolated Harness"},{"invalid_name_version","名称或版本格式无效","Invalid name or version"},{"creating_harness","正在创建独立 Harness","Creating isolated Harness"},{"create_failed","Harness 创建失败，请查看 logs 目录","Harness creation failed; see the logs folder"},{"create_complete","Harness 创建完成","Harness created"},{"runtime_missing_create","该实例的便携运行时不存在，请新建或安装对应版本。","Portable runtime is missing; create or install this version."},{"enabled","已启用","Enabled"},{"installed_only","已安装","Installed"},{"no_runtimes","尚未安装便携运行时","No portable runtimes installed"},
                {"search","搜索","Search"},{"search_plugins","搜索插件 ID、名称或版本…","Search plugin ID, name, or version…"},{"hide_official_plugins","隐藏官方插件 (@deepseek-ai/*)","Hide official plugins (@deepseek-ai/*)"}
        };
        for (String[] row : rows) { zh.put(row[0], row[1]); en.put(row[0], row[2]); }
        Map<String, Map<String, String>> result = new HashMap<>(); result.put("zh", zh); result.put("en", en); return result;
    }

    public static final class Instance {
        final String id;
        String name, runtimeVersion, profile, customCommand;
        int port;

        public Instance(String id, String name, String runtimeVersion, String profile, String customCommand) {
            this(id, name, runtimeVersion, profile, customCommand, 3080);
        }

        public Instance(String id, String name, String runtimeVersion, String profile, String customCommand, int port) {
            this.id = id;
            this.name = name;
            this.runtimeVersion = runtimeVersion;
            this.profile = profile;
            this.customCommand = customCommand == null ? "" : customCommand;
            this.port = port;
        }

        public String toString() { return name + "  ·  " + runtimeVersion; }
    }

    public static final class PluginInfo {
        final String id, name, version;
        final boolean enabled, managed;
        public PluginInfo(String id, String name, String version, boolean enabled, boolean managed) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.enabled = enabled;
            this.managed = managed;
        }
    }

    private interface Installer { String command(String source, String registry); }
    private static final class Dependency { final String name, detect; final Installer installer; Dependency(String name, String detect, Installer installer) { this.name = name; this.detect = detect; this.installer = installer; } String install(String source, String registry) { return installer.command(source, registry); } }

    private final class InstanceRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            Instance item = (Instance) value;
            InstanceState state = instanceStates.getOrDefault(item.id, InstanceState.STOPPED);
            String statusDot;
            if (state == InstanceState.RUNNING) {
                statusDot = "<span style='color:#2bb56f'>● " + tr("running") + "</span>";
            } else if (state == InstanceState.STARTING) {
                statusDot = "<span style='color:#3784ff'>● " + tr("starting") + "</span>";
            } else if (state == InstanceState.PORT_OCCUPIED) {
                statusDot = "<span style='color:#b83838'>● " + tr("port_occupied") + "</span>";
            } else {
                statusDot = "<span style='color:#8795aa'>● " + tr("stopped") + "</span>";
            }
            l.setText("<html><b>" + escapeHtml(item.name) + "</b> <span style='color:#8795aa'>:" + item.port + "</span><br><span style='font-size:11px;color:#718199'>" + escapeHtml(item.runtimeVersion) + " · " + statusDot + "</span></html>");
            l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, selected ? 4 : 0, 0, 0, ACCENT), new EmptyBorder(10, 11, 10, 11)));
            l.setForeground(selected ? new Color(35, 96, 205) : INK);
            l.setBackground(selected ? new Color(232, 240, 255) : Color.WHITE);
            l.setOpaque(true);
            return l;
        }
    }

    private final class PluginRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            PluginInfo item = (PluginInfo) value;
            String id = item.id == null || item.id.isBlank() ? "" : " <span style='color:#8795aa'>[" + escapeHtml(item.id) + "]</span>";
            l.setText("<html><b>" + escapeHtml(item.name) + "</b>" + id + "<br><span style='color:#718199'>" + escapeHtml(item.version) + (item.enabled ? " · " + tr("enabled") : " · " + tr("installed_only")) + (item.managed ? " · " + tr("managed_package") : "") + "</span></html>");
            l.setBorder(new EmptyBorder(7, 12, 7, 12));
            l.setBackground(selected ? new Color(232, 240, 255) : Color.WHITE);
            l.setForeground(INK);
            return l;
        }
    }

    private static class FrostPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Color fill;
        private final int arc;
        FrostPanel(Color fill, int arc) { this.fill = fill; this.arc = arc; setOpaque(false); }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            g2.dispose();
        }
    }

    private static final class SoftButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final Color base;
        private boolean hover;
        SoftButton(String text, Color base, Color foreground) {
            super(text);
            this.base = base;
            setForeground(foreground);
            setFont(new Font(UI_FONT, Font.BOLD, 13));
            setFocusPainted(false);
            setBorder(new EmptyBorder(10, 14, 12, 14));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color active = hover ? mix(base, Color.WHITE, .12f) : base;
            if (getModel().isPressed()) active = mix(base, Color.BLACK, .08f);
            g2.setColor(new Color(83, 107, 145, 28));
            g2.fillRoundRect(1, 3, getWidth() - 2, getHeight() - 4, 15, 15);
            g2.setPaint(new GradientPaint(0, 1, mix(active, Color.WHITE, .18f), 0, getHeight() - 3, active));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 4, 14, 14);
            g2.setColor(mix(active, new Color(89, 115, 154), .20f));
            g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 5, 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
        private static Color mix(Color a, Color b, float amount) {
            return new Color(Math.round(a.getRed() * (1 - amount) + b.getRed() * amount), Math.round(a.getGreen() * (1 - amount) + b.getGreen() * amount), Math.round(a.getBlue() * (1 - amount) + b.getBlue() * amount), a.getAlpha());
        }
    }

    private static final class HeroPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private transient BufferedImage image;
        HeroPanel(BufferedImage image) { this.image = image; setOpaque(false); }
        void setImage(BufferedImage image) { this.image = image; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.setColor(Color.WHITE);
            g2.fill(shape);
            g2.clip(shape);
            if (image != null) {
                double scale = Math.max((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 238), (int) (getWidth() * .67), 0, new Color(255, 255, 255, 18)));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setClip(null);
            g2.setColor(new Color(203, 218, 240));
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class WhaleIcon implements Icon {
        private final int size;
        private final Color color;
        WhaleIcon(int size, Color color) { this.size = size; this.color = color; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D q = (Graphics2D) g.create();
            q.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            q.setColor(color);
            q.fillOval(x + 3, y + 11, size - 13, size - 23);
            q.fillPolygon(new int[]{x + size - 13, x + size - 2, x + size - 8}, new int[]{y + 16, y + 7, y + 26}, 3);
            q.fillPolygon(new int[]{x + size - 13, x + size - 2, x + size - 5}, new int[]{y + 20, y + 31, y + 14}, 3);
            q.setColor(Color.WHITE);
            q.fillOval(x + 7, y + 22, size - 24, size - 20);
            q.setColor(new Color(36, 70, 130));
            q.fillOval(x + size / 2, y + 17, 3, 3);
            q.dispose();
        }
    }

    private static final class LineIcon implements Icon {
        private final String type;
        private final int size;
        private final Color color;
        LineIcon(String type, int size, Color color) { this.type = type; this.size = size; this.color = color; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D q = (Graphics2D) g.create();
            q.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            q.setStroke(new BasicStroke(Math.max(1.6f, size / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            q.setColor(color);
            int m = size / 2;
            if (type.equals("home")) {
                q.drawPolyline(new int[]{x + 2, x + m, x + size - 2}, new int[]{y + m, y + 2, y + m}, 3);
                q.drawRect(x + 5, y + m - 1, size - 10, size - m);
                q.drawRect(x + m - 2, y + m + 5, 4, size - m - 5);
            } else if (type.equals("runtime")) {
                q.drawPolygon(new int[]{x + m, x + size - 2, x + m, x + 2}, new int[]{y + 2, y + 7, y + 13, y + 7}, 4);
                q.drawLine(x + 2, y + 7, x + 2, y + size - 5);
                q.drawLine(x + size - 2, y + 7, x + size - 2, y + size - 5);
                q.drawLine(x + m, y + 13, x + m, y + size - 1);
                q.drawPolyline(new int[]{x + 2, x + m, x + size - 2}, new int[]{y + size - 5, y + size - 1, y + size - 5}, 3);
            } else if (type.equals("plugins")) {
                q.drawRoundRect(x + 3, y + 3, size - 6, size - 6, 5, 5);
                q.drawArc(x + m - 3, y, size / 3, size / 3, 180, 180);
                q.drawArc(x + size - 7, y + m - 3, size / 3, size / 3, 90, 180);
            } else if (type.equals("play")) {
                q.fillPolygon(new int[]{x + 4, x + 4, x + size - 3}, new int[]{y + 2, y + size - 2, y + m}, 3);
            } else if (type.equals("stop")) {
                q.fillRoundRect(x + 2, y + 2, size - 4, size - 4, 3, 3);
            } else if (type.equals("send")) {
                q.drawPolygon(new int[]{x + 1, x + size - 2, x + m - 2}, new int[]{y + 3, y + m, y + size - 2}, 3);
                q.drawLine(x + m - 2, y + size - 2, x + m, y + m);
            } else if (type.equals("add")) {
                q.drawLine(x + 2, y + m, x + size - 2, y + m);
                q.drawLine(x + m, y + 2, x + m, y + size - 2);
            } else if (type.equals("copy")) {
                q.drawRect(x + 1, y + 4, size - 6, size - 6);
                q.drawRect(x + 5, y + 1, size - 6, size - 6);
            } else if (type.equals("delete")) {
                q.drawRect(x + 4, y + 5, size - 8, size - 7);
                q.drawLine(x + 2, y + 4, x + size - 2, y + 4);
                q.drawLine(x + 6, y + 1, x + size - 6, y + 1);
            } else if (type.equals("rocket")) {
                q.drawOval(x + 5, y + 2, size - 9, size - 9);
                q.drawLine(x + 5, y + size - 5, x + size - 3, y + 3);
                q.drawLine(x + 4, y + size - 4, x + 2, y + size - 2);
            } else if (type.equals("shield")) {
                q.drawPolygon(new int[]{x + m, x + size - 2, x + size - 4, x + m, x + 3, x + 1}, new int[]{y + 1, y + 4, y + size - 6, y + size - 1, y + size - 6, y + 4}, 6);
                q.drawLine(x + 5, y + m, x + m - 1, y + size - 5);
                q.drawLine(x + m - 1, y + size - 5, x + size - 5, y + 5);
            } else if (type.equals("bolt")) {
                q.drawPolyline(new int[]{x + m + 2, x + 3, x + m - 1, x + m - 3, x + size - 3, x + m + 1}, new int[]{y + 1, y + m + 2, y + m + 2, y + size - 1, y + m - 4, y + m - 4}, 6);
            } else {
                q.drawOval(x + 5, y + 5, size - 10, size - 10);
                q.drawOval(x + 9, y + 9, size - 18, size - 18);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    int x1 = x + m + (int) (Math.cos(a) * (m - 4)), y1 = y + m + (int) (Math.sin(a) * (m - 4));
                    int x2 = x + m + (int) (Math.cos(a) * m), y2 = y + m + (int) (Math.sin(a) * m);
                    q.drawLine(x1, y1, x2, y2);
                }
            }
            q.dispose();
        }
    }

    private static final class BackgroundPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private transient BufferedImage image;
        BackgroundPanel(BufferedImage image) { this.image = image; setOpaque(true); setBackground(PAPER); }
        void setImage(BufferedImage image) { this.image = image; }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            if (image != null) {
                double scale = Math.max((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } else {
                g2.setColor(PAPER);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.setColor(new Color(248, 250, 253, 208));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().show());
    }
}
