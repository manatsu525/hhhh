package com.lanfile.manager.editor;

import java.util.ArrayList;

/** 撤销/重做。连续输入、连续退格会合并成一步；只保存在内存里。 */
final class UndoManager {

    static final class Edit {
        int pos;
        String removed;
        String inserted;
        long time;
        /** 整篇替换（全部替换）：直接保存前后两个缓冲区 */
        TextBuffer before, after;
        int cursorBefore;
    }

    private static final long MAX_CHARS = 16L * 1024 * 1024;
    private final ArrayList<Edit> undo = new ArrayList<>();
    private final ArrayList<Edit> redo = new ArrayList<>();
    private long chars;
    private boolean noMerge = true;

    void clear() {
        undo.clear();
        redo.clear();
        chars = 0;
        noMerge = true;
    }

    /** 下一次编辑不与上一次合并 */
    void breakMerge() {
        noMerge = true;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void record(int pos, String removed, String inserted, int cursorBefore) {
        redo.clear();
        long now = System.currentTimeMillis();
        Edit last = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        if (!noMerge && last != null && last.before == null && now - last.time < 1500) {
            // 连续输入
            if (removed.isEmpty() && last.removed.isEmpty() && pos == last.pos + last.inserted.length()
                    && inserted.indexOf('\n') < 0 && last.inserted.length() < 2000) {
                last.inserted += inserted;
                last.time = now;
                chars += inserted.length();
                return;
            }
            // 连续退格
            if (inserted.isEmpty() && last.inserted.isEmpty() && pos + removed.length() == last.pos
                    && removed.indexOf('\n') < 0 && last.removed.length() < 2000) {
                last.removed = removed + last.removed;
                last.pos = pos;
                last.time = now;
                chars += removed.length();
                return;
            }
            // 输入法组字：替换刚刚插入的那一段
            if (last.inserted.length() > 0 && pos >= last.pos && pos + removed.length() == last.pos + last.inserted.length()
                    && last.inserted.endsWith(removed) && removed.length() > 0) {
                last.inserted = last.inserted.substring(0, last.inserted.length() - removed.length()) + inserted;
                last.time = now;
                chars += inserted.length();
                return;
            }
        }
        Edit e = new Edit();
        e.pos = pos;
        e.removed = removed;
        e.inserted = inserted;
        e.time = now;
        e.cursorBefore = cursorBefore;
        push(e, removed.length() + inserted.length());
        noMerge = false;
    }

    void recordWhole(TextBuffer before, TextBuffer after, int cursorBefore) {
        redo.clear();
        Edit e = new Edit();
        e.before = before;
        e.after = after;
        e.cursorBefore = cursorBefore;
        e.time = System.currentTimeMillis();
        // 整篇快照很占内存，只保留最近一个
        for (int i = undo.size() - 1; i >= 0; i--) {
            if (undo.get(i).before != null) {
                undo.subList(0, i + 1).clear();
                break;
            }
        }
        push(e, 0);
        noMerge = true;
    }

    private void push(Edit e, long n) {
        undo.add(e);
        chars += n;
        while (chars > MAX_CHARS && undo.size() > 1) {
            Edit o = undo.remove(0);
            if (o.before == null) chars -= o.removed.length() + o.inserted.length();
        }
    }

    Edit popUndo() {
        if (undo.isEmpty()) return null;
        Edit e = undo.remove(undo.size() - 1);
        redo.add(e);
        noMerge = true;
        return e;
    }

    Edit popRedo() {
        if (redo.isEmpty()) return null;
        Edit e = redo.remove(redo.size() - 1);
        undo.add(e);
        noMerge = true;
        return e;
    }
}
