package com.lightdrop.transfer.net;

/** 传输协议常量。TCP 自定协议：握手头 + 文件清单 + 原始字节流。 */
public final class Protocol {

    public static final int MAGIC = 0x4C445401; // "LDT\1"
    public static final int VERSION = 1;

    public static final int TCP_PORT = 46000;
    public static final int UDP_PORT = 46001;

    public static final String DISCOVER_REQ = "LIGHTDROP/1 DISCOVER";

    public static final int CHUNK = 128 * 1024;

    private Protocol() {
    }
}
