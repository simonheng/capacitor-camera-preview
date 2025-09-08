import UIKit
import Capacitor

@objc public class SceneDelegate: UIResponder, UIWindowSceneDelegate {
  public var window: UIWindow?

  public func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = (scene as? UIWindowScene) else { return }
        let window = UIWindow(windowScene: windowScene)
        self.window = window

        let viewController = CAPBridgeViewController()
        let navigation = UINavigationController(rootViewController: viewController)
        window.rootViewController = navigation
        window.makeKeyAndVisible()
    }

  public func sceneDidDisconnect(_ scene: UIScene) {}

  public func sceneDidBecomeActive(_ scene: UIScene) {}

  public func sceneWillResignActive(_ scene: UIScene) {}

  public func sceneWillEnterForeground(_ scene: UIScene) {}

  public func sceneDidEnterBackground(_ scene: UIScene) {}

  public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let url = URLContexts.first?.url else { return }
        ApplicationDelegateProxy.shared.application(UIApplication.shared, open: url, options: [:])
    }

  public func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        ApplicationDelegateProxy.shared.application(UIApplication.shared, continue: userActivity, restorationHandler: { _ in })
    }
}
