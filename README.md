# Luminara

[简体中文](README_zh.md)

![Luminara Logo](.github/luminara.png)

中国/简体中文[QQ交流群](https://qun.qq.com/universal-share/share?ac=1&authKey=yQkhQe55waZU8xE6eX7Fd%2BvpwUF9KAFM4ZJwF7L6nnXmpugVlU59bUF6KwEmO4UT&busi_data=eyJncm91cENvZGUiOiI2NDg2NzE2NjYiLCJ0b2tlbiI6ImZOTzRMWFU0NkZLNXpOSHB5YjJHcHpwdnBQeUcyN0kxR3ZDM2ZWOVg3V0VNMlJXRnVySGN4OEFmVVBTc0dXSVgiLCJ1aW4iOiIzMTQyMTUwNjkxIn0%3D&data=gdk6byrm3D3fbPcziZS4OxW0De03PQfPtQuS3W4OgguRMRgMWxqysa3G3RsWxl-8uBOB2Sff1mJObv5W8-AF2A&svctype=4&tempid=h5_group_info)

An Arclight fork, aiming to make more optimizations and improvements on Arclight (1.20.1)

> Any issues encountered while using this server software should be reported in this project's Issues, not in the
> Arclight project Issues!

## Features

- **Strong Compatibility** - Supports Bukkit/Spigot plugins and Forge mods running simultaneously
- **High Performance** - Asynchronous world saving, chunk optimization...
- **Easy to Use** - Simple installation and usage
- **Velocity Support** - Supports Velocity Modern forwarding enabling cross-server functionality

## Main Maintained Version

> **Currently Main Maintained Version: Minecraft 1.20.1**
>
> - **Forge Version**: 47.4.10
> - **Stability**: Good
> - **Plugin Compatibility**: Average, Spigot only
> - **Mod Compatibility**: Excellent

## Download

### Stable Versions

- [GitHub Releases](https://github.com/QianMo0721/Luminara/releases) - Recommended for production environments

### Development Versions

- [Daily Build Versions](https://github.com/QianMo0721/Luminara/actions/workflows/gradle.yml?query=branch%3ATrials) *(
  Requires
  GitHub login)*

### Self-Build

- Clone this project locally `git clone -b <branch> https://github.com/QianMo0721/Luminara.git`
- Run `./gradlew cleanBuild remapSpigotJar idea --no-daemon -i --stacktrace --refresh-dependencies` for configuration
- Run `./gradlew build collect` to build the project
- After building, the jar file is located in the `./build/libs` directory

## Installation and Usage

1. **Download** the jar file
2. **Start the server**:

   ```bash
   java -jar luminara.jar nogui
   ```

   > The `nogui` parameter will disable the server control panel
   >
3. Before each update, replace the old JAR file with the new one, then delete the .arclight folder. Otherwise, certain fixes will not take effect!

## Incompatibilities

- May not be compatible with some optimization mods.
- Incompatible with all optimized Bukkit plugins

## Support and Help

### Documentation

- [Arclight Documentation](https://wiki.izzel.io/s/arclight-docs) - Detailed usage guides and configuration instructions
- [To-Do List](TODO.md)

### Issue Reporting

- [Submit Bug](https://github.com/QianMo0721/Luminara/issues/new/choose) - Report problems here
- [Discussion Forum](https://github.com/QianMo0721/Luminara/discussions) - Ask questions and discuss
- Do not report issues with this server software to Arclight!

## License

This project is open source under the [GPL v3](LICENSE) license.
