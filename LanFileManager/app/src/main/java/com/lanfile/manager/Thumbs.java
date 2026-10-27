package com.lanfile.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import com.lanfile.manager.core.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 缩略图：只在内存中缓存，绝不写入磁盘 */
public final class Thumbs {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService exec = Executors.newFixedThreadPool(2);
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public static int iconFor(String name, boolean dir) {
        if (dir) return R.drawable.ft_folder;
        switch (Util.ext(name)) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic": case "heif": case "svg":
                return R.drawable.ft_image;
            case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv": case "webm": case "m4v": case "3gp": case "ts":
                return R.drawable.ft_video;
            case "mp3": case "flac": case "wav": case "aac": case "m4a": case "ogg": case "wma": case "ape": case "opus": case "amr":
                return R.drawable.ft_audio;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz": case "zst":
                return R.drawable.ft_archive;
            case "apk": case "apks": case "xapk":
                return R.drawable.ft_apk;
            case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "pdf": case "txt": case "md":
            case "csv": case "json": case "xml": case "html": case "log": case "epub":
                return R.drawable.ft_doc;
            default:
                return R.drawable.ft_file;
        }
    }

    static boolean wantsThumb(String name) {
        int i = iconFor(name, false);
        return i == R.drawable.ft_image || i == R.drawable.ft_video || (i == R.drawable.ft_apk && Util.ext(name).equals("apk"));
    }

    public static void load(final Context ctx, final ImageView iv, final String path, final String name, boolean dir, boolean restricted) {
        iv.setTag(path);
        iv.setImageResource(iconFor(name, dir));
        if (dir || restricted || !wantsThumb(name)) return;
        Bitmap b = cache.get(path);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                if (!path.equals(iv.getTag())) return;
                final Bitmap bm = make(ctx, path, name);
                if (bm == null) return;
                cache.put(path, bm);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (path.equals(iv.getTag())) iv.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static Bitmap make(Context ctx, String path, String name) {
        try {
            int icon = iconFor(name, false);
            if (icon == R.drawable.ft_image) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                if (o.outWidth <= 0) return null;
                int s = 1;
                while (o.outWidth / (s * 2) >= 128 && o.outHeight / (s * 2) >= 128) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                Bitmap full = BitmapFactory.decodeFile(path, o);
                if (full == null) return null;
                Bitmap t = ThumbnailUtils.extractThumbnail(full, 128, 128);
                if (t != full) full.recycle();
                return t;
            }
            if (icon == R.drawable.ft_video) {
                return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MICRO_KIND);
            }
            if (icon == R.drawable.ft_apk) {
                PackageManager pm = ctx.getPackageManager();
                PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
                if (pi == null || pi.applicationInfo == null) return null;
                ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = path;
                ai.publicSourceDir = path;
                Drawable d = ai.loadIcon(pm);
                return toBitmap(d);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, 128, 128);
        d.draw(c);
        return b;
    }
}
