import Foundation
import Network


@objc(NWBrowserBridge) public class NWBrowserBridge: NSObject {
    
    private var browser: NWBrowser?
    private var serviceQueue: DispatchQueue
    private var activeConnections: [String: NWConnection] = [:]

    public override init() {
        self.serviceQueue = DispatchQueue(label: "com.klibs.nwbrowser", qos: .userInitiated)
        super.init()
    }

    @objc public func startBrowsing(
        serviceType: String,
        domain: String?,
        onServiceFound: @escaping (String, String, String) -> Void,
        onServiceRemoved: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        let domainToUse = domain ?? "local."

        let descriptor = NWBrowser.Descriptor.bonjour(type: serviceType, domain: domainToUse)

        let params = NWParameters()
        params.includePeerToPeer = true

        browser = NWBrowser(for: descriptor, using: params)

        browser?.stateUpdateHandler = { newState in
            switch newState {
            case .failed(let error):
                switch error {
                case .posix(let code) where code == .EPERM:
                    onError("Network permission denied. Please allow local network access in Settings.")
                case .dns(let dnsError):
                    // kDNSServiceErr_NoAuth = -65555
                    if dnsError == -65555 {
                        onError("Network permission denied. Please allow local network access in Settings.")
                    } else {
                        onError("DNS error: \(dnsError)")
                    }
                default:
                    onError("Browser failed: \(error.localizedDescription)")
                }
            case .ready:
                // Browser is ready
                break
            case .cancelled:
                // Browser was cancelled
                break
            case .waiting(let error):
                // Also check waiting state for permission errors
                if case .dns(let dnsError) = error, dnsError == -65555 {
                    onError("Network permission denied. Please allow local network access in Settings.")
                } else {
                    onError("Browser waiting: \(error.localizedDescription)")
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

    deinit {
        stop()
    }
}
