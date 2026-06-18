package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session driven by an abstract byte-stream transport instead of a local subprocess.
 * <p>
 * MODIFIED for a-ssh (originally from Termux, GPLv3): the JNI pty/subprocess code has been
 * replaced by a pluggable {@link SessionTransport} so the session can be backed by an SSH
 * channel (or any other remote stream). The ByteQueue based threading model, the
 * {@link TerminalEmulator} integration and the client callback surface are preserved so that
 * {@code TerminalView} keeps working unchanged.
 * <p>
 * The transport will be started when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)}; terminal emulation begins and threads are spawned
 * to handle the transport I/O. All terminal emulation and callback methods are performed on
 * the main thread.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** Abstraction of the remote endpoint (e.g. an SSH shell channel). */
    public interface SessionTransport {
        /** Stream producing remote output (remote stdout/stderr merged by the PTY). */
        InputStream getInputStream();

        /** Stream accepting input destined for the remote side (remote stdin). */
        OutputStream getOutputStream();

        /** Inform the remote PTY of a new window size. */
        void onResize(int columns, int rows);

        /** Close the transport (disconnect). */
        void close();
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_TRANSPORT_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the transport produces output, and read by
     * main thread to process by terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another
     * thread which forwards by writing to the transport output stream.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Whether the transport is (still) running. */
    private boolean mRunning = false;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final SessionTransport mTransport;
    private final Integer mTranscriptRows;

    public TerminalSession(SessionTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached transport of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mTransport.onResize(columns, rows);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        mRunning = true;

        mTransport.onResize(columns, rows);

        new Thread("TermSessionInputReader[" + mHandle.substring(0, 8) + "]") {
            @Override
            public void run() {
                try {
                    final InputStream termIn = mTransport.getInputStream();
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        // 高吞吐（cat 大文件 / 编译日志）时避免主线程堆积冗余 MSG_NEW_INPUT：
                        // handleMessage 已一次性 drain 整个队列，只要有一条待处理消息即可。
                        if (!mMainThreadHandler.hasMessages(MSG_NEW_INPUT)) {
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                        }
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_TRANSPORT_EXITED, 0));
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + mHandle.substring(0, 8) + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    final OutputStream termOut = mTransport.getOutputStream();
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                        termOut.flush();
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();
    }

    /** Write data to the remote endpoint. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the transport. */
    public void finishIfRunning() {
        if (isRunning()) {
            mTransport.close();
        }
    }

    /** Cleanup resources when the transport closes. */
    void cleanupResources() {
        synchronized (this) {
            mRunning = false;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mTransport.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Exit status of the remote endpoint; always 0 since remote exit codes are not tracked. */
    public synchronized int getExitStatus() {
        return 0;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    /** No local process backs this session; kept for API compatibility. */
    public int getPid() {
        return -1;
    }

    /** No local working directory for a remote session. */
    public String getCwd() {
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_TRANSPORT_EXITED && mRunning) {
                cleanupResources();

                byte[] bytesToWrite = "\r\n[连接已断开]".getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
