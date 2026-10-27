package com.lanfile.manager.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 统一的"路径式"文件系统抽象。
 * 普通目录走 java.io.File；Android 11+ 的 Android/data、Android/obb 由 Android 端实现透明地走 SAF 授权。
 * 网页端浏览和手机端文件管理器都基于它，因此两边都能访问各 app 的私有目录。
 */
public interface Vfs {

    class Entry {
        public String path;
        public String name;
        /** 可选的展示名（例如 Android/data 下显示 app 名称） */
        public String label;
        public boolean dir;
        public long size;
        public long mtime;
        /** 需要授权才能进入 */
        public boolean locked;
        /** 目录子项数量，-1 表示未知 */
        public int childCount = -1;
    }

    /** 需要用户授权（SAF）才能访问该路径 */
    class NeedAccessException extends IOException {
        public final String grantPath;

        public NeedAccessException(String grantPath) {
            super("需要授权访问: " + grantPath);
            this.grantPath = grantPath;
        }
    }

    List<Entry> list(String dirPath) throws IOException;

    /** 不存在时返回 null */
    Entry stat(String path) throws IOException;

    InputStream openRead(String path, long offset) throws IOException;

    /** 在 parent 下新建文件，name 由调用方保证不重名 */
    OutputStream create(String parentPath, String name) throws IOException;

    /** 返回新目录路径 */
    String mkdir(String parentPath, String name) throws IOException;

    /** 递归删除 */
    void delete(String path) throws IOException;

    /** 返回新路径 */
    String rename(String path, String newName) throws IOException;

    /** 存储卷根目录 */
    List<Entry> roots();
}
