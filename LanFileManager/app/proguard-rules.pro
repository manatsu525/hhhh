# Shizuku UserService 在 shell 进程中通过反射实例化，必须保留
-keep class com.lanfile.manager.ShizukuFileService { *; }
-keep class com.lanfile.manager.IFileService* { *; }
