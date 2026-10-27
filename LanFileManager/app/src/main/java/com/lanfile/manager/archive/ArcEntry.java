package com.lanfile.manager.archive;

/** 压缩包里的一项。path 用 / 分隔，没有开头和结尾的 /，已去掉 .. 等危险片段 */
public final class ArcEntry {
    public String path;
    public boolean dir;
    /** 未知时为 -1 */
    public long size = -1, packed = -1;
    public long mtime;
    public boolean encrypted;
    /** 列表里自动补出来的中间目录（压缩包里没有单独记录） */
    public boolean implicit;

    /** 各实现内部使用：条目序号 / 原始对象 */
    int index = -1;
    Object ref;
    /** zip 文件名的原始字节（用于切换文件名编码） */
    byte[] rawName;
    boolean utf8;

    public String name() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    public String parent() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
