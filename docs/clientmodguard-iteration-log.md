# ClientModGuard 迭代史与判据实证

> 本文件承接原代码头部的大段版本注释。**代码内注释一律 ≤2 行**（见铁律）。
> 任何改判据/加信号/改隔离动作后，务必同步 bump `SCAN_ALGO_VERSION` 并补一条本记录。
> 1.20.1 源码：`arclight-forge/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java`（当前 v22 真·总开关，随 v1.0.54 发版）。

## 核心判定哲学（v12 确立，全程不改）
「会不会让服务端崩」≠「是不是客户端模组」。实测 209 个正常运行的模组里 81% 含客户端代码，v11 把二者混为一谈导致既误杀又漏检。证据不足一律 AMBER 保留+观察+报告（无罪推定），只有 L1 硬证据（黑名单/类名指纹/mods.toml 自声明 CLIENT/中毒 mixin）才自动隔离。历史 5 次误删全部源于「证据不足即隔离」。

## 版本迭代
- **v7** 依赖一致性闭包 + common mixin 精确化。
- **v8** anyPoison 毒 mixin 否决。
- **v9** GeneralFeedback/ServerCore 指纹；**v9b** MineMenu/FancyMenu/Konkrete 指纹 + `_guard_precheck.log` 落盘；**v9c** ae2ct 白名单 + mcwifipnp 指纹；**v9d** MaFgLib/Tweakerge/leawind/chat_heads/appleskin 指纹。
- **v10 自愈式 + 状态记忆**：预扫描只做高置信隔离，模糊双端模组交给运行时自愈；Launcher.main try/catch 包 Main_Forge.main，捕获「缺失客户端类」→ 定位 offending mod → 隔离 → 新 JVM 重启（PRTS_GUARD_RETRY，上限 MAX_RESTART）；状态记忆 `_guard_state.json`（quarantined / insisted_failed / scan_cache）；白名单 `clientside-guard.json` allowlist；指纹降级为加速提示；`_guard_precheck.log` 每模组 SIG 明细可审计。
- **v12 P0 判定转向**（基于 283 真实 jar 三方对账）：删除 hasDistGuard/hasKjsPlugin 对 suspect 的豁免（实测安全集 72% > 客户端集 61%，方向反）；DIST_GUARD_MARKERS 剔除裸 Dist/EnvType（@OnlyIn 注解本身写入常量池无区分度）；证据不足→AMBER；trustedModList 否决启发式但不否决硬证据；隔离原子化（实测 3 jar 同时存于 mods/ 与隔离区）；AMBER 信号收敛。
- **v14** 解析 Forge LoadingFailedException 直接点名的失败模组（`parseLoadingFailureNames`）；记录 displayName 反查；`findJarByModIdOrName` 容错。
- **v15 (1.0.42) 三大启动阻断全解决**：① IInventoryBridge 依赖断链连坐隔离；② 崩溃报告点名自愈（handleCrash Path 1.5 + shutdownHeal）；③ 中毒 mixin 预启动静态拦截（`detectPoisonMixin`）。中毒 mixin 根因：模组 mixin 注入原版服务端类却调用客户端类，MixinPreProcessorStandard 对每条 invoke/field 的 owner 调 ClassInfo.forDescriptor，专用服解析不到 → ClassMetadataNotFoundException → MixinTransformerError，发生在 vanilla Main.main 内被吞（不写 crash-report、不点名、Forge 不报），只能启动前静态拦截。五条判据同时满足才隔离（精确率优先），实测测试集 261 模组命中 1（lightspeed）、人工 213 纯服务端集命中 0，部署后整包 Done (2.050s) 零误删。
- **v16 (1.0.45) 真凶而非守护者 + 客户端目标 mixin**：
  - `@Mixin` 目标是客户端类 ≠ 会崩服。Mixin 只在目标类真正加载时才 APPLY；专用服永不加载 `net/minecraft/client/**` → 注入客户端类的 mixin 静默躺平。真凶 betterlockon 目标是 EpicFight 客户端类 `LocalPlayerPatch`（服务端会加载）→ CHECKCAST 客户端类 → InvalidMixinException FATAL。
  - 静态判据仿真「目标∈net/minecraft/client/**」命中 4 全误伤且漏检真凶；「目标∈模组自身/client/包」误伤 13 个正常模组 → v16(a) 静态检测降级为 AMBER 观察上报不隔离。
  - 该崩溃既不冒泡 handleCrash 也不生成 crash-report，特征只在 `logs/latest.log` → `shutdownHeal` 路径 C：扫日志尾部 600 行提取 `*.mixins.json` 配置名 → findJarContainingEntry 反查属主 jar → 隔离 → 重启。配置名即属主，精度最高。
  - Done 闸门（零误伤）：APPLY 失败不必然致命（required=false 只打日志），失败行之后若仍出现 `Done (` 一律不隔离；只向后扫描。边界 5/5 通过。实测 20:08→20:10 Done (11.641s) 零误伤。
- **v17 (1.0.46) 守护者归因**：tcrcore 抛 `IllegalStateException: AAA Particles mod is detected ... Please remove it`，Forge 只报 tcrcore(守护者)。解析崩溃报告归因文本、识别「移除/不兼容」祈使句，隔离被点名者（aaa_particles）、保留守护者。但单点名无家族概念，只删 aaa_particles-forge 漏了姊妹包 aaa_particles_world → 「模组没删干净」。
- **v17b (1.0.47) 家族连坐 + 文件收敛 + 隔离区去双层**：
  - 家族连坐：directHit 归一化 core 作家族前缀，凡同前缀（双向）姊妹包一并隔离；过白名单/L0 覆盖、前缀阈值≥5、多 directHit 歧义保 guardian。只把「错删一个」补成「删全家族」，不新增隔离对象。
  - 关键旁证：只删 aaa_particles 留 aaa_particles_world（其对 aaa_particles 强制依赖 [2.1,)）→ 缺失时 LexForge 加载器（[s.l.Lazyyyyy]，整合包自带）初始化抛 ExceptionInInitializerError → NoClassDefFoundError: IInventoryBridge，不写崩溃报告 → shutdownHeal 无物可解析 → 死循环重启。无 aaa 时直连 Done，证明 IInventoryBridge 仅是缺失依赖下游症状（内嵌 common.jar 含该类，jar 完好）。
  - 文件收敛：`_clientcheck/`（precheck.log/isolation.log/state.json/whitelist.json）；隔离区去双层 `_quarantine/clientside` → `_quarantine`。
- **v17c (1.0.48) 隔离区改名**：`_quarantine` → `_disabled_mods`（用户要求直观）；migrateLegacyFiles 递归迁移旧目录并清理。
- **v17d (1.0.49) 注释收敛 + 逻辑简化**：代码注释全压缩至 ≤2 行（版本史迁至本文件）；抽出 `isUnguardedClient` / `collectMixinConfigs` / `newestCrashReport` 三个 helper 去重（零行为变更）。

## 致命 Java 陷阱（保留为 1-2 行代码警示，勿删）
- 隔离删源失败必须回滚目标，否则复现 mods/ 与隔离区同名共存（实测 CutThrough/gtmoldraw/UniLib）。
- class 解析游标：`c.p += c.u2() * 2` 复合赋值先取旧 c.p 再求值，u2() 已消费 2 字节被抹 → 全程错位 → 静默返回 null。必须 `int n = c.u2(); c.p += n * 2;`。

## 关键实证数据
- 客户端集(74 已知) vs 安全集(209 已知)：hasClient 61%/81%；DIST_GUARD/BROAD_GUARD 61% vs 72%（方向反，当免死金牌造成 61% 漏检）。
- data/(recipes|loot_tables|tags|worldgen|advancements)/ 存在 = 高精度安全信号（安全集 52% vs 客户端集 1%）；kubejs.plugins.txt|classfilter.txt = KubeJS 双端附属。
- 历史误删须永久保留：kubejs-create, taczjs, vintage_kubejs, barrels_2012, ftb_ultimine_indicator, cloth-config/resourcefulconfig/kotlinforforge/rhino/ferritecore/UniLib/yacl。
- 已知必隔离：masa 家族(forgematica/litematica/malilib/tweakeroo/minihud)、mafglib、oculus+iris_shader_folder+supplemental_patches+mekalus、IMBlocker、FancyMenu/konkrete、Sodium 系(0.8+ 包名 net.caffeinemc.mods.sodium)。
