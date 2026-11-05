import XCTest
@testable import GostX

final class YamlHighlightingTests: XCTestCase {
    private func kinds(in text: String, covering needle: String) -> Set<YamlTokenKind> {
        let ns = text as NSString
        let target = ns.range(of: needle)
        XCTAssertNotEqual(target.location, NSNotFound, "needle not found: \(needle)")
        return Set(YamlHighlighting.spans(in: text)
            .filter { NSIntersectionRange($0.range, target).length > 0 }
            .map(\.kind))
    }

    func testCommentIsHighlighted() {
        XCTAssertEqual(kinds(in: "# note", covering: "# note"), [.comment])
    }

    func testKeyIsHighlighted() {
        XCTAssertTrue(kinds(in: "name: vpn", covering: "name:").contains(.key))
    }

    func testQuotedStringIsHighlighted() {
        XCTAssertTrue(kinds(in: "name: \"vpn\"", covering: "\"vpn\"").contains(.string))
    }

    func testNumberIsHighlighted() {
        XCTAssertTrue(kinds(in: "mtu: 1500", covering: "1500").contains(.number))
    }

    func testBooleanIsHighlighted() {
        XCTAssertTrue(kinds(in: "enabled: true", covering: "true").contains(.boolean))
    }

    /// Punctuation inside a comment must not be re-coloured as a token.
    func testTokensInsideCommentAreSuppressed() {
        let text = "mtu: 1500 # value \"quoted\" stays a comment"
        XCTAssertEqual(kinds(in: text, covering: "\"quoted\""), [.comment])
    }

    /// The comment range is computed in UTF-16 units; non-ASCII keys must not
    /// shift it.
    func testUnicodeKeyStillHighlightsFollowingComment() {
        let text = "名称: 值 # 注释"
        XCTAssertTrue(kinds(in: text, covering: "名称:").contains(.key))
        XCTAssertEqual(kinds(in: text, covering: "# 注释"), [.comment])
    }
}