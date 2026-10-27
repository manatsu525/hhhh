package com.lanfile.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.lanfile.manager.core.Util;
import com.lanfile.manager.core.Vfs;

import java.io.FileNotFoundException;
import java.util.Locale;

/** 只读的文件分享 Provider（替代 androidx FileProvider），用于"打开方式"和"分享" */
public class ShareProvider extends ContentProvider {
    public static final String AUTH = "com.lanfile.manager.files";

    public static Uri uriFor(String path) {
        return new Uri.Builder().scheme("content").authority(AUTH).encodedPath(Uri.encode(path, "/")).build();
    }

    public static String mimeOf(String name) {
        String ext = Util.ext(name);
        String m = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        if (m == null) {
            m = Util.mime(name);
            int i = m.indexOf(';');
            if (i > 0) m = m.substring(0, i);
        }
        return m;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("只读");
        String path = Util.normPath(uri.getPath());
        if (path == null) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor pfd = App.vfs.openPfd(path);
            if (pfd == null) throw new FileNotFoundException(path);
            return pfd;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = Util.normPath(uri.getPath());
        if (path == null) return null;
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Vfs.Entry e = null;
        try {
            e = App.vfs.stat(path);
        } catch (Exception ignored) {
        }
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = Util.nameOf(path);
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = e == null ? null : e.size;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return mimeOf(Util.nameOf(uri.getPath() == null ? "" : uri.getPath()));
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
