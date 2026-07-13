// macos/GostX/AppGroupConfig.swift
import Foundation

/// 通过 App Group 容器在主 App 和 Extension 之间共享 YAML 配置。
struct AppGroupConfig {
    static let groupId = "group.cn.liukebin.gostx"
    static let yamlFileName = "gost.yaml"

    static var containerURL: URL? {
        FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupId
        )
    }

    static var yamlFileURL: URL? {
        containerURL?.appendingPathComponent(yamlFileName)
    }

    /// 从 App Group 容器读取 YAML 配置字符串。
    /// 返回 nil 表示容器不可用或文件不存在。
    static func readYaml() -> String? {
        guard let url = yamlFileURL else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// 将 YAML 配置写入 App Group 容器。
    /// 失败静默忽略（Extension 启动时会检查并报错）。
    static func writeYaml(_ yaml: String) {
        guard let url = yamlFileURL else { return }
        try? yaml.write(to: url, atomically: true, encoding: .utf8)
    }
}
