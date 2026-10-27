package com.lanfile.manager.archive;

import java.io.IOException;

public final class CancelledException extends IOException {
    public CancelledException() {
        super("已取消");
    }
}
