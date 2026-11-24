import ActivityKit
import FeatureTracking
import SwiftUI
import WidgetKit

@main
struct MalhaVivaWidgets: WidgetBundle {
    var body: some Widget {
        TrackingLiveActivity()
    }
}

struct TrackingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackingActivityAttributes.self) { _ in
            EmptyView()
        } dynamicIsland: { _ in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { EmptyView() }
            } compactLeading: {
                EmptyView()
            } compactTrailing: {
                EmptyView()
            } minimal: {
                EmptyView()
            }
        }
    }
}
