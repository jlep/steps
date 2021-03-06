package com.hiit.steps;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Formatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IOLoop {

    private static final String TAG = "Steps";

    private void log(String msg) {
        Log.d(TAG, "IOLoop(" + System.identityHashCode(this) + "): " + msg);
    }

    private static final String FILE_PREFIX = "steps";
    private static final String FILE_SUFFIX = "";

    private Handler handler;
    private Runnable done;
    private Thread thread;
    private Context context;

    private File file;
    private FileOutputStream stream;
    private Formatter formatter;

    private CachedIntArrayBufferQueue queue;

    private AtomicInteger rows = new AtomicInteger();

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            for (;;) {
                CachedIntArrayBufferQueue.Message message = queue.take();
                writeBuffer(message.data);
                if (message.getCommand() == CachedIntArrayBufferQueue.Command.Quit) {
                    handler.post(quitLoop);
                    return;
                }
            }
        }

        public void writeBuffer(IntArrayBuffer buffer) {
            for (int i = 0; i < buffer.getEnd(); ++i) {
                rows.incrementAndGet();
                int offset = buffer.get(i);

                // peek type
                if (buffer.buffer[offset] < 0) {
                    LocationSerializer.formatIntArray(buffer.buffer, offset, formatter);
                } else {
                    SensorEventSerializer.formatIntArray(buffer.buffer, offset, formatter);
                }
            }
        }

    };

    public IOLoop(Context context, CachedIntArrayBufferQueue queue, Runnable done) {
        this.handler = new Handler();
        this.done = done;
        this.context = context;
        this.thread = new Thread(runnable);
        this.queue = queue;
        try {
            file = File.createTempFile(FILE_PREFIX, FILE_SUFFIX,
                    context.getCacheDir());
            stream = new FileOutputStream(file);
            formatter = new Formatter(stream, "UTF-8", null);
            Log.d(TAG, "writing to " + file.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        log("start");
        thread.start();
    }

    private Runnable quitLoop = new Runnable() {
        @Override
        public void run() {
            try {
                thread.join();
                formatter.close();
                if (FileSystem.isExternalStorageWritable()) {
                    // move to files dir
                    File externalFolder = context.getExternalFilesDir(null);
                    File externalFile = FileSystem.nextAvailableFile(
                            FILE_PREFIX, FILE_SUFFIX, externalFolder);
                    try {
                        FileSystem.moveFile(file, externalFile);
                        setFile(externalFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                String msg = "wrote " + rows + " rows to " + file.toString();
                log(msg);
                Toast toast = Toast.makeText(context, msg, Toast.LENGTH_LONG);
                toast.show();
                if (done != null) {
                    done.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    public void setFile(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public int getRows() {
        return rows.get();
    }
}
