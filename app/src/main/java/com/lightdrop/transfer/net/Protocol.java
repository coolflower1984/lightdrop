package com.lightdrop.transfer.net;

/**
 * 传输协议 v2。
 *
 * <pre>
 * C→S  MAGIC(4) VERSION(4) MODE(4)
 *      MODE=PROBE  → S→C  UTF(设备信息 JSON)
 *      MODE=SEND   → count(4) [nameUTF size(8)]*count
 *                    S→C  1
 *                    C→S  文件裸字节流 * count
 *                    S→C  done(1) totalBytes(8) [errUTF]
 * </pre>
 *
 * 最后一步的确认是关键：接收端把文件真正落到磁盘之后才回 1，
 * 发送端拿到 1 才算成功，否则可能「显示成功但对方没有文件」。
 */
public final class Protocol {

    public static final int MAGIC = 0x4C445402; // "LDT\2"
    public static final int VERSION = 2;

    public static final int TCP_PORT = 46000;
    /** 46000 被占用时依次尝试的备用端口数量。 */
    public static final int PORT_TRIES = 10;

    public static final int UDP_PORT = 46001;
    public static final String DISCOVER_REQ = "LIGHTDROP/2 DISCOVER";

    public static final int CHUNK = 64 * 1024;

    /** 正常发送文件。 */
    public static final int MODE_SEND = 1;
    /** 仅探测设备（网段扫描用），不影响接收端界面。 */
    public static final int MODE_PROBE = 2;

    private Protocol() {
    }
}
