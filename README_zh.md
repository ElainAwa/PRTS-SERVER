# Luminara

![Luminara Logo](.github/luminara.png)

一个Arclight fork，争取在Arclight上做出更多优化与改进（1.20.1）

> 使用本服务端过程中出现的任何问题请在本项目Issue反馈，请勿在Arclight项目Issue反馈！

## 特性

- **兼容性强** - 支持 Bukkit/Spigot 插件与 Forge 模组同时运行
- **高性能** - 异步保存世界，区块优化...
- **易于使用** - 简单的安装与使用
- **Velocity支持** - 支持Velocity Modern转发，实现跨服功能

## 主要维护版本

> **当前主要维护版本：Minecraft 1.20.1**
>
> - **Forge 版本**：47.4.10
> - **稳定性**：较好
> - **插件兼容性**：一般，仅Spigot
> - **模组兼容性**：优秀

## 下载

### 稳定版本

- [GitHub Releases](https://github.com/QianMo0721/Luminara/releases) - 推荐用于生产环境

### 开发版本

- [每日构建版本](https://github.com/QianMo0721/Luminara/actions/workflows/gradle.yml?query=branch%3ATrials) *(需要
  GitHub 登录)*

### 自行构建

- 克隆本项目到本地 `git clone -b <分支> https://github.com/QianMo0721/Luminara.git`
- 运行 `./gradlew cleanBuild remapSpigotJar idea --no-daemon -i --stacktrace --refresh-dependencies` 进行配置
- 运行 `./gradlew build collect` 构建项目
- 构建完成后，jar 文件位于 `./build/libs` 目录下

## 安装使用

1. **下载** jar 文件
2. **启动服务器**：
   ```bash
   java -jar luminara.jar nogui
   ```

   > `nogui` 参数将禁用服务器控制面板

>

## 不兼容

- 可能不兼容一些优化模组
- 与所有的优化Bukkit插件不兼容

## 支持与帮助

### 文档

- [Arclight文档](https://wiki.izzel.io/s/arclight-docs) - 详细的使用指南和配置说明
- [待办事项](TODO.md)

### 问题反馈

- [提交 Bug](https://github.com/QianMo0721/Luminara/issues/new/choose) - 遇到问题请在这里报告
- [讨论区](https://github.com/QianMo0721/Luminara/discussions) - 提问和讨论
- 请勿将本服务端的问题反馈到Arclight！

## 开源协议

本项目基于 [GPL v3](LICENSE) 协议开源。
