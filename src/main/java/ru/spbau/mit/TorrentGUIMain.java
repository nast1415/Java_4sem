package ru.spbau.mit;

import org.apache.commons.io.FileUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TorrentGUIMain {
    private enum Columns {
        NAME,
        LOCAL_PATH,
        SIZE,
        PROGRESS
    }

    private static final Map<Columns, String> COLUMNS_NAMES = new EnumMap<>(Columns.class);

    static {
        COLUMNS_NAMES.put(Columns.NAME, "File name");
        COLUMNS_NAMES.put(Columns.LOCAL_PATH, "Local path");
        COLUMNS_NAMES.put(Columns.SIZE, "File size");
        COLUMNS_NAMES.put(Columns.PROGRESS, "Progress");
    }

    private static final class TableRow {
        private String name;
        private String localPath;
        private String size;
        private double progress;

        private TableRow(ClientInfo.FileInfo info) {
            try (MyLock myLock = MyLock.lock(info.fileLock.readLock())) {
                name = info.descriptor.getFileName();
                localPath = info.localPath.toString();
                size = FileUtils.byteCountToDisplaySize(info.descriptor.getFileSize());
                progress = (info.parts.getCount() + 0.0) / info.descriptor.getNumberOfTheParts();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static final class TableModel extends AbstractTableModel {
        private volatile List<TableRow> data = Collections.emptyList();

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return Columns.values().length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS_NAMES.get(Columns.values()[column]);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            switch (Columns.values()[columnIndex]) {
                case NAME:
                    return data.get(rowIndex).name;
                case LOCAL_PATH:
                    return data.get(rowIndex).localPath;
                case SIZE:
                    return data.get(rowIndex).size;
                case PROGRESS:
                    return data.get(rowIndex).progress;
            }
            return null;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (Columns.values()[columnIndex]) {
                case NAME:
                case LOCAL_PATH:
                case SIZE:
                    return String.class;
                case PROGRESS:
                    return Double.TYPE;
            }
            return null;
        }

        private void setData(List<TableRow> newData) {
            data = newData;
            fireTableDataChanged();
        }
    }

    private static final class ProgressRenderer implements TableCellRenderer {
        private static final int SCALE = 1000;

        private JProgressBar progressBar = new JProgressBar(0, SCALE);

        private ProgressRenderer() {
            progressBar.setStringPainted(true);
            progressBar.setMinimum(0);
            progressBar.setMaximum(SCALE);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value,
                boolean isSelected, boolean hasFocus,
                int row, int column
        ) {
            progressBar.setValue((int) ((double) value * SCALE));
            return progressBar;
        }
    }

    private JFileChooser fileChooser = new JFileChooser();
    private TableModel tableModel;
    private JTextArea logArea;
    private ClientInfo info;
    private RunningClient runningClient;
    private JFrame frame;

    private void writeMessage(String format, Object... args) {
        SwingUtilities.invokeLater(() -> logArea.append(String.format(format + "\n", args)));
    }

    private final RunningClient.StatusCallbacks callbacks = new RunningClient.StatusCallbacks() {
        @Override
        public void onTrackerUpdated(boolean result, Throwable e) {
            if (!result) {
                writeMessage("Failed to update tracker: %s ", e.getMessage());
            }
        }

        @Override
        public void onDownloadIssue(FileDescriptor descriptor, String message, Throwable e) {
            writeMessage(
                    "File \"%s\" (#%d) download issue: %s (%s)",
                    descriptor.getFileName(),
                    descriptor.getFileId(),
                    message,
                    (e == null) ? "" : e.getMessage()
            );
        }

        @Override
        public void onDownloadStart(FileDescriptor descriptor) {
            writeMessage("Starting file \"%s\" (#%d) download", descriptor.getFileName(),
                    descriptor.getFileId());
        }

        @Override
        public void onDownloadPart(FileDescriptor descriptor, int partId) {
            fetchModel();
        }

        @Override
        public void onDownloadComplete(FileDescriptor descriptor) {
            writeMessage("File \"%s\" (#%d) downloaded", descriptor.getFileName(), descriptor.getFileId());
        }

        @Override
        public void onPeerToPeerServerIssue(Throwable e) {
            writeMessage("Seeding server issue: %s", e.getMessage());
        }
    };

    private final Action newFileAction = new AbstractAction() {
        {
            putValue(NAME, "New file");
            putValue(SHORT_DESCRIPTION, "Upload new file to tracker");
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            SwingUtilities.invokeLater(() -> {
                int ret = fileChooser.showOpenDialog(frame);
                if (ret == JFileChooser.APPROVE_OPTION) {
                    Path p = fileChooser.getSelectedFile().toPath();
                    try {
                        new Client(info).newFile(p);
                        fetchModel();
                    } catch (IOException e) {
                        showErrorDialog("Failed to upload file: " + e.getMessage());
                    }
                }
            });
        }
    };

    private final Action getFileAction = new AbstractAction() {
        {
            putValue(NAME, "Get file");
            putValue(SHORT_DESCRIPTION, "Download new file from peers");
            putValue(MNEMONIC_KEY, KeyEvent.VK_D);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            SwingUtilities.invokeLater(() -> {
                List<FileDescriptor> files;
                try {
                    files = new Client(info).list();
                } catch (IOException e) {
                    showErrorDialog(String.format("Failed to get file list from tracker: %s\n", e.getMessage()));
                    return;
                }
                try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
                    for (ClientInfo.FileInfo fileInfo : info.files.values()) {
                        files.remove(fileInfo.descriptor);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Integer result = new TorrentGUIListDialog(frame, files).showDialog();
                try {
                    if (result != null) {
                        new Client(info).get(files.get(result).getFileId());
                        fetchModel();
                    }
                } catch (Exception e) {
                    showErrorDialog(String.format("Failed to add file to downloads list: %s\n", e.getMessage()));
                }
            });
        }
    };

    private final Action startRunAction = new AbstractAction() {
        {
            putValue(NAME, "Start running");
            putValue(SHORT_DESCRIPTION, "Start seeding and downloading files");
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            try {
                runningClient = new RunningClient(info);
                runningClient.startingRun(callbacks);

                writeMessage("Started running");
                setEnabled(false);
                stopRunAction.setEnabled(true);
                newFileAction.setEnabled(false);
                getFileAction.setEnabled(false);
            } catch (Exception e) {
                e.printStackTrace();
                showErrorDialog("Failed to run client: " + e.getMessage());
                runningClient = null;
            }
        }
    };

    private final Action stopRunAction = new AbstractAction() {
        {
            putValue(NAME, "Stop running");
            putValue(SHORT_DESCRIPTION, "Stop seeding and downloading files");
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_MASK));
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            runningClient.shutdown();
            writeMessage("Stopped running");
            setEnabled(false);
            startRunAction.setEnabled(true);
            newFileAction.setEnabled(true);
            getFileAction.setEnabled(true);
        }
    };

    private final Action closeAction = new AbstractAction() {
        {
            putValue(NAME, "Close");
            putValue(SHORT_DESCRIPTION, "Exit application");
            putValue(MNEMONIC_KEY, KeyEvent.VK_Q);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            close();
        }
    };

    private TorrentGUIMain() {
        tableModel = new TableModel();
        buildUI();
        try {
            info = new ClientInfo(Paths.get(""));
            fetchModel();
        } catch (IOException e) {
            e.printStackTrace();
            showErrorDialog("Client info: " + e.getMessage());
            ClientInfo.removeInfo(Paths.get(""));
            close();
        }
    }

    public static void main(String[] args) {
        new TorrentGUIMain();
    }

    private void fetchModel() {
        SwingUtilities.invokeLater(() -> {
            List<TableRow> data;
            try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
                data = info.files.values()
                        .stream()
                        .map(TableRow::new)
                        .collect(Collectors.toList());
                tableModel.setData(data);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void close() {
        frame.dispose();
        if (runningClient != null) {
            runningClient.shutdown();
        }
        if (info != null) {
            try {
                info.close();
            } catch (Exception e) {
                showErrorDialog("Failed to save info: " + e.getMessage());
            }
        }
        System.exit(0);
    }

    private void buildUI() {
        frame = new JFrame("Torrent client");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });

        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setColumnSelectionAllowed(false);
        table.setCellSelectionEnabled(false);
        table.getColumn(COLUMNS_NAMES.get(Columns.NAME)).setMinWidth(200);
        table.getColumn(COLUMNS_NAMES.get(Columns.NAME)).setMaxWidth(400);
        table.getColumn(COLUMNS_NAMES.get(Columns.LOCAL_PATH)).setMinWidth(200);
        table.getColumn(COLUMNS_NAMES.get(Columns.SIZE)).setMinWidth(50);
        table.getColumn(COLUMNS_NAMES.get(Columns.SIZE)).setMaxWidth(75);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setMinWidth(50);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setMaxWidth(150);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setCellRenderer(new ProgressRenderer());

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setMinimumSize(new Dimension(200, 100));

        JMenuBar menuBar = new JMenuBar();
        {
            JMenu fileMenu = new JMenu("File");
            fileMenu.add(newFileAction);
            fileMenu.add(getFileAction);
            fileMenu.addSeparator();
            fileMenu.add(startRunAction);
            fileMenu.add(stopRunAction);
            fileMenu.addSeparator();
            fileMenu.add(closeAction);
            menuBar.add(fileMenu);
        }

        SwingUtilities.invokeLater(() -> {
            frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.PAGE_AXIS));
            frame.setJMenuBar(menuBar);
            frame.add(new JScrollPane(table));
            frame.add(new JScrollPane(logArea));
            frame.setMinimumSize(new Dimension(700, 600));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private void showErrorDialog(String message) {
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
        writeMessage("Error: " + message);
    }

}
