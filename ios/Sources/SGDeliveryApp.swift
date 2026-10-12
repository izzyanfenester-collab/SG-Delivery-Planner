import SwiftUI

@main
struct SGDeliveryApp: App {
    @StateObject private var store = DeliveryStore()
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(store)
        }
    }
}
