package com.local.voicenotes.audio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Binder provider in the test APK, never packaged in the application.
 * Java deliberately avoids Kotlin/app dependencies: this component runs in the
 * test package's OWN process, without the instrumentation target's classloader.
 */
public final class DecoderFixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "com.local.voicenotes.litert.test.decoder-fixtures";
    public static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    public static final Uri OFFSET = Uri.parse("content://" + AUTHORITY + "/offset/tone.opus");
    public static final Uri PIPE = Uri.parse("content://" + AUTHORITY + "/pipe/tone.opus");
    public static final long PREFIX = 438L;
    private final ConcurrentLinkedQueue<CountDownLatch> writers = new ConcurrentLinkedQueue<>();
    private volatile byte[] opus;

    @Override public boolean onCreate() { return true; }

    private File wrapped() { return new File(getContext().getCacheDir(), "synthetic-wrapped.opus"); }

    private void checkCaller() {
        try {
            int target = getContext().getPackageManager().getApplicationInfo("com.local.voicenotes.litert", 0).uid;
            int caller = Binder.getCallingUid();
            if (caller != target && caller != getContext().getApplicationInfo().uid)
                throw new SecurityException("Only instrumentation target/test UIDs may use synthetic fixtures");
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Instrumentation target is missing", error);
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        Bundle result = new Bundle();
        if ("stage".equals(method)) {
            byte[] bytes = extras == null ? null : extras.getByteArray("opus");
            if (bytes == null || bytes.length < 4 || bytes.length > 131072 ||
                    bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S')
                throw new IllegalArgumentException("Only the small synthetic Ogg fixture is accepted");
            try (FileOutputStream out = new FileOutputStream(wrapped())) {
                byte[] prefix = new byte[(int) PREFIX]; Arrays.fill(prefix, (byte) 0x5a);
                byte[] suffix = new byte[737]; Arrays.fill(suffix, (byte) 0x6b);
                out.write(prefix); out.write(bytes); out.write(suffix);
            } catch (IOException error) { throw new IllegalStateException(error); }
            opus = bytes;
            result.putBoolean("staged", true);
        } else if ("awaitIdle".equals(method)) {
            boolean idle = true;
            for (CountDownLatch writer : writers) {
                try {
                    // Deadlock/cleanup guard, not a performance assertion.
                    if (!writer.await(5, TimeUnit.SECONDS)) idle = false;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                if (writer.getCount() == 0) writers.remove(writer);
            }
            result.putBoolean("idle", idle);
        } else { throw new IllegalArgumentException("Unknown fixture operation"); }
        return result;
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        final byte[] bytes = opus;
        if (bytes == null) throw new FileNotFoundException("Stage synthetic tone first");
        if (OFFSET.equals(uri)) {
            return new AssetFileDescriptor(ParcelFileDescriptor.open(wrapped(), ParcelFileDescriptor.MODE_READ_ONLY),
                    PREFIX, bytes.length);
        }
        if (!PIPE.equals(uri)) throw new FileNotFoundException("Fixture URI is not allowlisted: " + uri);
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final CountDownLatch done = new CountDownLatch(1);
            writers.add(done);
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(bytes);
                } catch (IOException expected) {
                    // EPIPE is expected when native/MediaExtractor refuses a pipe.
                } finally { done.countDown(); }
            }, "synthetic-opus-pipe").start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }

    @Override public String getType(Uri uri) {
        checkCaller();
        if (!OFFSET.equals(uri) && !PIPE.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        return "audio/ogg";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
