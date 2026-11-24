import Observation

@MainActor
@Observable
public final class MviStore<State, Intent> {
    public private(set) var state: State
    private let reducer: (State, Intent) -> State

    public init(initialState: State, reducer: @escaping (State, Intent) -> State) {
        state = initialState
        self.reducer = reducer
    }

    public func send(_ intent: Intent) {
        state = reducer(state, intent)
    }
}
