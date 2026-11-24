@testable import CoreUI
import XCTest

@MainActor
final class MviStoreTests: XCTestCase {
    func testIntentsReduceIntoState() {
        let store = MviStore<Int, Int>(initialState: 0) { state, intent in state + intent }
        store.send(2)
        store.send(3)
        XCTAssertEqual(store.state, 5)
    }
}
