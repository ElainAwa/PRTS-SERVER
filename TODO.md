# 📋 Luminara Paper优化集成计划

## 📝 当前版本待办事项

- [ ] 1.0.8-RELEASE：更多i18n（打算用AI，我很懒）

## 🚀 Paper优化
- [ ] 集成Starlight光照引擎 (0016-Starlight.patch)
- [ ] 集成新的chunk加载系统 (0019-Rewrite-chunk-system.patch)
- [ ] 集成Entity Activation Range 2.0 (0337-Entity-Activation-Range-2.0.patch)
- [ ] 优化光照计算算法 (0016-Starlight.patch相关)
- [ ] 实现异步光照更新 (0016-Starlight.patch相关)
- [ ] 实现并行chunk处理 (0019-Rewrite-chunk-system.patch相关)
- [ ] 实现数据包压缩优化 (0298-Optimize-Network-Manager.patch)
- [ ] 添加数据包限制器 (0694-Add-packet-limiter-config.patch)
- [ ] 集成Velocity压缩和加密 (0707-Use-Velocity-compression-and-cipher-natives.patch)
- [ ] 实现连接节流优化 (0108-Configurable-packet-in-spam-threshold.patch)
- [ ] 添加Proxy Protocol支持 (0823-Add-support-for-Proxy-Protocol.patch)
- [ ] 优化网络数据包处理 (0298-Optimize-Network-Manager.patch)
- [ ] 实现异步网络处理 (0104-Avoid-blocking-on-Network-Manager-creation.patch)


- [ ] 优化HashMapPalette (0737-Optimize-HashMapPalette.patch)
- [ ] 实现更高效的数据容器 (0087-Optimize-DataBits.patch)
- [ ] 优化BlockPosition缓存 (0690-Manually-inline-methods-in-BlockPosition.patch)
- [ ] 集成ConcurrentHashMap优化 (0238-Use-ConcurrentHashMap-in-JsonList.patch)
- [ ] 添加内存池机制 (自定义实现)
- [ ] 优化对象创建和销毁 (0075-Use-a-Shared-Random-for-Entities.patch)
- [ ] 优化实体数据管理器 (0982-Array-backed-synched-entity-data.patch)
- [ ] 实现缓存优化 (0772-Use-a-CHM-for-StructureTemplate.Pallete-cache.patch)


- [ ] 优化寻路算法 (0081-EntityPathfindEvent.patch)
- [ ] 集成村民AI优化 (0706-Remove-streams-for-villager-AI.patch)
- [ ] 优化地形生成算法 (0325-Flat-bedrock-generator-settings.patch)
- [ ] 实现结构生成缓存 (0774-Add-missing-structure-set-seed-configs.patch)
- [ ] 添加生物群系优化 (0758-Expose-vanilla-BiomeProvider-from-WorldInfo.patch)
- [ ] 集成世界边界优化 (0118-Bound-Treasure-Maps-to-World-Border.patch)
- [ ] 优化爆炸计算 (0040-Optimize-explosions.patch)
- [ ] 优化碰撞检测 (0739-Highly-optimise-single-and-multi-AABB-VoxelShapes-an.patch)


- [ ] 集成Paper配置文件系统集成到arclight.conf (0005-Paper-config-files.patch)
- [ ] 添加动态配置重载 (0067-Allow-Reloading-of-Custom-Permissions.patch)
- [ ] 添加更多可配置选项 (0020-0100系列多个patch)


- [ ] 添加更多实体API (0033-Entity-Origin-API.patch, 0079-Entity-AddTo-RemoveFrom-World-Events.patch)
- [ ] 实现高级世界API (0071-Add-World-Util-Methods.patch, 0215-Implement-World.getEntity-UUID-API.patch)
- [ ] 集成玩家数据API (0141-Basic-PlayerProfile-API.patch, 0182-Player.setPlayerProfile-API.patch)
- [ ] 优化事件调用性能 (0078-Only-process-BlockPhysicsEvent-if-a-plugin-has-a-lis.patch)
- [ ] 实现事件优先级优化 (自定义实现)
- [ ] 集成自定义事件系统 (0045-0200系列多个事件patch)
- [ ] 添加Brigadier API支持 (0295-Implement-Brigadier-Mojang-API.patch)


- [ ] 修复chunk加载相关bug (0064-Chunk-Save-Reattempt.patch, 0316-Fix-World-isChunkGenerated-calls.patch)
- [ ] 实现自动恢复功能 (0131-Properly-handle-async-calls-to-restart-the-server.patch)
- [ ] 优化错误处理 (0679-Improve-and-expand-AsyncCatcher.patch)
- [ ] 修复数据同步问题 (0785-Fix-Entity-Position-Desync.patch)

## ✅ 已完成

- [x] 1.0.8-PRE1：支持Velocity Modern转发（Port Mohist and PCF）
- [x] 1.0.8-PRE2：并入MPEM的部分优化项
- [x] 1.0.8-PRE2：支持Adventure库
- [x] 1.0.8-PRE3：使用Paper方法优化初始化世界的速度