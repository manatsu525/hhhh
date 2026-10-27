package com.lanfile.manager.archive;

import java.io.IOException;

/** 压缩包已加密：还没提供密码（wrong=false），或者密码不对（wrong=true） */
public final class PasswordException extends IOException {
    public final boolean wrong;

    public PasswordException(boolean wrong) {
        super(wrong ? "密码错误" : "需要密码");
        this.wrong = wrong;
    }
}
