import Foundation
import Network

// MARK: - Permission State Enum

@objc public enum PermissionState: Int {
    case undetermined = 0
    case granted = 1
    case denied = 2
}

// MARK: - NWBrowserBridge
@available(iOS 14.0, macOS 11.0, tvOS 14.0, *)
@objc(NWBrowserBridge) public class NWBrowserBridge: NSObject {
    
    private var browser: NWBrowser?
    private var serviceQueue: DispatchQueue
    private var activeConnections: [String: NWConnection] = [:]
    private var waitingStateTimer: DispatchWorkItem?
    private var permissionTrigger: LocalNetworkPermissionTrigger?

    public override init() {
        self.serviceQueue = DispatchQueue(label: "com.klibs.nwbrowser", qos: .userInitiated)
        super.init()
    }

    @objc public func startBrowsing(
        serviceType: String,
        domain: String?,
        triggerPermissionPrompt: Bool = false,
        waitingStateTimeout: TimeInterval = -1,
        onServiceFound: @escaping (String, String, String) -> Void,
        onServiceRemoved: @escaping (String) -> Void,
        onError: @escaping (String) -> Void,
        onPermissionStateChanged: ((PermissionState) -> Void)? = nil
    ) {
        let domainToUse = domain ?? "local."
        let actualTimeout: TimeInterval? = waitingStateTimeout > 0 ? waitingStateTimeout : nil

        let startBrowsingInternal = { [weak self] in
            self?.startBrowsingInternal(
                serviceType: serviceType,
                domain: domainToUse,
                waitingStateTimeout: actualTimeout,
                onServiceFound: onServiceFound,
                onServiceRemoved: onServiceRemoved,
                onError: onError,
                onPermissionStateChanged: onPermissionStateChanged
            )
        }

        if triggerPermissionPrompt {
            permissionTrigger = LocalNetworkPermissionTrigger(queue: serviceQueue)
            permissionTrigger?.trigger { [weak self] success in
                if success {
                    onPermissionStateChanged?(.granted)
                } else {
                    onPermissionStateChanged?(.denied)
                }
                self?.permissionTrigger = nil
                startBrowsingInternal()
            }
        } else {
            startBrowsingInternal()
        }
    }

    private func startBrowsingInternal(
        serviceType: String,
        domain: String,
        waitingStateTimeout: TimeInterval?,
        onServiceFound: @escaping (String, String, String) -> Void,
        onServiceRemoved: @escaping (String) -> Void,
        onError: @escaping (String) -> Void,
        onPermissionStateChanged: ((PermissionState) -> Void)?
    ) {
        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(type: serviceType, domain: domain)

        let params = NWParameters()
        params.includePeerToPeer = true

        browser = NWBrowser(for: descriptor, using: params)

        browser?.stateUpdateHandler = { [weak self] newState in
            // Cancel any pending waiting state timer
            self?.waitingStateTimer?.cancel()
            self?.waitingStateTimer = nil

            switch newState {
            case .failed(let error):
                let isPermissionError = self?.isPermissionError(error) ?? false
                if isPermissionError {
                    onPermissionStateChanged?(.denied)
                    onError("Network permission denied. Please allow local network access in Settings.")
                } else {
                    onError("Browser failed: \(error.localizedDescription)")
                }
            case .ready:
                // Browser is ready, permission was granted
                onPermissionStateChanged?(.granted)
            case .cancelled:
                // Browser was cancelled
                break
            case .waiting(let error):
                // Check for permission errors in waiting state
                let isPermissionError = self?.isPermissionError(error) ?? false
                if isPermissionError {
                    onPermissionStateChanged?(.denied)
                    onError("Network permission denied. Please allow local network access in Settings.")
                } else {
                    // Start timeout timer for transient network issues
                    if let timeout = waitingStateTimeout {
                        let timer = DispatchWorkItem { [weak self] in
                            guard self?.browser != nil else { return }
                            onError("Browser waiting state timeout: \(error.localizedDescription)")
                        }
                        self?.waitingStateTimer = timer
                        self?.serviceQueue.asyncAfter(deadline: .now() + timeout, execute: timer)
                    }
                }
            default:
                break
            }
        }

        browser?.browseResultsChangedHandler = { results, changes in
            for change in changes {
                switch change {
                case .added(let result):
                    if case .service(let name, let type, let domain, _) = result.endpoint {
                        onServiceFound(name, type, domain)
                    }
                case .removed(let result):
                    if case .service(let name, _, _, _) = result.endpoint {
                        onServiceRemoved(name)
                    }
                case .changed(_, let new, _):
                    // Handle service changes if needed
                    if case .service(let name, let type, let domain, _) = new.endpoint {
                        onServiceFound(name, type, domain)
                    }
                case .identical:
                    // No change needed
                    break
                @unknown default:
                    break
                }
            }
        }

        browser?.start(queue: serviceQueue)
    }

    @objc public func stop() {
        waitingStateTimer?.cancel()
        waitingStateTimer = nil

        permissionTrigger = nil

        browser?.cancel()
        browser = nil

        // Cancel all active connections
        for (_, connection) in activeConnections {
            connection.cancel()
        }
        activeConnections.removeAll()
    }

    @objc public func resolveService(
        name: String,
        type: String,
        domain: String,
        onResolved: @escaping (String, [String], Int, String, [String: Data]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        let endpoint = NWEndpoint.service(name: name, type: type, domain: domain, interface: nil)

        let params = NWParameters()
        params.includePeerToPeer = true

        let connection = NWConnection(to: endpoint, using: params)
        let connectionKey = "\(name).\(type).\(domain)"
        activeConnections[connectionKey] = connection

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                // Extract endpoint information
                if let innerEndpoint = connection.currentPath?.remoteEndpoint {
                    self?.extractServiceInfo(
                        from: innerEndpoint,
                        name: name,
                        type: type,
                        domain: domain,
                        onResolved: onResolved,
                        onError: onError
                    )
                } else {
                    onError("Could not get endpoint information")
                }

                // Clean up connection
                connection.cancel()
                self?.activeConnections.removeValue(forKey: connectionKey)

            case .failed(let error):
                onError("Resolution failed: \(error.localizedDescription)")
                connection.cancel()
                self?.activeConnections.removeValue(forKey: connectionKey)

            case .waiting(let error):
                onError("Resolution waiting: \(error.localizedDescription)")

            default:
                break
            }
        }

        connection.start(queue: serviceQueue)
    }

    private func extractServiceInfo(
        from endpoint: NWEndpoint,
        name: String,
        type: String,
        domain: String,
        onResolved: @escaping (String, [String], Int, String, [String: Data]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        var addresses: [String] = []
        var port: Int = 0
        var hostname: String = ""

        switch endpoint {
        case .hostPort(let host, let portValue):
            // Extract hostname
            switch host {
            case .name(let hostName, _):
                hostname = hostName
            case .ipv4(let ipv4):
                hostname = ipv4.debugDescription
                addresses.append(ipv4.debugDescription)
            case .ipv6(let ipv6):
                hostname = ipv6.debugDescription
                addresses.append(ipv6.debugDescription)
            @unknown default:
                break
            }

            // Extract port
            port = Int(portValue.rawValue)

        case .service(let serviceName, _, _, _):
            hostname = serviceName

        default:
            break
        }

        // Try to resolve TXT records
        resolveTXTRecords(name: name, type: type, domain: domain) { txtRecords in
            onResolved(name, addresses, port, hostname, txtRecords)
        }
    }

    private func resolveTXTRecords(
        name: String,
        type: String,
        domain: String,
        completion: @escaping ([String: Data]) -> Void
    ) {
        // For now, return empty TXT records
        // Full TXT record resolution would require DNS-SD queries or using NSNetService
        // This can be enhanced later with DNSServiceQueryRecord or similar APIs
        completion([:])
    }

    // MARK: - Permission Error Detection

    /// Checks if the given NWError represents a permission denial
    /// - Parameter error: The NWError to check
    /// - Returns: true if the error indicates permission was denied
    private func isPermissionError(_ error: NWError) -> Bool {
        switch error {
        case .posix(let code) where code == .EPERM:
            return true
        case .dns(let dnsError):
            // kDNSServiceErr_NoAuth = -65555
            // kDNSServiceErr_PolicyDenied = -65570
            return dnsError == -65555 || dnsError == -65570
        default:
            return false
        }
    }

    deinit {
        stop()
    }
}

// MARK: - LocalNetworkPermissionTrigger

/// Helper class that starts a dummy NWListener to force the local network permission dialog on iOS 14+
@available(iOS 14.0, macOS 11.0, tvOS 14.0, *)
private class LocalNetworkPermissionTrigger {
    private var listener: NWListener?
    private let queue: DispatchQueue
    private var timeoutWorkItem: DispatchWorkItem?
    private var hasCompleted = false

    /// Timeout for the permission trigger in seconds
    private let triggerTimeout: TimeInterval = 5.0

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    /// Triggers the local network permission dialog
    /// - Parameter completion: Called with `true` if permission was granted, `false` if denied or timed out
    func trigger(completion: @escaping (Bool) -> Void) {
        do {
            // Use a random high port to avoid conflicts
            let port = NWEndpoint.Port(rawValue: UInt16.random(in: 49152...65535)) ?? NWEndpoint.Port(rawValue: 49152)!

            let params = NWParameters.udp
            params.includePeerToPeer = true

            listener = try NWListener(using: params, on: port)
        } catch {
            completion(false)
            return
        }

        listener?.stateUpdateHandler = { [weak self] state in
            guard let self = self, !self.hasCompleted else { return }

            switch state {
            case .ready:
                // Listener is ready, permission was granted
                self.complete(success: true, completion: completion)
            case .failed(let error):
                // Check if it's a permission error
                let isPermissionError = self.isListenerPermissionError(error)
                self.complete(success: !isPermissionError, completion: completion)
            case .cancelled:
                // Don't call completion on cancel if already completed
                break
            case .waiting(let error):
                // Check for permission errors in waiting state
                if self.isListenerPermissionError(error) {
                    self.complete(success: false, completion: completion)
                }
                // Otherwise wait for state to change
            default:
                break
            }
        }

        // Set up timeout
        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self, !self.hasCompleted else { return }
            // Timeout reached without clear permission result
            // Assume permission was not granted
            self.complete(success: false, completion: completion)
        }
        timeoutWorkItem = timeout
        queue.asyncAfter(deadline: .now() + triggerTimeout, execute: timeout)

        listener?.start(queue: queue)
    }

    private func complete(success: Bool, completion: @escaping (Bool) -> Void) {
        guard !hasCompleted else { return }
        hasCompleted = true

        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil

        listener?.cancel()
        listener = nil

        completion(success)
    }

    private func isListenerPermissionError(_ error: NWError) -> Bool {
        switch error {
        case .posix(let code) where code == .EPERM:
            return true
        case .dns(let dnsError):
            // kDNSServiceErr_NoAuth = -65555
            // kDNSServiceErr_PolicyDenied = -65570
            return dnsError == -65555 || dnsError == -65570
        default:
            return false
        }
    }

    deinit {
        timeoutWorkItem?.cancel()
        listener?.cancel()
    }
}

