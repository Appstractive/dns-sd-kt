import Foundation
import Network
import os.log

// MARK: - Logging

@available(iOS 14.0, macOS 11.0, tvOS 14.0, *)
private let logger = Logger(subsystem: "com.klibs.nwbrowser", category: "NWBrowserBridge")

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
    private var resolver: AsyncDNSResolver = AsyncDNSResolver()
    private var serviceQueue: DispatchQueue
    private var discoveredResults: [String: NWBrowser.Result] = [:]
    private var waitingStateTimer: DispatchWorkItem?
    private var permissionTrigger: LocalNetworkPermissionTrigger?


    public override init() {
        self.serviceQueue = DispatchQueue(label: "com.klibs.nwbrowser", qos: .userInitiated)
        super.init()
        logger.debug("NWBrowserBridge initialized")
    }

    @objc public func startBrowsing(
        serviceType: String,
        domain: String?,
        triggerPermissionPrompt: Bool = false,
        waitingStateTimeout: TimeInterval = 5,
        onServiceFound: @escaping (String, String, String) -> Void,
        onServiceRemoved: @escaping (String) -> Void,
        onError: @escaping (String) -> Void,
        onPermissionStateChanged: ((PermissionState) -> Void)? = nil
    ) {
        let domainToUse = domain ?? "local."
        logger.info("startBrowsing called - serviceType: \(serviceType), domain: \(domainToUse), triggerPermissionPrompt: \(triggerPermissionPrompt)")

        let startBrowsingInternal = { [weak self] in
            self?.startBrowsingInternal(
                serviceType: serviceType,
                domain: domainToUse,
                waitingStateTimeout: waitingStateTimeout,
                onServiceFound: onServiceFound,
                onServiceRemoved: onServiceRemoved,
                onError: onError,
                onPermissionStateChanged: onPermissionStateChanged
            )
        }

        if triggerPermissionPrompt {
            logger.debug("Triggering permission prompt")
            permissionTrigger = LocalNetworkPermissionTrigger(queue: serviceQueue)
            permissionTrigger?.trigger { [weak self] success in
                logger.info("Permission trigger completed - success: \(success)")
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
        logger.debug("startBrowsingInternal - serviceType: \(serviceType), domain: \(domain)")

        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(type: serviceType, domain: domain)

        let params = NWParameters()
        params.includePeerToPeer = true

        browser = NWBrowser(for: descriptor, using: params)

        browser?.stateUpdateHandler = { [weak self] newState in
            logger.info("Browser state changed: \(String(describing: newState))")

            // Cancel any pending waiting state timer
            self?.waitingStateTimer?.cancel()
            self?.waitingStateTimer = nil

            switch newState {
            case .failed(let error):
                logger.error("Browser failed: \(error.localizedDescription)")
                let isPermissionError = self?.isPermissionError(error) ?? false
                logger.debug("Is permission error: \(isPermissionError)")
                if isPermissionError {
                    onPermissionStateChanged?(.denied)
                    onError("Network permission denied. Please allow local network access in Settings.")
                } else {
                    onError("Browser failed: \(error.localizedDescription)")
                }
            case .ready:
                logger.info("Browser ready - permission granted")
                // Browser is ready, permission was granted
                onPermissionStateChanged?(.granted)
            case .cancelled:
                logger.debug("Browser cancelled")
                // Browser was cancelled
                break
            case .waiting(let error):
                logger.warning("Browser waiting: \(error.localizedDescription)")
                // Check for permission errors in waiting state
                let isPermissionError = self?.isPermissionError(error) ?? false
                logger.debug("Is permission error: \(isPermissionError)")
                if isPermissionError {
                    onPermissionStateChanged?(.denied)
                    onError("Network permission denied. Please allow local network access in Settings.")
                } else {
                    // Start timeout timer for transient network issues
                    if let timeout = waitingStateTimeout {
                        logger.debug("Starting waiting state timeout: \(timeout)s")
                        let timer = DispatchWorkItem { [weak self] in
                            guard self?.browser != nil else { return }
                            logger.error("Browser waiting state timeout reached")
                            onError("Browser waiting state timeout: \(error.localizedDescription)")
                        }
                        self?.waitingStateTimer = timer
                        self?.serviceQueue.asyncAfter(deadline: .now() + timeout, execute: timer)
                    }
                }
            default:
                logger.debug("Browser state: \(String(describing: newState))")
                break
            }
        }

        browser?.browseResultsChangedHandler = { [weak self] results, changes in
            logger.debug("Browse results changed - \(changes.count) changes")
            for change in changes {
                switch change {
                case .added(let result):
                    if case .service(let name, let type, let domain, _) = result.endpoint {
                        let key = "\(name).\(type).\(domain)"
                        logger.info("Service added: \(key)")
                        self?.discoveredResults[key] = result
                        onServiceFound(name, type, domain)
                    }
                case .removed(let result):
                    if case .service(let name, let type, let domain, _) = result.endpoint {
                        let key = "\(name).\(type).\(domain)"
                        logger.info("Service removed: \(key)")
                        self?.discoveredResults.removeValue(forKey: key)
                        onServiceRemoved(name)
                    }
                case .changed(let old, let new, _):
                    // Remove old result and add new one
                    if case .service(let oldName, let oldType, let oldDomain, _) = old.endpoint {
                        let oldKey = "\(oldName).\(oldType).\(oldDomain)"
                        logger.debug("Service changed - removing old: \(oldKey)")
                        self?.discoveredResults.removeValue(forKey: oldKey)
                    }
                    if case .service(let name, let type, let domain, _) = new.endpoint {
                        let key = "\(name).\(type).\(domain)"
                        logger.info("Service changed - adding new: \(key)")
                        self?.discoveredResults[key] = new
                        onServiceFound(name, type, domain)
                    }
                case .identical:
                    // No change needed
                    break
                @unknown default:
                    logger.warning("Unknown browse result change")
                    break
                }
            }
        }

        logger.debug("Starting browser")
        browser?.start(queue: serviceQueue)
    }

    @objc public func stop() {
        logger.info("Stopping NWBrowserBridge")
        waitingStateTimer?.cancel()
        waitingStateTimer = nil

        permissionTrigger = nil

        browser?.cancel()
        browser = nil

        // Clear discovered results cache
        discoveredResults.removeAll()
        logger.debug("NWBrowserBridge stopped")
    }

    @objc public func resolveService(
        name: String,
        type: String,
        domain: String,
        onResolved: @escaping (String, [String], Int, String, [String: Data]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        let connectionKey = "\(name).\(type).\(domain)"
        logger.info("Resolving service: \(connectionKey)")

        Task {
            await withTaskGroup(of: Void.self) { taskGroup in
                do {
                    let cachedResult: NWBrowser.Result? = self.discoveredResults[connectionKey]
                    let srvRecords = try! await self.resolver.querySRV(name: connectionKey)
                    let txtRecords = cachedResult.map {
                        self.extractTXTRecords(from: $0)
                    } ?? [:]

                    logger.debug("\(name) SRVRecords: \(srvRecords)")

                    var addresses: [String] = []

                    for record in srvRecords {
                        taskGroup.addTask {
                            let aRecords = try! await self.resolver.queryA(name: record.host)

                            logger.debug("\(name) ARecords: \(aRecords)")

                            for aRecord in aRecords {
                                addresses.append(aRecord.address.address)
                            }

                            onResolved(name, addresses, Int(record.port), record.host, txtRecords)
                        }

                        taskGroup.addTask {
                            let aaaaRecords = try! await self.resolver.queryAAAA(name: record.host)

                            logger.debug("\(name) AAAARecords: \(aaaaRecords)")

                            for aaaaRecord in aaaaRecords {
                                addresses.append(aaaaRecord.address.address)
                            }

                            onResolved(name, addresses, Int(record.port), record.host, txtRecords)
                        }
                    }
                } catch let error {
                    onError("Error resolving service \(connectionKey): \(error.localizedDescription)")
                }
            }
        }
    }

    private func extractTXTRecords(from result: NWBrowser.Result) -> [String: Data] {
        if #available(iOS 13.0, macOS 10.15, tvOS 13.0, *),
           case let .bonjour(txtRecord) = result.metadata {
            var dataDict: [String: Data] = [:]
            // Iterating over the dictionary of NWTXTRecord
            for (key, value) in txtRecord.dictionary {
                if let data = value.data(using: .utf8) {
                    dataDict[key] = data
                }
            }
            logger.debug("Extracted \(dataDict.count) TXT records")
            return dataDict
        }
        return [:]
    }

    // MARK: - Permission Error Detection

    /// Checks if the given NWError represents a permission denial
    /// - Parameter error: The NWError to check
    /// - Returns: true if the error indicates permission was denied
    private func isPermissionError(_ error: NWError) -> Bool {
        switch error {
        case .posix(let code) where code == .EPERM:
            logger.debug("Permission error: POSIX EPERM")
            return true
        case .dns(let dnsError):
            // kDNSServiceErr_NoAuth = -65555
            // kDNSServiceErr_PolicyDenied = -65570
            let isPermError = dnsError == -65555 || dnsError == -65570
            if isPermError {
                logger.debug("Permission error: DNS \(dnsError)")
            }
            return isPermError
        default:
            return false
        }
    }

    deinit {
        logger.debug("NWBrowserBridge deinit")
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
        logger.debug("LocalNetworkPermissionTrigger initialized")
    }

    /// Triggers the local network permission dialog
    /// - Parameter completion: Called with `true` if permission was granted, `false` if denied or timed out
    func trigger(completion: @escaping (Bool) -> Void) {
        do {
            // Use a random high port to avoid conflicts
            let port = NWEndpoint.Port(rawValue: UInt16.random(in: 49152...65535)) ?? NWEndpoint.Port(rawValue: 49152)!
            logger.debug("Permission trigger using port: \(port.rawValue)")

            let params = NWParameters.udp
            params.includePeerToPeer = true

            listener = try NWListener(using: params, on: port)
        } catch {
            logger.error("Failed to create listener: \(error.localizedDescription)")
            completion(false)
            return
        }

        // Required: Set a connection handler (even if it does nothing)
        listener?.newConnectionHandler = { connection in
            // Just cancel any incoming connections - we only need the listener
            // to trigger the permission prompt
            logger.debug("New connection handler")
            connection.cancel()
        }

        listener?.stateUpdateHandler = { [weak self] state in
            guard let self = self, !self.hasCompleted else { return }
            logger.debug("Permission trigger listener state: \(String(describing: state))")

            switch state {
            case .ready:
                // Listener is ready, permission was granted
                logger.info("Permission trigger: listener ready - permission granted")
                self.complete(success: true, completion: completion)
            case .failed(let error):
                // Check if it's a permission error
                logger.error("Permission trigger: listener failed - \(error.localizedDescription)")
                let isPermissionError = self.isListenerPermissionError(error)
                self.complete(success: !isPermissionError, completion: completion)
            case .cancelled:
                // Don't call completion on cancel if already completed
                break
            case .waiting(let error):
                // Check for permission errors in waiting state
                logger.warning("Permission trigger: listener waiting - \(error.localizedDescription)")
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
            logger.warning("Permission trigger timeout reached")
            self.complete(success: false, completion: completion)
        }
        timeoutWorkItem = timeout
        queue.asyncAfter(deadline: .now() + triggerTimeout, execute: timeout)

        logger.debug("Starting permission trigger listener")
        listener?.start(queue: queue)
    }

    private func complete(success: Bool, completion: @escaping (Bool) -> Void) {
        guard !hasCompleted else { return }
        hasCompleted = true
        logger.debug("Permission trigger completed: \(success)")

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
        logger.debug("LocalNetworkPermissionTrigger deinit")
        timeoutWorkItem?.cancel()
        listener?.cancel()
    }
}
