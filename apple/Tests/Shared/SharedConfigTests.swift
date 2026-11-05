import XCTest
@testable import GostX

final class GostDefaultsTests: XCTestCase {
    func testDefaultYAMLMatchesPlatformMode() {
        #if os(iOS)
        XCTAssertTrue(GostDefaults.yaml.contains("type: tungo"),
                      "iOS 默认配置必须是 tungo 模板")
        #else
        XCTAssertTrue(GostDefaults.yaml.contains("type: socks5"),
                      "macOS 默认配置保持 socks5 模板")
        XCTAssertFalse(GostDefaults.yaml.contains("type: tungo"))
        #endif
    }
}

final class ProfileNameValidatorTests: XCTestCase {
    func testEmptyNameRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "  ", existing: []))
    }

    func testDuplicateNameRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "Default", existing: ["Default"]))
    }

    func testCommaRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "a,b", existing: []))
    }

    func testValidNameAccepted() {
        XCTAssertNil(ProfileNameValidator.error(name: "Office", existing: ["Default"]))
    }

    func testWhitespaceIsTrimmedBeforeDuplicateCheck() {
        XCTAssertNotNil(ProfileNameValidator.error(name: " Default ", existing: ["Default"]))
    }
}

final class AppGroupLogSizeTests: XCTestCase {
    func testDefaultLogMaxSizeIs2048KB() {
        UserDefaults(suiteName: AppGroupConfig.groupId)?.removeObject(forKey: "log_max_size_kb")
        XCTAssertEqual(AppGroupConfig.logMaxSizeKB, 2048)
    }

    func testLogMaxSizeOptionsMatchAndroid() {
        XCTAssertEqual(AppGroupConfig.logMaxSizeOptions, [512, 1024, 2048, 5120])
    }
}