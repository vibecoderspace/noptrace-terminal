package com.vibecoderspace.noptrace.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** Forwards {@link TerminalSession} callbacks to the activity. */
final class SessionClient implements TerminalSessionClient {

    private static final String TAG = "NoptraceSession";

    private final MainActivity activity;

    SessionClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        activity.onScreenTextChanged();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        activity.onSessionTitleChanged(changedSession);
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        activity.onSessionFinished(finishedSession);
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (session == null) return;
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text != null && text.length() > 0) {
            byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            session.write(bytes, 0, bytes.length);
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        activity.onBell();
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        activity.onColorsChanged();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        activity.onCursorStateChanged(state);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        // Only informational in this app; the session owns the pid.
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(TAG + "/" + tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(TAG + "/" + tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(TAG + "/" + tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(TAG + "/" + tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(TAG + "/" + tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(TAG + "/" + tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(TAG + "/" + tag, "exception", e);
    }
}
