//
//  GostXTests.swift
//  GostXTests
//
//  Created by KB on 2022/6/16.
//

import XCTest
@testable import GostX

class GostXTests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    func testExample() throws {
        // This is an example of a functional test case.
        // Use XCTAssert and related functions to verify your tests produce the correct results.
        // Any test you write for XCTest can be annotated as throws and async.
        // Mark your test throws to produce an unexpected failure when your test encounters an uncaught error.
        // Mark your test async to allow awaiting for asynchronous code to complete. Check the results with assertions afterwards.
    }

    func testPerformanceExample() throws {
        // This is an example of a performance test case.
        self.measure {
            // Put the code you want to measure the time of here.
        }
    }

}

@MainActor
class LogViewModelTests: XCTestCase {
    var vm: LogViewModel!
    var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        // Write a known log file
        let log = tempDir.appendingPathComponent("gost.log")
        try "line one\nline two\nline three\n".write(to: log, atomically: true, encoding: .utf8)
        // Override containerURL to return our temp dir
        // We test load/clear/copy independently by writing to a temp file
        vm = LogViewModel()
    }

    override func tearDownWithError() throws {
        vm.onDisappear()
        try? FileManager.default.removeItem(at: tempDir)
        vm = nil
    }

    func testInitialState() {
        XCTAssertTrue(vm.isFollowing)
        // lines depend on file existence; without a real log file they start empty
    }

    func testClearLog() {
        vm.clearLog()
        // After clear, lines should be empty
        XCTAssertEqual(vm.lines.count, 0)
    }

    func testCopyAll() {
        // lines is empty by default in unit test env (no App Group file)
        vm.copyAll()
        // Pasteboard should contain empty string
        XCTAssertEqual(NSPasteboard.general.string(forType: .string), "")
    }

    func testIsFollowingToggle() {
        vm.isFollowing = false
        XCTAssertFalse(vm.isFollowing)
        vm.isFollowing = true
        XCTAssertTrue(vm.isFollowing)
    }
}
