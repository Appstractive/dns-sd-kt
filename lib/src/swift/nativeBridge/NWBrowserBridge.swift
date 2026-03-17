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
    private var serviceQueue: DispatchQueue
    private var activeConnections: [String: NWConnection] = [:]
    private var discoveredResults: [String: NWBrowser.Result] = [:]
    private var waitingStateTimer: DispatchWorkItem?
    private var permissionTrigger: LocalNetworkPermissionTrigger?
    private var resolutionTimers: [String: DispatchWorkItem] = [:]
    private let resolutionTimeout: TimeInterval = 5.0


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

        // Cancel all active connections
        logger.debug("Cancelling \(self.activeConnections.count) active connections")
        for (key, connection) in activeConnections {
            logger.debug("Cancelling connection: \(key)")
            connection.cancel()
        }
        activeConnections.removeAll()

        // Clear discovered results cache
        discoveredResults.removeAll()
        logger.debug("NWBrowserBridge stopped")

        for (_, timer) in resolutionTimers {
            timer.cancel()
        }
        resolutionTimers.removeAll()
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

        serviceQueue.async { [weak self] in guard let self = self else {return }
            let cachedResult: NWBrowser.Result? = self.discoveredResults[connectionKey]
            logger.debug("Cached result found: \(cachedResult != nil)")

            // Extract TXT records from cached result if available
            let txtRecords = cachedResult.map { self.extractTXTRecords(from: $0) } ?? [:]
            logger.debug("TXT records: \(txtRecords.keys.joined(separator: ", "))")

            // Use cached endpoint if available, otherwise construct from strings
            let endpoint: NWEndpoint
            if let result = cachedResult {
                endpoint = result.endpoint
                logger.debug("Using cached endpoint")
            } else {
                // Fallback: construct endpoint from strings (may have Port 0 issue)
                endpoint = NWEndpoint.service(name: name, type: type, domain: domain, interface: nil)
                logger.debug("Constructed endpoint from strings")
            }

            // Determine protocol from service type
            let params: NWParameters
            if type.lowercased().contains("._udp") {
                params = NWParameters.udp
                logger.debug("Using UDP parameters")
            } else {
                params = NWParameters.tcp
                logger.debug("Using TCP parameters")
            }
            params.includePeerToPeer = true

            let connection = NWConnection(to: endpoint, using: params) //setup an IP connection to determine IP addresses and hostname
            self.activeConnections[connectionKey] = connection

            connection.stateUpdateHandler = { [weak self] state in
                logger.debug("Resolution connection state: \(String(describing: state))")
                switch state {
                case .ready:
                    self?.resolutionTimers[connectionKey]?.cancel()
                    self?.resolutionTimers.removeValue(forKey: connectionKey)
                    logger.info("Resolution connection ready")
                    // Extract endpoint information
                    if let innerEndpoint = connection.currentPath?.remoteEndpoint {
                        logger.debug("Remote endpoint: \(String(describing: innerEndpoint))")
                        self?.extractServiceInfo(
                            from: innerEndpoint,
                            name: name,
                            type: type,
                            domain: domain,
                            txtRecords: txtRecords,
                            onResolved: onResolved,
                            onError: onError
                        )
                    } else {
                        logger.error("Could not get endpoint information")
                        onError("Could not get endpoint information")
                    }

                    // Clean up connection
                    connection.cancel()
                    self?.activeConnections.removeValue(forKey: connectionKey)

                case .failed(let error):
                    logger.error("Resolution failed: \(error.localizedDescription)")
                    self?.resolutionTimers[connectionKey]?.cancel()
                    self?.resolutionTimers.removeValue(forKey: connectionKey)
                    logger.info("Returning partial resolution for: \(connectionKey)")
                    onResolved(name, [], 0, "", txtRecords)
                    onError("Resolution failed: \(error.localizedDescription)")
                    connection.cancel()
                    self?.activeConnections.removeValue(forKey: connectionKey)

                case .waiting(let error):
                    logger.warning("Resolution waiting: \(error.localizedDescription)")
                    self?.resolutionTimers[connectionKey]?.cancel()
                    self?.resolutionTimers.removeValue(forKey: connectionKey)
                    logger.info("Returning partial resolution for: \(connectionKey)")
                    onResolved(name, [], 0, "", txtRecords)

                default:
                    logger.warning("Connection state: \(String(describing: state))")
                    break
                }
            }

            logger.debug("Starting resolution connection")
            connection.start(queue: self.serviceQueue)

            // start resolution timeout timer and call onResolve with partial information on timeout
            let resolutionTimer = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                logger.warning("Resolution timed out for: \(connectionKey)")
                logger.info("Returning partial resolution for: \(connectionKey)")
                onResolved(name, [], 0, "", txtRecords)
                self.activeConnections[connectionKey]?.cancel()
                self.activeConnections.removeValue(forKey: connectionKey)
                self.resolutionTimers.removeValue(forKey: connectionKey)
            }
            self.resolutionTimers[connectionKey] = resolutionTimer
            self.serviceQueue.asyncAfter(deadline: .now() + resolutionTimeout, execute: resolutionTimer)

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

    private func extractServiceInfo(
        from endpoint: NWEndpoint,
        name: String,
        type: String,
        domain: String,
        txtRecords: [String: Data],
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
                var addr = "\(ipv4)"
                // Remove scope identifier (e.g., "%en0") if present
                if let percentIndex = addr.firstIndex(of: "%") {
                    addr = String(addr[..<percentIndex])
                }
                hostname = addr
                addresses.append(addr)
            case .ipv6(let ipv6):
                var addr = "\(ipv6)"
                // Remove scope identifier (e.g., "%en0") if present
                if let percentIndex = addr.firstIndex(of: "%") {
                    addr = String(addr[..<percentIndex])
                }
                hostname = addr
                addresses.append(addr)
            @unknown default:
                break
            }

            // Extract port
            port = Int(portValue.rawValue)

        case .service(let serviceName, _, _, _):
            hostname = "" //if this NWEndpoint represents a bonjour service we cannot know the hostname.
            // Bonjour does not guarantee the hostname and IPs to stay the same
        default:
            break
        }

        logger.info("Service resolved - name: \(name), hostname: \(hostname), port: \(port), addresses: \(addresses)")
        onResolved(name, addresses, port, hostname, txtRecords)
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
