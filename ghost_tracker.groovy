import groovy.json.JsonOutput
import groovy.json.JsonSlurper

definition(
        name: "mmWave Ghost Buster",
        namespace: "lnjustin",
        author: "Justin Leonard",
        description: "Detects persistent mmWave ghost targets and recommends interference zones",
        category: "Utility",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: false
)

preferences {
    page(name: "mainPage")
    page(name: "settingsPage")
    page(name: "statsPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section(getInterface("header", "Overview")) {
            paragraph renderStatBlock("Setup Status", getMainPageOverviewStats())
        }

        section(getInterface("header", "Configuration")) {
            href "settingsPage", title: "Configure Devices and Detection", description: "Choose devices, activation, clustering, persistence, and notifications"
            paragraph renderStatBlock("Configuration Summary", getConfigurationSummaryStats())
        }

        section(getInterface("header", "Statistics")) {
            href "statsPage", title: "View Detailed Statistics", description: "Per-device graphs, clusters, and interference controls"
            paragraph renderStatBlock("Current Status", getMainPageStatsSummary())
        }

        section(getInterface("header", "Quick Actions")) {
            input "recommendNow", "button", title: "Generate Recommendation"

            if (state.recommendation) {
                paragraph renderRecommendationSummary(state.recommendation)
            }
        }
    }
}

def settingsPage() {
    dynamicPage(name: "settingsPage", install: false, uninstall: false) {
        section(getInterface("header", "Devices")) {
            input "mmwaveDevices",
                    "device.InovellimmWaveDimmerBlueSeriesVZM32-SN",
                    title: "Choose mmWave switches",
                    multiple: true,
                    submitOnChange: true
        }

        section(getInterface("header", "Activation")) {
            input "ghostModes",
                    "mode",
                    title: "Modes where ghost detection is active",
                    multiple: true,
                    required: true

            input "activationMode",
                    "enum",
                    title: "Ghost detection activation",
                    options: [
                            "Always Active During Ghost Modes",
                            "Conditioned On Virtual Switch"
                    ],
                    required: true,
                    submitOnChange: true

            if (activationMode == "Conditioned On Virtual Switch") {
                input "autoDisableDays",
                        "number",
                        title: "Turn off activator switches after X days (0 disables auto-off)",
                        defaultValue: 0
            }
        }

        section(getInterface("header", "Activator Switches")) {
            if (!mmwaveDevices) {
                paragraph "Select mmWave switches to see activator switch details."
            } else {
                def gateStatus = getGhostGateStatus()

                if (gateStatus.existing) {
                    paragraph renderNoteCard("Existing Activators", gateStatus.existing.join("<br>"))
                }

                if (gateStatus.toCreate) {
                    paragraph renderNoteCard("Will Be Created On Save", gateStatus.toCreate.join("<br>"))
                }

                if (gateStatus.toDelete) {
                    paragraph renderNoteCard("Will Be Removed On Save", gateStatus.toDelete.join("<br>"))
                }

                if (!gateStatus.existing && !gateStatus.toCreate) {
                    paragraph "No activator switches are needed with the current configuration."
                }
            }
        }

        section(getInterface("header", "Daily Boundary")) {
            input "boundaryType",
                    "enum",
                    title: "Define day by",
                    options: ["Mode Boundary", "Time Boundary"],
                    required: true,
                    submitOnChange: true

            if (boundaryType == "Mode Boundary") {
                input "resetModeEnterExit",
                        "enum",
                        title: "Start a new day when",
                        options: ["Entering", "Exiting"],
                        required: true

                input "resetMode",
                        "mode",
                        title: "Selected mode",
                        required: true
            }

            if (boundaryType == "Time Boundary") {
                input "dayEnd",
                        "time",
                        title: "Run daily analysis at",
                        required: true
            }
        }

        section(getInterface("header", "Point Filtering")) {
            input "filterPointsByDeviceBounds",
                    "bool",
                    title: "Filter out points not within device X/Y/Z min/max settings",
                    defaultValue: false,
                    submitOnChange: true

            if (filterPointsByDeviceBounds) {
                input "captureOutOfBoundsPoints",
                        "bool",
                        title: "Still capture and graph out-of-bounds points (but don't use for ghost detection)",
                        defaultValue: false,
                        submitOnChange: true

                if (captureOutOfBoundsPoints) {
                    paragraph renderNoteCard("Filtering with Visualization", "Out-of-bounds points will be captured for graphing and reporting but excluded from ghost detection.")
                } else {
                    paragraph renderNoteCard("Filtering Enabled", "Points outside the device's configured X/Y/Z min/max ranges will be completely excluded.")
                }
            }
        }

        section(getInterface("header", "Clustering")) {
            input "clusteringAlgorithm",
                    "enum",
                    title: "Primary clustering algorithm",
                    options: ["DBSCAN", "K-Means"],
                    defaultValue: "DBSCAN",
                    required: true

            input "clusterRadius",
                    "decimal",
                    title: "Cluster radius / match threshold (meters)",
                    defaultValue: 0.5

            input "minClusterEvents",
                    "number",
                    title: "Minimum events per cluster",
                    defaultValue: 5

            input "maxClusters",
                    "number",
                    title: "Maximum K-Means clusters",
                    defaultValue: 5
        }

        section(getInterface("header", "Persistence")) {
            input "historyDays",
                    "number",
                    title: "Rolling stability window (days)",
                    defaultValue: 14

            input "persistentGhostDays",
                    "number",
                    title: "Consecutive days to become persistent",
                    defaultValue: 2

            input "bustedGhostDays",
                    "number",
                    title: "Consecutive missing days to become busted",
                    defaultValue: 2

            input "stableThreshold",
                    "number",
                    title: "Recommendation threshold (%)",
                    defaultValue: 70
        }

        section(getInterface("header", "Automatic Ghost Busting")) {
            input "enableAutoGhostBusting",
                    "bool",
                    title: "Automatically apply interference zones for matching persistent clusters",
                    defaultValue: false,
                    submitOnChange: true

            if (enableAutoGhostBusting) {
                paragraph renderNoteCard("Auto Ghost Busting", "Boundary values below are in centimeters, matching the mmWave report values. Auto mode can either require the full cluster bounds to stay inside the boundary or clamp the applied interference zone to the boundary so nothing beyond it is written.")

                input "autoBustMode",
                        "enum",
                        title: "How automatic busting should handle the boundary",
                        options: [
                                "Only apply when the full cluster is inside the boundary",
                                "Apply the overlapping part of the cluster, clamped to the boundary"
                        ],
                        defaultValue: "Only apply when the full cluster is inside the boundary",
                        required: true

                input "autoBustBoundaryXMin",
                        "decimal",
                        title: "Boundary X min (cm)",
                        required: true
                input "autoBustBoundaryXMax",
                        "decimal",
                        title: "Boundary X max (cm)",
                        required: true
                input "autoBustBoundaryYMin",
                        "decimal",
                        title: "Boundary Y min (cm)",
                        required: true
                input "autoBustBoundaryYMax",
                        "decimal",
                        title: "Boundary Y max (cm)",
                        required: true
                input "autoBustBoundaryZMin",
                        "decimal",
                        title: "Boundary Z min (cm)",
                        required: true
                input "autoBustBoundaryZMax",
                        "decimal",
                        title: "Boundary Z max (cm)",
                        required: true
            }
        }

        section(getInterface("header", "Notifications")) {
            input "sendPush",
                    "bool",
                    title: "Send notifications",
                    defaultValue: false,
                    submitOnChange: true

            if (sendPush) {
                input "notifyDevices",
                        "capability.notification",
                        title: "Notification devices",
                        multiple: true,
                        required: false

                input "notifyOnActivate",
                        "bool",
                        title: "Notify on activation",
                        defaultValue: true

                input "notifyOnDeactivate",
                        "bool",
                        title: "Notify on deactivation",
                        defaultValue: true

                input "notifyDailySummary",
                        "bool",
                        title: "Notify on daily summary",
                        defaultValue: true
            }
        }

        section(getInterface("header", "Logging")) {
            input "enableDebugLogging",
                    "bool",
                    title: "Enable debug logging",
                    defaultValue: false
        }
    }
}

def statsPage() {
    initializeState()

    dynamicPage(name: "statsPage", install: false, uninstall: false) {
        if (!mmwaveDevices) {
            section { paragraph "No mmWave devices configured." }
            return
        }

        def aggregateToday = 0
        def aggregatePersistent = 0
        def aggregateBusted = 0
        def aggregateBustSources = getAggregateBustSourceCounts()

        mmwaveDevices.each { dev ->
            def displayCounts = getDisplayCounts(dev.id)
            aggregateToday += displayCounts.ghostsToday
            aggregatePersistent += displayCounts.persistentGhosts
            aggregateBusted += displayCounts.bustedGhosts
        }

            section(getInterface("header", "All Devices Summary")) {
                paragraph renderStatBlock("Network Summary", [
                    "Last processed ghosts": aggregateToday,
                    "Persistent ghosts": aggregatePersistent,
                    "Busted ghosts": aggregateBusted,
                    "Auto-busted persistent": aggregateBustSources.autoBusted,
                    "Manual-busted persistent": aggregateBustSources.manualBusted,
                    "Unbusted persistent": aggregateBustSources.unbusted,
                    "Processed days": state.totalDays ?: 0
            ])

            if (state.recommendation) {
                paragraph renderRecommendationSummary(state.recommendation)
            }
        }

        mmwaveDevices.each { dev ->
            def devKey = deviceKey(dev.id)
            def todayPoints = getPointsForDevice(dev.id)
            def todayOutOfBounds = getOutOfBoundsPointsForDevice(dev.id)
            def todayClusters = getTodayClusters(dev.id)
            def stableClusters = getStableClusters(dev.id)
            def liveCounts = getGhostCounts(devKey, todayClusters)
            def displayCounts = getDisplayCounts(dev.id)
            def displayData = getDisplayData(dev.id)
            def lastSummary = getSummaryForDevice(dev.id)

            section(getInterface("header", "Device: ${dev.displayName}")) {
                def currentStats = [
                        "Points buffered": todayPoints.size(),
                        "Ghosts detected": liveCounts.ghostsToday,
                        "Active": isDeviceActive(dev) ? "Yes" : "No"
                ]
                if (todayOutOfBounds) {
                    currentStats["Out-of-bounds points"] = todayOutOfBounds.size()
                }

                def lastDayStats = [
                        "Ghosts detected": displayCounts.ghostsToday,
                        "Persistent ghosts": displayCounts.persistentGhosts,
                        "Busted ghosts": displayCounts.bustedGhosts,
                        "Auto-busted persistent": displayCounts.autoBusted,
                        "Manual-busted persistent": displayCounts.manualBusted,
                        "Unbusted persistent": displayCounts.unbusted,
                        "Points processed": lastSummary.pointCount ?: 0,
                        "Non-cluster points": lastSummary.unclusteredPointCount ?: 0
                ]
                if (lastSummary.outOfBoundsPointCount) {
                    lastDayStats["Out-of-bounds points"] = lastSummary.outOfBoundsPointCount
                }

                paragraph renderDualStatBlocks(
                        "Current Collection",
                        currentStats,
                        "Last Processed Day",
                        lastDayStats
                )

                paragraph renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev)

                if (stableClusters) {
                    paragraph getInterface("subHeader", "Tracked Clusters")
                    stableClusters.eachWithIndex { cluster, idx ->
                        paragraph renderClusterDetails(cluster, idx + 1, devKey)
                    }
                } else {
                    paragraph "No stability history yet for this device."
                }

                if ((lastSummary.unclusteredPointCount ?: 0) > 0) {
                    paragraph renderNoteCard("Detection Note", "Last processed run had ${lastSummary.unclusteredPointCount} point(s) that did not form a ghost cluster.")
                }

                paragraph getInterface("subHeader", "Actions")

                if (displayData.selectableClusters) {
                    input "blockClusters_${devKey}",
                            "enum",
                            title: "Clusters to convert to interference zones",
                            multiple: true,
                            required: false,
                            options: displayData.selectableClusters.collectEntries { cluster ->
                                def idx = displayData.selectableClusters.indexOf(cluster)
                                [(idx.toString()): describeClusterOption(cluster, idx)]
                            }
                    input "applyZones_${devKey}", "button", title: "Apply Selected Interference Zones"
                } else {
                    paragraph "No clusters are available for interference zone selection."
                }

                input "reclusterDevice_${devKey}", "button", title: "Recluster All Points for This Device"
                input "clearDeviceStats_${devKey}", "button", title: "Clear This Device's Statistics"

                paragraph getInterface("line")
            }
        }
    }
}

def appButtonHandler(btn) {
    if (!btn) {
        return
    }

    if (btn == "recommendNow") {
        generateRecommendation()
        return
    }

    if (btn.startsWith("clearDeviceStats_")) {
        def devKey = btn.substring("clearDeviceStats_".length())
        clearDeviceStats(devKey)
        return
    }

    if (btn.startsWith("applyZones_")) {
        def devKey = btn.substring("applyZones_".length())
        applySelectedZones(devKey)
        return
    }

    if (btn.startsWith("splitCluster_")) {
        def clusterKey = btn.substring("splitCluster_".length())
        splitCluster(clusterKey)
        return
    }

    if (btn.startsWith("reclusterDevice_")) {
        def devKey = btn.substring("reclusterDevice_".length())
        reclusterDevice(devKey)
        return
    }
}

def installed() {
    initializeState()
    syncChildDevices()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initializeState()
    syncChildDevices()
    initialize()
}

private initialize() {
    subscribe(location, "mode", modeHandler)

    if (boundaryType == "Time Boundary" && dayEnd) {
        schedule(dayEnd, "endOfDay")
    }

    mmwaveDevices?.each { dev ->
        subscribe(dev, "targetInfo", targetInfoHandler)
    }

    if (activationMode == "Conditioned On Virtual Switch") {
        getChildDevices()?.each { child ->
            subscribe(child, "switch", activatorSwitchHandler)
        }
    }

    updateDetectionState()
}

private initializeState() {
    state.dailyPoints = state.dailyPoints ?: [:]
    state.dailyOutOfBoundsPoints = state.dailyOutOfBoundsPoints ?: [:]
    state.stabilityData = state.stabilityData ?: [:]
    state.dailySummary = state.dailySummary ?: [:]
    state.lastPointsSnapshot = state.lastPointsSnapshot ?: [:]
    state.lastOutOfBoundsSnapshot = state.lastOutOfBoundsSnapshot ?: [:]
    state.lastClustersSnapshot = state.lastClustersSnapshot ?: [:]
    state.recommendation = state.recommendation ?: null
    state.activeDevices = state.activeDevices ?: [:]
    state.activationStart = state.activationStart ?: [:]
    state.autoBustedZones = state.autoBustedZones ?: [:]
    state.lastMode = state.lastMode ?: location.mode
    state.dayIndex = state.dayIndex ?: 0
    state.totalDays = state.totalDays ?: 0
}

private updateDetectionState() {
    mmwaveDevices?.each { dev ->
        def devKey = deviceKey(dev.id)
        def shouldBeActive = isDeviceActive(dev)
        def wasActive = state.activeDevices[devKey] ?: false

        debugLog("${dev.displayName} shouldBeActive=${shouldBeActive}, wasActive=${wasActive}")

        if (shouldBeActive && !wasActive) {
            activateDetection(dev)
        }

        if (!shouldBeActive && wasActive) {
            deactivateDetection(dev)
        }

        state.activeDevices[devKey] = shouldBeActive
    }
}

private boolean isDeviceActive(dev) {
    if (!dev) {
        return false
    }

    def modeMatches = (ghostModes ?: []).contains(location.mode)
    if (!modeMatches) {
        return false
    }

    if (activationMode != "Conditioned On Virtual Switch") {
        return true
    }

    def child = getChildDevice(childDni(dev.id))
    return child?.currentSwitch == "on"
}

private activateDetection(dev) {
    sendMmWaveBind(dev)
    state.activeDevices[deviceKey(dev.id)] = true
    state.activationStart[deviceKey(dev.id)] = now()
    infoLog("Ghost detection activated for ${dev.displayName}")

    if (notifyOnActivate) {
        sendNotification("Ghost detection activated for ${dev.displayName}")
    }
}

private deactivateDetection(dev) {
    sendMmWaveUnBind(dev)
    state.activeDevices[deviceKey(dev.id)] = false
    infoLog("Ghost detection deactivated for ${dev.displayName}")

    if (notifyOnDeactivate) {
        def counts = getGhostCounts(deviceKey(dev.id), getTodayClusters(dev.id))
        sendNotification("""Ghost detection deactivated for ${dev.displayName}
Ghosts today: ${counts.ghostsToday}
Persistent ghosts: ${counts.persistentGhosts}
Busted ghosts: ${counts.bustedGhosts}""")
    }
}

private sendMmWaveBind(dev) {
    if (!dev) {
        return
    }

    dev.updateSetting("parameter107", [value: "1", type: "enum"])
    dev.configure("All")
}

private sendMmWaveUnBind(dev) {
    if (!dev) {
        return
    }

    dev.updateSetting("parameter107", [value: "0", type: "enum"])
    dev.configure("All")
}

private sendNotification(String message) {
    if (!sendPush || !message) {
        return
    }

    if (notifyDevices) {
        notifyDevices*.deviceNotification(message)
    } else {
        sendPushMessage(message)
    }
}

def syncChildDevices() {
    def selectedDeviceKeys = (mmwaveDevices ?: []).collect { deviceKey(it.id) }

    getChildDevices()?.each { child ->
        def devKey = child.deviceNetworkId?.replace("${app.id}-", "")
        if (activationMode != "Conditioned On Virtual Switch" || !selectedDeviceKeys.contains(devKey)) {
            deleteChildDevice(child.deviceNetworkId)
        }
    }

    if (activationMode != "Conditioned On Virtual Switch") {
        return
    }

    mmwaveDevices?.each { dev ->
        def dni = childDni(dev.id)
        if (!getChildDevice(dni)) {
            addChildDevice("hubitat", "Virtual Switch", dni, [label: "${dev.displayName} Ghost Detection Activator"])
        }
    }
}

private Map getGhostGateStatus() {
    def status = [existing: [], toCreate: [], toDelete: []]
    def desiredDnis = (mmwaveDevices ?: []).collect { childDni(it.id) }
    def existingChildren = getChildDevices() ?: []

    existingChildren.each { child ->
        status.existing << child.label
        if (activationMode != "Conditioned On Virtual Switch" || !desiredDnis.contains(child.deviceNetworkId)) {
            status.toDelete << child.label
        }
    }

    if (activationMode == "Conditioned On Virtual Switch") {
        mmwaveDevices?.each { dev ->
            def dni = childDni(dev.id)
            if (!existingChildren.find { it.deviceNetworkId == dni }) {
                status.toCreate << "${dev.displayName} Ghost Detection Activator"
            }
        }
    }

    status
}

def modeHandler(evt) {
    debugLog("Mode change detected: ${state.lastMode} -> ${evt.value}")

    if (boundaryType == "Mode Boundary" && resetMode) {
        def enteringBoundary = resetModeEnterExit == "Entering" && evt.value == resetMode
        def exitingBoundary = resetModeEnterExit == "Exiting" && state.lastMode == resetMode && evt.value != resetMode

        if (enteringBoundary || exitingBoundary) {
            endOfDay()
        }
    }

    state.lastMode = evt.value
    updateDetectionState()
}

def activatorSwitchHandler(evt) {
    debugLog("Activator switch changed: ${evt.device?.displayName} -> ${evt.value}")
    updateDetectionState()
}

def targetInfoHandler(evt) {
    def dev = evt?.device
    if (!dev) {
        return
    }

    if (!isDeviceActive(dev)) {
        debugLog("Dropped targetInfo for ${dev.displayName}: device inactive")
        return
    }

    def payload = parseTargetPayload(evt.value)
    if (!payload) {
        debugLog("Dropped targetInfo for ${dev.displayName}: malformed payload")
        return
    }

    def result = extractPoints(payload, dev)
    if (!result.inBounds && !result.outOfBounds) {
        debugLog("Dropped targetInfo for ${dev.displayName}: no valid targets")
        return
    }

    def devKey = deviceKey(dev.id)

    // Store in-bounds points (used for ghost detection)
    def currentPoints = ((state.dailyPoints[devKey] ?: []) as List).collect { it }
    currentPoints.addAll(result.inBounds)
    state.dailyPoints[devKey] = currentPoints

    // Store out-of-bounds points (for graphing only, if enabled)
    if (result.outOfBounds) {
        def currentOutOfBounds = ((state.dailyOutOfBoundsPoints[devKey] ?: []) as List).collect { it }
        currentOutOfBounds.addAll(result.outOfBounds)
        state.dailyOutOfBoundsPoints[devKey] = currentOutOfBounds
        debugLog("Stored ${result.inBounds.size()} in-bounds and ${result.outOfBounds.size()} out-of-bounds points for ${dev.displayName}")
    } else {
        debugLog("Stored ${result.inBounds.size()} points for ${dev.displayName}; total=${currentPoints.size()}")
    }
}

private parseTargetPayload(String rawValue) {
    if (!rawValue) {
        return null
    }

    try {
        return new JsonSlurper().parseText(rawValue)
    } catch (Exception ex) {
        warnLog("Unable to parse targetInfo JSON: ${ex.message}")
        return null
    }
}

private Map extractPoints(def payload, dev = null) {
    def targets = []

    if (payload instanceof Map) {
        if (payload.targets instanceof Collection) {
            targets = payload.targets as List
        } else if (payload.target instanceof Map) {
            targets = [payload.target]
        } else if (payload.x != null && payload.y != null && payload.z != null) {
            targets = [payload]
        }
    } else if (payload instanceof Collection) {
        targets = payload as List
    }

    def bounds = filterPointsByDeviceBounds && dev ? getDeviceBounds(dev) : null
    def captureOOB = captureOutOfBoundsPoints && bounds

    def inBoundsPoints = []
    def outOfBoundsPoints = []

    targets.each { target ->
        def x = toDouble(target?.x)
        def y = toDouble(target?.y)
        def z = toDouble(target?.z)

        if (x == null || y == null || z == null) {
            return
        }

        def xMeters = x / 1000.0d
        def yMeters = y / 1000.0d
        def zMeters = z / 1000.0d

        def point = [
                x: xMeters,
                y: yMeters,
                z: zMeters
        ]

        // Apply bounds filtering if enabled
        if (bounds) {
            if (isPointWithinBounds(xMeters, yMeters, zMeters, bounds)) {
                inBoundsPoints << point
            } else if (captureOOB) {
                // Capture out-of-bounds points for visualization
                outOfBoundsPoints << point
            }
            // else: completely discard out-of-bounds points
        } else {
            // No filtering enabled, all points are in-bounds
            inBoundsPoints << point
        }
    }

    [inBounds: inBoundsPoints, outOfBounds: outOfBoundsPoints]
}

def endOfDay() {
    state.dayIndex = (state.dayIndex ?: 0) + 1
    state.totalDays = Math.min((state.totalDays ?: 0) + 1, safeHistoryDays())

    def summaries = [:]

    mmwaveDevices?.each { dev ->
        def devKey = deviceKey(dev.id)
        def points = getPointsForDevice(dev.id)
        def outOfBoundsPoints = getOutOfBoundsPointsForDevice(dev.id)
        def clustersToday = detectClusters(points)
        def unclusteredPointCount = calculateUnclusteredPointCount(points, clustersToday)

        updateStabilityForDay(dev.id, clustersToday)
        applyAutomaticGhostBusting(dev)

        def counts = getGhostCounts(devKey, clustersToday)
        summaries[devKey] = counts + [
                pointCount: points.size(),
                unclusteredPointCount: unclusteredPointCount,
                outOfBoundsPointCount: outOfBoundsPoints.size()
        ]
        state.lastPointsSnapshot[devKey] = points.collect { clonePoint(it) }
        state.lastOutOfBoundsSnapshot[devKey] = outOfBoundsPoints.collect { clonePoint(it) }
        state.lastClustersSnapshot[devKey] = clustersToday.collect { snapshotCluster(it) }

        debugLog("Processed ${dev.displayName}: points=${points.size()}, out-of-bounds=${outOfBoundsPoints.size()}, unclustered=${unclusteredPointCount}, clusters=${clustersToday.size()}, persistent=${counts.persistentGhosts}, busted=${counts.bustedGhosts}")
    }

    state.dailySummary = summaries
    state.dailyPoints = [:]
    state.dailyOutOfBoundsPoints = [:]
    pruneOldActivationStarts()

    if (sendPush && notifyDailySummary) {
        sendNotification(buildDailySummary())
    }
}

private updateStabilityForDay(deviceId, List clustersToday) {
    def devKey = deviceKey(deviceId)
    def currentDay = state.dayIndex ?: 0
    def historyCutoff = Math.max(1, currentDay - safeHistoryDays() + 1)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    def remainingStable = stableClusters.collect { it }

    clustersToday.each { cluster ->
        def match = findBestMatch(cluster, remainingStable)

        if (match) {
            mergeDailyCluster(match, cluster, currentDay, historyCutoff)
            remainingStable.remove(match)
        } else {
            stableClusters << buildStableCluster(cluster, currentDay)
        }
    }

    remainingStable.each { cluster ->
        pruneSeenHistory(cluster, historyCutoff)
        cluster.daysSeen = cluster.seenHistory.size()
        cluster.absentStreak = (cluster.absentStreak ?: 0) + 1
        cluster.consecutiveSeen = 0
    }

    stableClusters.each { cluster ->
        pruneSeenHistory(cluster, historyCutoff)
        cluster.daysSeen = cluster.seenHistory.size()
    }

    state.stabilityData[devKey] = stableClusters.findAll { cluster ->
        cluster.daysSeen > 0 || (cluster.absentStreak ?: 0) < safeHistoryDays()
    }
}

private Map findBestMatch(Map cluster, List stableClusters) {
    def candidates = stableClusters.findAll {
        distance3D(it.center, cluster.center) <= safeClusterRadius()
    }

    if (!candidates) {
        return null
    }

    candidates.min { distance3D(it.center, cluster.center) }
}

private void mergeDailyCluster(Map stableCluster, Map dailyCluster, Integer currentDay, Integer historyCutoff) {
    stableCluster.center = dailyCluster.center
    stableCluster.bounds = dailyCluster.bounds
    stableCluster.radius = dailyCluster.radius
    stableCluster.density = dailyCluster.density
    stableCluster.points = dailyCluster.points  // Store points for cluster splitting
    stableCluster.lastSeen = currentDay
    stableCluster.seenHistory = ((stableCluster.seenHistory ?: []) + currentDay).unique().sort()
    pruneSeenHistory(stableCluster, historyCutoff)
    stableCluster.daysSeen = stableCluster.seenHistory.size()
    stableCluster.absentStreak = 0
    stableCluster.consecutiveSeen = ((stableCluster.lastMatchedDay ?: 0) == currentDay - 1) ?
            ((stableCluster.consecutiveSeen ?: 0) + 1) : 1
    stableCluster.lastMatchedDay = currentDay
}

private Map buildStableCluster(Map dailyCluster, Integer currentDay) {
    [
            center: clonePoint(dailyCluster.center),
            bounds: cloneBounds(dailyCluster.bounds),
            radius: dailyCluster.radius,
            density: dailyCluster.density,
            points: dailyCluster.points?.collect { clonePoint(it) },  // Store points for cluster splitting
            daysSeen: 1,
            lastSeen: currentDay,
            seenHistory: [currentDay],
            consecutiveSeen: 1,
            absentStreak: 0,
            lastMatchedDay: currentDay,
            bustMode: null,
            appliedBounds: null
    ]
}

private void pruneSeenHistory(Map stableCluster, Integer historyCutoff) {
    stableCluster.seenHistory = (stableCluster.seenHistory ?: []).findAll { it >= historyCutoff }.sort()
}

private String buildDailySummary() {
    if (!mmwaveDevices) {
        return "No mmWave devices configured."
    }

    def lines = ["Daily Ghost Report"]
    mmwaveDevices.each { dev ->
        def summary = getSummaryForDevice(dev.id)
        lines << ""
        lines << "${dev.displayName}:"
        lines << "Points processed: ${summary.pointCount}"
        if (summary.outOfBoundsPointCount) {
            lines << "Out-of-bounds points: ${summary.outOfBoundsPointCount}"
        }
        lines << "Non-cluster points: ${summary.unclusteredPointCount ?: 0}"
        lines << "Ghosts today: ${summary.ghostsToday}"
        lines << "Persistent ghosts: ${summary.persistentGhosts}"
        lines << "Busted ghosts: ${summary.bustedGhosts}"
        lines << "Auto-busted persistent: ${summary.autoBusted ?: 0}"
        lines << "Manual-busted persistent: ${summary.manualBusted ?: 0}"
        lines << "Unbusted persistent: ${summary.unbusted ?: 0}"
    }

    lines.join("\n")
}

private void pruneOldActivationStarts() {
    if ((autoDisableDays ?: 0) <= 0 || activationMode != "Conditioned On Virtual Switch") {
        return
    }

    def cutoff = now() - ((autoDisableDays as Integer) * 24L * 60L * 60L * 1000L)
    def expiredKeys = []
    (state.activationStart ?: [:]).each { devKey, startedAt ->
        if (startedAt && startedAt < cutoff) {
            def child = getChildDevice("${app.id}-${devKey}")
            if (child?.currentSwitch == "on") {
                child.off()
                debugLog("Auto-disabled activator ${child.displayName}")
            }
            expiredKeys << devKey
        }
    }
    expiredKeys.each { state.activationStart.remove(it) }
}

private List getTodayClusters(deviceId) {
    detectClusters(getPointsForDevice(deviceId))
}

private List detectClusters(List points) {
    if (!points || points.size() < safeMinClusterEvents()) {
        return []
    }

    def algorithm = clusteringAlgorithm ?: "DBSCAN"
    def clusters = []

    if (algorithm == "K-Means") {
        clusters = detectClustersKMeans(points, safeMaxClusters(), 40)
        if (!clusters) {
            clusters = detectClustersDBSCAN(points, safeClusterRadius(), safeMinClusterEvents())
        }
    } else {
        clusters = detectClustersDBSCAN(points, safeClusterRadius(), safeMinClusterEvents())
        if (!clusters) {
            clusters = detectClustersKMeans(points, safeMaxClusters(), 40)
        }
    }

    clusters
}

def detectClustersKMeans(List points, Integer k, Integer maxIterations) {
    if (!points) {
        return []
    }

    k = Math.max(1, Math.min(k ?: 1, points.size()))
    def centroids = points.take(k).collect { clonePoint(it) }
    def assignments = new ArrayList(points.size())
    def converged = false

    (maxIterations ?: 40).times {
        assignments = points.collect { point ->
            def distances = centroids.collect { centroid -> distance3D(point, centroid) }
            distances.indexOf(distances.min())
        }

        def newCentroids = (0..<k).collect { idx ->
            def clusterPoints = []
            points.eachWithIndex { point, pointIdx ->
                if (assignments[pointIdx] == idx) {
                    clusterPoints << point
                }
            }

            if (!clusterPoints) {
                return centroids[idx]
            }

            [
                    x: clusterPoints.collect { it.x }.sum() / clusterPoints.size(),
                    y: clusterPoints.collect { it.y }.sum() / clusterPoints.size(),
                    z: clusterPoints.collect { it.z }.sum() / clusterPoints.size()
            ]
        }

        converged = (0..<k).every { idx ->
            distance3D(newCentroids[idx], centroids[idx]) < 0.01d
        }

        centroids = newCentroids
        if (converged) {
            return false
        }

        return true
    }

    def clusters = []
    (0..<k).each { idx ->
        def clusterPoints = []
        points.eachWithIndex { point, pointIdx ->
            if (assignments[pointIdx] == idx) {
                clusterPoints << point
            }
        }

        if (clusterPoints.size() >= safeMinClusterEvents()) {
            clusters << buildCluster(clusterPoints)
        }
    }

    clusters
}

private List detectClustersDBSCAN(List points, BigDecimal eps, Integer minPts) {
    if (!points) {
        return []
    }

    def visited = [] as Set
    def assigned = [] as Set
    def clusters = []

    points.eachWithIndex { point, idx ->
        if (visited.contains(idx)) {
            return
        }

        visited << idx
        def neighbors = regionQuery(points, point, eps)
        if (neighbors.size() < minPts) {
            return
        }

        def clusterIndexes = [] as Set
        expandCluster(points, idx, neighbors, clusterIndexes, visited, assigned, eps, minPts)

        def clusterPoints = clusterIndexes.collect { points[it] }
        if (clusterPoints.size() >= minPts) {
            clusters << buildCluster(clusterPoints)
            assigned.addAll(clusterIndexes)
        }
    }

    clusters
}

private void expandCluster(List points, Integer seedIdx, List neighbors, Set clusterIndexes, Set visited, Set assigned, BigDecimal eps, Integer minPts) {
    def queue = [seedIdx] + neighbors

    while (queue) {
        def idx = queue.remove(0)

        if (!visited.contains(idx)) {
            visited << idx
            def extraNeighbors = regionQuery(points, points[idx], eps)
            if (extraNeighbors.size() >= minPts) {
                queue.addAll(extraNeighbors.findAll { !queue.contains(it) })
            }
        }

        if (!assigned.contains(idx)) {
            clusterIndexes << idx
        }
    }
}

private List regionQuery(List points, Map origin, BigDecimal eps) {
    def neighbors = []

    points.eachWithIndex { point, idx ->
        if (distance3D(origin, point) <= eps) {
            neighbors << idx
        }
    }

    neighbors
}

private Map buildCluster(List points) {
    def xs = points.collect { it.x }
    def ys = points.collect { it.y }
    def zs = points.collect { it.z }
    def center = [
            x: xs.sum() / xs.size(),
            y: ys.sum() / ys.size(),
            z: zs.sum() / zs.size()
    ]
    def bounds = [
            xmin: xs.min(),
            xmax: xs.max(),
            ymin: ys.min(),
            ymax: ys.max(),
            zmin: zs.min(),
            zmax: zs.max()
    ]

    def maxDistance = points.collect { point -> distance3D(point, center) }.max() ?: 0.0d

    [
            center: center,
            bounds: bounds,
            radius: maxDistance,
            density: points.size(),
            points: points.collect { clonePoint(it) }
    ]
}

private Map getGhostCounts(String devKey, List todayClusters) {
    def stableClusters = (state.stabilityData[devKey] ?: []) as List
    def bustCounts = getPersistentBustSourceCounts(stableClusters)

    [
            ghostsToday: todayClusters?.size() ?: 0,
            persistentGhosts: stableClusters.count { (it.consecutiveSeen ?: 0) >= safePersistentGhostDays() },
            bustedGhosts: stableClusters.count { (it.absentStreak ?: 0) >= safeBustedGhostDays() },
            autoBusted: bustCounts.autoBusted,
            manualBusted: bustCounts.manualBusted,
            unbusted: bustCounts.unbusted
    ]
}

def generateRecommendation() {
    def totalDays = state.totalDays ?: 0
    if (totalDays <= 0) {
        state.recommendation = [message: "Insufficient data to generate a recommendation."]
        return
    }

    def best = null
    def bestScore = -1.0d

    (state.stabilityData ?: [:]).each { devKey, clusters ->
        (clusters ?: []).each { cluster ->
            def stabilityPct = calculateStabilityPercent(cluster)
            if (stabilityPct >= safeStableThreshold() && stabilityPct > bestScore) {
                best = [
                        deviceId: devKey,
                        center: clonePoint(cluster.center),
                        bounds: cloneBounds(cluster.bounds),
                        radius: cluster.radius,
                        density: cluster.density,
                        stabilityPct: stabilityPct
                ]
                bestScore = stabilityPct
            }
        }
    }

    if (!best) {
        state.recommendation = [message: "No stable cluster currently meets the threshold."]
        return
    }

    def dev = mmwaveDevices?.find { deviceKey(it.id) == best.deviceId }
    best.deviceName = dev?.displayName ?: best.deviceId
    state.recommendation = best
}

private void applySelectedZones(String devKey) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
    if (!dev) {
        return
    }

    def selected = settings["blockClusters_${devKey}"]
    if (!selected) {
        return
    }

    def clusters = getSelectableClusters(dev.id)
    def selectedClusters = selected.collect { rawIdx -> clusters[(rawIdx as Integer)] }.findAll { it }
    def appliedBoundsList = applyZonesToDevice(dev, selectedClusters.collect { [cluster: it, bounds: cloneBounds(it.bounds)] }, "manual selection")
    updateClusterBustMode(dev.id, selectedClusters, appliedBoundsList, "manual")
}

private void applyAutomaticGhostBusting(dev) {
    if (!enableAutoGhostBusting) {
        clearClusterBustMode(dev.id, "auto")
        state.autoBustedZones[deviceKey(dev.id)] = []
        return
    }

    def autoBounds = getAutoGhostBustBoundary()
    if (!isValidBounds(autoBounds)) {
        clearClusterBustMode(dev.id, "auto")
        state.autoBustedZones[deviceKey(dev.id)] = []
        warnLog("Automatic ghost busting is enabled, but the configured boundary is invalid.")
        return
    }

    def devKey = deviceKey(dev.id)
    def stableClusters = getStableClusters(dev.id)
    clearClusterBustMode(dev.id, "auto")
    def matchingClusters = stableClusters.collect { cluster ->
        if ((cluster.consecutiveSeen ?: 0) < safePersistentGhostDays()) {
            return null
        }

        def appliedBounds = getAutoAppliedBoundsForCluster(cluster, autoBounds)
        if (!appliedBounds) {
            return null
        }

        [cluster: cluster, bounds: appliedBounds]
    }.findAll { it }

    if (!matchingClusters) {
        state.autoBustedZones[devKey] = []
        return
    }

    def signature = matchingClusters.collect { integerBounds(it.bounds) }
    if (state.autoBustedZones[devKey] == signature) {
        debugLog("Skipping auto ghost busting for ${dev.displayName}; matching bounds are unchanged.")
        updateClusterBustMode(dev.id, matchingClusters.collect { it.cluster }, matchingClusters.collect { it.bounds }, "auto")
        return
    }

    def appliedBoundsList = applyZonesToDevice(dev, matchingClusters, "automatic ghost busting")
    updateClusterBustMode(dev.id, matchingClusters.collect { it.cluster }, appliedBoundsList, "auto")
    state.autoBustedZones[devKey] = appliedBoundsList.collect { integerBounds(it) }
}

private List applyZonesToDevice(dev, List zoneSpecs, String sourceLabel) {
    def appliedBoundsList = []

    (zoneSpecs ?: []).eachWithIndex { zoneSpec, zoneIdx ->
        def zoneBounds = zoneSpec.bounds
        if (!isValidBounds(zoneBounds)) {
            warnLog("Skipped invalid interference bounds for ${dev.displayName} from ${sourceLabel}")
            return
        }

        def bounds = integerBounds(zoneBounds)
        debugLog("Applying zone ${zoneIdx + 1} to ${dev.displayName} from ${sourceLabel}: ${JsonOutput.toJson(bounds)}")
        dev.mmWaveSetInterferenceArea(
                zoneIdx + 1,
                bounds.xmin,
                bounds.xmax,
                bounds.ymin,
                bounds.ymax,
                bounds.zmin,
                bounds.zmax
        )
        appliedBoundsList << cloneBounds(zoneBounds)
    }

    appliedBoundsList
}

private void splitCluster(String clusterKey) {
    // Parse clusterKey format: "deviceKey_cluster_index"
    def parts = clusterKey.split("_cluster_")
    if (parts.size() != 2) {
        warnLog("Invalid cluster key format: ${clusterKey}")
        return
    }

    def devKey = parts[0]
    def clusterIndex = (parts[1] as Integer) - 1  // Convert to 0-based index

    // Get the cluster and split radius
    def stableClusters = (state.stabilityData[devKey] ?: []) as List
    if (clusterIndex < 0 || clusterIndex >= stableClusters.size()) {
        warnLog("Invalid cluster index: ${clusterIndex}")
        return
    }

    def cluster = stableClusters[clusterIndex]
    def splitRadiusInput = settings["splitClusterRadius_${clusterKey}"]
    def splitRadius = splitRadiusInput ? (splitRadiusInput as BigDecimal) : (cluster.radius * 0.5)

    if (!cluster.points || cluster.points.size() < safeMinClusterEvents() * 2) {
        warnLog("Cluster ${clusterIndex + 1} doesn't have enough points to split")
        return
    }

    // Re-cluster the points with tighter radius
    def subClusters = detectClustersDBSCAN(cluster.points, splitRadius, safeMinClusterEvents())

    if (!subClusters || subClusters.size() <= 1) {
        infoLog("Cluster ${clusterIndex + 1} could not be split with radius ${splitRadius}m - no sub-clusters found")
        return
    }

    infoLog("Successfully split cluster ${clusterIndex + 1} into ${subClusters.size()} sub-clusters")

    // Remove the original cluster
    stableClusters.remove(clusterIndex)

    // Add the new sub-clusters, preserving stability data where applicable
    subClusters.each { subCluster ->
        def newCluster = buildStableCluster(subCluster, state.dayIndex ?: 0)
        // Copy stability history from parent cluster
        newCluster.seenHistory = cluster.seenHistory ?: []
        newCluster.daysSeen = cluster.daysSeen ?: 0
        newCluster.lastSeen = cluster.lastSeen ?: 0
        newCluster.consecutiveSeen = cluster.consecutiveSeen ?: 0
        newCluster.absentStreak = cluster.absentStreak ?: 0
        newCluster.lastMatchedDay = cluster.lastMatchedDay ?: 0
        stableClusters << newCluster
    }

    state.stabilityData[devKey] = stableClusters
}

private void reclusterDevice(String devKey) {
    def stableClusters = (state.stabilityData[devKey] ?: []) as List

    if (!stableClusters) {
        infoLog("No stable clusters found for device ${devKey}")
        return
    }

    // Try to collect points from stable clusters first
    def allPoints = []
    stableClusters.each { cluster ->
        if (cluster.points) {
            allPoints.addAll(cluster.points)
        }
    }

    // If no points in stable clusters (old clusters before fix), use snapshot points
    if (!allPoints) {
        def snapshotPoints = (state.lastPointsSnapshot[devKey] ?: []) as List
        if (snapshotPoints) {
            allPoints.addAll(snapshotPoints)
            infoLog("Using ${allPoints.size()} snapshot points for reclustering (stable clusters don't have points stored)")
        }
    }

    // If still no points, try current daily points
    if (!allPoints) {
        def dailyPoints = (state.dailyPoints[devKey] ?: []) as List
        if (dailyPoints) {
            allPoints.addAll(dailyPoints)
            infoLog("Using ${allPoints.size()} current daily points for reclustering")
        }
    }

    if (!allPoints) {
        infoLog("No points available for reclustering device ${devKey}")
        return
    }

    infoLog("Reclustering ${allPoints.size()} points for device ${devKey}")

    // Re-run clustering on all points
    def newClusters = detectClusters(allPoints)

    if (!newClusters) {
        infoLog("No clusters detected after reclustering")
        return
    }

    // Replace stable clusters with new clusters, preserving the current day index
    def currentDay = state.dayIndex ?: 0
    def newStableClusters = []

    newClusters.each { cluster ->
        def newStable = buildStableCluster(cluster, currentDay)
        newStableClusters << newStable
    }

    state.stabilityData[devKey] = newStableClusters

    infoLog("Reclustering complete: ${newClusters.size()} new clusters created from ${allPoints.size()} points")
}

private void clearDeviceStats(String devKey) {
    state.dailyPoints?.remove(devKey)
    state.dailyOutOfBoundsPoints?.remove(devKey)
    state.stabilityData?.remove(devKey)
    state.dailySummary?.remove(devKey)
    state.lastPointsSnapshot?.remove(devKey)
    state.lastOutOfBoundsSnapshot?.remove(devKey)
    state.lastClustersSnapshot?.remove(devKey)
    state.autoBustedZones?.remove(devKey)

    if (state.recommendation?.deviceId == devKey) {
        state.recommendation = [message: "Recommendation cleared because that device's statistics were reset."]
    }
}

private boolean isValidBounds(Map bounds) {
    if (!bounds) {
        return false
    }

    def values = [bounds.xmin, bounds.xmax, bounds.ymin, bounds.ymax, bounds.zmin, bounds.zmax]
    if (values.any { it == null }) {
        return false
    }

    bounds.xmin <= bounds.xmax &&
            bounds.ymin <= bounds.ymax &&
            bounds.zmin <= bounds.zmax
}

private Map integerBounds(Map bounds) {
    [
            xmin: Math.round((bounds.xmin ?: 0.0d) * 1000.0d) as Integer,
            xmax: Math.round((bounds.xmax ?: 0.0d) * 1000.0d) as Integer,
            ymin: Math.round((bounds.ymin ?: 0.0d) * 1000.0d) as Integer,
            ymax: Math.round((bounds.ymax ?: 0.0d) * 1000.0d) as Integer,
            zmin: Math.round((bounds.zmin ?: 0.0d) * 1000.0d) as Integer,
            zmax: Math.round((bounds.zmax ?: 0.0d) * 1000.0d) as Integer
    ]
}

private List getPointsForDevice(deviceId) {
    ((state.dailyPoints[deviceKey(deviceId)] ?: []) as List).collect { clonePoint(it) }
}

private List getOutOfBoundsPointsForDevice(deviceId) {
    ((state.dailyOutOfBoundsPoints[deviceKey(deviceId)] ?: []) as List).collect { clonePoint(it) }
}

private List getStableClusters(deviceId) {
    def devKey = deviceKey(deviceId)
    ((state.stabilityData[devKey] ?: []) as List).collect { cluster ->
        def clone = cloneCluster(cluster)
        clone.stabilityPct = calculateStabilityPercent(cluster)
        clone
    }
}

private Map getDisplayData(deviceId) {
    def currentPoints = getPointsForDevice(deviceId)
    def lastPoints = getSnapshotPoints(deviceId)
    def currentOutOfBounds = getOutOfBoundsPointsForDevice(deviceId)
    def lastOutOfBounds = getSnapshotOutOfBoundsPoints(deviceId)
    def currentClusters = getTodayClusters(deviceId)
    def historicalClusters = getHistoricalClustersForDisplay(deviceId)

    [
            points: currentPoints ?: lastPoints,
            outOfBoundsPoints: currentOutOfBounds ?: lastOutOfBounds,
            currentClusters: currentClusters,
            historicalClusters: historicalClusters,
            selectableClusters: buildSelectableClusters(currentClusters, historicalClusters)
    ]
}

private Map getSummaryForDevice(deviceId) {
    def summary = state.dailySummary?.get(deviceKey(deviceId)) ?: [
            ghostsToday: 0,
            persistentGhosts: 0,
            bustedGhosts: 0,
            autoBusted: 0,
            manualBusted: 0,
            unbusted: 0,
            pointCount: 0,
            unclusteredPointCount: 0
    ]

    if (summary.unclusteredPointCount == null) {
        summary = summary + [
                unclusteredPointCount: ((summary.ghostsToday ?: 0) == 0 ? (summary.pointCount ?: 0) : 0)
        ]
    }

    summary
}

private Map getDisplayCounts(deviceId) {
    def devKey = deviceKey(deviceId)
    def liveClusters = getTodayClusters(deviceId)
    def liveCounts = getGhostCounts(devKey, liveClusters)

    if (getPointsForDevice(deviceId) || liveClusters) {
        return liveCounts
    }

    def summary = getSummaryForDevice(deviceId)
    [
            ghostsToday: summary.ghostsToday ?: 0,
            persistentGhosts: summary.persistentGhosts ?: 0,
            bustedGhosts: summary.bustedGhosts ?: 0,
            autoBusted: summary.autoBusted ?: 0,
            manualBusted: summary.manualBusted ?: 0,
            unbusted: summary.unbusted ?: 0
    ]
}

private List getSnapshotPoints(deviceId) {
    ((state.lastPointsSnapshot?.get(deviceKey(deviceId)) ?: []) as List).collect { clonePoint(it) }
}

private List getSnapshotOutOfBoundsPoints(deviceId) {
    ((state.lastOutOfBoundsSnapshot?.get(deviceKey(deviceId)) ?: []) as List).collect { clonePoint(it) }
}

private List getHistoricalClustersForDisplay(deviceId) {
    def devKey = deviceKey(deviceId)
    def snapshotClusters = ((state.lastClustersSnapshot?.get(devKey) ?: []) as List).collect { snapshotCluster(it) }
    def stableClusters = getStableClusters(deviceId).collect { snapshotCluster(it) }
    dedupeClusters(snapshotClusters + stableClusters)
}

private List getSelectableClusters(deviceId) {
    def displayData = getDisplayData(deviceId)
    displayData.selectableClusters
}

private List buildSelectableClusters(List currentClusters, List historicalClusters) {
    def combined = []
    combined.addAll((currentClusters ?: []).collect { snapshotCluster(it) })
    combined.addAll((historicalClusters ?: []).collect { snapshotCluster(it) })
    dedupeClusters(combined)
}

private List dedupeClusters(List clusters) {
    def deduped = []
    (clusters ?: []).each { cluster ->
        def duplicate = deduped.find { existing ->
            distance3D(existing.center, cluster.center) <= 0.05d
        }
        if (!duplicate) {
            deduped << snapshotCluster(cluster)
        }
    }
    deduped
}

private Integer calculateUnclusteredPointCount(List points, List clusters) {
    Math.max(0, (points?.size() ?: 0) - ((clusters ?: []).collect { it.points?.size() ?: 0 }.sum() ?: 0))
}

private Map getAutoGhostBustBoundary() {
    [
            xmin: centimetersToMeters(autoBustBoundaryXMin),
            xmax: centimetersToMeters(autoBustBoundaryXMax),
            ymin: centimetersToMeters(autoBustBoundaryYMin),
            ymax: centimetersToMeters(autoBustBoundaryYMax),
            zmin: centimetersToMeters(autoBustBoundaryZMin),
            zmax: centimetersToMeters(autoBustBoundaryZMax)
    ]
}

private Map getAutoAppliedBoundsForCluster(Map cluster, Map boundary) {
    if (!cluster?.bounds || !isValidBounds(boundary)) {
        return null
    }

    if (autoBustMode == "Apply the overlapping part of the cluster, clamped to the boundary") {
        return intersectBounds(cluster.bounds, boundary)
    }

    if (isBoundsWithinBounds(cluster.bounds, boundary)) {
        return cloneBounds(cluster.bounds)
    }

    null
}

private Map intersectBounds(Map a, Map b) {
    if (!a || !b) {
        return null
    }

    def intersection = [
            xmin: Math.max(a.xmin ?: 0.0d, b.xmin ?: 0.0d),
            xmax: Math.min(a.xmax ?: 0.0d, b.xmax ?: 0.0d),
            ymin: Math.max(a.ymin ?: 0.0d, b.ymin ?: 0.0d),
            ymax: Math.min(a.ymax ?: 0.0d, b.ymax ?: 0.0d),
            zmin: Math.max(a.zmin ?: 0.0d, b.zmin ?: 0.0d),
            zmax: Math.min(a.zmax ?: 0.0d, b.zmax ?: 0.0d)
    ]

    isValidBounds(intersection) ? intersection : null
}

private boolean isBoundsWithinBounds(Map inner, Map outer) {
    if (!isValidBounds(inner) || !isValidBounds(outer)) {
        return false
    }

    inner.xmin >= outer.xmin && inner.xmax <= outer.xmax &&
            inner.ymin >= outer.ymin && inner.ymax <= outer.ymax &&
            inner.zmin >= outer.zmin && inner.zmax <= outer.zmax
}

private void updateClusterBustMode(deviceId, List sourceClusters, List appliedBoundsList, String bustMode) {
    def devKey = deviceKey(deviceId)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    def remainingStable = stableClusters.collect { it }

    sourceClusters.eachWithIndex { sourceCluster, idx ->
        def match = findBestMatch(sourceCluster, remainingStable)
        if (!match) {
            return
        }

        if (bustMode == "manual" || match.bustMode != "manual") {
            match.bustMode = bustMode
            match.appliedBounds = cloneBounds(appliedBoundsList[idx])
        }
        remainingStable.remove(match)
    }

    state.stabilityData[devKey] = stableClusters
}

private void clearClusterBustMode(deviceId, String bustMode) {
    def devKey = deviceKey(deviceId)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    stableClusters.each { cluster ->
        if (cluster.bustMode == bustMode) {
            cluster.bustMode = null
            cluster.appliedBounds = null
        }
    }
    state.stabilityData[devKey] = stableClusters
}

private Map getPersistentBustSourceCounts(List stableClusters) {
    def persistentClusters = (stableClusters ?: []).findAll { (it.consecutiveSeen ?: 0) >= safePersistentGhostDays() }
    [
            autoBusted: persistentClusters.count { it.bustMode == "auto" },
            manualBusted: persistentClusters.count { it.bustMode == "manual" },
            unbusted: persistentClusters.count { !it.bustMode }
    ]
}

private Map getAggregateBustSourceCounts() {
    def totals = [autoBusted: 0, manualBusted: 0, unbusted: 0]
    mmwaveDevices?.each { dev ->
        def counts = getPersistentBustSourceCounts((state.stabilityData[deviceKey(dev.id)] ?: []) as List)
        totals.autoBusted += counts.autoBusted
        totals.manualBusted += counts.manualBusted
        totals.unbusted += counts.unbusted
    }
    totals
}

private Double centimetersToMeters(value) {
    def cm = toDouble(value)
    if (cm == null) {
        return null
    }

    cm / 100.0d
}

private BigDecimal calculateStabilityPercent(Map cluster) {
    def totalDays = state.totalDays ?: 0
    if (totalDays <= 0) {
        return 0.0d
    }

    return (((cluster.daysSeen ?: 0) as BigDecimal) / totalDays) * 100.0d
}

private String renderClusterPlot(List points, List outOfBoundsPoints, List currentClusters, List historicalClusters, dev = null) {
    if (!points && !outOfBoundsPoints && !currentClusters && !historicalClusters) {
        return "No points or cluster history available."
    }

    def allPointsForScale = []
    allPointsForScale.addAll(points ?: [])
    allPointsForScale.addAll(outOfBoundsPoints ?: [])

    // Include cluster bounds (not just centers) for proper scaling
    (currentClusters ?: []).each { cluster ->
        if (cluster.bounds) {
            allPointsForScale << [x: cluster.bounds.xmin, y: cluster.bounds.ymin, z: 0]
            allPointsForScale << [x: cluster.bounds.xmax, y: cluster.bounds.ymax, z: 0]
        }
    }
    (historicalClusters ?: []).each { cluster ->
        if (cluster.bounds) {
            allPointsForScale << [x: cluster.bounds.xmin, y: cluster.bounds.ymin, z: 0]
            allPointsForScale << [x: cluster.bounds.xmax, y: cluster.bounds.ymax, z: 0]
        }
    }

    def xs = allPointsForScale.collect { it.x }
    def ys = allPointsForScale.collect { it.y }
    def minX = xs.min()
    def maxX = xs.max()
    def minY = ys.min()
    def maxY = ys.max()

    // Add 20% padding around the data for better visualization
    def xRange = maxX - minX
    def yRange = maxY - minY
    def xPadding = xRange > 0 ? xRange * 0.2 : 0.5
    def yPadding = yRange > 0 ? yRange * 0.2 : 0.5

    minX -= xPadding
    maxX += xPadding
    minY -= yPadding
    maxY += yPadding

    def width = 360
    def height = 280
    def plotLeft = 44
    def plotRight = width - 16
    def plotTop = 18
    def plotBottom = height - 42

    def normalizeX = { value ->
        if (maxX == minX) {
            return (plotLeft + plotRight) / 2
        }
        plotLeft + (((value - minX) / (maxX - minX)) * (plotRight - plotLeft))
    }

    def normalizeY = { value ->
        if (maxY == minY) {
            return (plotTop + plotBottom) / 2
        }
        plotBottom - (((value - minY) / (maxY - minY)) * (plotBottom - plotTop))
    }

    def svg = new StringBuilder()
    svg << "<svg width='${width}' height='${height}' viewBox='0 0 ${width} ${height}' style='border:1px solid #d0d0d0;background:#fafafa'>"
    svg << "<rect x='${plotLeft}' y='${plotTop}' width='${plotRight - plotLeft}' height='${plotBottom - plotTop}' fill='#ffffff' stroke='#d0d0d0' />"

    // Draw device bounds box if out-of-bounds points exist (gray dashed box)
    if (outOfBoundsPoints && dev) {
        def deviceBounds = getDeviceBounds(dev)
        if (deviceBounds) {
            def x1 = normalizeX(deviceBounds.xmin)
            def x2 = normalizeX(deviceBounds.xmax)
            def y1 = normalizeY(deviceBounds.ymax)
            def y2 = normalizeY(deviceBounds.ymin)
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='none' stroke='#888888' stroke-width='1' stroke-dasharray='4,2' />"
        }
    }

    // Draw cluster bounds boxes (blue dashed box) - behind points
    def allClusters = []
    allClusters.addAll(currentClusters ?: [])
    allClusters.addAll(historicalClusters ?: [])

    allClusters.each { cluster ->
        if (cluster.bounds) {
            def x1 = normalizeX(cluster.bounds.xmin)
            def x2 = normalizeX(cluster.bounds.xmax)
            def y1 = normalizeY(cluster.bounds.ymax)
            def y2 = normalizeY(cluster.bounds.ymin)
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='none' stroke='#1565c0' stroke-width='1' stroke-dasharray='3,2' />"
        }
    }

    svg << "<line x1='${plotLeft}' y1='${plotBottom}' x2='${plotRight}' y2='${plotBottom}' stroke='#666666' stroke-width='1' />"
    svg << "<line x1='${plotLeft}' y1='${plotTop}' x2='${plotLeft}' y2='${plotBottom}' stroke='#666666' stroke-width='1' />"
    svg << "<text x='${(plotLeft + plotRight) / 2}' y='${height - 10}' text-anchor='middle' fill='#333333' font-size='11'>X (meters)</text>"
    svg << "<text x='14' y='${(plotTop + plotBottom) / 2}' text-anchor='middle' fill='#333333' font-size='11' transform='rotate(-90 14 ${(plotTop + plotBottom) / 2})'>Y (meters)</text>"
    svg << "<text x='${plotLeft}' y='${height - 24}' text-anchor='start' fill='#555555' font-size='10'>${round2(minX)}</text>"
    svg << "<text x='${plotRight}' y='${height - 24}' text-anchor='end' fill='#555555' font-size='10'>${round2(maxX)}</text>"
    svg << "<text x='${plotLeft - 6}' y='${plotBottom + 4}' text-anchor='end' fill='#555555' font-size='10'>${round2(minY)}</text>"
    svg << "<text x='${plotLeft - 6}' y='${plotTop + 4}' text-anchor='end' fill='#555555' font-size='10'>${round2(maxY)}</text>"

    def legendY = plotTop + 12
    svg << "<text x='${plotLeft + 6}' y='${legendY}' fill='#ff9800' font-size='10'>Orange = in-bounds</text>"
    legendY += 12
    if (outOfBoundsPoints) {
        svg << "<text x='${plotLeft + 6}' y='${legendY}' fill='#888888' font-size='10'>Gray = out-of-bounds</text>"
        legendY += 12
        svg << "<text x='${plotLeft + 6}' y='${legendY}' fill='#888888' font-size='10'>Gray box = device bounds</text>"
        legendY += 12
    }
    svg << "<text x='${plotLeft + 6}' y='${legendY}' fill='#1565c0' font-size='10'>Blue box = cluster bounds</text>"
    legendY += 12
    svg << "<text x='${plotLeft + 6}' y='${legendY}' fill='#c62828' font-size='10'>Red dot = current cluster</text>"

    // Draw all points (both in-bounds and out-of-bounds)
    (outOfBoundsPoints ?: []).each { point ->
        svg << "<circle cx='${normalizeX(point.x)}' cy='${normalizeY(point.y)}' r='2' fill='#888888' />"
    }

    (points ?: []).each { point ->
        svg << "<circle cx='${normalizeX(point.x)}' cy='${normalizeY(point.y)}' r='2' fill='#ff9800' />"
    }

    // Draw current cluster centers (red dots)
    (currentClusters ?: []).each { cluster ->
        def centerX = normalizeX(cluster.center.x)
        def centerY = normalizeY(cluster.center.y)
        svg << "<circle cx='${centerX}' cy='${centerY}' r='4' fill='#c62828' />"
        svg << "<text x='${centerX + 8}' y='${centerY - 8}' fill='#333333' font-size='10'>${round2(cluster.center.z)}m z</text>"
    }

    svg << "</svg>"
    svg.toString()
}

private String renderClusterDetails(Map cluster, Integer displayIndex, String devKey) {
    def bounds = cluster.bounds ?: [:]
    def status = "Observed"

    if ((cluster.absentStreak ?: 0) >= safeBustedGhostDays()) {
        status = "Busted"
    } else if ((cluster.consecutiveSeen ?: 0) >= safePersistentGhostDays()) {
        status = "Persistent"
    }

    def hasPoints = cluster.points && cluster.points.size() > 0
    def clusterKey = "${devKey}_cluster_${displayIndex}"

    def html = renderStatBlock("Cluster ${displayIndex}: ${status}", [
            "Center": "(${round2(cluster.center.x)}, ${round2(cluster.center.y)}, ${round2(cluster.center.z)}) m",
            "X range": "${round2(bounds.xmin)} to ${round2(bounds.xmax)}",
            "Y range": "${round2(bounds.ymin)} to ${round2(bounds.ymax)}",
            "Z range": "${round2(bounds.zmin)} to ${round2(bounds.zmax)}",
            "Radius": "${round2(cluster.radius)} m",
            "Density": cluster.density ?: 0,
            "Days in window": cluster.daysSeen ?: 0,
            "Consecutive days": cluster.consecutiveSeen ?: 0,
            "Missing streak": cluster.absentStreak ?: 0,
            "Stability": "${round2(cluster.stabilityPct ?: calculateStabilityPercent(cluster))}%",
            "Busting": describeBustMode(cluster)
    ])

    // Add cluster splitting controls if the cluster has points
    if (hasPoints && cluster.density >= safeMinClusterEvents() * 2) {
        html += "<div style='margin-top:8px;'>"
        html += "<input name='splitClusterRadius_${clusterKey}' type='decimal' title='Split radius (meters)' value='${round2(cluster.radius * 0.5)}' style='width:80px;'/> "
        html += "<input name='splitCluster_${clusterKey}' type='button' value='Split Cluster ${displayIndex}' style='margin-left:4px;'/>"
        html += "</div>"
    }

    html
}

private String describeClusterOption(Map cluster, Integer idx) {
    def bounds = cluster.bounds ?: [:]
    "Cluster ${idx + 1}: X ${round2(bounds.xmin)}..${round2(bounds.xmax)}, Y ${round2(bounds.ymin)}..${round2(bounds.ymax)}, Z ${round2(bounds.zmin)}..${round2(bounds.zmax)}"
}

private String renderRecommendationSummary(def recommendation) {
    if (recommendation?.message) {
        return renderNoteCard("Recommendation", recommendation.message as String)
    }

    renderStatBlock("Recommended Exclusion Zone", [
            "Device": recommendation.deviceName,
            "Center": "(${round2(recommendation.center.x)}, ${round2(recommendation.center.y)}, ${round2(recommendation.center.z)}) m",
            "Radius": "${round2(recommendation.radius)} m",
            "Stability": "${round2(recommendation.stabilityPct)}%"
    ])
}

private Map getMainPageOverviewStats() {
    def deviceCount = (mmwaveDevices ?: []).size()
    def activeCount = 0
    mmwaveDevices?.each { dev ->
        if (isDeviceActive(dev)) {
            activeCount += 1
        }
    }

    [
            "Configured devices": deviceCount,
            "Active devices now": activeCount,
            "Detection modes": ghostModes ? ghostModes.size() : 0,
            "Processed days": state.totalDays ?: 0
    ]
}

private String describeAutoBustConfiguration() {
    def boundary = getAutoGhostBustBoundary()
    def mode = (autoBustMode == "Apply the overlapping part of the cluster, clamped to the boundary") ?
            "Clamp to boundary" : "Whole cluster only"

    if (!isValidBounds(boundary)) {
        return "${mode} / boundary incomplete"
    }

    "${mode} / cm X ${autoBustBoundaryXMin}..${autoBustBoundaryXMax}, Y ${autoBustBoundaryYMin}..${autoBustBoundaryYMax}, Z ${autoBustBoundaryZMin}..${autoBustBoundaryZMax}"
}

private String describeBustMode(Map cluster) {
    if ((cluster.consecutiveSeen ?: 0) < safePersistentGhostDays()) {
        return "Not persistent yet"
    }

    if (cluster.bustMode == "auto") {
        return "Automatic"
    }

    if (cluster.bustMode == "manual") {
        return "Manual"
    }

    "Unbusted"
}

private Map getConfigurationSummaryStats() {
    def boundarySummary = boundaryType ?: "Not set"
    if (boundaryType == "Mode Boundary") {
        boundarySummary = "${resetModeEnterExit ?: 'Boundary'} ${resetMode ?: 'mode not set'}"
    } else if (boundaryType == "Time Boundary") {
        boundarySummary = dayEnd ? "Time boundary configured" : "Time not set"
    }

    def activationSummary = activationMode ?: "Not set"
    if (activationMode == "Conditioned On Virtual Switch") {
        activationSummary = "Virtual switch gating"
    } else if (activationMode == "Always Active During Ghost Modes") {
        activationSummary = "Always active in selected modes"
    }

    [
            "Devices": summarizeDeviceNames(mmwaveDevices),
            "Activation": activationSummary,
            "Boundary": boundarySummary,
            "Clustering": "${clusteringAlgorithm ?: 'DBSCAN'} / r=${clusterRadius ?: 0.5}m / min=${minClusterEvents ?: 5}",
            "Persistence": "history ${historyDays ?: 14}d / persistent ${persistentGhostDays ?: 2}d / busted ${bustedGhostDays ?: 2}d",
            "Auto busting": enableAutoGhostBusting ? describeAutoBustConfiguration() : "Disabled",
            "Notifications": sendPush ? "Enabled" : "Disabled"
    ]
}

private Map getMainPageStatsSummary() {
    def ghosts = 0
    def persistent = 0
    def busted = 0
    def pointsProcessed = 0

    mmwaveDevices?.each { dev ->
        def displayCounts = getDisplayCounts(dev.id)
        def summary = getSummaryForDevice(dev.id)
        ghosts += displayCounts.ghostsToday ?: 0
        persistent += displayCounts.persistentGhosts ?: 0
        busted += displayCounts.bustedGhosts ?: 0
        pointsProcessed += summary.pointCount ?: 0
    }

    [
            "Last processed ghosts": ghosts,
            "Persistent ghosts": persistent,
            "Busted ghosts": busted,
            "Points in last run": pointsProcessed
    ]
}

private String summarizeDeviceNames(devices) {
    if (!devices) {
        return "None selected"
    }

    def names = devices.collect { it.displayName }
    if (names.size() <= 2) {
        return names.join(", ")
    }

    "${names.take(2).join(', ')} +${names.size() - 2} more"
}

def distance3D(Map a, Map b) {
    Math.sqrt(
            Math.pow(((a?.x ?: 0.0d) - (b?.x ?: 0.0d)), 2) +
                    Math.pow(((a?.y ?: 0.0d) - (b?.y ?: 0.0d)), 2) +
                    Math.pow(((a?.z ?: 0.0d) - (b?.z ?: 0.0d)), 2)
    )
}

private String deviceKey(deviceId) {
    deviceId?.toString()
}

private String childDni(deviceId) {
    "${app.id}-${deviceKey(deviceId)}"
}

private Map cloneCluster(Map cluster) {
    [
            center: clonePoint(cluster.center),
            bounds: cloneBounds(cluster.bounds),
            radius: cluster.radius,
            density: cluster.density,
            daysSeen: cluster.daysSeen ?: 0,
            lastSeen: cluster.lastSeen ?: 0,
            seenHistory: ((cluster.seenHistory ?: []) as List).collect { it },
            consecutiveSeen: cluster.consecutiveSeen ?: 0,
            absentStreak: cluster.absentStreak ?: 0,
            lastMatchedDay: cluster.lastMatchedDay ?: 0,
            bustMode: cluster.bustMode,
            appliedBounds: cloneBounds(cluster.appliedBounds)
    ]
}

private Map snapshotCluster(Map cluster) {
    [
            center: clonePoint(cluster.center),
            bounds: cloneBounds(cluster.bounds),
            radius: cluster.radius ?: 0.0d,
            density: cluster.density ?: 0,
            points: ((cluster.points ?: []) as List).collect { clonePoint(it) },
            daysSeen: cluster.daysSeen ?: 0,
            lastSeen: cluster.lastSeen ?: 0,
            seenHistory: ((cluster.seenHistory ?: []) as List).collect { it },
            consecutiveSeen: cluster.consecutiveSeen ?: 0,
            absentStreak: cluster.absentStreak ?: 0,
            lastMatchedDay: cluster.lastMatchedDay ?: 0,
            bustMode: cluster.bustMode,
            appliedBounds: cloneBounds(cluster.appliedBounds)
    ]
}

private Map clonePoint(Map point) {
    [x: point?.x ?: 0.0d, y: point?.y ?: 0.0d, z: point?.z ?: 0.0d]
}

private Map cloneBounds(Map bounds) {
    [
            xmin: bounds?.xmin ?: 0.0d,
            xmax: bounds?.xmax ?: 0.0d,
            ymin: bounds?.ymin ?: 0.0d,
            ymax: bounds?.ymax ?: 0.0d,
            zmin: bounds?.zmin ?: 0.0d,
            zmax: bounds?.zmax ?: 0.0d
    ]
}

private Map getDeviceBounds(dev) {
    if (!dev) {
        return null
    }

    try {
        // parameter103 = Width Minimum (Left), parameter104 = Width Maximum (Right)
        def xMin = toDouble(dev.currentValue("parameter103")) ?: toDouble(dev.getSetting("parameter103"))
        def xMax = toDouble(dev.currentValue("parameter104")) ?: toDouble(dev.getSetting("parameter104"))

        // parameter105 = Depth Minimum (Near), parameter106 = Depth Maximum (Far)
        def yMin = toDouble(dev.currentValue("parameter105")) ?: toDouble(dev.getSetting("parameter105"))
        def yMax = toDouble(dev.currentValue("parameter106")) ?: toDouble(dev.getSetting("parameter106"))

        // parameter101 = Height Minimum (Floor), parameter102 = Height Maximum (Ceiling)
        def zMin = toDouble(dev.currentValue("parameter101")) ?: toDouble(dev.getSetting("parameter101"))
        def zMax = toDouble(dev.currentValue("parameter102")) ?: toDouble(dev.getSetting("parameter102"))

        if (xMin == null || xMax == null || yMin == null || yMax == null || zMin == null || zMax == null) {
            return null
        }

        return [
                xmin: xMin / 1000.0d,
                xmax: xMax / 1000.0d,
                ymin: yMin / 1000.0d,
                ymax: yMax / 1000.0d,
                zmin: zMin / 1000.0d,
                zmax: zMax / 1000.0d
        ]
    } catch (Exception ex) {
        warnLog("Unable to retrieve device bounds for ${dev.displayName}: ${ex.message}")
        return null
    }
}

private boolean isPointWithinBounds(Double x, Double y, Double z, Map bounds) {
    if (!bounds) {
        return true
    }

    return x >= bounds.xmin && x <= bounds.xmax &&
           y >= bounds.ymin && y <= bounds.ymax &&
           z >= bounds.zmin && z <= bounds.zmax
}

private Double toDouble(value) {
    if (value == null) {
        return null
    }

    try {
        return value as Double
    } catch (Exception ignored) {
        return null
    }
}

private BigDecimal safeClusterRadius() {
    (clusterRadius ?: 0.5) as BigDecimal
}

private Integer safeMinClusterEvents() {
    Math.max(1, (minClusterEvents ?: 5) as Integer)
}

private Integer safeMaxClusters() {
    Math.max(1, (maxClusters ?: 5) as Integer)
}

private Integer safeHistoryDays() {
    Math.max(1, (historyDays ?: 14) as Integer)
}

private Integer safePersistentGhostDays() {
    Math.max(1, (persistentGhostDays ?: 2) as Integer)
}

private Integer safeBustedGhostDays() {
    Math.max(1, (bustedGhostDays ?: 2) as Integer)
}

private BigDecimal safeStableThreshold() {
    Math.max(0, (stableThreshold ?: 70) as Integer) as BigDecimal
}

private BigDecimal round2(value) {
    ((value ?: 0.0d) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
}

private String renderDualStatBlocks(String leftTitle, Map leftStats, String rightTitle, Map rightStats) {
    """<table style='width:100%; border-collapse:separate; border-spacing:8px 0;'>
<tr>
<td style='width:50%; vertical-align:top;'>${renderStatBlock(leftTitle, leftStats)}</td>
<td style='width:50%; vertical-align:top;'>${renderStatBlock(rightTitle, rightStats)}</td>
</tr>
</table>"""
}

private String renderStatBlock(String title, Map stats) {
    def rows = new StringBuilder()
    rows << "<div style='border:1px solid #d7d7d7; background:#fbfbfb; padding:8px 10px; margin:4px 0;'>"
    rows << "<div style='font-weight:bold; color:#333333; margin-bottom:6px;'>${title}</div>"
    rows << "<table style='width:100%; border-collapse:collapse;'>"

    stats.each { label, value ->
        rows << "<tr>"
        rows << "<td style='padding:3px 0; color:#666666; width:58%;'>${label}</td>"
        rows << "<td style='padding:3px 0; color:#111111; font-weight:bold; text-align:right;'>${value}</td>"
        rows << "</tr>"
    }

    rows << "</table></div>"
    rows.toString()
}

private String renderNoteCard(String title, String message) {
    "<div style='border-left:4px solid #c28b00; background:#fff8e1; padding:8px 10px; margin:4px 0;'><div style='font-weight:bold; margin-bottom:4px;'>${title}</div><div>${message}</div></div>"
}

private void debugLog(String message) {
    if (enableDebugLogging) {
        log.debug message
    }
}

private void infoLog(String message) {
    log.info message
}

private void warnLog(String message) {
    log.warn message
}

def getInterface(type, txt = "", link = "") {
    switch (type) {
        case "line":
            return "<hr style='background-color:#555555; height:1px; border:0;' />"
        case "header":
            return "<div style='color:#ffffff;font-weight:bold;background-color:#555555;border:1px solid;box-shadow:2px 3px #A9A9A9'> ${txt}</div>"
        case "error":
            return "<div style='color:#ff0000;font-weight:bold;'>${txt}</div>"
        case "note":
            return "<div style='color:#333333;font-size:small;'>${txt}</div>"
        case "subField":
            return "<div style='color:#000000;background-color:#ededed;'>${txt}</div>"
        case "subHeader":
            return "<div style='color:#000000;font-weight:bold;background-color:#ededed;border:1px solid;box-shadow:2px 3px #A9A9A9'> ${txt}</div>"
        case "subSection1Start":
            return "<div style='color:#000000;background-color:#d4d4d4;border:0'>"
        case "subSection2Start":
            return "<div style='color:#000000;background-color:#e0e0e0;border:0'>"
        case "subSectionEnd":
            return "</div>"
        case "boldText":
            return "<b>${txt}</b>"
        case "link":
            return "<a href='${link}' target='_blank' style='color:#51ade5'>${txt}</a>"
    }
}
