import Foundation

enum Route: Hashable {
    case configEdit(profileId: String)
    case logs
    case settings
    case fileManage
}
