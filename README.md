# 轻传 · LightDrop

手机 ↔ 平板 局域网直传。微信里点「分享」就能把文件送到平板，平板不用装微信，也不经过任何服务器。
安装包约 100 KB，无任何第三方依赖（纯 Android 框架 API），最低支持 Android 6.0。

---

## 一、它解决什么

- **微信太占体积** → 平板不装微信，只装这个 90 KB 的小工具。
- **网页端传文件慢且麻烦** → 原生 App，两次点击完成；支持多选、支持大文件。
- **不耗流量、不限速** → 数据只在局域网内走，速度取决于路由器（一般 10–30 MB/s）。

## 二、安装

1. 把 `LightDrop-1.1.0.apk` 分别装到**手机**和**平板**（同一个包，两端功能一样）。
2. 首次安装需在系统里允许「安装未知来源应用」。
3. 两台设备连**同一个 Wi-Fi**；没有 Wi-Fi 时，用平板开热点、手机连上，同样可用。

## 三、怎么用

**把微信文件送到平板（主要场景）**

1. 平板打开「轻传 → 接收文件」，开关默认已打开，页面上会显示本机地址（如 `192.168.1.23`）。
   开启后页面可以关掉、平板可以锁屏——通知栏会常驻一条「轻传已开启接收」，点它回到页面，通知里的「停止接收」可随时关闭。
2. 手机微信里长按文件 →「分享」/「发送给朋友」→ 选择「轻传」。
3. App 自动搜到平板，点一下设备名，开始传输。
4. 文件保存在平板的 `内部存储 / Download / 轻传`，App 内也能直接打开。

**把平板的东西传回手机**：手机打开「接收文件」，平板选「发送文件」，反过来操作即可。
**传本地相册/文档**：点「发送文件 → 待发送」卡片，可多选任意文件；微信里分享纯文本时会自动存成 `.txt`。

## 四、它是怎么传的

| 环节 | 实现 |
|---|---|
| 找设备 | UDP 46001 端口广播发现，接收端应答设备名与端口 |
| 传文件 | TCP 46000 端口，自定义协议：魔数 + 版本 + 文件数 + 每个文件的(名称, 大小) + 原始字节流 |
| 落盘 | Android 10+ 走 MediaStore（`Download/轻传`，无需存储权限）；Android 9 及以下直写公共目录 |
| 失败处理 | 连接超时 / 中断会回滚半成品文件，不会留下损坏文件 |

**后台接收**：接收端是一个前台服务（`dataSync` 类型），持有 Wi-Fi 高性能锁，传输期间额外持有 CPU 唤醒锁，所以锁屏也能收。
代价是通知栏常驻一条通知、待机耗电略增；部分国产 ROM 还需在系统设置里允许「自启动 / 后台运行」，否则息屏后仍可能被清理。

**已知限制**：访客 Wi-Fi / AP 隔离的网络下广播发现会失败，此时在发送页手动输入平板上的 IP 即可。

## 五、自动检查更新

App 每次启动会静默查一次（24 小时内不重复），有新版弹窗提示，下载完直接调起系统安装。

**默认更新源已内置**：`coolflower1984/lightdrop`，装完即可用，不需要任何设置。

**维护者发版步骤**

1. 仓库：`https://github.com/coolflower1984/lightdrop`（必须公开，私有仓库的 Release 需要登录才能读）。
2. 仓库的 `Settings → Secrets and variables → Actions` 里加：
   - `KEYSTORE_BASE64`：`base64 -w0 keystore/lightdrop.jks` 的输出
   - `KEYSTORE_PASS`：证书口令（默认 `lightdrop2024`，建议改掉）
3. 打标签即可自动出包并发布 Release：

   ```bash
   git tag v1.1.1 && git push origin v1.1.1
   ```

   `.github/workflows/release.yml` 会编译、签名、把 apk 挂到 Release 上。
4. App 里「设置 → 更新源」默认就是 `coolflower1984/lightdrop`，只有换仓库时才需要改。

> 也可以不用 GitHub：更新源支持填一个 `version.json` 的完整网址，格式
> `{"version":"1.1.1","url":"https://.../LightDrop-1.1.1.apk","notes":"修复若干问题"}`。

> 签名证书**不在仓库里**（避免公开私钥），本地留了一份在 `../LightDrop-signing/lightdrop.jks`。
> 它决定了 App 的签名，务必自己保管好：换了证书就只能卸载重装，无法覆盖升级。
> CI 构建前需要把它转成 base64 放进 `KEYSTORE_BASE64`，否则产出的包签名不同、装不上。

## 六、自己重新构建

不需要 Android Studio、不需要 Gradle，脚本直接调 aapt2 / javac / d8 / apksigner：

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需 platforms;android-34、build-tools;34.0.0
mkdir -p keystore && cp /path/to/lightdrop.jks keystore/   # 先恢复签名证书
./build.sh                                  # 产物在 build/LightDrop-<版本>.apk
```

版本号优先级：`VERSION_NAME`/`VERSION_CODE` 环境变量 → git tag / 提交数 → `VERSION` 文件（第一行版本名、第二行版本号）。
改版本时改 `VERSION` 文件即可，CI 里打 tag 会自动覆盖版本名。

## 七、代码结构

```
app/src/main/java/com/lightdrop/transfer/
├── MainActivity.java        首页：发送 / 接收 / 设置入口，启动时静默查更新
├── SendActivity.java        选择文件、搜索设备、发送进度；接收微信 ACTION_SEND
├── ReceiveActivity.java     开关接收、显示本机地址、实时进度、已接收列表
├── ReceiveService.java      前台服务：锁屏/后台持续接收、状态通知、Wi-Fi 锁与唤醒锁
├── SettingsActivity.java    设备名、更新源、自动更新开关
├── net/
│   ├── Protocol.java        端口与协议常量
│   ├── DiscoveryClient.java 发送端 UDP 广播搜索
│   ├── DiscoveryResponder.java 接收端 UDP 应答
│   ├── TransferClient.java  发送端 TCP 推流
│   └── TransferServer.java  接收端 TCP 落盘
└── core/
    ├── Incoming.java        写入 MediaStore / 公共目录，失败回滚
    ├── Receiver.java        接收服务单例：界面退出了服务仍在跑
    ├── Prefs.java           配置与接收历史
    ├── UpdateManager.java   GitHub Releases / version.json 检查更新与安装
    ├── ApkProvider.java     给安装器读取 apk 的轻量 FileProvider
    ├── Util.java            文件名、大小、MIME、本机 IP 等
    └── Ui.java              dp / 文本控件小工具
```

## 八、后续可以加的东西

- 二维码扫码连接（访客网络兜底）
- 断点续传与传输完成通知
- 多设备同时发送、文件夹整体发送
