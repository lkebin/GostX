import Foundation

let RestartInterval = 10.0 // seconds
let MaxKeepLogLines = 200

@objc public protocol DaemonProcessDelegate: AnyObject {
    func process(_: DaemonProcess, isRunning: Bool)
}

@objc public class DaemonProcess: NSObject {
    private var path: String
    private var arguments: [String]?
    private weak var delegate: DaemonProcessDelegate?
    private var process: Process?
    private var queue = DispatchQueue(label: "DaemonProcess")
    private var shouldTerminate = false

    @objc init(path: String, arguments: String, delegate: DaemonProcessDelegate) {
        self.path = path
        self.delegate = delegate
        
        super.init()
        
        self.arguments = parseArguments(arguments)
    }
    
    private func parseArguments(_ v: String) -> [String] {
        if !v.isEmpty {
            var sep: CharacterSet = CharacterSet.whitespaces
            sep = sep.union(CharacterSet.newlines)
            return v.components(separatedBy: sep).filter { e in
                return !e.isEmpty
            }
        } else {
            return []
        }
    }
    
    @objc func setArguments(_ v: String) {
        self.arguments = parseArguments(v)
    }

    @objc func launch() {
        queue.async {
            self.launchSync()
        }
    }

    @objc func terminate() {
        queue.async {
            self.shouldTerminate = true
            self.process?.terminate()
        }
    }

    @objc func restart() {
        queue.async {
            self.process?.interrupt()
        }
    }

    private func launchSync() {
        if ((process?.isRunning) != nil) { // If process is created, terminate it before launch
            shouldTerminate = true
            return
        }
        
        logger.log("Launching gost daemon")
        shouldTerminate = false

        let p = Process()
        p.arguments = []
        if self.arguments != nil {
            p.arguments?.append(contentsOf: self.arguments!)
        }
        p.launchPath = path
        p.standardInput = Pipe() // isolate daemon from our stdin
        p.standardOutput = pipeIntoLineBuffer()
        p.standardError = pipeIntoLineBuffer()
        p.terminationHandler = { p in self.queue.async { self.didTerminate(p) } }
        p.qualityOfService = QualityOfService.background
        p.launch()

        DispatchQueue.main.async {
            self.delegate?.process(self, isRunning: true)
        }

        process = p
    }

    private func didTerminate(_ p: Process) {
        logger.log("Gost daemon terminated (exit code \(p.terminationStatus)")
        process = nil

        DispatchQueue.main.async {
            self.delegate?.process(self, isRunning: false)
        }

        if shouldTerminate {
            return
        }
        var delay = 0.0
        switch p.terminationStatus {
        case 0:
            // Successfull exit, such as when told to stop. We ignore
            // that fact and restart anyway, with no delay. TODO(jb):
            // Consider instead offering a "restart daemon" command in
            // the menu?
            break
        case 3:
            // Restarting. No delay necessary.
            break
        default:
            // Anything else is an error condition of some kind. Delay
            // the startup to not get caught in a tight loop.
            delay = RestartInterval
            logger.log("Delaying daemon startup by \(delay, privacy: .public) s")
        }
        queue.asyncAfter(deadline: DispatchTime.now() + delay) {
            self.launchSync()
        }
    }

    private func pipeIntoLineBuffer() -> Pipe {
        let p = Pipe()
        p.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.count == 0 {
                // No data available means EOF; we must unregister ourselves
                // in order to not immediately be called again.
                handle.readabilityHandler = nil
                return
            }

            guard let str = String(data: data, encoding: .utf8) else {
                // Non-UTF-8 data from Syncthing should never happen.
                return
            }

            logger.log("\(str, privacy: .public)")
        }
        return p
    }
}
