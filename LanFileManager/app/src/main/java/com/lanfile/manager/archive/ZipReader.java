package com.lanfile.manager.archive;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/** zip / jar / apk 等。没有 UTF-8 标记的文件名自动在 UTF-8 和 GBK 之间判断，也可以手动指定编码 */
final class ZipReader extends ArchiveReader {
    private final ZipFile zip;
    private String forced;
    private String used = "UTF-8";

    ZipReader(File f, String displayName) throws IOException {
        super(f, displayName);
        zip = new ZipFile(f);
        // zip4j 设置了编码后会忽略 UTF-8 标记，统一按它解码；用 ISO-8859-1 读取可以无损拿回原始字节，之后自己解码
        zip.setCharset(StandardCharsets.ISO_8859_1);
        List<FileHeader> hs = zip.getFileHeaders();
        for (FileHeader h : hs) {
            ArcEntry e = new ArcEntry();
            e.ref = h;
            e.dir = h.isDirectory();
            e.size = e.dir ? 0 : h.getUncompressedSize();
            e.packed = h.getCompressedSize();
            e.mtime = h.getLastModifiedTimeEpoch();
            e.encrypted = h.isEncrypted();
            e.rawName = h.getFileName().getBytes(StandardCharsets.ISO_8859_1);
            e.utf8 = h.isFileNameUTF8Encoded();
            entries.add(e);
        }
        decodeNames();
    }

    @Override
    public String format() {
        String ext = Formats.ext(displayName);
        return ext.equals("zip") || ext.isEmpty() ? "ZIP" : "ZIP（" + ext.toUpperCase(java.util.Locale.ROOT) + "）";
    }

    @Override
    public boolean supportsCharset() {
        return true;
    }

    @Override
    public void setCharset(String cs) {
        forced = cs;
        decodeNames();
        invalidateTree();
    }

    @Override
    public String charset() {
        return forced == null ? "自动（" + used + "）" : forced;
    }

    private void decodeNames() {
        Charset cs;
        if (forced != null) {
            cs = Charset.forName(forced);
        } else {
            cs = StandardCharsets.UTF_8;
            for (ArcEntry e : entries) {
                if (!e.utf8 && !validUtf8(e.rawName)) {
                    cs = gbk();
                    break;
                }
            }
        }
        used = cs.name();
        for (ArcEntry e : entries) {
            // 有 UTF-8 标记的文件名一律按 UTF-8 解码
            e.path = Formats.cleanPath(new String(e.rawName, e.utf8 ? StandardCharsets.UTF_8 : cs), true);
        }
    }

    private static Charset gbk() {
        try {
            return Charset.forName("GB18030");
        } catch (Exception e) {
            return Charset.forName("GBK");
        }
    }

    private static boolean validUtf8(byte[] b) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    @Override
    public void extract(Collection<ArcEntry> files, Sink sink, Progress p) throws IOException {
        zip.setPassword(password == null ? null : password.toCharArray());
        for (ArcEntry e : files) {
            if (e.dir || e.ref == null) continue;
            p.check();
            if (e.encrypted && password == null) throw new PasswordException(false);
            p.current = e.path;
            OutputStream out = sink.open(e);
            if (out == null) continue;
            boolean ok = false;
            try (ZipInputStream in = zip.getInputStream((FileHeader) e.ref)) {
                copy(in, out, p);
                ok = true;
            } catch (ZipException ze) {
                throw translate(ze, e);
            } catch (IOException ioe) {
                // 校验失败等情况，zip4j 有时直接抛 IOException
                if (e.encrypted && !(ioe instanceof CancelledException) && String.valueOf(ioe.getMessage()).toLowerCase().contains("crc")) {
                    throw new PasswordException(true);
                }
                throw ioe;
            } finally {
                sink.close(e, out, ok);
            }
            p.count++;
        }
    }

    private IOException translate(ZipException ze, ArcEntry e) {
        ZipException.Type t = ze.getType();
        if (t == ZipException.Type.WRONG_PASSWORD) return new PasswordException(password != null);
        if (e.encrypted && t == ZipException.Type.CHECKSUM_MISMATCH) return new PasswordException(true);
        if (ze.getCause() instanceof CancelledException) return (CancelledException) ze.getCause();
        String m = ze.getMessage();
        if (m != null && m.toLowerCase().contains("password")) return new PasswordException(password != null);
        return new IOException("解压「" + e.name() + "」失败：" + m, ze);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
