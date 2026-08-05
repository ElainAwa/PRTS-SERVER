# NBT 序列化路径 @Redirect 处理器 static 误用修复（v1.0.62）

> 本文档由 AI 生成，记录本次修复的设计与验证。功能溯源：PRTS 核心 Arclight/Luminara 移植层 NBT 防御 mixin（`StringTagVisitorMixin`），源自 Arclight 上游对 `StringTagVisitor` 的 SNBT 排序防御性修复。

## 背景
`StringTagVisitor` 在 SNBT（字符串化 NBT）序列化时，对 compound 的 key 做排序。当某个 key 对应的 value 为 `null` 时，原版 `Collections.sort` 会抛 NPE。PRTS 在 `856eed8`（2026-08-04，NBT 防御提交）加入 `StringTagVisitorMixin`，用 `@Redirect` 把 `Collections.sort` 重定向到 `arclight$sortKeys`，先 `removeIf(Objects::isNull)` 再排序，避免 NPE。

## 问题
mixin 处理器 `arclight$sortKeys` 被声明为 `private static void`，但 Mixin 0.8.5 的 `Injector.checkTargetModifiers(target, true)` 要求 **`@Redirect` 处理器的 static 修饰符必须与「被混入方法」一致**。被混入的 `StringTagVisitor.visitCompound` 是**实例方法**（非 static），因此处理器必须非 static。声明成 `static` 导致 `exactMatch` 下抛 `InvalidInjectionException: 'static' modifier of handler method does not match target`。

## 触发条件（潜伏回归）
mixin apply 发生在 `StringTagVisitor` 类**首次被类加载**时。它只在 `Tag.toString()` → SNBT 序列化路径被走到才会加载。FTB Library / FTB Ultimine 等模组在启动期保存 SNBT 配置时就会触发。本项目的测服未装 FTB 系列，因此从未触发；其他服主（如装了 FTB 的「猫窝正式环境」）用 `PRTS-1.20.1-1.0.61` 启动即崩。

## 影响版本
- `v1.0.60`（`856eed8` 已随其发布）
- `v1.0.61`（继承）

## 修复
`arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/nbt/StringTagVisitorMixin.java`：
```java
@Redirect(method = "visitCompound",
        at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;)V"))
private void arclight$sortKeys(List<String> list) {   // 去掉 static
    list.removeIf(Objects::isNull);
    Collections.sort(list);
}
```

## 验证
1. 构建 `PRTS-1.20.1-1.0.62.jar`，javap 确认 `arclight$sortKeys` 为 `private void`（无 `ACC_STATIC`）。
2. 部署测服，清 `.arclight` 缓存使修复后 common 重抽。
3. 冒烟脚本新增 `data get entity ...` 强制走 `Tag.toString()` → `StringTagVisitor` 路径，确认启动无 `MixinTransformerError`、无 `InvalidInjectionException`，`Done` 正常。
4. 日志确认启动版本 `PRTS-1.20.1-1.0.62-*`，mixin/static/injection 错误计数 0。

## 同款 bug 审计
扫全库 `@Redirect`：其余 `static` 处理器（`CraftChatMessageMixin.fromComponent`、`GameRuleCommandMixin.setRule`、`TimeCommandMixin.getAllLevels`）重定向的目标混入方法本身也是 `static`（命令方法），均正确。唯 `StringTagVisitorMixin` 一处违规。
