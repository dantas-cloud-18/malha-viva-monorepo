#if canImport(ActivityKit) && os(iOS)
    import ActivityKit

    public struct TrackingActivityAttributes: ActivityAttributes {
        public struct ContentState: Codable, Hashable {
            public init() {}
        }

        public init() {}
    }
#endif
