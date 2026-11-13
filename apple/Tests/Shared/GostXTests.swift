//
//  GostXTests.swift
//  GostXTests
//
//  Created by KB on 2022/6/16.
//

import XCTest
#if os(macOS)
import AppKit
#else
import UIKit
#endif
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
    var tempLogURL: URL!

    override func setUpWithError() throws {
        tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        tempLogURL = tempDir.appendingPathComponent("gost.log")
        try "line one\nline two\nline three\n".write(to: tempLogURL, atomically: true, encoding: .utf8)
        vm = LogViewModel(logFileURL: tempLogURL)
        vm.onAppear(loggingEnabled: true)
    }

    override func tearDownWithError() throws {
        vm.onDisappear()
        try? FileManager.default.removeItem(at: tempDir)
        vm = nil
    }

    // loadInitialTail() reads the file on a detached task, so the lines arrive
    // asynchronously; a synchronous assertion here races and sees an empty list.
    private func waitForInitialLoad(timeout: TimeInterval = 2) async {
        let deadline = Date().addingTimeInterval(timeout)
        while vm.lines.isEmpty, Date() < deadline {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testLoadsLinesFromFile() async {
        await waitForInitialLoad()
        XCTAssertEqual(vm.lines.count, 3)
        XCTAssertEqual(vm.lines[0], "line one")
        XCTAssertEqual(vm.lines[1], "line two")
        XCTAssertEqual(vm.lines[2], "line three")
    }

    func testInitialState() {
        XCTAssertFalse(vm.isFollowing)
    }

    func testClearLogTruncatesFile() {
        vm.clearLog()
        XCTAssertEqual(vm.lines.count, 0)
        // Verify the temp file was truncated, not the real one
        let content = try? String(contentsOf: tempLogURL, encoding: .utf8)
        XCTAssertEqual(content, "")
    }

    func testCopyAll() async {
        await waitForInitialLoad()
        vm.copyAll()
        // Clipboard is platform-specific: NSPasteboard on macOS, UIPasteboard on
        // iOS. This file is shared by both test targets.
        #if os(macOS)
        let pb = NSPasteboard.general.string(forType: .string)
        #else
        let pb = UIPasteboard.general.string
        #endif
        XCTAssertTrue(pb?.contains("line one") ?? false)
        XCTAssertTrue(pb?.contains("line three") ?? false)
    }

    func testIsFollowingToggle() {
        vm.isFollowing = false
        XCTAssertFalse(vm.isFollowing)
        vm.isFollowing = true
        XCTAssertTrue(vm.isFollowing)
    }
}
