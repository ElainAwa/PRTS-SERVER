# 📋 Luminara Paper优化集成计划

## 🚀 Paper API & 优化完整集成

### 1.1.0-PRE1 - 核心基础设施 (0001-0050)

#### 构建系统 & 基础架构

- [ ] 0001-Setup-Gradle-project.patch - 设置Gradle项目结构
- [ ] 0002-Remap-fixes.patch - 修复重映射问题
- [ ] 0003-Build-system-changes.patch - 构建系统变更
- [ ] 0004-Test-changes.patch - 测试系统变更
- [ ] 0005-Paper-config-files.patch - Paper配置文件系统
- [ ] 0006-MC-Dev-fixes.patch - MC开发修复
- [ ] 0007-ConcurrentUtil.patch - 并发工具类
- [ ] 0008-CB-fixes.patch - CraftBukkit修复
- [ ] 0009-MC-Utils.patch - Minecraft工具类

#### 核心系统

- [ ] 0010-Adventure.patch - Adventure文本组件系统
- [ ] 0011-Paper-command.patch - Paper命令系统
- [ ] 0012-Paper-Metrics.patch - Paper指标系统
- [ ] 0013-Paper-Plugins.patch - Paper插件系统
- [ ] 0014-Timings-v2.patch - Timings v2性能分析系统
- [ ] 0015-Rewrite-dataconverter-system.patch - 数据转换器重写

#### 核心优化引擎

- [ ] 0016-Starlight.patch - Starlight光照引擎
- [ ] 0017-Add-TickThread.patch - TickThread系统
- [ ] 0018-Add-command-line-option-to-load-extra-plugin-jars-no.patch - 额外插件jar加载选项
- [ ] 0019-Rewrite-chunk-system.patch - 区块系统重写

#### 基础配置选项

- [ ] 0020-Configurable-cactus-bamboo-and-reed-growth-heights.patch - 可配置仙人掌竹子芦苇生长高度
- [ ] 0021-Configurable-baby-zombie-movement-speed.patch - 可配置幼体僵尸移动速度
- [ ] 0022-Configurable-fishing-time-ranges.patch - 可配置钓鱼时间范围
- [ ] 0023-Allow-nerfed-mobs-to-jump-and-take-water-damage.patch - 允许被削弱的生物跳跃和受水伤害
- [ ] 0024-Add-configurable-despawn-distances-for-living-entiti.patch - 可配置生物消失距离
- [ ] 0025-Allow-for-toggling-of-spawn-chunks.patch - 允许切换生成区块

#### 实体优化基础

- [ ] 0026-Drop-falling-block-and-tnt-entities-at-the-specified.patch - 在指定高度丢弃下落方块和TNT实体
- [ ] 0027-Show-Paper-in-client-crashes-server-lists-and-Mojang.patch - 在客户端崩溃和服务器列表中显示Paper
- [ ] 0028-Implement-Paper-VersionChecker.patch - 实现Paper版本检查器
- [ ] 0029-Add-version-history-to-version-command.patch - 为版本命令添加版本历史

#### 基础API

- [ ] 0030-Player-affects-spawning-API.patch - 玩家影响生成API
- [ ] 0031-Further-improve-server-tick-loop.patch - 进一步改进服务器tick循环
- [ ] 0032-Only-refresh-abilities-if-needed.patch - 仅在需要时刷新能力
- [ ] 0033-Entity-Origin-API.patch - 实体起源API
- [ ] 0034-Prevent-tile-entity-and-entity-crashes.patch - 防止方块实体和实体崩溃
- [ ] 0035-Configurable-top-of-nether-void-damage.patch - 可配置下界虚空顶部伤害

#### 基础修复

- [ ] 0036-Check-online-mode-before-converting-and-renaming-pla.patch - 转换和重命名玩家前检查在线模式
- [ ] 0037-Always-tick-falling-blocks.patch - 始终tick下落方块
- [ ] 0038-Configurable-end-credits.patch - 可配置末地致谢
- [ ] 0039-Fix-lag-from-explosions-processing-dead-entities.patch - 修复爆炸处理死亡实体造成的延迟
- [ ] 0040-Optimize-explosions.patch - 优化爆炸

#### 基础配置扩展

- [ ] 0041-Disable-explosion-knockback.patch - 禁用爆炸击退
- [ ] 0042-Disable-thunder.patch - 禁用雷电
- [ ] 0043-Disable-ice-and-snow.patch - 禁用冰雪
- [ ] 0044-Configurable-mob-spawner-tick-rate.patch - 可配置生物生成器tick速率
- [ ] 0045-Implement-PlayerLocaleChangeEvent.patch - 实现玩家语言变更事件
- [ ] 0046-Add-BeaconEffectEvent.patch - 添加信标效果事件
- [ ] 0047-Configurable-container-update-tick-rate.patch - 可配置容器更新tick速率
- [ ] 0048-Use-UserCache-for-player-heads.patch - 为玩家头颅使用用户缓存
- [ ] 0049-Disable-spigot-tick-limiters.patch - 禁用Spigot tick限制器
- [ ] 0050-Add-PlayerInitialSpawnEvent.patch - 添加玩家初始生成事件

### 1.1.0-PRE2 - 基础API与事件 (0051-0100)

#### 基础配置与检测

- [ ] 0051-Configurable-Disabling-Cat-Chest-Detection.patch - 可配置禁用猫箱子检测
- [ ] 0052-Improve-Player-chat-API-handling.patch - 改进玩家聊天API处理
- [ ] 0053-All-chunks-are-slime-spawn-chunks-toggle.patch - 所有区块都是史莱姆生成区块切换
- [ ] 0054-Expose-server-CommandMap.patch - 暴露服务器命令映射
- [ ] 0055-Be-a-bit-more-informative-in-maxHealth-exception.patch - 在最大生命值异常中提供更多信息

#### 玩家API扩展

- [ ] 0056-Player-Tab-List-and-Title-APIs.patch - 玩家Tab列表和标题API
- [ ] 0057-Add-configurable-portal-search-radius.patch - 添加可配置传送门搜索半径
- [ ] 0058-Add-velocity-warnings.patch - 添加速度警告
- [ ] 0059-Configurable-inter-world-teleportation-safety.patch - 可配置跨世界传送安全
- [ ] 0060-Add-exception-reporting-event.patch - 添加异常报告事件

#### 文本与计分板优化

- [ ] 0061-Don-t-nest-if-we-don-t-need-to-when-cerealising-text.patch - 序列化文本时避免不必要的嵌套
- [ ] 0062-Disable-Scoreboards-for-non-players-by-default.patch - 默认为非玩家禁用计分板
- [ ] 0063-Add-methods-for-working-with-arrows-stuck-in-living-.patch - 添加处理插在生物身上箭矢的方法
- [ ] 0064-Chunk-Save-Reattempt.patch - 区块保存重试
- [ ] 0065-Complete-resource-pack-API.patch - 完整资源包API

#### 权限与元数据

- [ ] 0066-Default-loading-permissions.yml-before-plugins.patch - 默认在插件前加载permissions.yml
- [ ] 0067-Allow-Reloading-of-Custom-Permissions.patch - 允许重载自定义权限
- [ ] 0068-Remove-Metadata-on-reload.patch - 重载时移除元数据
- [ ] 0069-Handle-Item-Meta-Inconsistencies.patch - 处理物品元数据不一致
- [ ] 0070-Configurable-Non-Player-Arrow-Despawn-Rate.patch - 可配置非玩家箭矢消失速率

#### 世界工具与优化

- [ ] 0071-Add-World-Util-Methods.patch - 添加世界工具方法
- [ ] 0072-Custom-replacement-for-eaten-items.patch - 被吃物品的自定义替换
- [ ] 0073-Strip-raytracing-for-EntityLiving-hasLineOfSight.patch - 为EntityLiving.hasLineOfSight剥离光线追踪
- [ ] 0074-handle-NaN-health-absorb-values-and-repair-bad-data.patch - 处理NaN生命值吸收值并修复坏数据
- [ ] 0075-Use-a-Shared-Random-for-Entities.patch - 为实体使用共享随机数

#### 生成配置

- [ ] 0076-Configurable-spawn-chances-for-skeleton-horses.patch - 可配置骷髅马生成几率
- [ ] 0077-Optimize-isInWorldBounds-and-getBlockState-for-inlin.patch - 优化isInWorldBounds和getBlockState以便内联
- [ ] 0078-Only-process-BlockPhysicsEvent-if-a-plugin-has-a-lis.patch - 仅在插件有监听器时处理方块物理事件
- [ ] 0079-Entity-AddTo-RemoveFrom-World-Events.patch - 实体添加到/从世界移除事件
- [ ] 0080-Configurable-Chunk-Inhabited-Time.patch - 可配置区块居住时间

#### 路径查找与区域文件

- [ ] 0081-EntityPathfindEvent.patch - 实体路径查找事件
- [ ] 0082-Sanitise-RegionFileCache-and-make-configurable.patch - 清理区域文件缓存并使其可配置
- [ ] 0083-Do-not-load-chunks-for-Pathfinding.patch - 路径查找时不加载区块
- [ ] 0084-Add-PlayerUseUnknownEntityEvent.patch - 添加玩家使用未知实体事件
- [ ] 0085-Configurable-Grass-Spread-Tick-Rate.patch - 可配置草地传播tick速率

#### 方块事件修复

- [ ] 0086-Fix-Cancelling-BlockPlaceEvent-triggering-physics.patch - 修复取消方块放置事件触发物理
- [ ] 0087-Optimize-DataBits.patch - 优化数据位
- [ ] 0088-Option-to-use-vanilla-per-world-scoreboard-coloring-.patch - 使用原版每世界计分板着色选项
- [ ] 0089-Configurable-Player-Collision.patch - 可配置玩家碰撞
- [ ] 0090-Add-handshake-event-to-allow-plugins-to-handle-clien.patch - 添加握手事件允许插件处理客户端

#### 网络与健康

- [ ] 0091-Configurable-RCON-IP-address.patch - 可配置RCON IP地址
- [ ] 0092-EntityRegainHealthEvent-isFastRegen-API.patch - EntityRegainHealthEvent快速再生API
- [ ] 0093-Add-ability-to-configure-frosted_ice-properties.patch - 添加配置霜冰属性的能力
- [ ] 0094-remove-null-possibility-for-getServer-singleton.patch - 移除getServer单例的null可能性
- [ ] 0095-Improve-Maps-in-item-frames-performance-and-bug-fixe.patch - 改进物品展示框中地图的性能和bug修复

#### 战利品表与计分板

- [ ] 0096-LootTable-API-Replenishable-Lootables-Feature.patch - 战利品表API可补充战利品功能
- [ ] 0097-Don-t-save-empty-scoreboard-teams-to-scoreboard.dat.patch - 不保存空计分板队伍到scoreboard.dat
- [ ] 0098-System-property-for-disabling-watchdoge.patch - 禁用看门狗的系统属性
- [ ] 0099-Async-GameProfileCache-saving.patch - 异步游戏配置文件缓存保存
- [ ] 0100-Optional-TNT-doesn-t-move-in-water.patch - 可选TNT在水中不移动

### 1.1.0-PRE3 - 红石与网络优化 (0101-0200)

#### 红石与服务器优化

- [ ] 0101-Faster-redstone-torch-rapid-clock-removal.patch - 更快的红石火把快速时钟移除
- [ ] 0102-Add-server-name-parameter.patch - 添加服务器名称参数
- [ ] 0103-Only-send-global-sounds-to-same-world-if-limiting-ra.patch - 限制范围时仅向同世界发送全局声音
- [ ] 0104-Avoid-blocking-on-Network-Manager-creation.patch - 避免在网络管理器创建时阻塞
- [ ] 0105-Don-t-lookup-game-profiles-that-have-no-UUID-and-no-.patch - 不查找没有UUID和名称的游戏配置文件

#### 网络与代理

- [ ] 0106-Add-setting-for-proxy-online-mode-status.patch - 添加代理在线模式状态设置
- [ ] 0107-Optimise-BlockState-s-hashCode-equals.patch - 优化方块状态的hashCode和equals
- [ ] 0108-Configurable-packet-in-spam-threshold.patch - 可配置数据包输入垃圾邮件阈值
- [ ] 0109-Configurable-flying-kick-messages.patch - 可配置飞行踢出消息
- [ ] 0110-Add-EntityZapEvent.patch - 添加实体电击事件

#### 数据处理与缓存

- [ ] 0111-Filter-bad-tile-entity-nbt-data-from-falling-blocks.patch - 过滤下落方块中的坏方块实体NBT数据
- [ ] 0112-Cache-user-authenticator-threads.patch - 缓存用户认证器线程
- [ ] 0113-Allow-Reloading-of-Command-Aliases.patch - 允许重载命令别名
- [ ] 0114-Add-source-to-PlayerExpChangeEvent.patch - 为玩家经验变更事件添加来源
- [ ] 0115-Add-ProjectileCollideEvent.patch - 添加投射物碰撞事件

#### 世界边界与优化

- [ ] 0116-Prevent-Pathfinding-out-of-World-Border.patch - 防止路径查找超出世界边界
- [ ] 0117-Optimize-World.isLoaded-BlockPosition-Z.patch - 优化World.isLoaded BlockPosition Z
- [ ] 0118-Bound-Treasure-Maps-to-World-Border.patch - 将宝藏地图绑定到世界边界
- [ ] 0119-Configurable-Cartographer-Treasure-Maps.patch - 可配置制图师宝藏地图
- [ ] 0120-Add-API-methods-to-control-if-armour-stands-can-move.patch - 添加控制盔甲架是否可移动的API方法

#### 动作栏与物品修复

- [ ] 0121-String-based-Action-Bar-API.patch - 基于字符串的动作栏API
- [ ] 0122-Properly-fix-item-duplication-bug.patch - 正确修复物品复制bug
- [ ] 0123-Firework-API-s.patch - 烟花API
- [ ] 0124-PlayerTeleportEndGatewayEvent.patch - 玩家传送末地折跃门事件
- [ ] 0125-Provide-E-TE-Chunk-count-stat-methods.patch - 提供实体/方块实体区块计数统计方法

#### 玩家保存与经验

- [ ] 0126-Enforce-Sync-Player-Saves.patch - 强制同步玩家保存
- [ ] 0127-ExperienceOrbs-API-for-Reason-Source-Triggering-play.patch - 经验球API用于原因来源触发玩家
- [ ] 0128-Cap-Entity-Collisions.patch - 限制实体碰撞
- [ ] 0129-Remove-CraftScheduler-Async-Task-Debugger.patch - 移除CraftScheduler异步任务调试器
- [ ] 0130-Do-not-let-armorstands-drown.patch - 不让盔甲架溺水

#### 服务器重启与配置

- [ ] 0131-Properly-handle-async-calls-to-restart-the-server.patch - 正确处理重启服务器的异步调用
- [ ] 0132-Add-option-to-make-parrots-stay-on-shoulders-despite.patch - 添加让鹦鹉留在肩膀上的选项
- [ ] 0133-Add-configuration-option-to-prevent-player-names-fro.patch - 添加防止玩家名称的配置选项
- [ ] 0134-Use-TerminalConsoleAppender-for-console-improvements.patch - 使用TerminalConsoleAppender改进控制台
- [ ] 0135-provide-a-configurable-option-to-disable-creeper-lin.patch - 提供禁用爬行者链接的可配置选项

#### 物品拾取API

- [ ] 0136-Item-canEntityPickup.patch - 物品canEntityPickup
- [ ] 0137-PlayerPickupItemEvent-setFlyAtPlayer.patch - 玩家拾取物品事件setFlyAtPlayer
- [ ] 0138-PlayerAttemptPickupItemEvent.patch - 玩家尝试拾取物品事件
- [ ] 0139-Do-not-submit-profile-lookups-to-worldgen-threads.patch - 不向世界生成线程提交配置文件查找
- [ ] 0140-Add-UnknownCommandEvent.patch - 添加未知命令事件

#### 玩家配置文件API

- [ ] 0141-Basic-PlayerProfile-API.patch - 基础玩家配置文件API
- [ ] 0142-Shoulder-Entities-Release-API.patch - 肩膀实体释放API
- [ ] 0143-Profile-Lookup-Events.patch - 配置文件查找事件
- [ ] 0144-Block-player-logins-during-server-shutdown.patch - 服务器关闭期间阻止玩家登录
- [ ] 0145-Entity-fromMobSpawner.patch - 实体来自生物生成器

#### 马匹与转换API

- [ ] 0146-Improve-the-Saddle-API-for-Horses.patch - 改进马匹鞍具API
- [ ] 0147-Implement-ensureServerConversions-API.patch - 实现确保服务器转换API
- [ ] 0148-Implement-getI18NDisplayName.patch - 实现getI18NDisplayName
- [ ] 0149-ProfileWhitelistVerifyEvent.patch - 配置文件白名单验证事件
- [ ] 0150-Fix-this-stupid-bullshit.patch - 修复这个愚蠢的问题

#### 生物API扩展

- [ ] 0151-LivingEntity-setKiller.patch - 生物实体设置杀手
- [ ] 0152-Ocelot-despawns-should-honor-nametags-and-leash.patch - 豹猫消失应该尊重名牌和拴绳
- [ ] 0153-Reset-spawner-timer-when-spawner-event-is-cancelled.patch - 生成器事件取消时重置生成器计时器
- [ ] 0154-Allow-specifying-a-custom-authentication-servers-dow.patch - 允许指定自定义认证服务器
- [ ] 0155-Handle-plugin-prefixes-using-Log4J-configuration.patch - 使用Log4J配置处理插件前缀

#### 日志与玩家事件

- [ ] 0156-Improve-Log4J-Configuration-Plugin-Loggers.patch - 改进Log4J配置插件记录器
- [ ] 0157-Add-PlayerJumpEvent.patch - 添加玩家跳跃事件
- [ ] 0158-handle-ServerboundKeepAlivePacket-async.patch - 异步处理服务器绑定保持活动数据包
- [ ] 0159-Expose-client-protocol-version-and-virtual-host.patch - 暴露客户端协议版本和虚拟主机
- [ ] 0160-revert-serverside-behavior-of-keepalives.patch - 恢复保持活动的服务器端行为

#### 声音与盔甲事件

- [ ] 0161-Send-attack-SoundEffects-only-to-players-who-can-see.patch - 仅向能看到的玩家发送攻击声音效果
- [ ] 0162-Add-PlayerArmorChangeEvent.patch - 添加玩家盔甲变更事件
- [ ] 0163-Prevent-logins-from-being-processed-when-the-player-.patch - 防止在玩家时处理登录
- [ ] 0164-Fix-MC-117075-TE-Unload-Lag-Spike.patch - 修复MC-117075方块实体卸载延迟峰值
- [ ] 0165-use-CB-BlockState-implementations-for-captured-block.patch - 为捕获的方块使用CB方块状态实现

#### 方块状态与Tab补全

- [ ] 0166-API-to-get-a-BlockState-without-a-snapshot.patch - 获取无快照方块状态的API
- [ ] 0167-AsyncTabCompleteEvent.patch - 异步Tab补全事件
- [ ] 0168-PlayerPickupExperienceEvent.patch - 玩家拾取经验事件
- [ ] 0169-Ability-to-apply-mending-to-XP-API.patch - 应用修补到XP的API能力
- [ ] 0170-PlayerNaturallySpawnCreaturesEvent.patch - 玩家自然生成生物事件

#### 头颅与生物生成

- [ ] 0171-Add-setPlayerProfile-API-for-Skulls.patch - 为头颅添加setPlayerProfile API
- [ ] 0172-PreCreatureSpawnEvent.patch - 生物生成前事件
- [ ] 0173-Fill-Profile-Property-Events.patch - 填充配置文件属性事件
- [ ] 0174-PlayerAdvancementCriterionGrantEvent.patch - 玩家进度标准授予事件
- [ ] 0175-Add-ArmorStand-Item-Meta.patch - 添加盔甲架物品元数据

#### 玩家交互扩展

- [ ] 0176-Extend-Player-Interact-cancellation.patch - 扩展玩家交互取消
- [ ] 0177-Tameable-getOwnerUniqueId-API.patch - 可驯服实体获取主人唯一ID API
- [ ] 0178-Toggleable-player-crits-helps-mitigate-hacked-client.patch - 可切换玩家暴击帮助缓解黑客客户端
- [ ] 0179-Disable-Explicit-Network-Manager-Flushing.patch - 禁用显式网络管理器刷新
- [ ] 0180-Implement-extended-PaperServerListPingEvent.patch - 实现扩展的Paper服务器列表ping事件

#### 玩家配置文件高级API

- [ ] 0181-Ability-to-change-PlayerProfile-in-AsyncPreLoginEven.patch - 在异步预登录事件中更改玩家配置文件的能力
- [ ] 0182-Player.setPlayerProfile-API.patch - Player.setPlayerProfile API
- [ ] 0183-getPlayerUniqueId-API.patch - getPlayerUniqueId API
- [ ] 0184-Improved-Async-Task-Scheduler.patch - 改进的异步任务调度器
- [ ] 0185-Make-legacy-ping-handler-more-reliable.patch - 使传统ping处理器更可靠

#### Ping事件与通道

- [ ] 0186-Call-PaperServerListPingEvent-for-legacy-pings.patch - 为传统ping调用PaperServerListPingEvent
- [ ] 0187-Flag-to-disable-the-channel-limit.patch - 禁用通道限制的标志
- [ ] 0188-Add-openSign-method-to-HumanEntity.patch - 为HumanEntity添加openSign方法
- [ ] 0189-Configurable-sprint-interruption-on-attack.patch - 可配置攻击时冲刺中断
- [ ] 0190-EndermanEscapeEvent.patch - 末影人逃脱事件

#### 末影人API

- [ ] 0191-Enderman.teleportRandomly.patch - 末影人随机传送
- [ ] 0192-Block-Enderpearl-Travel-Exploit.patch - 阻止末影珍珠旅行漏洞
- [ ] 0193-Expand-World.spawnParticle-API-and-add-Builder.patch - 扩展World.spawnParticle API并添加构建器
- [ ] 0194-Fix-exploit-that-allowed-colored-signs-to-be-created.patch - 修复允许创建彩色告示牌的漏洞
- [ ] 0195-Prevent-Frosted-Ice-from-loading-holding-chunks.patch - 防止霜冰加载持有区块

#### 末影人与女巫事件

- [ ] 0196-EndermanAttackPlayerEvent.patch - 末影人攻击玩家事件
- [ ] 0197-WitchConsumePotionEvent.patch - 女巫消耗药水事件
- [ ] 0198-WitchThrowPotionEvent.patch - 女巫投掷药水事件
- [ ] 0199-Allow-spawning-Item-entities-with-World.spawnEntity.patch - 允许使用World.spawnEntity生成物品实体
- [ ] 0200-WitchReadyPotionEvent.patch - 女巫准备药水事件

### 1.1.0-PRE4 - 物品与实体API (0201-0300)

#### 物品使用与传送

- [ ] 0201-ItemStack-getMaxItemUseDuration.patch - 物品堆叠获取最大使用持续时间
- [ ] 0202-Implement-EntityTeleportEndGatewayEvent.patch - 实现实体传送末地折跃门事件
- [ ] 0203-Unset-Ignited-flag-on-cancel-of-Explosion-Event.patch - 取消爆炸事件时取消点燃标志
- [ ] 0204-Fix-CraftEntity-hashCode.patch - 修复CraftEntity hashCode
- [ ] 0205-Configurable-Alternative-LootPool-Luck-Formula.patch - 可配置替代战利品池幸运公式

#### 玩家数据与盾牌

- [ ] 0206-Print-Error-details-when-failing-to-save-player-data.patch - 保存玩家数据失败时打印错误详情
- [ ] 0207-Make-shield-blocking-delay-configurable.patch - 使盾牌阻挡延迟可配置
- [ ] 0208-Improve-EntityShootBowEvent.patch - 改进实体射弓事件
- [ ] 0209-PlayerReadyArrowEvent.patch - 玩家准备箭矢事件
- [ ] 0210-Implement-EntityKnockbackByEntityEvent-and-EntityPus.patch - 实现实体被实体击退事件和实体推

#### 爆炸与生物API

- [ ] 0211-Expand-Explosions-API.patch - 扩展爆炸API
- [ ] 0212-LivingEntity-Hand-Raised-Item-Use-API.patch - 生物实体举手物品使用API
- [ ] 0213-RangedEntity-API.patch - 远程实体API
- [ ] 0214-Add-config-to-disable-ender-dragon-legacy-check.patch - 添加禁用末影龙传统检查的配置
- [ ] 0215-Implement-World.getEntity-UUID-API.patch - 实现World.getEntity UUID API

#### 背包与召唤者

- [ ] 0216-InventoryCloseEvent-Reason-API.patch - 背包关闭事件原因API
- [ ] 0217-Vex-get-setSummoner-API.patch - 恼鬼获取/设置召唤者API
- [ ] 0218-Refresh-player-inventory-when-cancelling-PlayerInter.patch - 取消玩家交互时刷新玩家背包
- [ ] 0219-Use-AsyncAppender-to-keep-logging-IO-off-main-thread.patch - 使用AsyncAppender保持日志IO脱离主线程
- [ ] 0220-add-more-information-to-Entity.toString.patch - 为Entity.toString添加更多信息

#### 末影龙与鞘翅

- [ ] 0221-EnderDragon-Events.patch - 末影龙事件
- [ ] 0222-PlayerElytraBoostEvent.patch - 玩家鞘翅推进事件
- [ ] 0223-PlayerLaunchProjectileEvent.patch - 玩家发射投射物事件
- [ ] 0224-Improve-BlockPosition-inlining.patch - 改进方块位置内联
- [ ] 0225-Option-to-prevent-armor-stands-from-doing-entity-loo.patch - 防止盔甲架进行实体查找的选项

#### 盔甲架优化

- [ ] 0226-Vanished-players-don-t-have-rights.patch - 隐身玩家没有权利
- [ ] 0227-Allow-disabling-armour-stand-ticking.patch - 允许禁用盔甲架tick
- [ ] 0228-SkeletonHorse-Additions.patch - 骷髅马添加
- [ ] 0229-Don-t-call-getItemMeta-on-hasItemMeta.patch - 在hasItemMeta上不调用getItemMeta
- [ ] 0230-Implement-Expanded-ArmorStand-API.patch - 实现扩展的盔甲架API

#### 铁砧与TNT事件

- [ ] 0231-AnvilDamageEvent.patch - 铁砧损坏事件
- [ ] 0232-Add-TNTPrimeEvent.patch - 添加TNT引爆事件
- [ ] 0233-Break-up-and-make-tab-spam-limits-configurable.patch - 分解并使tab垃圾邮件限制可配置
- [ ] 0234-Fix-NBT-type-issues.patch - 修复NBT类型问题
- [ ] 0235-Remove-unnecessary-itemmeta-handling.patch - 移除不必要的物品元数据处理

#### 调试与监控

- [ ] 0236-Add-Debug-Entities-option-to-debug-dupe-uuid-issues.patch - 添加调试实体选项以调试重复UUID问题
- [ ] 0237-Add-Early-Warning-Feature-to-WatchDog.patch - 为看门狗添加早期警告功能
- [ ] 0238-Use-ConcurrentHashMap-in-JsonList.patch - 在JsonList中使用ConcurrentHashMap
- [ ] 0239-Use-a-Queue-for-Queueing-Commands.patch - 使用队列排队命令
- [ ] 0240-Ability-to-get-Tile-Entities-from-a-chunk-without-sn.patch - 从区块获取方块实体而不快照的能力

#### 方块位置与生物生成

- [ ] 0241-Optimize-BlockPosition-helper-methods.patch - 优化方块位置辅助方法
- [ ] 0242-Restore-vanilla-default-mob-spawn-range-and-water-an.patch - 恢复原版默认生物生成范围和水
- [ ] 0243-Slime-Pathfinder-Events.patch - 史莱姆路径查找事件
- [ ] 0244-Configurable-speed-for-water-flowing-over-lava.patch - 可配置水流过熔岩的速度
- [ ] 0245-Optimize-CraftBlockData-Creation.patch - 优化CraftBlockData创建

#### 注册表与幻翼

- [ ] 0246-Optimize-MappedRegistry.patch - 优化映射注册表
- [ ] 0247-Add-PhantomPreSpawnEvent.patch - 添加幻翼预生成事件
- [ ] 0248-Add-More-Creeper-API.patch - 添加更多爬行者API
- [ ] 0249-Inventory-removeItemAnySlot.patch - 背包移除任意槽位物品
- [ ] 0250-Make-CraftWorld-loadChunk-int-int-false-load-unconve.patch - 使CraftWorld loadChunk int int false加载无条件

#### 光线追踪与攻击

- [ ] 0251-Add-ray-tracing-methods-to-LivingEntity.patch - 为生物实体添加光线追踪方法
- [ ] 0252-Expose-attack-cooldown-methods-for-Player.patch - 为玩家暴露攻击冷却方法
- [ ] 0253-Improve-death-events.patch - 改进死亡事件
- [ ] 0254-Allow-chests-to-be-placed-with-NBT-data.patch - 允许放置带有NBT数据的箱子
- [ ] 0255-Mob-Pathfinding-API.patch - 生物路径查找API

#### NBT与AI规则

- [ ] 0256-Implement-an-API-for-CanPlaceOn-and-CanDestroy-NBT-v.patch - 实现CanPlaceOn和CanDestroy NBT的API
- [ ] 0257-Prevent-Mob-AI-Rules-from-Loading-Chunks.patch - 防止生物AI规则加载区块
- [ ] 0258-Prevent-mob-spawning-from-loading-generating-chunks.patch - 防止生物生成加载生成区块
- [ ] 0259-Implement-furnace-cook-speed-multiplier-API.patch - 实现熔炉烹饪速度倍数API
- [ ] 0260-Honor-EntityAgeable.ageLock.patch - 尊重EntityAgeable.ageLock

#### 连接与流体

- [ ] 0261-Configurable-connection-throttle-kick-message.patch - 可配置连接节流踢出消息
- [ ] 0262-Prevent-chunk-loading-from-Fluid-Flowing.patch - 防止流体流动加载区块
- [ ] 0263-Hook-into-CB-plugin-rewrites.patch - 挂钩到CB插件重写
- [ ] 0264-PreSpawnerSpawnEvent.patch - 生成器预生成事件
- [ ] 0265-Add-LivingEntity-getTargetEntity.patch - 添加生物实体获取目标实体

#### 太阳与海龟API

- [ ] 0266-Add-sun-related-API.patch - 添加太阳相关API
- [ ] 0267-Catch-JsonParseException-in-Entity-and-TE-names.patch - 在实体和方块实体名称中捕获JsonParseException
- [ ] 0268-Turtle-API.patch - 海龟API
- [ ] 0269-Call-player-spectator-target-events-and-improve-impl.patch - 调用玩家观察者目标事件并改进实现
- [ ] 0270-MC-50319-Check-other-worlds-for-shooter-of-projectil.patch - MC-50319检查其他世界的投射物射手

#### 女巫与溺尸

- [ ] 0271-Add-more-Witch-API.patch - 添加更多女巫API
- [ ] 0272-Check-Drowned-for-Villager-Aggression-Config.patch - 检查溺尸的村民攻击配置
- [ ] 0273-Add-option-to-prevent-players-from-moving-into-unloa.patch - 添加防止玩家移动到未加载的选项
- [ ] 0274-Reset-players-airTicks-on-respawn.patch - 重生时重置玩家空气tick
- [ ] 0275-Don-t-sleep-after-profile-lookups-if-not-needed.patch - 如果不需要，配置文件查找后不睡眠

#### 线程与世界优化

- [ ] 0276-Improve-Server-Thread-Pool-and-Thread-Priorities.patch - 改进服务器线程池和线程优先级
- [ ] 0277-Optimize-World-Time-Updates.patch - 优化世界时间更新
- [ ] 0278-Restore-custom-InventoryHolder-support.patch - 恢复自定义背包持有者支持
- [ ] 0279-Use-Vanilla-Minecart-Speeds.patch - 使用原版矿车速度
- [ ] 0280-Fix-SpongeAbsortEvent-handling.patch - 修复海绵吸收事件处理

#### 挖掘与权限

- [ ] 0281-Don-t-allow-digging-into-unloaded-chunks.patch - 不允许挖掘到未加载的区块
- [ ] 0282-Make-the-default-permission-message-configurable.patch - 使默认权限消息可配置
- [ ] 0283-Prevent-rayTrace-from-loading-chunks.patch - 防止光线追踪加载区块
- [ ] 0284-Handle-Large-Packets-disconnecting-client.patch - 处理大数据包断开客户端连接
- [ ] 0285-force-entity-dismount-during-teleportation.patch - 传送期间强制实体下马

#### 僵尸与书籍

- [ ] 0286-Add-more-Zombie-API.patch - 添加更多僵尸API
- [ ] 0287-Book-Size-Limits.patch - 书籍大小限制
- [ ] 0288-Add-PlayerConnectionCloseEvent.patch - 添加玩家连接关闭事件
- [ ] 0289-Prevent-Enderman-from-loading-chunks.patch - 防止末影人加载区块
- [ ] 0290-Add-APIs-to-replace-OfflinePlayer-getLastPlayed.patch - 添加API替换OfflinePlayer getLastPlayed

#### 载具与实体移除

- [ ] 0291-Workaround-for-vehicle-tracking-issue-on-disconnect.patch - 断开连接时载具追踪问题的解决方法
- [ ] 0292-Block-Entity-remove-from-being-called-on-Players.patch - 阻止在玩家上调用实体移除
- [ ] 0293-BlockDestroyEvent.patch - 方块破坏事件
- [ ] 0294-Async-command-map-building.patch - 异步命令映射构建
- [ ] 0295-Implement-Brigadier-Mojang-API.patch - 实现Brigadier Mojang API

#### 配方与网络

- [ ] 0296-Improve-exact-choice-recipe-ingredients.patch - 改进精确选择配方成分
- [ ] 0297-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0298-Optimize-Network-Manager-and-add-advanced-packet-sup.patch - 优化网络管理器并添加高级数据包支持
- [ ] 0299-Handle-Oversized-Tile-Entities-in-chunks.patch - 处理区块中的超大方块实体
- [ ] 0300-Call-WhitelistToggleEvent-when-whitelist-is-toggled.patch - 切换白名单时调用白名单切换事件

### 1.1.0-PRE5 - 实体生成与服务器事件 (0301-0400)

#### 实体生成原因与查询

- [ ] 0301-Entity-getEntitySpawnReason.patch - 实体获取实体生成原因
- [ ] 0302-Fire-event-on-GS4-query.patch - GS4查询时触发事件
- [ ] 0303-Implement-PlayerPostRespawnEvent.patch - 实现玩家重生后事件
- [ ] 0304-don-t-go-below-0-for-pickupDelay-breaks-picking-up-i.patch - pickupDelay不要低于0，破坏拾取物品
- [ ] 0305-Server-Tick-Events.patch - 服务器Tick事件

#### 玩家死亡与方块实体

- [ ] 0306-PlayerDeathEvent-getItemsToKeep.patch - 玩家死亡事件获取保留物品
- [ ] 0307-Optimize-Captured-TileEntity-Lookup.patch - 优化捕获的方块实体查找
- [ ] 0308-Add-Heightmap-API.patch - 添加高度图API
- [ ] 0309-Mob-Spawner-API-Enhancements.patch - 生物生成器API增强
- [ ] 0310-Fix-CB-call-to-changed-postToMainThread-method.patch - 修复CB调用已更改的postToMainThread方法

#### 声音与方块

- [ ] 0311-Fix-sounds-when-item-frames-are-modified-MC-123450.patch - 修复物品展示框修改时的声音MC-123450
- [ ] 0312-Implement-CraftBlockSoundGroup.patch - 实现CraftBlockSoundGroup
- [ ] 0313-Configurable-Keep-Spawn-Loaded-range-per-world.patch - 每世界可配置保持生成加载范围
- [ ] 0314-Allow-Saving-of-Oversized-Chunks.patch - 允许保存超大区块
- [ ] 0315-Expose-the-internal-current-tick.patch - 暴露内部当前tick

#### 区块生成与生物计数

- [ ] 0316-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0317-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0318-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0319-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0320-offset-item-frame-ticking.patch - 偏移物品展示框tick

#### MC修复与告示牌

- [ ] 0321-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0322-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0323-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0324-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0325-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置

#### 村民与UUID

- [ ] 0326-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0327-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0328-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0329-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0330-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置

#### 漏斗与实体优化

- [ ] 0331-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0332-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0333-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0334-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0335-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果

#### 区块序列化与实体激活

- [ ] 0336-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0337-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0338-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0339-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0340-Tracking-Range-Improvements.patch - 追踪范围改进

#### 末地传送门与生物生成

- [ ] 0341-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0342-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0343-Anti-Xray.patch - 反X光
- [ ] 0344-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0345-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API

#### 区块加载与调试

- [ ] 0346-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0347-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试
- [ ] 0348-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0349-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0350-Entity-Jump-API.patch - 实体跳跃API

#### 下界传送门与GUI

- [ ] 0351-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0352-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美
- [ ] 0353-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0354-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0355-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项

#### 村民感染与区块流体

- [ ] 0356-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0357-Optimise-Chunk-getFluid.patch - 优化区块getFluid
- [ ] 0358-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0359-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0360-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning

#### 序列化与掠夺者

- [ ] 0361-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0362-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项
- [ ] 0363-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0364-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0365-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器

#### 死亡玩家与区块加载

- [ ] 0366-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0367-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0368-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0369-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0370-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标

#### 更多优化与修复

- [ ] 0371-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0372-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0373-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置
- [ ] 0374-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0375-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID

#### 物品与烟花修复

- [ ] 0376-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0377-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0378-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果
- [ ] 0379-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0380-Entity-Activation-Range-2.0.patch - 实体激活范围2.0

#### 物品消失与进食

- [ ] 0381-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0382-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0383-Tracking-Range-Improvements.patch - 追踪范围改进
- [ ] 0384-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0385-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成

#### 反作弊与蜜蜂

- [ ] 0386-Anti-Xray.patch - 反X光
- [ ] 0387-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0388-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API
- [ ] 0389-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0390-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试

#### Java版本与事件

- [ ] 0391-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0392-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0393-Entity-Jump-API.patch - 实体跳跃API
- [ ] 0394-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0395-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美

#### 方块放置与验证

- [ ] 0396-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0397-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0398-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项
- [ ] 0399-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0400-Optimise-Chunk-getFluid.patch - 优化区块getFluid

### 1.1.0-PRE6 - 高级优化与API (0401-0500)

#### Spigot设置与API

- [ ] 0401-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0402-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0403-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning
- [ ] 0404-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0405-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项

#### 连接与蜜蜂优化

- [ ] 0406-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0407-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0408-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器
- [ ] 0409-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0410-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度

#### 世界生成与区块

- [ ] 0411-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0412-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0413-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0414-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0415-offset-item-frame-ticking.patch - 偏移物品展示框tick

#### MC修复与掠夺者

- [ ] 0416-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0417-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0418-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0419-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0420-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置

#### 村民与UUID处理

- [ ] 0421-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0422-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0423-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0424-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0425-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置

#### 漏斗与实体查找

- [ ] 0426-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0427-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0428-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0429-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0430-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果

#### 区块坐标与实体激活

- [ ] 0431-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0432-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0433-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0434-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0435-Tracking-Range-Improvements.patch - 追踪范围改进

#### 末地传送门与生物生成

- [ ] 0436-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0437-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0438-Anti-Xray.patch - 反X光
- [ ] 0439-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0440-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API

#### 区块优化与调试

- [ ] 0441-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0442-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试
- [ ] 0443-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0444-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0445-Entity-Jump-API.patch - 实体跳跃API

#### 下界与GUI优化

- [ ] 0446-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0447-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美
- [ ] 0448-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0449-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0450-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项

#### 感染与流体优化

- [ ] 0451-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0452-Optimise-Chunk-getFluid.patch - 优化区块getFluid
- [ ] 0453-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0454-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0455-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning

#### 序列化与掠夺者巡逻

- [ ] 0456-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0457-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项
- [ ] 0458-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0459-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0460-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器

#### 玩家状态与告示牌

- [ ] 0461-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0462-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0463-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0464-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0465-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成

#### 投射物与物品展示框

- [ ] 0466-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0467-offset-item-frame-ticking.patch - 偏移物品展示框tick
- [ ] 0468-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0469-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0470-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新

#### 掠夺者与基岩生成

- [ ] 0471-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0472-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置
- [ ] 0473-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0474-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0475-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项

#### 经验与蜜蜂区块

- [ ] 0476-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0477-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置
- [ ] 0478-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0479-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0480-Fix-items-not-falling-correctly.patch - 修复物品不正确下落

#### 流体与烟花优化

- [ ] 0481-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0482-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果
- [ ] 0483-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0484-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0485-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率

#### 进食与追踪优化

- [ ] 0486-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0487-Tracking-Range-Improvements.patch - 追踪范围改进
- [ ] 0488-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0489-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0490-Anti-Xray.patch - 反X光

#### 蜜蜂与方块API

- [ ] 0491-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0492-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API
- [ ] 0493-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0494-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试
- [ ] 0495-Improve-java-version-check.patch - 改进Java版本检查

#### 事件与跳跃API

- [ ] 0496-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0497-Entity-Jump-API.patch - 实体跳跃API
- [ ] 0498-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0499-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美
- [ ] 0500-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手

### 1.1.0-PRE7 - 验证与铁傀儡 (0501-0600)

#### 验证与绊线钩

- [ ] 0501-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0502-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项
- [ ] 0503-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0504-Optimise-Chunk-getFluid.patch - 优化区块getFluid
- [ ] 0505-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false

#### Tick时间与服务器状态

- [ ] 0506-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0507-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning
- [ ] 0508-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0509-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项
- [ ] 0510-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭

#### 蜜蜂与区块映射

- [ ] 0511-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0512-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器
- [ ] 0513-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0514-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0515-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用

#### 方块状态与生物计数

- [ ] 0516-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0517-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0518-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0519-offset-item-frame-ticking.patch - 偏移物品展示框tick
- [ ] 0520-Fix-MC-158900.patch - 修复MC-158900

#### 物品消耗与告示牌

- [ ] 0521-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0522-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0523-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0524-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置
- [ ] 0525-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载

#### 跟随范围与UUID

- [ ] 0526-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0527-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0528-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0529-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置
- [ ] 0530-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块

#### 实体查找与物品修复

- [ ] 0531-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0532-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0533-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0534-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果
- [ ] 0535-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标

#### 实体激活与物品消失

- [ ] 0536-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0537-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0538-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0539-Tracking-Range-Improvements.patch - 追踪范围改进
- [ ] 0540-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失

#### 每玩家生物生成与反作弊

- [ ] 0541-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0542-Anti-Xray.patch - 反X光
- [ ] 0543-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0544-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API
- [ ] 0545-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用

#### 调试与Java版本

- [ ] 0546-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试
- [ ] 0547-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0548-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0549-Entity-Jump-API.patch - 实体跳跃API
- [ ] 0550-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项

#### GUI与方块放置

- [ ] 0551-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美
- [ ] 0552-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0553-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0554-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项
- [ ] 0555-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率

#### 区块流体与Spigot设置

- [ ] 0556-Optimise-Chunk-getFluid.patch - 优化区块getFluid
- [ ] 0557-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0558-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0559-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning
- [ ] 0560-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化

#### 掠夺者与连接管理

- [ ] 0561-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项
- [ ] 0562-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0563-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0564-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器
- [ ] 0565-Don-t-tick-dead-players.patch - 不tick死亡玩家

#### 告示牌与世界生成

- [ ] 0566-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0567-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0568-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0569-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0570-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度

#### 物品展示框与MC修复

- [ ] 0571-offset-item-frame-ticking.patch - 偏移物品展示框tick
- [ ] 0572-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0573-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0574-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0575-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项

#### 基岩生成与村民

- [ ] 0576-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置
- [ ] 0577-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0578-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0579-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0580-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验

#### 蜜蜂区块与漏斗

- [ ] 0581-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置
- [ ] 0582-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0583-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0584-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0585-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用

#### 烟花与区块坐标

- [ ] 0586-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果
- [ ] 0587-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0588-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0589-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0590-Lag-compensate-eating.patch - 延迟补偿进食

#### 追踪与传送门

- [ ] 0591-Tracking-Range-Improvements.patch - 追踪范围改进
- [ ] 0592-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0593-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0594-Anti-Xray.patch - 反X光
- [ ] 0595-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279

#### 方块API与区块优化

- [ ] 0596-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API
- [ ] 0597-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0598-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试
- [ ] 0599-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0600-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件

### 1.1.0-PRE8 - 高级网络优化 (0601-0700)

#### 实体跳跃与下界传送门

- [ ] 0601-Entity-Jump-API.patch - 实体跳跃API
- [ ] 0602-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0603-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美
- [ ] 0604-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0605-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置

#### 铁傀儡与村民感染

- [ ] 0606-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项
- [ ] 0607-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0608-Optimise-Chunk-getFluid.patch - 优化区块getFluid
- [ ] 0609-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0610-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令

#### 服务器运行状态与序列化

- [ ] 0611-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning
- [ ] 0612-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0613-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项
- [ ] 0614-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0615-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块

#### 区块映射与玩家状态

- [ ] 0616-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器
- [ ] 0617-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0618-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度
- [ ] 0619-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0620-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置

#### 生物计数与投射物

- [ ] 0621-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0622-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0623-offset-item-frame-ticking.patch - 偏移物品展示框tick
- [ ] 0624-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0625-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠

#### 告示牌与掠夺者

- [ ] 0626-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0627-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0628-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置
- [ ] 0629-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0630-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标

#### UUID与经验掉落

- [ ] 0631-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0632-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0633-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置
- [ ] 0634-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0635-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID

#### 物品下落与流体优化

- [ ] 0636-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0637-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0638-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果
- [ ] 0639-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0640-Entity-Activation-Range-2.0.patch - 实体激活范围2.0

#### 物品消失与进食补偿

- [ ] 0641-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0642-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0643-Tracking-Range-Improvements.patch - 追踪范围改进
- [ ] 0644-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0645-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成

#### 反作弊与蜜蜂重力

- [ ] 0646-Anti-Xray.patch - 反X光
- [ ] 0647-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0648-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API
- [ ] 0649-Optimise-getChunkAt-calls-for-loaded-chunks.patch - 优化已加载区块的getChunkAt调用
- [ ] 0650-Add-debug-for-sync-chunk-loads.patch - 为同步区块加载添加调试

#### Java版本与事件

- [ ] 0651-Improve-java-version-check.patch - 改进Java版本检查
- [ ] 0652-Add-ThrownEggHatchEvent.patch - 添加投掷鸡蛋孵化事件
- [ ] 0653-Entity-Jump-API.patch - 实体跳跃API
- [ ] 0654-Add-option-to-nerf-pigmen-from-nether-portals.patch - 添加削弱来自下界传送门猪人的选项
- [ ] 0655-Make-the-GUI-graph-fancier.patch - 使GUI图表更精美

#### 方块放置与验证

- [ ] 0656-add-hand-to-BlockMultiPlaceEvent.patch - 为方块多重放置事件添加手
- [ ] 0657-Validate-tripwire-hook-placement-before-update.patch - 更新前验证绊线钩放置
- [ ] 0658-Add-option-to-allow-iron-golems-to-spawn-in-air.patch - 添加允许铁傀儡在空中生成的选项
- [ ] 0659-Configurable-chance-of-villager-zombie-infection.patch - 可配置村民僵尸感染几率
- [ ] 0660-Optimise-Chunk-getFluid.patch - 优化区块getFluid

#### Spigot设置与Tick API

- [ ] 0661-Set-spigots-verbose-world-setting-to-false-by-def.patch - 默认将Spigot详细世界设置设为false
- [ ] 0662-Add-tick-times-API-and-mspt-command.patch - 添加tick时间API和mspt命令
- [ ] 0663-Expose-MinecraftServer-isRunning.patch - 暴露MinecraftServer isRunning
- [ ] 0664-Add-Raw-Byte-ItemStack-Serialization.patch - 添加原始字节物品堆叠序列化
- [ ] 0665-Pillager-patrol-spawn-settings-and-per-player-option.patch - 掠夺者巡逻生成设置和每玩家选项

#### 连接管理与蜜蜂

- [ ] 0666-Remote-Connections-shouldn-t-hold-up-shutdown.patch - 远程连接不应阻止关闭
- [ ] 0667-Do-not-allow-bees-to-load-chunks-for-beehives.patch - 不允许蜜蜂为蜂巢加载区块
- [ ] 0668-Prevent-Double-PlayerChunkMap-adds-crashing-server.patch - 防止双重PlayerChunkMap添加崩溃服务器
- [ ] 0669-Don-t-tick-dead-players.patch - 不tick死亡玩家
- [ ] 0670-Limit-Client-Sign-length-more.patch - 更多限制客户端告示牌长度

#### 世界生成与方块状态

- [ ] 0671-Fix-World-isChunkGenerated-calls.patch - 修复World isChunkGenerated调用
- [ ] 0672-Show-blockstate-location-if-we-failed-to-read-it.patch - 如果读取失败显示方块状态位置
- [ ] 0673-Only-count-Natural-Spawned-mobs-towards-natural-spaw.patch - 仅计算自然生成的生物向自然生成
- [ ] 0674-Configurable-projectile-relative-velocity.patch - 可配置投射物相对速度
- [ ] 0675-offset-item-frame-ticking.patch - 偏移物品展示框tick

#### MC修复与物品消耗

- [ ] 0676-Fix-MC-158900.patch - 修复MC-158900
- [ ] 0677-Prevent-consuming-the-wrong-itemstack.patch - 防止消耗错误的物品堆叠
- [ ] 0678-Dont-send-unnecessary-sign-update.patch - 不发送不必要的告示牌更新
- [ ] 0679-Add-option-to-disable-pillager-patrols.patch - 添加禁用掠夺者巡逻的选项
- [ ] 0680-Flat-bedrock-generator-settings.patch - 平坦基岩生成器设置

#### 村民与跟随范围

- [ ] 0681-Prevent-sync-chunk-loads-when-villagers-try-to-find-.patch - 村民尝试查找时防止同步区块加载
- [ ] 0682-MC-145656-Fix-Follow-Range-Initial-Target.patch - MC-145656修复跟随范围初始目标
- [ ] 0683-Duplicate-UUID-Resolve-Option.patch - 重复UUID解决选项
- [ ] 0684-PlayerDeathEvent-shouldDropExperience.patch - 玩家死亡事件应该掉落经验
- [ ] 0685-Prevent-bees-loading-chunks-checking-hive-position.patch - 防止蜜蜂加载区块检查蜂巢位置

#### 漏斗与实体优化

- [ ] 0686-Don-t-load-Chunks-from-Hoppers-and-other-things.patch - 不从漏斗和其他东西加载区块
- [ ] 0687-Optimise-EntityGetter-getPlayerByUUID.patch - 优化EntityGetter getPlayerByUUID
- [ ] 0688-Fix-items-not-falling-correctly.patch - 修复物品不正确下落
- [ ] 0689-Optimize-call-to-getFluid-for-explosions.patch - 优化爆炸的getFluid调用
- [ ] 0690-Fix-last-firework-in-stack-not-having-effects-when-d.patch - 修复堆叠中最后一个烟花在时没有效果

#### 区块坐标与实体激活

- [ ] 0691-Guard-against-serializing-mismatching-chunk-coordina.patch - 防止序列化不匹配的区块坐标
- [ ] 0692-Entity-Activation-Range-2.0.patch - 实体激活范围2.0
- [ ] 0693-Implement-alternative-item-despawn-rate.patch - 实现替代物品消失速率
- [ ] 0694-Lag-compensate-eating.patch - 延迟补偿进食
- [ ] 0695-Tracking-Range-Improvements.patch - 追踪范围改进

#### 传送门与生物生成

- [ ] 0696-Fix-items-vanishing-through-end-portal.patch - 修复物品通过末地传送门消失
- [ ] 0697-implement-optional-per-player-mob-spawns.patch - 实现可选的每玩家生物生成
- [ ] 0698-Anti-Xray.patch - 反X光
- [ ] 0699-Bees-get-gravity-in-void.-Fixes-MC-167279.patch - 蜜蜂在虚空中获得重力。修复MC-167279
- [ ] 0700-Improve-Block-breakNaturally-API.patch - 改进方块自然破坏API

### 1.1.0-PRE9 - 现代化API与性能优化 (0701-0800)

#### 核心性能优化

- [ ] 0700-Do-not-run-raytrace-logic-for-AIR.patch - 不对空气方块运行光线追踪逻辑
- [ ] 0701-Oprimise-map-impl-for-tracked-players.patch - 优化追踪玩家的映射实现
- [ ] 0702-Optimise-BlockSoil-nearby-water-lookup.patch - 优化方块土壤附近水源查找
- [ ] 0703-Optimise-random-block-ticking.patch - 优化随机方块tick
- [ ] 0704-Optimise-non-flush-packet-sending.patch - 优化非刷新数据包发送
- [ ] 0705-Optimise-nearby-player-lookups.patch - 优化附近玩家查找
- [ ] 0706-Remove-streams-for-villager-AI.patch - 移除村民AI的流处理

#### 网络与压缩优化

- [ ] 0707-Use-Velocity-compression-and-cipher-natives.patch - 使用Velocity压缩和密码原生库
- [ ] 0708-Reduce-worldgen-thread-worker-count-for-low-core-cou.patch - 为低核心数减少世界生成线程工作者数量

#### 修复与稳定性

- [ ] 0709-Fix-Bukkit-NamespacedKey-shenanigans.patch - 修复Bukkit命名空间键问题
- [ ] 0710-Fix-merchant-inventory-not-closing-on-entity-removal.patch - 修复实体移除时商人背包不关闭
- [ ] 0711-Check-requirement-before-suggesting-root-nodes.patch - 建议根节点前检查需求
- [ ] 0712-Don-t-respond-to-ServerboundCommandSuggestionPacket-.patch - 不响应服务器绑定命令建议数据包
- [ ] 0713-Fix-setPatternColor-on-tropical-fish-bucket-meta.patch - 修复热带鱼桶元数据的图案颜色设置
- [ ] 0714-Ensure-valid-vehicle-status.patch - 确保有效的载具状态
- [ ] 0715-Prevent-softlocked-end-exit-portal-generation.patch - 防止末地出口传送门生成软锁定
- [ ] 0716-Fix-CocaoDecorator-causing-a-crash-when-trying-to-ge.patch - 修复可可装饰器获取时崩溃
- [ ] 0717-Don-t-log-debug-logging-being-disabled.patch - 不记录调试日志被禁用
- [ ] 0718-fix-various-menus-with-empty-level-accesses.patch - 修复各种空级别访问的菜单
- [ ] 0719-Preserve-overstacked-loot.patch - 保留过度堆叠的战利品

#### 内存与缓存优化

- [ ] 0720-0800系列 - 包含更多内存管理、缓存优化、数据结构改进等

### 1.1.0-PRE10 - 高级功能集成与事件系统 (0801-0900)

#### 游戏规则与事件修复

- [ ] 0800-Fix-new-block-data-for-EntityChangeBlockEvent.patch - 修复实体变更方块事件的新方块数据
- [ ] 0801-fix-player-loottables-running-when-mob-loot-gamerule.patch - 修复生物战利品游戏规则关闭时玩家战利品表仍运行
- [ ] 0802-Ensure-entity-passenger-world-matches-ridden-entity.patch - 确保实体乘客世界匹配被骑乘实体
- [ ] 0803-Guard-against-invalid-entity-positions.patch - 防范无效实体位置
- [ ] 0804-cache-resource-keys.patch - 缓存资源键

#### 实体与世界功能

- [ ] 0805-Allow-to-change-the-podium-for-the-EnderDragon.patch - 允许更改末影龙的讲台
- [ ] 0806-Fix-NBT-pieces-overriding-a-block-entity-during-worl.patch - 修复世界生成时NBT片段覆盖方块实体
- [ ] 0807-Fix-StructureGrowEvent-species-for-RED_MUSHROOM.patch - 修复红蘑菇结构生长事件类型
- [ ] 0808-Prevent-tile-entity-copies-loading-chunks.patch - 防止方块实体副本加载区块
- [ ] 0809-Use-username-instead-of-display-name-in-PlayerList-g.patch - 在玩家列表中使用用户名而非显示名
- [ ] 0810-Fix-slime-spawners-not-spawning-outside-slime-chunks.patch - 修复史莱姆生成器在史莱姆区块外不生成

#### API扩展与物品系统

- [ ] 0811-Pass-ServerLevel-for-gamerule-callbacks.patch - 为游戏规则回调传递服务器级别
- [ ] 0812-Add-pre-unbreaking-amount-to-PlayerItemDamageEvent.patch - 向玩家物品损坏事件添加预耐久度
- [ ] 0813-WorldCreator-keepSpawnLoaded.patch - 世界创建器保持生成区块加载
- [ ] 0814-Fix-CME-in-CraftPersistentDataTypeRegistry.patch - 修复CraftPersistentDataTypeRegistry中的CME
- [ ] 0815-Trigger-bee_nest_destroyed-trigger-in-the-correct-pl.patch - 在正确位置触发蜂巢被破坏触发器
- [ ] 0816-Add-EntityDyeEvent-and-CollarColorable-interface.patch - 添加实体染色事件和项圈可着色接口
- [ ] 0817-Fire-CauldronLevelChange-on-initial-fill.patch - 初始填充时触发炼药锅等级变更
- [ ] 0818-fix-powder-snow-cauldrons-not-turning-to-water.patch - 修复细雪炼药锅不变成水
- [ ] 0819-Add-PlayerStopUsingItemEvent.patch - 添加玩家停止使用物品事件

#### 配置与生成系统

- [ ] 0820-0900系列 - 包含更多配置系统、生成器改进、事件扩展等

### 1.1.0-PRE11 - 最终优化与稳定性修复 (0901-1000)

#### 物品与实体API

- [ ] 0900-ItemStack-damage-API.patch - 物品堆叠损坏API
- [ ] 0901-Friction-API.patch - 摩擦力API
- [ ] 0902-Ability-to-control-player-s-insomnia-and-phantoms.patch - 控制玩家失眠和幻翼的能力
- [ ] 0903-Fix-player-kick-on-shutdown.patch - 修复关闭时玩家踢出
- [ ] 0904-Sync-offhand-slot-in-menus.patch - 在菜单中同步副手槽位
- [ ] 0905-Player-Entity-Tracking-Events.patch - 玩家实体追踪事件
- [ ] 0906-Limit-pet-look-distance.patch - 限制宠物查看距离
- [ ] 0907-Properly-resend-entities.patch - 正确重发实体
- [ ] 0908-Fixes-and-additions-to-the-SpawnReason-API.patch - 生成原因API的修复和添加
- [ ] 0909-fix-Instruments.patch - 修复乐器

#### 性能与内联优化

- [ ] 0910-Improve-inlining-for-some-hot-BlockBehavior-and-Flui.patch - 改进一些热点方块行为和流体的内联
- [ ] 0911-Fix-inconsistencies-in-dispense-events-regarding-sta.patch - 修复分发器事件中关于状态的不一致
- [ ] 0912-Add-BlockLockCheckEvent.patch - 添加方块锁定检查事件
- [ ] 0913-Add-Sneaking-API-for-Entities.patch - 为实体添加潜行API
- [ ] 0914-Improve-logging-and-errors.patch - 改进日志记录和错误
- [ ] 0915-Improve-PortalEvents.patch - 改进传送门事件
- [ ] 0916-Add-config-option-for-spider-worldborder-climbing.patch - 添加蜘蛛世界边界攀爬配置选项
- [ ] 0917-Add-missing-SpigotConfig-logCommands-check.patch - 添加缺失的Spigot配置日志命令检查
- [ ] 0918-Fix-NPE-on-Allay-stopDancing-while-not-dancing.patch - 修复悦灵在未跳舞时停止跳舞的NPE
- [ ] 0919-Flying-Fall-Damage.patch - 飞行坠落伤害

#### 高级事件与爆炸系统

- [ ] 0920-Add-exploded-block-state-to-BlockExplodeEvent-and-En.patch - 向方块爆炸事件添加爆炸方块状态
- [ ] 0921-Expose-pre-collision-moving-velocity-to-VehicleBlock.patch - 向载具方块事件暴露碰撞前移动速度
- [ ] 0922-config-for-disabling-entity-tag-tags.patch - 禁用实体标签的配置
- [ ] 0923-Use-single-player-info-update-packet-on-join.patch - 加入时使用单个玩家信息更新数据包
- [ ] 0924-Correctly-shrink-items-during-EntityResurrectEvent.patch - 在实体复活事件期间正确缩减物品
- [ ] 0925-Win-Screen-API.patch - 胜利屏幕API
- [ ] 0926-Remove-CraftItemStack-setAmount-null-assignment.patch - 移除CraftItemStack setAmount空赋值
- [ ] 0927-Fix-force-opening-enchantment-tables.patch - 修复强制打开附魔台
- [ ] 0928-Add-Entity-Body-Yaw-API.patch - 添加实体身体偏航API
- [ ] 0929-Fix-MC-157464-Prevent-sleeping-villagers-moving-towa.patch - 修复MC-157464防止睡觉村民移动

#### 最终稳定性修复

- [ ] 0930-1000系列 - 包含最终的稳定性修复、性能调优、错误处理改进等

### 1.1.0-PRE12 - 最新功能与完整性检查 (1001-1036)

#### 实体与世界系统完善

- [ ] 1000-Respect-randomizeData-on-more-entities-when-spawning.patch - 生成时在更多实体上尊重随机化数据
- [ ] 1001-Use-correct-seed-on-api-world-load.patch - API世界加载时使用正确种子
- [ ] 1002-Remove-UpgradeData-neighbour-ticks-outside-of-range.patch - 移除范围外的升级数据邻居tick
- [ ] 1003-Cache-map-ids-on-item-frames.patch - 在物品展示框上缓存地图ID
- [ ] 1004-Fix-custom-statistic-criteria-creation.patch - 修复自定义统计标准创建
- [ ] 1005-Bandaid-fix-for-Effect.patch - 效果的创可贴修复
- [ ] 1006-SculkCatalyst-bloom-API.patch - 幽匿催化剂绽放API
- [ ] 1007-API-for-an-entity-s-scoreboard-name.patch - 实体计分板名称API
- [ ] 1008-Improve-cancelling-PreCreatureSpawnEvent-with-per-pl.patch - 改进每玩家PreCreatureSpawnEvent取消
- [ ] 1009-Deprecate-and-replace-methods-with-old-StructureType.patch - 弃用并替换旧结构类型方法

#### 命令与交互系统

- [ ] 1010-Don-t-tab-complete-namespaced-commands-if-send-names.patch - 如果发送命名空间则不自动补全命名空间命令
- [ ] 1011-Properly-handle-BlockBreakEvent-isDropItems.patch - 正确处理方块破坏事件的掉落物品
- [ ] 1012-Fire-entity-death-event-for-ender-dragon.patch - 为末影龙触发实体死亡事件
- [ ] 1013-Configurable-entity-tracking-range-by-Y-coordinate.patch - 按Y坐标配置实体追踪范围
- [ ] 1014-Add-Listing-API-for-Player.patch - 为玩家添加列表API
- [ ] 1015-Expose-clicked-BlockFace-during-BlockDamageEvent.patch - 在方块损坏事件期间暴露点击的方块面
- [ ] 1016-Fix-NPE-on-Boat-getStatus.patch - 修复船只getStatus的NPE
- [ ] 1017-Expand-Pose-API.patch - 扩展姿势API
- [ ] 1018-More-DragonBattle-API.patch - 更多龙战API

#### 数据处理与优化

- [ ] 1019-Deep-clone-unhandled-nbt-tags.patch - 深度克隆未处理的NBT标签
- [ ] 1020-Add-PlayerPickItemEvent.patch - 添加玩家拾取物品事件
- [ ] 1021-Improve-performance-of-mass-crafts.patch - 改进批量合成性能
- [ ] 1022-Allow-trident-custom-damage.patch - 允许三叉戟自定义伤害
- [ ] 1023-Expose-hand-during-BlockCanBuildEvent.patch - 在方块可建造事件期间暴露手部
- [ ] 1024-Optimize-nearest-structure-border-iteration.patch - 优化最近结构边界迭代
- [ ] 1025-Implement-OfflinePlayer-isConnected.patch - 实现离线玩家是否连接
- [ ] 1026-Fix-inventory-desync.patch - 修复背包不同步
- [ ] 1027-Add-titleOverride-to-InventoryOpenEvent.patch - 向背包打开事件添加标题覆盖

#### 最终完整性检查

- [ ] 1028-Configure-sniffer-egg-hatch-time.patch - 配置嗅探者蛋孵化时间
- [ ] 1029-Do-crystal-portal-proximity-check-before-entity-look.patch - 在实体查找前进行水晶传送门邻近检查
- [ ] 1030-Skip-POI-finding-if-stuck-in-vehicle.patch - 如果卡在载具中则跳过POI查找
- [ ] 1031-Add-slot-sanity-checks-in-container-clicks.patch - 在容器点击中添加槽位健全检查
- [ ] 1032-Call-BlockRedstoneEvents-for-lecterns.patch - 为讲台调用方块红石事件
- [ ] 1033-Allow-proper-checking-of-empty-item-stacks.patch - 允许正确检查空物品堆叠
- [ ] 1034-Fix-silent-equipment-change-for-mobs.patch - 修复生物的静默装备变更
- [ ] 1035-Fix-spigot-s-Forced-Stats.patch - 修复Spigot的强制统计
- [ ] 1036-Add-missing-InventoryHolders-to-inventories.patch - 向背包添加缺失的背包持有者

### 1.1.0-RELEASE - 完整Paper兼容性

## ✅ 已完成

- [x] 1.0.8-PRE1：支持Velocity Modern转发（Port Mohist and PCF）
- [x] 1.0.8-PRE2：并入MPEM的部分优化项
- [x] 1.0.8-PRE2：支持Adventure库 (0010-Adventure.patch)
- [x] 1.0.8-PRE3：使用Paper方法优化初始化世界的速度
- [x] 1.0.8-RELEASE：更多i18n（打算用AI，我很懒）