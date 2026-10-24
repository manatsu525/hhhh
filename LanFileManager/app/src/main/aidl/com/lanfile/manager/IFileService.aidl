package com.lanfile.manager;

interface IFileService {
    // Shizuku 约定：销毁服务
    void destroy() = 16777114;

    // 返回多行 "d|size|mtime|name"，目录不存在/不可读返回 null
    String list(String path) = 1;
    // 返回单行 "d|size|mtime|name"，不存在返回 null
    String stat(String path) = 2;
    // mode: 0 读，1 写（创建/截断）
    ParcelFileDescriptor open(String path, int mode) = 3;
    boolean mkdir(String path) = 4;
    boolean delete(String path) = 5;
    boolean rename(String from, String to) = 6;
}
