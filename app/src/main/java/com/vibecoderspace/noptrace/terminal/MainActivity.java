package com.vibecoderspace.noptrace.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-screen terminal over an embedded noptrace engine.
 *
 * <p>On first launch the Alpine minirootfs is downloaded and extracted into
 * {@code filesDir/rootfs}. Every session then enters the engine in the fork()ed
 * PTY child; nothing is exec'd from app storage.</p>
 */
public final class MainActivity extends Activity {

    private static final String TAG = "NoptraceTerminal";
    /** Text size in sp; {@link TerminalView#setTextSize(int)} takes pixels. */
    private static final int DEFAULT_TEXT_SIZE_SP = 14;
    private static final int MIN_TEXT_SIZE_SP = 8;
    private static final int MAX_TEXT_SIZE_SP = 32;
    private static final int TRANSCRIPT_ROWS = 2000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TerminalView terminalView;
    private View installPanel;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button retryButton;
    private View keyRow;
    private Button ctrlButton;
    private Button altButton;

    private TerminalSession session;
    private SessionClient sessionClient;
    private boolean resumed;
    private boolean ctrlDown;
    private boolean altDown;
    private int textSizeSp = DEFAULT_TEXT_SIZE_SP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        terminalView = findViewById(R.id.terminal_view);
        installPanel = findViewById(R.id.install_panel);
        statusText = findViewById(R.id.install_status);
        progressBar = findViewById(R.id.install_progress);
        retryButton = findViewById(R.id.install_retry);
        keyRow = findViewById(R.id.key_row);
        ctrlButton = findViewById(R.id.key_ctrl);
        altButton = findViewById(R.id.key_alt);

        sessionClient = new SessionClient(this);
        terminalView.setTerminalViewClient(new ViewClient(this));
        applyTextSize();

        retryButton.setOnClickListener(v -> retry());
        bindKeys();

        if (RootfsInstaller.isInstalled(this)) {
            startSession();
        } else {
            installRootfs();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (session != null && session.isRunning()) {
            terminalView.setTerminalCursorBlinkerState(true, true);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        terminalView.setTerminalCursorBlinkerState(false, false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.finishIfRunning();
            session = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------- lifecycle

    /** Retry starts a session if the rootfs is intact, otherwise reinstalls. */
    private void retry() {
        installPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        if (RootfsInstaller.isInstalled(this)) {
            startSession();
        } else {
            installRootfs();
        }
    }

    private void installRootfs() {
        installPanel.setVisibility(View.VISIBLE);
        keyRow.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        statusText.setText("Preparing…");

        io.execute(() -> {
            try {
                RootfsInstaller.install(this, new RootfsInstaller.Progress() {
                    @Override
                    public void onStage(String stage) {
                        mainHandler.post(() -> statusText.setText(stage));
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        mainHandler.post(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(1000);
                            progressBar.setProgress((int) (downloaded * 1000 / total));
                        });
                    }
                });
                mainHandler.post(this::startSession);
            } catch (Exception e) {
                Log.e(TAG, "Rootfs installation failed", e);
                mainHandler.post(() -> showInstallError(e));
            }
        });
    }

    private void showInstallError(Exception e) {
        installPanel.setVisibility(View.VISIBLE);
        keyRow.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
        statusText.setText("Installation failed:\n" + e.getMessage());
    }

    private void startSession() {
        try {
            File rootfs = RootfsInstaller.rootfsDir(this);
            File tmp = TmpDir.ensure(this);
            File cwd = new File(rootfs, "root");
            if (!cwd.isDirectory() && !cwd.mkdirs() && !cwd.isDirectory()) {
                throw new java.io.IOException("Cannot create " + cwd);
            }

            String[] argv = NoptraceCommand.argv(rootfs);
            String[] env = NoptraceCommand.hostEnvironment(tmp);

            session = new TerminalSession(null, cwd.getAbsolutePath(), argv, env,
                    TRANSCRIPT_ROWS, sessionClient);

            installPanel.setVisibility(View.GONE);
            keyRow.setVisibility(View.VISIBLE);
            terminalView.attachSession(session);

            terminalView.post(() -> {
                terminalView.requestFocus();
                showKeyboard();
            });
        } catch (Exception e) {
            Log.e(TAG, "Cannot start session", e);
            showInstallError(e);
        }
    }

    // ---------------------------------------------------------------- session

    void onScreenTextChanged() {
        terminalView.onScreenUpdated();
    }

    void onSessionTitleChanged(TerminalSession changedSession) {
        String title = changedSession.getTitle();
        setTitle(title == null || title.isEmpty() ? "noptrace Terminal" : title);
    }

    void onSessionFinished(TerminalSession finishedSession) {
        int status = finishedSession.getExitStatus();
        Toast.makeText(this, "Shell exited (" + status + ")", Toast.LENGTH_LONG).show();
        statusText.setText("Shell exited (" + status + ").\nTap Retry to start a new session.");
        installPanel.setVisibility(View.VISIBLE);
        keyRow.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
    }

    void onColorsChanged() {
        terminalView.invalidate();
    }

    void onCursorStateChanged(boolean state) {
        terminalView.setTerminalCursorBlinkerState(state && resumed, false);
    }

    void onEmulatorSet() {
        terminalView.setTerminalCursorBlinkerState(resumed, true);
    }

    void onBell() {
        terminalView.invalidate();
    }

    // ------------------------------------------------------------------ input

    boolean isCtrlDown() {
        return ctrlDown;
    }

    boolean isAltDown() {
        return altDown;
    }

    float onScale(float scale) {
        // TerminalView multiplies the value returned here into its running
        // scale factor, so reset it once a step has been taken.
        if (scale < 0.9f || scale > 1.1f) {
            textSizeSp += scale > 1f ? 1 : -1;
            textSizeSp = Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, textSizeSp));
            applyTextSize();
            return 1f;
        }
        return scale;
    }

    private void applyTextSize() {
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        terminalView.setTextSize(Math.max(1, Math.round(textSizeSp * scaledDensity)));
    }

    void toggleKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        } else {
            terminalView.requestFocus();
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void bindKeys() {
        ctrlButton.setOnClickListener(v -> {
            ctrlDown = !ctrlDown;
            v.setSelected(ctrlDown);
            terminalView.requestFocus();
        });
        altButton.setOnClickListener(v -> {
            altDown = !altDown;
            v.setSelected(altDown);
            terminalView.requestFocus();
        });

        bindKey(R.id.key_esc, "\u001b");
        bindKey(R.id.key_tab, "\t");
        bindKey(R.id.key_left, "\u001b[D");
        bindKey(R.id.key_up, "\u001b[A");
        bindKey(R.id.key_down, "\u001b[B");
        bindKey(R.id.key_right, "\u001b[C");
        bindKey(R.id.key_slash, "/");
        bindKey(R.id.key_dash, "-");
    }

    private void bindKey(int id, String sequence) {
        findViewById(id).setOnClickListener(v -> write(sequence));
    }

    private void write(String text) {
        if (session == null || !session.isRunning()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
        terminalView.requestFocus();
    }
}
