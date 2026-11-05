package com.vibecoderspace.noptrace.terminal;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

/** Forwards {@link com.termux.view.TerminalView} callbacks to the activity. */
final class ViewClient implements TerminalViewClient {

    private static final String TAG = "NoptraceView";

    private final MainActivity activity;

    ViewClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public float onScale(float scale) {
        return activity.onScale(scale);
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        activity.toggleKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean shouldSupportClipboardKeybindings() {
        return true;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        // No contextual UI in the minimal app.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return activity.isCtrlDown();
    }

    @Override
    public boolean readAltKey() {
        return activity.isAltDown();
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        activity.onEmulatorSet();
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
