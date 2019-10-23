import UIKit
import KotlinConfAPI
import Firebase

let Conference = ConferenceService(context: ApplicationContext())

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func applicationDidFinishLaunching(_ application: UIApplication) {
        FirebaseApp.configure()
    }
}
