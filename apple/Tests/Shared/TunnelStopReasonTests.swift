//
//  TunnelStopReasonTests.swift
//  GostXTests
//

import XCTest
import NetworkExtension
@testable import GostX

final class TunnelStopReasonTests: XCTestCase {

    // Reasons the user caused, or that need no explanation, must stay silent:
    // an alert after a deliberate stop or a laptop lid close is pure noise.
    func testUserInitiatedStopIsSilent() {
        XCTAssertNil(TunnelStopReason.message(for: .userInitiated))
    }

    func testAuthenticationCancelIsSilent() {
        XCTAssertNil(TunnelStopReason.message(for: .authenticationCanceled))
    }

    func testSleepIsSilent() {
        XCTAssertNil(TunnelStopReason.message(for: .sleep))
    }

    // The case that used to be invisible: the extension killed by the system
    // (jetsam, crash) looks exactly like a user stop without this.
    func testProviderFailureIsReported() {
        let message = TunnelStopReason.message(for: .providerFailed)
        // Compare against the localized lookup so the assertion holds in any
        // language the simulator/app is set to.
        XCTAssertEqual(
            message,
            NSLocalizedString(
                "The tunnel was stopped by the system (it failed or ran out of memory).",
                comment: "Tunnel stop reason"))
    }

    func testNetworkLossIsReported() {
        XCTAssertNotNil(TunnelStopReason.message(for: .noNetworkAvailable))
        XCTAssertNotNil(TunnelStopReason.message(for: .unrecoverableNetworkChange))
    }

    func testConfigurationProblemsAreReported() {
        XCTAssertNotNil(TunnelStopReason.message(for: .configurationRemoved))
        XCTAssertNotNil(TunnelStopReason.message(for: .configurationDisabled))
    }

    // Unmapped reasons still surface, with the raw value so the log is useful.
    func testUnknownReasonFallsBackToRawValue() {
        let raw: Int = NEProviderStopReason.superceded.rawValue
        let reason = NEProviderStopReason(rawValue: raw)
        guard let reason else {
            return XCTFail("raw value \(raw) is not a valid reason")
        }
        let message = TunnelStopReason.message(for: reason)
        XCTAssertNotNil(message)
        XCTAssertTrue(message?.contains("\(raw)") == true, message ?? "nil")
    }
}