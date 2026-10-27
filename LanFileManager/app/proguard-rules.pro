# Shizuku UserService 在 shell 进程中通过反射实例化，必须保留
-keep class com.lanfile.manager.ShizukuFileService { *; }
-keep class com.lanfile.manager.IFileService* { *; }

# 7-Zip-JBinding：原生代码通过 JNI 按名字访问这些类、字段和回调方法
-keep class net.sf.sevenzipjbinding.** { *; }
-keep class * implements net.sf.sevenzipjbinding.IArchiveExtractCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.IArchiveOpenCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.ICryptoGetTextPassword { *; }
-keep class * implements net.sf.sevenzipjbinding.IOutCreateCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.ISequentialInStream { *; }
-keep class * implements net.sf.sevenzipjbinding.ISequentialOutStream { *; }
-keep class * implements net.sf.sevenzipjbinding.IInStream { *; }
-keep class * implements net.sf.sevenzipjbinding.IOutStream { *; }
-keep class * implements net.sf.sevenzipjbinding.IProgress { *; }

# commons-compress 的可选依赖（zstd、brotli、asm 等）本应用没用到
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn java.lang.invoke.**
