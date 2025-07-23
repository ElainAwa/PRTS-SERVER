# Luminara

> 一个在常见模组加载器上运行的 Bukkit 服务器实现

## ✨ 特性

- 🔧 **兼容性强** - 支持 Bukkit/Spigot/Paper 插件与 Forge 模组同时运行
- 🚀 **高性能** - 基于 Minecraft Forge 构建，性能优异
- 🛠️ **易于使用** - 简单的安装和配置过程
- 🌐 **多版本支持** - 支持多个 Minecraft 版本

## 🎯 主要维护版本

> **当前主要维护版本：Minecraft 1.20.1**
>
> - **Forge 版本**：47.4.4
> - **稳定性**：⭐⭐⭐⭐⭐ 
> - **插件兼容性**：一般，仅Spigot
> - **模组兼容性**：优秀

## 📥 下载

### 稳定版本
- [GitHub Releases](https://github.com/QianMoo0121/Luminara/releases) - 推荐用于生产环境

### 开发版本
- [每日构建版本](https://github.com/QianMoo0121/Luminara/actions/workflows/gradle.yml?query=branch%3ATrials) *(需要 GitHub 登录)*


### 自行构建
 - 克隆本项目到本地 `git clone -b <分支> https://github.com/QianMoo0121/Luminara.git`
 - 运行 `./gradlew cleanBuild remapSpigotJar idea --no-daemon -i --stacktrace --refresh-dependencies` 进行配置
 - 运行 `./gradlew build collect` 构建项目
 - 构建完成后，jar 文件位于 `./build/libs` 目录下

## 🚀 安装使用

1. **下载** jar 文件
2. **启动服务器**：
   ```bash
   java -jar luminara.jar nogui
   ```
   > `nogui` 参数将禁用服务器控制面板
   

## 📚 支持与帮助

### 📖 文档
- [官方文档](https://wiki.izzel.io/s/arclight-docs) - 详细的使用指南和配置说明

### 🐛 问题反馈
- [提交 Bug](https://github.com/QianMoo0121/Luminara/issues/new/choose) - 遇到问题请在这里报告
- [讨论区](https://github.com/QianMoo0121/Luminara/discussions) - 提问和讨论
- 请勿将本服务端的问题反馈到Arclight！

## ✏️ 待办事项
- [ ] 支持Velocity Modern转发（Port PCF）
- [ ] 并入*①MPEM的部分优化项
- [ ] 支持Adventure库
- [ ] 使用Paper方法加速初始化世界的速度
## 📄 开源协议

本项目基于 [GPL v3](LICENSE) 协议开源。
