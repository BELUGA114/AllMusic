# 客户端玩家点歌屏蔽

**客户端请安装 `[fabric-26.3]AllMusic_Client-4.2.5.jar`，不要误装文件名带 `AllMusic_Server` 的服务端包。**

所有 `/music block` 命令仅在本地执行，黑名单与未知来源策略按服务器地址或单人世界分别保存至 `config/allmusic_client_blocklist.json`。

| 命令 | 用途 |
| --- | --- |
| `/music block` | 查看帮助 |
| `/music block add <玩家名>` | 拉黑玩家的点歌，大小写不敏感 |
| `/music block current` | 拉黑当前歌曲的点歌者，并立即停止本地播放 |
| `/music block remove <玩家名>` | 解除拉黑，该玩家从下一首歌曲起可播放 |
| `/music block list` | 查看当前服务器黑名单和策略 |
| `/music block unknown` | 查询未知来源策略 |
| `/music block unknown mute` | 未知来源保持静音，默认策略 |
| `/music block unknown play` | 等待识别约 1.5 秒后允许未知来源播放；稍后识别为拉黑玩家时仍停止播放 |

黑名单为空时沿用原有播放行为。黑名单非空时先等待点歌者信息，已识别且未拉黑的玩家可以正常播放。添加命令补全在线玩家和当前点歌者，移除命令补全黑名单玩家；也可以手动输入离线玩家名。玩家名支持 1 至 16 位字母、数字和下划线，改名后需重新添加。

当前服务器协议仅在 `INFO` 展示文本中提供点歌者，客户端解析默认模板末行 `by: 玩家名`，忽略 Minecraft 颜色码。模板被修改、歌曲信息 HUD 被服务器关闭、空闲歌单或第三方播放未提供玩家信息时，会视为未知来源。`mute` 策略可能因此使这些歌曲保持静音，`play` 策略可能漏过屏蔽。协议没有歌曲实例标识，无法完全排除迟到的 `INFO` 被关联到下一首歌曲的情况。

解除拉黑不会突然恢复已经屏蔽的当前歌曲。未知来源策略切换立即应用于当前歌曲；已有可靠进度时按该进度启动。原有 `/music` 命令仍交给服务器处理，黑名单不会改变服务端队列或其他玩家的播放。

配置保存由单个后台线程按顺序完成。保存失败会提示本次设置仅在当前会话生效；无法读取的配置不会被默认值覆盖，需修复原文件后重启。客户端只消费手动输入的管理命令，点击服务器提供的命令链接不会修改本地黑名单。

验证命令：`gradlew :client:verifyPlayerBlock :client:fabric_26_3:verifyBlockCommands :client:fabric_26_3:build`。

## 客户端更新与旧服务端兼容

此客户端继续兼容 Minecraft 26.3 / Fabric 的 AllMusic 4.2.5 服务端，保留现有播放协议与本地黑名单。移植了 main 的初始化保护、日志等级修复、FLAC 播放和中途定位、客户端诊断命令、动态封面尺寸，以及最终的默认封面分辨率 240。

| 本地命令 | 用途 |
| --- | --- |
| `/music_client help` | 查看帮助 |
| `/music_client info` | 查询客户端播放、HUD、配置状态 |
| `/music_client test` | 检查核心和 HUD 资源状态 |
| `/music_client imagesize` | 查询封面纹理分辨率 |
| `/music_client imagesize <16-1024>` | 设置并保存封面纹理分辨率，重新加载当前封面 |

`imagesize` 调整封面清晰度，HUD 在屏幕上的显示大小仍由服务端的 `/music hud pic size` 控制。已有客户端配置会保留原尺寸；损坏的配置不会被默认值覆盖。封面尺寸替换和客户端关闭时会释放旧纹理。旧服务端没有 `pic.bg` 字段时，唱片背景保持开启。

重进服务器时，收到歌曲后优先等待 `POS`，直接从服务端当前进度启动；最多等待 2 秒，缺失 `POS` 时使用已收到的 `TIME` 和经过的时间兜底。普通新歌沿用直接启播，来源识别与黑名单策略仍生效。客户端收到 `PLAY` 前无法可靠恢复音乐，服务端已有 `joinDelay` 配置不会被客户端修改。

本分支服务端的新配置默认 `joinDelay` 为 `20`（正常 TPS 下约 1 秒），已有配置保留原值，需要服务端管理员调整 `allmusic_server/config.json` 后重载。

完整验证：`gradlew :client:check :client:fabric_26_3:build :server:fabric_26_3:build :onejar:fabric_26_3:build`。专用回归任务包括 `:client:verifyClientCompatibility`（旧协议、HUD 缺省值、配置、日志及封面资源）和 `:client:verifyFlac`（PCM、带/不带定位表的跳转、未知长度及定位失败）。
