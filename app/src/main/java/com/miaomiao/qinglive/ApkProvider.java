package com.miaomiao.qinglive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只服务于「应用内更新」：把下载到 cache/update/ 里的 APK 以 content:// 交给系统安装程序。
 *
 * Android 7.0 起禁止把 file:// 传给别的应用（FileUriExposedException），
 * 而项目约定零第三方依赖、不引 androidx，所以这里自己写一个最小 Provider，
 * 只暴露 cache/update 目录下的文件，其余一律拒绝。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.miaomiao.qinglive.apk";

    public static Uri uriFor(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(apk.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 只允许 cache/update 下的文件名（不含路径分隔符），防止越权读别的文件。 */
    private File resolve(Uri uri) {
        if (getContext() == null) {
            return null;
        }
        String name;
        try {
            name = uri.getLastPathSegment();
        } catch (Throwable t) {
            return null;
        }
        if (name == null || name.length() == 0
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        File dir = new File(getContext().getCacheDir(), "update");
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return f;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
