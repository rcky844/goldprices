package com.goldmonitor.data;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Tiny file based cache so the last good data survives restarts and network outages. */
final class Cache {

    private static final String DIRECTORY = "market-cache";

    private Cache() {
    }

    static byte[] read(Context context, String name) {
        File file = new File(directory(context), name);
        if (!file.isFile()) {
            return null;
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(256,
                    Math.min(file.length(), 1024 * 1024)));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 8 * 1024 * 1024) {
                    return null;
                }
            }
            return output.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    static void write(Context context, String name, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        File target = new File(directory(context), name);
        File temporary = new File(directory(context), name + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temporary);
            output.write(data);
            output.flush();
            output.close();
            output = null;
            if (!temporary.renameTo(target)) {
                temporary.delete();
            }
        } catch (IOException e) {
            temporary.delete();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    private static File directory(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
        return directory;
    }
}
