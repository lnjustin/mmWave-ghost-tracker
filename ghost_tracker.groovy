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

        section(getInterface("header", "Analysis & Controls")) {
            href "statsPage", title: "View Device Analysis And Interference Controls", description: "Per-device graphs, cluster states, and interference area controls"
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

        section(getInterface("header", "Correlation Tracking")) {
            if (!mmwaveDevices) {
                paragraph "Select mmWave switches first to configure cross-device correlation tracking."
            } else {
                paragraph getInterface("note", "For each mmWave device, choose other devices and the attributes to watch. The app will sample current attribute values during mmWave activity and summarize whether ghost-present samples and ghost appearances correlate with those values or recent state changes.")
                input "correlationChangeWindowSeconds",
                        "number",
                        title: "Treat an attribute change as coincident if it happened within this many seconds before a ghost appearance",
                        defaultValue: 60

                mmwaveDevices.each { dev ->
                    def devKey = deviceKey(dev.id)
                    input "correlationDevices_${devKey}",
                            "capability.actuator",
                            title: "Devices to correlate with ${dev.displayName}",
                            multiple: true,
                            required: false,
                            submitOnChange: true

                    def selectedCorrelationDevices = (settings["correlationDevices_${devKey}"] ?: []) as List
                    selectedCorrelationDevices.each { trackedDev ->
                        def attributeOptions = getDeviceAttributeOptions(trackedDev)
                        input "correlationAttrs_${devKey}_${deviceKey(trackedDev.id)}",
                                "enum",
                                title: "Attributes to track for ${trackedDev.displayName}",
                                options: attributeOptions,
                                multiple: true,
                                required: false
                    }
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
                    title: "Cluster radius / match threshold (cm)",
                    defaultValue: 50

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
    def sortedDevices = getSortedMmwaveDevices()

    def shouldAutoRefresh = (state.statsPageRefreshUntil ?: 0L) > now()
    if (!shouldAutoRefresh) {
        state.statsPageRefreshUntil = null
    }

    dynamicPage(name: "statsPage", install: false, uninstall: false, refreshInterval: shouldAutoRefresh ? 1 : null) {
        if (!sortedDevices) {
            section { paragraph "No mmWave devices configured." }
            return
        }

        def aggregateToday = 0
        def aggregatePersistent = 0
        def aggregateBusted = 0
        def aggregateBustSources = getAggregateBustSourceCounts()

        sortedDevices.each { dev ->
            def displayCounts = getDisplayCounts(dev.id)
            aggregateToday += displayCounts.ghostsToday
            aggregatePersistent += displayCounts.persistentGhosts
            aggregateBusted += displayCounts.bustedGhosts
        }

            section(getInterface("header", "All Devices Summary")) {
                paragraph renderStatBlock("Network Summary", [
                    "Last processed ghosts": aggregateToday,
                    "Detected, not persistent": sortedDevices.collect { getDisplayCounts(it.id).detectedGhosts ?: 0 }.sum() ?: 0,
                    "Persistent ghosts": aggregatePersistent,
                    "Targeted ghosts": sortedDevices.collect { getDisplayCounts(it.id).targetedGhosts ?: 0 }.sum() ?: 0,
                    "Busted ghosts": aggregateBusted,
                    "Auto-targeted persistent": aggregateBustSources.autoBusted,
                    "Manual-targeted persistent": aggregateBustSources.manualBusted,
                    "Untargeted persistent": aggregateBustSources.unbusted,
                    "Processed days": state.totalDays ?: 0
            ])

            if (state.recommendation) {
                paragraph renderRecommendationSummary(state.recommendation)
            }
        }

        sortedDevices.each { dev ->
            def devKey = deviceKey(dev.id)
            def todayPoints = getPointsForDevice(dev.id)
            def todayOutOfBounds = getOutOfBoundsPointsForDevice(dev.id)
            def todayClusters = getTodayClusters(dev.id)
            def stableClusters = getStableClusters(dev.id)
            def liveCounts = getGhostCounts(devKey, todayClusters)
            def displayCounts = getDisplayCounts(dev.id)
            def displayData = getDisplayData(dev.id)
            def lastSummary = getSummaryForDevice(dev.id)
            def clusterSummary = getClusterSummary(dev.id)
            def xyScale = calculatePlotScale(
                    displayData.points,
                    displayData.outOfBoundsPoints,
                    displayData.currentClusters,
                    displayData.historicalClusters,
                    dev,
                    "x",
                    "y"
            )

            section(getInterface("header", "Device: ${dev.displayName}")) {
                paragraph renderStatBlock("Cluster Summary", clusterSummary)

                def currentStats = [
                        "Points buffered": todayPoints.size(),
                        "Ghosts detected": liveCounts.ghostsToday,
                        "Active": isDeviceActive(dev) ? "Yes" : "No"
                ]
                if (todayOutOfBounds) {
                    currentStats["Out-of-bounds points"] = todayOutOfBounds.size()
                }

                def lastDayStats = [
                        "Detected, not persistent": displayCounts.detectedGhosts,
                        "Ghosts detected": displayCounts.ghostsToday,
                        "Persistent ghosts": displayCounts.persistentGhosts,
                        "Targeted ghosts": displayCounts.targetedGhosts,
                        "Busted ghosts": displayCounts.bustedGhosts,
                        "Auto-targeted persistent": displayCounts.autoBusted,
                        "Manual-targeted persistent": displayCounts.manualBusted,
                        "Untargeted persistent": displayCounts.unbusted,
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

                paragraph renderSideBySidePlots(
                        "X / Y View",
                        renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "y", xyScale),
                        "X / Z View",
                        renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "z", xyScale)
                )

                def correlationSummary = renderCorrelationSummary(dev.id)
                if (correlationSummary) {
                    paragraph getInterface("subHeader", "Correlation Tracking")
                    paragraph correlationSummary
                }

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
                            title: "Cluster to set as an interference area",
                            multiple: false,
                            required: false,
                            options: displayData.selectableClusters.collectEntries { cluster ->
                                def idx = displayData.selectableClusters.indexOf(cluster)
                                [(idx.toString()): describeClusterOption(cluster, idx)]
                            }
                    input "targetAreaIndex_${devKey}",
                            "enum",
                            title: "Interference area index to write on the device (0-3)",
                            multiple: false,
                            required: false,
                            options: interferenceAreaIndexOptions()
                    input "applyZones_${devKey}", "button", title: "Set Selected Cluster As Interference Area"
                } else {
                    paragraph "No clusters are available for interference zone selection."
                }

                paragraph renderInterferenceAreasCard(dev.id)
                paragraph renderNoteCard("Manual Area Memory", "Use the fields below to record an interference area that already exists on the device. This does not write to the device; it only lets the app remember the area for tracking targeted and busted ghosts.")
                input "manualAreaIndex_${devKey}",
                        "enum",
                        title: "Remembered interference area index (0-3)",
                        multiple: false,
                        required: false,
                        options: interferenceAreaIndexOptions()
                input "manualAreaXMin_${devKey}", "decimal", title: "X min (cm)", required: false
                input "manualAreaXMax_${devKey}", "decimal", title: "X max (cm)", required: false
                input "manualAreaYMin_${devKey}", "decimal", title: "Y min (cm)", required: false
                input "manualAreaYMax_${devKey}", "decimal", title: "Y max (cm)", required: false
                input "manualAreaZMin_${devKey}", "decimal", title: "Z min (cm)", required: false
                input "manualAreaZMax_${devKey}", "decimal", title: "Z max (cm)", required: false
                input "saveManualArea_${devKey}", "button", title: "Remember Manual Interference Area"
                input "clearRememberedArea_${devKey}", "button", title: "Clear Remembered Interference Area"

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
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("clearDeviceStats_")) {
        def devKey = btn.substring("clearDeviceStats_".length())
        clearDeviceStats(devKey)
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("applyZones_")) {
        def devKey = btn.substring("applyZones_".length())
        applySelectedZones(devKey)
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("saveManualArea_")) {
        def devKey = btn.substring("saveManualArea_".length())
        saveManualInterferenceArea(devKey)
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("clearRememberedArea_")) {
        def devKey = btn.substring("clearRememberedArea_".length())
        clearRememberedInterferenceArea(devKey)
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("splitCluster_")) {
        def clusterKey = btn.substring("splitCluster_".length())
        splitCluster(clusterKey)
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("reclusterDevice_")) {
        def devKey = btn.substring("reclusterDevice_".length())
        reclusterDevice(devKey)
        scheduleStatsPageRefresh()
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

    getAllConfiguredCorrelationTrackers().each { tracker ->
        subscribe(tracker.device, tracker.attribute, correlationAttributeHandler)
        seedCorrelationAttributeState(tracker.device, tracker.attribute)
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
    state.interferenceAreas = state.interferenceAreas ?: [:]
    state.autoManagedZoneRange = state.autoManagedZoneRange ?: [:]
    state.manualManagedZoneRange = state.manualManagedZoneRange ?: [:]
    state.correlationDaily = state.correlationDaily ?: [:]
    state.correlationHistory = state.correlationHistory ?: [:]
    state.correlationStatus = state.correlationStatus ?: [:]
    state.correlationGhostPresence = state.correlationGhostPresence ?: [:]
    state.deviceActiveDayHistory = state.deviceActiveDayHistory ?: [:]
    state.deviceActiveToday = state.deviceActiveToday ?: [:]
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
    state.deviceActiveToday[deviceKey(dev.id)] = true
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

def correlationAttributeHandler(evt) {
    def trackedDevice = evt?.device
    if (!trackedDevice || !evt?.name) {
        return
    }

    def trackedKey = deviceKey(trackedDevice.id)
    def currentStatus = (state.correlationStatus[trackedKey] ?: [:]) as Map
    currentStatus[evt.name] = [
            value: normalizeCorrelationValue(evt.value),
            updatedAt: now()
    ]
    state.correlationStatus[trackedKey] = currentStatus
    debugLog("Correlation attribute changed: ${trackedDevice.displayName} ${evt.name}=${evt.value}")
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

    recordCorrelationSample(dev, result)
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

        def point = [
                x: x,
                y: y,
                z: z
        ]

        // Apply bounds filtering if enabled
        if (bounds) {
            if (isPointWithinBounds(x, y, z, bounds)) {
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
        recordActiveTrackingDay(devKey, state.dayIndex as Integer, (state.deviceActiveToday[devKey] ?: false) || isDeviceActive(dev))
        def points = getEffectivePointsForDevice(dev.id)
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
        recordCorrelationDay(devKey, counts)
        state.lastPointsSnapshot[devKey] = points.collect { clonePoint(it) }
        state.lastOutOfBoundsSnapshot[devKey] = outOfBoundsPoints.collect { clonePoint(it) }
        state.lastClustersSnapshot[devKey] = clustersToday.collect { snapshotCluster(it) }
        state.correlationGhostPresence[devKey] = false

        debugLog("Processed ${dev.displayName}: points=${points.size()}, out-of-bounds=${outOfBoundsPoints.size()}, unclustered=${unclusteredPointCount}, clusters=${clustersToday.size()}, persistent=${counts.persistentGhosts}, busted=${counts.bustedGhosts}")
    }

    state.dailySummary = summaries
    state.dailyPoints = [:]
    state.dailyOutOfBoundsPoints = [:]
    state.deviceActiveToday = [:]
    state.correlationDaily = [:]
    pruneOldActivationStarts()

    if (sendPush && notifyDailySummary) {
        sendNotification(buildDailySummary())
    }
}

private updateStabilityForDay(deviceId, List clustersToday) {
    def devKey = deviceKey(deviceId)
    def currentDay = state.dayIndex ?: 0
    def historyCutoff = Math.max(1, currentDay - safeHistoryDays() + 1)
    def activeDayHistory = (((state.deviceActiveDayHistory ?: [:])[devKey] ?: []) as List)
            .collect { it as Integer }
            .findAll { it >= historyCutoff }
            .unique()
            .sort()
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
        cluster.activeDayHistory = activeDayHistory.collect { it }
    }

    state.stabilityData[devKey] = stableClusters.findAll { cluster ->
        cluster.daysSeen > 0 || (cluster.absentStreak ?: 0) < safeHistoryDays()
    }
    syncClusterTargetsForDevice(devKey)
}

private void recordActiveTrackingDay(String devKey, Integer currentDay, boolean wasActiveToday) {
    if (!devKey) {
        return
    }

    if (!wasActiveToday) {
        pruneActiveDayHistory(devKey, currentDay)
        return
    }

    def activeDays = (((state.deviceActiveDayHistory ?: [:])[devKey] ?: []) as List).collect { it as Integer }
    activeDays << currentDay
    state.deviceActiveDayHistory[devKey] = activeDays.unique().sort()
    pruneActiveDayHistory(devKey, currentDay)
}

private void pruneActiveDayHistory(String devKey, Integer currentDay = null) {
    if (!devKey) {
        return
    }

    def day = currentDay ?: (state.dayIndex ?: 0)
    def historyCutoff = Math.max(1, day - safeHistoryDays() + 1)
    def activeDays = (((state.deviceActiveDayHistory ?: [:])[devKey] ?: []) as List)
            .collect { it as Integer }
            .findAll { it >= historyCutoff }
            .unique()
            .sort()
    state.deviceActiveDayHistory[devKey] = activeDays
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
            activeDayHistory: [currentDay],
            consecutiveSeen: 1,
            absentStreak: 0,
            lastMatchedDay: currentDay,
            bustMode: null,
            targetSource: null,
            targetAreaIndex: null,
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
        lines << "Detected, not persistent: ${summary.detectedGhosts ?: 0}"
        lines << "Persistent ghosts: ${summary.persistentGhosts}"
        lines << "Targeted ghosts: ${summary.targetedGhosts ?: 0}"
        lines << "Busted ghosts: ${summary.bustedGhosts}"
        lines << "Auto-targeted persistent: ${summary.autoBusted ?: 0}"
        lines << "Manual-targeted persistent: ${summary.manualBusted ?: 0}"
        lines << "Untargeted persistent: ${summary.unbusted ?: 0}"
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
    detectClusters(getEffectivePointsForDevice(deviceId))
}

private List getAllConfiguredCorrelationTrackers() {
    def trackers = []
    mmwaveDevices?.each { dev ->
        trackers.addAll(getConfiguredCorrelationTrackersForDevice(deviceKey(dev.id)))
    }
    trackers.unique { tracker -> "${tracker.mmwaveKey}:${tracker.deviceKey}:${tracker.attribute}" }
}

private List getConfiguredCorrelationTrackersForDevice(String mmwaveKey) {
    def selectedDevices = (settings["correlationDevices_${mmwaveKey}"] ?: []) as List
    def trackers = []

    selectedDevices.each { trackedDev ->
        def attrs = (settings["correlationAttrs_${mmwaveKey}_${deviceKey(trackedDev.id)}"] ?: []) as List
        attrs = attrs.collect { it?.toString()?.trim() }.findAll { it }.unique()

        attrs.each { attr ->
            trackers << [
                    mmwaveKey: mmwaveKey,
                    device: trackedDev,
                    deviceKey: deviceKey(trackedDev.id),
                    deviceName: trackedDev.displayName,
                    attribute: attr
            ]
        }
    }

    trackers
}

private List getDeviceAttributeOptions(dev) {
    if (!dev) {
        return []
    }

    try {
        def attrs = dev.supportedAttributes ?: dev.getSupportedAttributes()
        (attrs ?: []).collect { it?.name?.toString() }.findAll { it }.unique().sort()
    } catch (Exception ex) {
        debugLog("Unable to inspect attributes for ${dev.displayName}: ${ex.message}")
        []
    }
}

private void seedCorrelationAttributeState(dev, String attribute) {
    if (!dev || !attribute) {
        return
    }

    try {
        def value = dev.currentValue(attribute)
        if (value != null) {
            def trackedKey = deviceKey(dev.id)
            def currentStatus = (state.correlationStatus[trackedKey] ?: [:]) as Map
            currentStatus[attribute] = [
                    value: normalizeCorrelationValue(value),
                    updatedAt: now()
            ]
            state.correlationStatus[trackedKey] = currentStatus
        }
    } catch (Exception ex) {
        debugLog("Unable to seed correlation state for ${dev.displayName} ${attribute}: ${ex.message}")
    }
}

private void recordCorrelationSample(dev, Map result) {
    def mmwaveKey = deviceKey(dev?.id)
    def trackers = getConfiguredCorrelationTrackersForDevice(mmwaveKey)
    if (!trackers) {
        return
    }

    def dailyForDevice = ((state.correlationDaily[mmwaveKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): cloneCorrelationTrackerStats(value as Map)]
    }
    def ghostPresent = getTodayClusters(dev.id).size() > 0
    def priorGhostPresent = state.correlationGhostPresence[mmwaveKey] ?: false
    def ghostAppearance = ghostPresent && !priorGhostPresent
    state.correlationGhostPresence[mmwaveKey] = ghostPresent
    def nowMs = now()
    def changeWindowMs = safeCorrelationChangeWindowSeconds() * 1000L

    trackers.each { tracker ->
        def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
        def trackerStats = cloneCorrelationTrackerStats((dailyForDevice[trackerKey] ?: [
                deviceName: tracker.deviceName,
                attribute: tracker.attribute,
                samples: 0,
                ghostSamples: 0,
                clearSamples: 0,
                ghostAppearances: 0,
                ghostAppearancesNearAnyChange: 0,
                values: [:],
                changeToValues: [:]
        ]) as Map)
        def currentValue = getTrackedCorrelationValue(tracker.device, tracker.attribute)
        def valueKey = normalizeCorrelationValue(currentValue)
        def recentChange = (((state.correlationStatus[tracker.deviceKey] ?: [:]) as Map)[tracker.attribute]?.updatedAt ?: 0L) > 0L &&
                (nowMs - ((((state.correlationStatus[tracker.deviceKey] ?: [:]) as Map)[tracker.attribute]?.updatedAt ?: 0L) as Long)) <= changeWindowMs
        trackerStats.samples = (trackerStats.samples ?: 0) + 1
        if (ghostPresent) {
            trackerStats.ghostSamples = (trackerStats.ghostSamples ?: 0) + 1
        } else {
            trackerStats.clearSamples = (trackerStats.clearSamples ?: 0) + 1
        }
        trackerStats.lastValue = valueKey

        def valueStats = ((trackerStats.values ?: [:])[valueKey] ?: [
                samples: 0,
                ghostSamples: 0,
                clearSamples: 0
        ]) as Map
        valueStats.samples = (valueStats.samples ?: 0) + 1
        if (ghostPresent) {
            valueStats.ghostSamples = (valueStats.ghostSamples ?: 0) + 1
        } else {
            valueStats.clearSamples = (valueStats.clearSamples ?: 0) + 1
        }
        trackerStats.values = (trackerStats.values ?: [:]) + [(valueKey): valueStats]

        if (ghostAppearance) {
            trackerStats.ghostAppearances = (trackerStats.ghostAppearances ?: 0) + 1
            if (recentChange) {
                trackerStats.ghostAppearancesNearAnyChange = (trackerStats.ghostAppearancesNearAnyChange ?: 0) + 1
                def changedValueStats = ((trackerStats.changeToValues ?: [:])[valueKey] ?: [ghostAppearances: 0]) as Map
                changedValueStats.ghostAppearances = (changedValueStats.ghostAppearances ?: 0) + 1
                trackerStats.changeToValues = (trackerStats.changeToValues ?: [:]) + [(valueKey): changedValueStats]
            }
        }

        dailyForDevice[trackerKey] = trackerStats
    }

    state.correlationDaily[mmwaveKey] = dailyForDevice
}

private String getTrackedCorrelationValue(dev, String attribute) {
    def cachedValue = state.correlationStatus[deviceKey(dev?.id)]?.get(attribute)?.value
    if (cachedValue != null) {
        return normalizeCorrelationValue(cachedValue)
    }

    try {
        return normalizeCorrelationValue(dev?.currentValue(attribute))
    } catch (Exception ex) {
        debugLog("Unable to read correlation value for ${dev?.displayName} ${attribute}: ${ex.message}")
        return "unknown"
    }
}

private String normalizeCorrelationValue(value) {
    value == null ? "unknown" : value.toString()
}

private Map cloneCorrelationTrackerStats(Map stats) {
    [
            deviceName: stats.deviceName,
            attribute: stats.attribute,
            samples: stats.samples ?: 0,
            ghostSamples: stats.ghostSamples ?: 0,
            clearSamples: stats.clearSamples ?: 0,
            ghostAppearances: stats.ghostAppearances ?: 0,
            ghostAppearancesNearAnyChange: stats.ghostAppearancesNearAnyChange ?: 0,
            lastValue: stats.lastValue,
            values: ((stats.values ?: [:]) as Map).collectEntries { key, value ->
                [(key): [
                        samples: value.samples ?: 0,
                        ghostSamples: value.ghostSamples ?: 0,
                        clearSamples: value.clearSamples ?: 0
                ]]
            },
            changeToValues: ((stats.changeToValues ?: [:]) as Map).collectEntries { key, value ->
                [(key): [
                        ghostAppearances: value.ghostAppearances ?: 0
                ]]
            }
    ]
}

private List getEffectivePointsForDevice(deviceId) {
    def points = getPointsForDevice(deviceId)
    if (!filterPointsByDeviceBounds) {
        return points
    }

    filterPointsToDeviceBounds(points, deviceKey(deviceId))
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
            detectedGhosts: stableClusters.count { !isClusterPersistent(it) && !isClusterTargeted(it) && !isClusterBusted(it) },
            persistentGhosts: stableClusters.count { isClusterPersistent(it) && !isClusterTargeted(it) && !isClusterBusted(it) },
            targetedGhosts: stableClusters.count { isClusterTargeted(it) && !isClusterBusted(it) },
            bustedGhosts: stableClusters.count { isClusterBusted(it) },
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

    def selectedRaw = settings["blockClusters_${devKey}"]
    def selectedIndex = selectedRaw instanceof Collection ? selectedRaw?.find { it != null } : selectedRaw
    def areaIndex = safeInterferenceAreaIndex(settings["targetAreaIndex_${devKey}"])
    if (selectedIndex == null || areaIndex == null) {
        warnLog("Choose both a cluster and an interference area index for ${dev.displayName} before applying.")
        return
    }

    def clusters = getSelectableClusters(dev.id)
    def selectedCluster = clusters[(selectedIndex as Integer)]
    if (!selectedCluster?.bounds || !isValidBounds(selectedCluster.bounds)) {
        warnLog("Selected cluster is not available for ${dev.displayName}.")
        return
    }

    def zoneSpec = [cluster: selectedCluster, bounds: cloneBounds(selectedCluster.bounds), areaIndex: areaIndex]
    def appliedZoneSpecs = applyZonesToDevice(dev, [zoneSpec], "manual cluster selection")
    rememberAppliedZones(devKey, appliedZoneSpecs, "manual")
    updateClusterBustMode(dev.id, [selectedCluster], appliedZoneSpecs, "manual")
    syncClusterTargetsForDevice(devKey)
}

private void applyAutomaticGhostBusting(dev) {
    def devKey = deviceKey(dev.id)

    if (!enableAutoGhostBusting) {
        clearManagedZones(dev, "auto")
        clearClusterBustMode(dev.id, "auto")
        state.autoBustedZones[devKey] = []
        syncClusterTargetsForDevice(devKey)
        return
    }

    def autoBounds = getAutoGhostBustBoundary()
    if (!isValidBounds(autoBounds)) {
        clearManagedZones(dev, "auto")
        clearClusterBustMode(dev.id, "auto")
        state.autoBustedZones[devKey] = []
        warnLog("Automatic ghost busting is enabled, but the configured boundary is invalid.")
        return
    }

    def stableClusters = getStableClusters(dev.id)
    clearClusterBustMode(dev.id, "auto")
    def matchingClusters = stableClusters.collect { cluster ->
        if (!isClusterPersistent(cluster) || isClusterTargeted(cluster)) {
            return null
        }

        def appliedBounds = getAutoAppliedBoundsForCluster(cluster, autoBounds)
        if (!appliedBounds) {
            return null
        }

        [cluster: cluster, bounds: appliedBounds]
    }.findAll { it }

    if (!matchingClusters) {
        clearManagedZones(dev, "auto")
        state.autoBustedZones[devKey] = []
        syncClusterTargetsForDevice(devKey)
        return
    }

    def assignedIndexes = getAutoAssignableIndexes(devKey, matchingClusters.size())
    if (!assignedIndexes) {
        warnLog("No free interference area indexes remain for automatic ghost busting on ${dev.displayName}.")
        clearManagedZones(dev, "auto")
        state.autoBustedZones[devKey] = []
        syncClusterTargetsForDevice(devKey)
        return
    }

    def limitedMatches = []
    matchingClusters.take(assignedIndexes.size()).eachWithIndex { spec, idx ->
        limitedMatches << (spec + [areaIndex: assignedIndexes[idx]])
    }
    def signature = limitedMatches.collect { [areaIndex: it.areaIndex, bounds: integerBounds(it.bounds)] }
    if (state.autoBustedZones[devKey] == signature) {
        debugLog("Skipping auto ghost busting for ${dev.displayName}; matching bounds are unchanged.")
        updateClusterBustMode(dev.id, limitedMatches.collect { it.cluster }, limitedMatches, "auto")
        syncClusterTargetsForDevice(devKey)
        return
    }

    clearManagedZones(dev, "auto")
    def appliedZoneSpecs = applyZonesToDevice(dev, limitedMatches, "automatic ghost busting")
    rememberAppliedZones(devKey, appliedZoneSpecs, "auto")
    updateClusterBustMode(dev.id, limitedMatches.collect { it.cluster }, appliedZoneSpecs, "auto")
    state.autoBustedZones[devKey] = appliedZoneSpecs.collect { [areaIndex: it.areaIndex, bounds: integerBounds(it.bounds)] }
    syncClusterTargetsForDevice(devKey)
}

private List applyZonesToDevice(dev, List zoneSpecs, String sourceLabel) {
    def appliedZoneSpecs = []

    (zoneSpecs ?: []).each { zoneSpec ->
        def zoneBounds = zoneSpec.bounds
        def areaIndex = safeInterferenceAreaIndex(zoneSpec.areaIndex)
        if (!isValidBounds(zoneBounds)) {
            warnLog("Skipped invalid interference bounds for ${dev.displayName} from ${sourceLabel}")
            return
        }
        if (areaIndex == null) {
            warnLog("Skipped interference area with invalid index for ${dev.displayName} from ${sourceLabel}")
            return
        }

        def bounds = integerBounds(zoneBounds)
        debugLog("Applying interference area ${areaIndex} to ${dev.displayName} from ${sourceLabel}: ${JsonOutput.toJson(bounds)}")
        dev.mmWaveSetInterferenceArea(
                areaIndex,
                bounds.xmin,
                bounds.xmax,
                bounds.ymin,
                bounds.ymax,
                bounds.zmin,
                bounds.zmax
        )
        appliedZoneSpecs << [
                areaIndex: areaIndex,
                bounds: cloneBounds(zoneBounds),
                cluster: zoneSpec.cluster
        ]
    }

    appliedZoneSpecs
}

private void clearManagedZones(dev, String managedType) {
    def devKey = deviceKey(dev.id)
    def indexes = getRememberedInterferenceAreas(devKey)
            .findAll { it.source == managedType }
            .collect { it.areaIndex as Integer }
    clearManagedZoneIndexes(dev, indexes)
    indexes.each { idx -> clearRememberedInterferenceArea(devKey, idx as Integer, false) }
}

private void clearManagedZoneIndexes(dev, List zoneIndexes) {
    if (!dev || !zoneIndexes) {
        return
    }

    zoneIndexes.unique().sort().each { zoneIdx ->
        debugLog("Clearing auto-managed zone ${zoneIdx} on ${dev.displayName}")
        // A zero-volume zone effectively removes the previously applied exclusion area.
        dev.mmWaveSetInterferenceArea(zoneIdx, 0, 0, 0, 0, 0, 0)
    }
}

private Map interferenceAreaIndexOptions() {
    ["0": "0", "1": "1", "2": "2", "3": "3"]
}

private Integer safeInterferenceAreaIndex(value) {
    if (value == null || value == "") {
        return null
    }

    try {
        def idx = value as Integer
        (idx >= 0 && idx <= 3) ? idx : null
    } catch (Exception ignored) {
        null
    }
}

private List getRememberedInterferenceAreas(String devKey) {
    (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map).collect { idx, area ->
        [
                areaIndex: idx as Integer,
                bounds: cloneBounds(area.bounds),
                source: area.source ?: "manual-entry",
                updatedAt: area.updatedAt ?: 0L
        ]
    }.sort { a, b -> (a.areaIndex as Integer) <=> (b.areaIndex as Integer) }
}

private void rememberInterferenceArea(String devKey, Integer areaIndex, Map bounds, String source) {
    if (!devKey || areaIndex == null || !isValidBounds(bounds)) {
        return
    }

    def current = (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): [bounds: cloneBounds(value.bounds), source: value.source, updatedAt: value.updatedAt]]
    }
    current[areaIndex.toString()] = [
            bounds: cloneBounds(bounds),
            source: source,
            updatedAt: now()
    ]
    state.interferenceAreas[devKey] = current
}

private void rememberAppliedZones(String devKey, List appliedZoneSpecs, String source) {
    (appliedZoneSpecs ?: []).each { zoneSpec ->
        rememberInterferenceArea(devKey, zoneSpec.areaIndex as Integer, zoneSpec.bounds as Map, source)
    }
}

private List getAutoAssignableIndexes(String devKey, Integer neededCount) {
    def remembered = getRememberedInterferenceAreas(devKey)
    def reserved = remembered.findAll { it.source != "auto" }.collect { it.areaIndex as Integer }
    def existingAuto = remembered.findAll { it.source == "auto" }.collect { it.areaIndex as Integer }.sort()
    def free = (0..3).findAll { !reserved.contains(it) && !existingAuto.contains(it) }
    (existingAuto + free).take(Math.max(0, neededCount ?: 0))
}

private Map getManualInterferenceAreaInput(String devKey) {
    [
            areaIndex: safeInterferenceAreaIndex(settings["manualAreaIndex_${devKey}"]),
            bounds: [
                    xmin: toDouble(settings["manualAreaXMin_${devKey}"]),
                    xmax: toDouble(settings["manualAreaXMax_${devKey}"]),
                    ymin: toDouble(settings["manualAreaYMin_${devKey}"]),
                    ymax: toDouble(settings["manualAreaYMax_${devKey}"]),
                    zmin: toDouble(settings["manualAreaZMin_${devKey}"]),
                    zmax: toDouble(settings["manualAreaZMax_${devKey}"])
            ]
    ]
}

private void saveManualInterferenceArea(String devKey) {
    def input = getManualInterferenceAreaInput(devKey)
    if (input.areaIndex == null || !isValidBounds(input.bounds as Map)) {
        warnLog("Enter a valid interference area index and bounds before saving the remembered area for ${devKey}.")
        return
    }

    rememberInterferenceArea(devKey, input.areaIndex as Integer, input.bounds as Map, "manual-entry")
    syncClusterTargetsForDevice(devKey)
}

private void clearRememberedInterferenceArea(String devKey) {
    def areaIndex = safeInterferenceAreaIndex(settings["manualAreaIndex_${devKey}"])
    if (areaIndex == null) {
        warnLog("Choose an interference area index to clear for ${devKey}.")
        return
    }
    clearRememberedInterferenceArea(devKey, areaIndex, true)
}

private void clearRememberedInterferenceArea(String devKey, Integer areaIndex, boolean syncTargets) {
    if (!devKey || areaIndex == null) {
        return
    }

    def current = (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): value]
    }
    current.remove(areaIndex.toString())
    state.interferenceAreas[devKey] = current
    if (syncTargets) {
        syncClusterTargetsForDevice(devKey)
    }
}

private Map getManagedZoneRange(String devKey, String managedType) {
    if (managedType == "auto") {
        return normalizeManagedZoneRange(state.autoManagedZoneRange[devKey])
    }
    if (managedType == "manual") {
        return normalizeManagedZoneRange(state.manualManagedZoneRange[devKey])
    }
    [start: 1, count: 0, end: 0, indexes: []]
}

private void setManagedZoneRange(String devKey, String managedType, Integer startIndex, Integer count) {
    def normalized = normalizeManagedZoneRange([start: startIndex, count: count])
    if (managedType == "auto") {
        state.autoManagedZoneRange[devKey] = [start: normalized.start, count: normalized.count]
    } else if (managedType == "manual") {
        state.manualManagedZoneRange[devKey] = [start: normalized.start, count: normalized.count]
    }
}

private Map normalizeManagedZoneRange(Map range) {
    def start = Math.max(1, (range?.start ?: 1) as Integer)
    def count = Math.max(0, (range?.count ?: 0) as Integer)
    def indexes = buildManagedZoneIndexes(start, count)
    [
            start: start,
            count: count,
            end: count > 0 ? (start + count - 1) : (start - 1),
            indexes: indexes
    ]
}

private List buildManagedZoneIndexes(Integer startIndex, Integer count) {
    def start = Math.max(1, startIndex ?: 1)
    def safeCount = Math.max(0, count ?: 0)
    if (safeCount <= 0) {
        return []
    }

    (start..<(start + safeCount)).collect { it as Integer }
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
        newCluster.activeDayHistory = cluster.activeDayHistory ?: []
        newCluster.daysSeen = cluster.daysSeen ?: 0
        newCluster.lastSeen = cluster.lastSeen ?: 0
        newCluster.consecutiveSeen = cluster.consecutiveSeen ?: 0
        newCluster.absentStreak = cluster.absentStreak ?: 0
        newCluster.lastMatchedDay = cluster.lastMatchedDay ?: 0
        newCluster.bustMode = cluster.bustMode
        newCluster.targetSource = cluster.targetSource
        newCluster.targetAreaIndex = cluster.targetAreaIndex
        newCluster.appliedBounds = cloneBounds(cluster.appliedBounds)
        stableClusters << newCluster
    }

    state.stabilityData[devKey] = stableClusters
    syncClusterTargetsForDevice(devKey)
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

    def filteredPoints = filterPointsToDeviceBounds(allPoints, devKey)
    if (filterPointsByDeviceBounds) {
        infoLog("Reclustering ${filteredPoints.size()} in-bounds points for device ${devKey} (from ${allPoints.size()} total)")
    } else {
        infoLog("Reclustering ${filteredPoints.size()} points for device ${devKey}")
    }

    // Re-run clustering on all points
    def newClusters = detectClusters(filteredPoints)

    if (!newClusters) {
        infoLog("No clusters detected after reclustering")
        state.lastClustersSnapshot[devKey] = []
        return
    }

    // Replace stable clusters with new clusters, preserving the current day index
    def currentDay = state.dayIndex ?: 0
    def newStableClusters = []

    newClusters.each { cluster ->
        def newStable = buildStableCluster(cluster, currentDay)
        newStable.activeDayHistory = (((state.deviceActiveDayHistory ?: [:])[devKey] ?: []) as List).collect { it as Integer }
        newStableClusters << newStable
    }

    state.stabilityData[devKey] = newStableClusters
    syncClusterTargetsForDevice(devKey)
    state.lastClustersSnapshot[devKey] = newClusters.collect { snapshotCluster(it) }
    state.lastPointsSnapshot[devKey] = filteredPoints.collect { clonePoint(it) }

    infoLog("Reclustering complete: ${newClusters.size()} new clusters created from ${filteredPoints.size()} points")
}

private void recordCorrelationDay(String devKey, Map ghostCounts) {
    def dailyStats = ((state.correlationDaily[devKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): cloneCorrelationTrackerStats(value as Map)]
    }
    if (!dailyStats) {
        return
    }

    def history = ((state.correlationHistory[devKey] ?: []) as List).collect { entry ->
        [
                day: entry.day,
                trackers: ((entry.trackers ?: [:]) as Map).collectEntries { key, value ->
                    [(key): cloneCorrelationTrackerStats(value as Map)]
                }
        ]
    }

    history << [
            day: state.dayIndex ?: 0,
            trackers: dailyStats
    ]
    state.correlationHistory[devKey] = history.takeRight(safeHistoryDays())
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
    state.autoManagedZoneRange?.remove(devKey)
    state.manualManagedZoneRange?.remove(devKey)
    state.correlationDaily?.remove(devKey)
    state.correlationHistory?.remove(devKey)
    state.correlationGhostPresence?.remove(devKey)
    state.deviceActiveDayHistory?.remove(devKey)
    state.deviceActiveToday?.remove(devKey)

    if (state.recommendation?.deviceId == devKey) {
        state.recommendation = [message: "Recommendation cleared because that device's statistics were reset."]
    }
}

private boolean isValidBounds(Map bounds) {
    if (!bounds) {
        debugLog("isValidBounds: rejected null/empty bounds")
        return false
    }

    def values = [bounds.xmin, bounds.xmax, bounds.ymin, bounds.ymax, bounds.zmin, bounds.zmax]
    if (values.any { it == null }) {
        debugLog("isValidBounds: rejected bounds with null value(s): ${JsonOutput.toJson(bounds)}")
        return false
    }

    def valid = bounds.xmin <= bounds.xmax &&
            bounds.ymin <= bounds.ymax &&
            bounds.zmin <= bounds.zmax
    debugLog("isValidBounds: ${valid ? 'accepted' : 'rejected'} ${JsonOutput.toJson(bounds)}")
    valid
}

private Map integerBounds(Map bounds) {
    [
            xmin: Math.round(bounds.xmin ?: 0.0d) as Integer,
            xmax: Math.round(bounds.xmax ?: 0.0d) as Integer,
            ymin: Math.round(bounds.ymin ?: 0.0d) as Integer,
            ymax: Math.round(bounds.ymax ?: 0.0d) as Integer,
            zmin: Math.round(bounds.zmin ?: 0.0d) as Integer,
            zmax: Math.round(bounds.zmax ?: 0.0d) as Integer
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

private Map getClusterSummary(deviceId) {
    def devKey = deviceKey(deviceId)
    def stableClusters = getStableClusters(deviceId)
    def persistentClusters = stableClusters.findAll { isClusterPersistent(it) && !isClusterTargeted(it) && !isClusterBusted(it) }
    def targetedClusters = stableClusters.findAll { isClusterTargeted(it) && !isClusterBusted(it) }
    def bustedClusters = stableClusters.findAll { isClusterBusted(it) }
    def detectedClusters = stableClusters.findAll { !isClusterPersistent(it) && !isClusterTargeted(it) && !isClusterBusted(it) }
    def bustCounts = getPersistentBustSourceCounts((state.stabilityData[devKey] ?: []) as List)

    [
            "Tracked clusters": stableClusters.size(),
            "Detected, not persistent": detectedClusters.size(),
            "Persistent": persistentClusters.size(),
            "Targeted": targetedClusters.size(),
            "Busted": bustedClusters.size(),
            "Auto-targeted persistent": bustCounts.autoBusted,
            "Manual-targeted persistent": bustCounts.manualBusted,
            "Untargeted persistent": bustCounts.unbusted,
            "Tracking days in window": getActiveTrackingDaysForDevice(deviceId)
    ]
}

private Map getDisplayData(deviceId) {
    def currentPoints = getEffectivePointsForDevice(deviceId)
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
            detectedGhosts: 0,
            persistentGhosts: 0,
            targetedGhosts: 0,
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
            detectedGhosts: summary.detectedGhosts ?: 0,
            persistentGhosts: summary.persistentGhosts ?: 0,
            targetedGhosts: summary.targetedGhosts ?: 0,
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
            distance3D(existing.center, cluster.center) <= 5.0d
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
            xmin: toDouble(autoBustBoundaryXMin),
            xmax: toDouble(autoBustBoundaryXMax),
            ymin: toDouble(autoBustBoundaryYMin),
            ymax: toDouble(autoBustBoundaryYMax),
            zmin: toDouble(autoBustBoundaryZMin),
            zmax: toDouble(autoBustBoundaryZMax)
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

        if (bustMode == "manual" || match.targetSource != "manual") {
            match.bustMode = bustMode
            match.targetSource = bustMode
            match.targetAreaIndex = appliedBoundsList[idx]?.areaIndex
            match.appliedBounds = cloneBounds(appliedBoundsList[idx]?.bounds as Map)
        }
        remainingStable.remove(match)
    }

    state.stabilityData[devKey] = stableClusters
}

private void clearClusterBustMode(deviceId, String bustMode) {
    def devKey = deviceKey(deviceId)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    stableClusters.each { cluster ->
        if ((cluster.targetSource ?: cluster.bustMode) == bustMode) {
            cluster.bustMode = null
            cluster.targetSource = null
            cluster.targetAreaIndex = null
            cluster.appliedBounds = null
        }
    }
    state.stabilityData[devKey] = stableClusters
}

private void syncClusterTargetsForDevice(String devKey) {
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    if (!stableClusters) {
        return
    }

    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    def areasByIndex = rememberedAreas.collectEntries { area -> [(area.areaIndex.toString()): area] }

    stableClusters.each { cluster ->
        def targetIndex = cluster.targetAreaIndex != null ? cluster.targetAreaIndex.toString() : null
        def existingArea = targetIndex != null ? areasByIndex[targetIndex] : null

        if (existingArea && boundsOverlap(cluster.appliedBounds ?: cluster.bounds, existingArea.bounds)) {
            cluster.targetAreaIndex = existingArea.areaIndex
            cluster.targetSource = existingArea.source
            cluster.bustMode = existingArea.source in ["manual", "auto"] ? existingArea.source : cluster.bustMode
            cluster.appliedBounds = cloneBounds(existingArea.bounds)
            return
        }

        def matchedArea = isClusterPersistent(cluster) ? rememberedAreas.find { area ->
            boundsOverlap(cluster.bounds, area.bounds)
        } : null

        if (matchedArea) {
            cluster.targetAreaIndex = matchedArea.areaIndex
            cluster.targetSource = matchedArea.source
            cluster.bustMode = matchedArea.source in ["manual", "auto"] ? matchedArea.source : cluster.bustMode
            cluster.appliedBounds = cloneBounds(matchedArea.bounds)
        } else {
            cluster.targetAreaIndex = null
            cluster.targetSource = null
            cluster.bustMode = null
            cluster.appliedBounds = null
        }
    }

    state.stabilityData[devKey] = stableClusters
}

private boolean boundsOverlap(Map a, Map b) {
    intersectBounds(a, b) != null
}

private Map getPersistentBustSourceCounts(List stableClusters) {
    def persistentClusters = (stableClusters ?: []).findAll { isClusterPersistent(it) || isClusterTargeted(it) || isClusterBusted(it) }
    [
            autoBusted: persistentClusters.count { (it.targetSource ?: it.bustMode) == "auto" },
            manualBusted: persistentClusters.count { (it.targetSource ?: it.bustMode) in ["manual", "manual-entry"] },
            unbusted: persistentClusters.count { isClusterPersistent(it) && !isClusterTargeted(it) && !isClusterBusted(it) }
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

private BigDecimal calculateStabilityPercent(Map cluster) {
    def activeDays = getActiveTrackingDaysForCluster(cluster)
    if (activeDays <= 0) {
        return 0.0d
    }

    return (((cluster.daysSeen ?: 0) as BigDecimal) / activeDays) * 100.0d
}

private Integer getActiveTrackingDaysForDevice(deviceId) {
    def devKey = deviceKey(deviceId)
    pruneActiveDayHistory(devKey)
    (((state.deviceActiveDayHistory ?: [:])[devKey] ?: []) as List).size()
}

private Integer getActiveTrackingDaysForCluster(Map cluster) {
    if (!cluster) {
        return 0
    }

    def currentDay = state.dayIndex ?: 0
    def historyCutoff = Math.max(1, currentDay - safeHistoryDays() + 1)
    def activeDays = ((cluster.activeDayHistory ?: []) as List)
            .collect { it as Integer }
            .findAll { it >= historyCutoff }
            .unique()
            .sort()
    activeDays.size()
}

private boolean isClusterPersistent(Map cluster) {
    (cluster?.consecutiveSeen ?: 0) >= safePersistentGhostDays()
}

private boolean isClusterTargeted(Map cluster) {
    cluster?.targetAreaIndex != null || cluster?.targetSource != null || cluster?.appliedBounds != null
}

private boolean isClusterBusted(Map cluster) {
    isClusterTargeted(cluster) && ((cluster?.absentStreak ?: 0) >= safeBustedGhostDays())
}

private Map calculatePlotScale(List points, List outOfBoundsPoints, List currentClusters, List historicalClusters, dev = null, String horizontalAxis = "x", String verticalAxis = "y", Map preferredScale = null) {
    def deviceBounds = dev ? getDeviceBounds(dev) : null
    def validDeviceBounds = isValidBounds(deviceBounds)
    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"

    if (!points && !outOfBoundsPoints && !currentClusters && !historicalClusters && !validDeviceBounds) {
        return null
    }

    def displayInBoundsPoints = []
    def displayOutOfBoundsPoints = []
    def allDisplayPoints = []
    allDisplayPoints.addAll(points ?: [])
    allDisplayPoints.addAll(outOfBoundsPoints ?: [])

    if (validDeviceBounds) {
        allDisplayPoints.each { point ->
            if (isPointWithinBounds(point.x, point.y, point.z, deviceBounds)) {
                displayInBoundsPoints << point
            } else {
                displayOutOfBoundsPoints << point
            }
        }
    } else {
        displayInBoundsPoints.addAll(points ?: [])
        displayOutOfBoundsPoints.addAll(outOfBoundsPoints ?: [])
    }

    def allPointsForScale = []
    allPointsForScale.addAll(displayInBoundsPoints.collect { [(horizontalAxis): it[horizontalAxis], (verticalAxis): it[verticalAxis]] })
    allPointsForScale.addAll(displayOutOfBoundsPoints.collect { [(horizontalAxis): it[horizontalAxis], (verticalAxis): it[verticalAxis]] })

    // Include cluster bounds (not just centers) for proper scaling
    (currentClusters ?: []).each { cluster ->
        if (cluster.bounds) {
            allPointsForScale << [(horizontalAxis): cluster.bounds[hMinField], (verticalAxis): cluster.bounds[vMinField]]
            allPointsForScale << [(horizontalAxis): cluster.bounds[hMaxField], (verticalAxis): cluster.bounds[vMaxField]]
        }
    }
    (historicalClusters ?: []).each { cluster ->
        if (cluster.bounds) {
            allPointsForScale << [(horizontalAxis): cluster.bounds[hMinField], (verticalAxis): cluster.bounds[vMinField]]
            allPointsForScale << [(horizontalAxis): cluster.bounds[hMaxField], (verticalAxis): cluster.bounds[vMaxField]]
        }
    }
    if (validDeviceBounds) {
        allPointsForScale << [(horizontalAxis): deviceBounds[hMinField], (verticalAxis): deviceBounds[vMinField]]
        allPointsForScale << [(horizontalAxis): deviceBounds[hMaxField], (verticalAxis): deviceBounds[vMaxField]]
    }

    def xs = allPointsForScale.collect { it[horizontalAxis] }
    def ys = allPointsForScale.collect { it[verticalAxis] }
    def minX = xs.min()
    def maxX = xs.max()
    def minY = ys.min()
    def maxY = ys.max()

    def boundaryXRange = validDeviceBounds ? (deviceBounds[hMaxField] - deviceBounds[hMinField]) : null
    def boundaryYRange = validDeviceBounds ? (deviceBounds[vMaxField] - deviceBounds[vMinField]) : null
    def dataXRange = maxX - minX
    def dataYRange = maxY - minY

    // Keep the full device boundary visible at all times and pad around it for context.
    def xBaseRange = Math.max(dataXRange ?: 0.0d, boundaryXRange ?: 0.0d)
    def yBaseRange = Math.max(dataYRange ?: 0.0d, boundaryYRange ?: 0.0d)
    def xPadding = Math.max(xBaseRange * 0.12d, 10.0d)
    def yPadding = Math.max(yBaseRange * 0.12d, 10.0d)

    minX -= xPadding
    maxX += xPadding
    minY -= yPadding
    maxY += yPadding

    def width = 360
    def height = 324
    def plotLeft = 62
    def plotRight = width - 16
    def plotTop = 46
    def plotBottom = height - 62
    def plotWidth = plotRight - plotLeft
    def plotHeight = plotBottom - plotTop

    // Maintain equal units-per-pixel on both axes so the device boundary is not visually distorted.
    def currentXRange = maxX - minX
    def currentYRange = maxY - minY
    if (currentXRange > 0 && currentYRange > 0) {
        def xUnitsPerPixel = currentXRange / plotWidth
        def yUnitsPerPixel = currentYRange / plotHeight
        if (xUnitsPerPixel > yUnitsPerPixel) {
            def expandedYRange = xUnitsPerPixel * plotHeight
            def yCenter = (minY + maxY) / 2.0d
            minY = yCenter - (expandedYRange / 2.0d)
            maxY = yCenter + (expandedYRange / 2.0d)
        } else if (yUnitsPerPixel > xUnitsPerPixel) {
            def expandedXRange = yUnitsPerPixel * plotWidth
            def xCenter = (minX + maxX) / 2.0d
            minX = xCenter - (expandedXRange / 2.0d)
            maxX = xCenter + (expandedXRange / 2.0d)
        }
    }

    if (preferredScale?.minX != null && preferredScale?.maxX != null && horizontalAxis == (preferredScale.horizontalAxis ?: horizontalAxis)) {
        minX = preferredScale.minX
        maxX = preferredScale.maxX
    }

    [
            minX: minX,
            maxX: maxX,
            minY: minY,
            maxY: maxY,
            width: width,
            height: height,
            plotLeft: plotLeft,
            plotRight: plotRight,
            horizontalAxis: horizontalAxis,
            verticalAxis: verticalAxis
    ]
}

private String renderClusterPlot(List points, List outOfBoundsPoints, List currentClusters, List historicalClusters, dev = null, String horizontalAxis = "x", String verticalAxis = "y", Map preferredScale = null) {
    def scale = calculatePlotScale(points, outOfBoundsPoints, currentClusters, historicalClusters, dev, horizontalAxis, verticalAxis, preferredScale)
    if (!scale) {
        return "No points or cluster history available."
    }

    def deviceBounds = dev ? getDeviceBounds(dev) : null
    def validDeviceBounds = isValidBounds(deviceBounds)
    def legendItems = ["<tspan fill='#ff9800'>Orange</tspan><tspan fill='#333333'> = in-bounds</tspan>"]
    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"
    def annotationAxis = ["x", "y", "z"].find { it != horizontalAxis && it != verticalAxis }

    def displayInBoundsPoints = []
    def displayOutOfBoundsPoints = []
    def allDisplayPoints = []
    allDisplayPoints.addAll(points ?: [])
    allDisplayPoints.addAll(outOfBoundsPoints ?: [])

    if (validDeviceBounds) {
        allDisplayPoints.each { point ->
            if (isPointWithinBounds(point.x, point.y, point.z, deviceBounds)) {
                displayInBoundsPoints << point
            } else {
                displayOutOfBoundsPoints << point
            }
        }
    } else {
        displayInBoundsPoints.addAll(points ?: [])
        displayOutOfBoundsPoints.addAll(outOfBoundsPoints ?: [])
    }

    if (displayOutOfBoundsPoints) {
        legendItems << "<tspan fill='#888888'>Gray</tspan><tspan fill='#333333'> = out-of-bounds</tspan>"
    }
    if (validDeviceBounds) {
        legendItems << "<tspan fill='#888888'>Gray box</tspan><tspan fill='#333333'> = device bounds</tspan>"
    }
    legendItems << "<tspan fill='#1565c0'>Blue box</tspan><tspan fill='#333333'> = cluster bounds</tspan>"
    legendItems << "<tspan fill='#c62828'>Red dot</tspan><tspan fill='#333333'> = current cluster</tspan>"
    def legendRows = legendItems.collate(2)
    def legendRowHeight = 12
    def legendBandHeight = 8 + (legendRows.size() * legendRowHeight)
    def width = scale.width
    def height = scale.height + Math.max(0, legendRows.size() - 2) * 12
    def plotLeft = scale.plotLeft
    def plotRight = scale.plotRight
    def plotTop = legendBandHeight + 14
    def plotBottom = height - 62
    def plotWidth = plotRight - plotLeft
    def plotHeight = plotBottom - plotTop
    def minX = scale.minX
    def maxX = scale.maxX
    def minY = scale.minY
    def maxY = scale.maxY

    // Preserve the inherited X scale and still keep equal units-per-pixel in each plot.
    def currentXRange = maxX - minX
    def currentYRange = maxY - minY
    if (currentXRange > 0 && currentYRange > 0) {
        def xUnitsPerPixel = currentXRange / plotWidth
        def yUnitsPerPixel = currentYRange / plotHeight
        if (xUnitsPerPixel > yUnitsPerPixel) {
            def expandedYRange = xUnitsPerPixel * plotHeight
            def yCenter = (minY + maxY) / 2.0d
            minY = yCenter - (expandedYRange / 2.0d)
            maxY = yCenter + (expandedYRange / 2.0d)
        }
    }

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
    svg << "<rect x='${plotLeft}' y='8' width='${plotWidth}' height='${legendBandHeight}' rx='3' ry='3' fill='rgba(255,255,255,0.96)' stroke='#d7dde1' />"
    legendRows.eachWithIndex { row, rowIndex ->
        def legendText = row.join("<tspan fill='#333333'>  |  </tspan>")
        svg << "<text x='${plotLeft + 6}' y='${20 + (rowIndex * legendRowHeight)}' fill='#333333' font-size='9'>${legendText}</text>"
    }
    svg << "<rect x='${plotLeft}' y='${plotTop}' width='${plotRight - plotLeft}' height='${plotBottom - plotTop}' fill='#ffffff' stroke='#d0d0d0' />"

    // Draw device bounds box (gray dashed box)
    if (validDeviceBounds) {
        def x1 = normalizeX(deviceBounds[hMinField])
        def x2 = normalizeX(deviceBounds[hMaxField])
        def y1 = normalizeY(deviceBounds[vMaxField])
        def y2 = normalizeY(deviceBounds[vMinField])
        svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='none' stroke='#888888' stroke-width='1' stroke-dasharray='4,2' />"
    }

    // Draw cluster bounds boxes (blue dashed box) - behind points
    def allClusters = []
    allClusters.addAll(currentClusters ?: [])
    allClusters.addAll(historicalClusters ?: [])

    allClusters.each { cluster ->
        if (cluster.bounds) {
            def x1 = normalizeX(cluster.bounds[hMinField])
            def x2 = normalizeX(cluster.bounds[hMaxField])
            def y1 = normalizeY(cluster.bounds[vMaxField])
            def y2 = normalizeY(cluster.bounds[vMinField])
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='none' stroke='#1565c0' stroke-width='1' stroke-dasharray='3,2' />"
        }
    }

    svg << "<line x1='${plotLeft}' y1='${plotBottom}' x2='${plotRight}' y2='${plotBottom}' stroke='#666666' stroke-width='1' />"
    svg << "<line x1='${plotLeft}' y1='${plotTop}' x2='${plotLeft}' y2='${plotBottom}' stroke='#666666' stroke-width='1' />"
    if (horizontalAxis == "x" && minX <= 0 && maxX >= 0) {
        def zeroX = normalizeX(0.0d)
        svg << "<line x1='${zeroX}' y1='${plotTop}' x2='${zeroX}' y2='${plotBottom}' stroke='#b0bec5' stroke-width='1' stroke-dasharray='2,2' />"
        svg << "<text x='${zeroX}' y='${plotBottom + 34}' text-anchor='middle' fill='#455a64' font-size='10'>0</text>"
    }
    svg << "<text x='${(plotLeft + plotRight) / 2}' y='${height - 12}' text-anchor='middle' fill='#333333' font-size='11'>${horizontalAxis.toUpperCase()} (cm)</text>"
    svg << "<text x='14' y='${(plotTop + plotBottom) / 2}' text-anchor='middle' fill='#333333' font-size='11' transform='rotate(-90 14 ${(plotTop + plotBottom) / 2})'>${verticalAxis.toUpperCase()} (cm)</text>"
    svg << "<text x='${plotLeft}' y='${plotBottom + 34}' text-anchor='start' fill='#555555' font-size='10'>${round2(minX)}</text>"
    svg << "<text x='${plotRight}' y='${plotBottom + 34}' text-anchor='end' fill='#555555' font-size='10'>${round2(maxX)}</text>"
    svg << "<text x='${plotLeft - 24}' y='${plotBottom + 4}' text-anchor='end' fill='#555555' font-size='10'>${round2(minY)}</text>"
    svg << "<text x='${plotLeft - 24}' y='${plotTop + 4}' text-anchor='end' fill='#555555' font-size='10'>${round2(maxY)}</text>"
    if (validDeviceBounds) {
        def xMinLabelX = normalizeX(deviceBounds[hMinField])
        def xMaxLabelX = normalizeX(deviceBounds[hMaxField])
        def yMinLabelY = normalizeY(deviceBounds[vMinField])
        def yMaxLabelY = normalizeY(deviceBounds[vMaxField])
        svg << "<line x1='${xMinLabelX}' y1='${plotBottom}' x2='${xMinLabelX}' y2='${plotBottom + 8}' stroke='#888888' stroke-width='1' />"
        svg << "<line x1='${xMaxLabelX}' y1='${plotBottom}' x2='${xMaxLabelX}' y2='${plotBottom + 8}' stroke='#888888' stroke-width='1' />"
        svg << "<text x='${xMinLabelX}' y='${plotBottom + 20}' text-anchor='middle' fill='#ef6c00' font-size='9' font-weight='bold'>${round2(deviceBounds[hMinField])}</text>"
        svg << "<text x='${xMaxLabelX}' y='${plotBottom + 20}' text-anchor='middle' fill='#ef6c00' font-size='9' font-weight='bold'>${round2(deviceBounds[hMaxField])}</text>"
        svg << "<line x1='${plotLeft - 7}' y1='${yMinLabelY}' x2='${plotLeft}' y2='${yMinLabelY}' stroke='#888888' stroke-width='1' />"
        svg << "<line x1='${plotLeft - 7}' y1='${yMaxLabelY}' x2='${plotLeft}' y2='${yMaxLabelY}' stroke='#888888' stroke-width='1' />"
        svg << "<text x='${plotLeft - 10}' y='${yMinLabelY + 4}' text-anchor='end' fill='#ef6c00' font-size='9' font-weight='bold'>${round2(deviceBounds[vMinField])}</text>"
        svg << "<text x='${plotLeft - 10}' y='${yMaxLabelY + 4}' text-anchor='end' fill='#ef6c00' font-size='9' font-weight='bold'>${round2(deviceBounds[vMaxField])}</text>"
    }

    // Draw all points (both in-bounds and out-of-bounds)
    displayOutOfBoundsPoints.each { point ->
        svg << "<circle cx='${normalizeX(point[horizontalAxis])}' cy='${normalizeY(point[verticalAxis])}' r='2' fill='#888888' />"
    }

    displayInBoundsPoints.each { point ->
        svg << "<circle cx='${normalizeX(point[horizontalAxis])}' cy='${normalizeY(point[verticalAxis])}' r='2' fill='#ff9800' />"
    }

    // Draw current cluster centers (red dots)
    (currentClusters ?: []).each { cluster ->
        def centerX = normalizeX(cluster.center[horizontalAxis])
        def centerY = normalizeY(cluster.center[verticalAxis])
        svg << "<circle cx='${centerX}' cy='${centerY}' r='4' fill='#c62828' />"
        if (annotationAxis && cluster.center[annotationAxis] != null) {
            svg << "<text x='${centerX + 8}' y='${centerY - 8}' fill='#333333' font-size='10'>${annotationAxis.toUpperCase()} ${round2(cluster.center[annotationAxis])}</text>"
        }
    }

    svg << "</svg>"
    svg.toString()
}

private String renderCorrelationSummary(deviceId) {
    def devKey = deviceKey(deviceId)
    def configuredTrackers = getConfiguredCorrelationTrackersForDevice(devKey)
    if (!configuredTrackers) {
        return null
    }

    def todayStats = (state.correlationDaily[devKey] ?: [:]) as Map
    def history = ((state.correlationHistory[devKey] ?: []) as List)
    def coincidenceWindow = safeCorrelationChangeWindowSeconds()
    def cards = new StringBuilder()

    configuredTrackers.each { tracker ->
        def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
        def aggregate = emptyCorrelationAggregate(tracker.deviceName, tracker.attribute)
        history.each { entry ->
            mergeCorrelationAggregate(aggregate, (((entry.trackers ?: [:]) as Map)[trackerKey] ?: [:]) as Map)
        }
        mergeCorrelationAggregate(aggregate, (todayStats[trackerKey] ?: [:]) as Map)

        def ghostSamplePct = percent(aggregate.ghostSamples, aggregate.samples)
        def anyChangePct = percent(aggregate.ghostAppearancesNearAnyChange, aggregate.ghostAppearances)
        def topValues = ((aggregate.values ?: [:]) as Map)
                .sort { a, b -> ((b.value.ghostSamples ?: 0) + (b.value.clearSamples ?: 0)) <=> ((a.value.ghostSamples ?: 0) + (a.value.clearSamples ?: 0)) }
                .collect { value, stats ->
                    "${value}: ghost ${percent(stats.ghostSamples, aggregate.ghostSamples)} | clear ${percent(stats.clearSamples, aggregate.clearSamples)}"
                }
                .take(3)
        def topChangeValues = ((aggregate.changeToValues ?: [:]) as Map)
                .sort { a, b -> (b.value.ghostAppearances ?: 0) <=> (a.value.ghostAppearances ?: 0) }
                .collect { value, stats ->
                    "${value}: ${percent(stats.ghostAppearances, aggregate.ghostAppearances)} of appearances"
                }
                .take(3)

        def body = new StringBuilder()
        body << "<div style='margin-bottom:10px;'>"
        body << "<div style='font-weight:bold; color:#333333; margin-bottom:4px;'>${tracker.deviceName} / ${tracker.attribute}</div>"
        body << "<div style='color:#555555; margin-bottom:4px;'>Ghost-present samples: ${aggregate.ghostSamples ?: 0} / ${aggregate.samples ?: 0} (${ghostSamplePct})</div>"
        body << "<div style='color:#555555; margin-bottom:4px;'>Ghost appearances near any change in last ${coincidenceWindow}s: ${aggregate.ghostAppearancesNearAnyChange ?: 0} / ${aggregate.ghostAppearances ?: 0} (${anyChangePct})</div>"
        if (aggregate.lastValue != null) {
            body << "<div style='color:#555555; margin-bottom:4px;'>Latest sampled value: ${aggregate.lastValue}</div>"
        }
        if (topValues) {
            body << "<div style='color:#555555; margin-bottom:4px;'>Value mix: ${topValues.join(' | ')}</div>"
        }
        if (topChangeValues) {
            body << "<div style='color:#555555;'>Change-to values before appearance: ${topChangeValues.join(' | ')}</div>"
        }
        body << "</div>"
        cards << renderNoteCard("Correlation", body.toString())
    }

    cards.toString()
}

private String renderInterferenceAreasCard(deviceId) {
    def devKey = deviceKey(deviceId)
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    if (!rememberedAreas) {
        return renderNoteCard("Remembered Interference Areas", "No interference areas are currently remembered for this device.")
    }

    def rows = rememberedAreas.collect { area ->
        "Index ${area.areaIndex}: X ${round2(area.bounds.xmin)}..${round2(area.bounds.xmax)}, Y ${round2(area.bounds.ymin)}..${round2(area.bounds.ymax)}, Z ${round2(area.bounds.zmin)}..${round2(area.bounds.zmax)} (${describeTargetSource(area.source)})"
    }
    renderNoteCard("Remembered Interference Areas", rows.join("<br>"))
}

private Map emptyCorrelationAggregate(String deviceName, String attribute) {
    [
            deviceName: deviceName,
            attribute: attribute,
            samples: 0,
            ghostSamples: 0,
            clearSamples: 0,
            ghostAppearances: 0,
            ghostAppearancesNearAnyChange: 0,
            lastValue: null,
            values: [:],
            changeToValues: [:]
    ]
}

private void mergeCorrelationAggregate(Map aggregate, Map stats) {
    if (!stats) {
        return
    }

    aggregate.samples = (aggregate.samples ?: 0) + (stats.samples ?: 0)
    aggregate.ghostSamples = (aggregate.ghostSamples ?: 0) + (stats.ghostSamples ?: 0)
    aggregate.clearSamples = (aggregate.clearSamples ?: 0) + (stats.clearSamples ?: 0)
    aggregate.ghostAppearances = (aggregate.ghostAppearances ?: 0) + (stats.ghostAppearances ?: 0)
    aggregate.ghostAppearancesNearAnyChange = (aggregate.ghostAppearancesNearAnyChange ?: 0) + (stats.ghostAppearancesNearAnyChange ?: 0)
    if (stats.lastValue != null) {
        aggregate.lastValue = stats.lastValue
    }

    ((stats.values ?: [:]) as Map).each { key, value ->
        def existing = (aggregate.values[key] ?: [ghostSamples: 0, clearSamples: 0]) as Map
        existing.ghostSamples = (existing.ghostSamples ?: 0) + (value.ghostSamples ?: 0)
        existing.clearSamples = (existing.clearSamples ?: 0) + (value.clearSamples ?: 0)
        aggregate.values[key] = existing
    }

    ((stats.changeToValues ?: [:]) as Map).each { key, value ->
        def existing = (aggregate.changeToValues[key] ?: [ghostAppearances: 0]) as Map
        existing.ghostAppearances = (existing.ghostAppearances ?: 0) + (value.ghostAppearances ?: 0)
        aggregate.changeToValues[key] = existing
    }
}

private Integer safeCorrelationChangeWindowSeconds() {
    Math.max(1, (correlationChangeWindowSeconds ?: 60) as Integer)
}

private String percent(Number numerator, Number denominator) {
    if (!denominator || denominator.toDouble() <= 0.0d) {
        return "0%"
    }
    "${round2((numerator ?: 0).toDouble() * 100.0d / denominator.toDouble())}%"
}

private String renderClusterDetails(Map cluster, Integer displayIndex, String devKey) {
    def bounds = cluster.bounds ?: [:]
    def status = determineClusterState(cluster)

    def hasPoints = cluster.points && cluster.points.size() > 0
    def clusterKey = "${devKey}_cluster_${displayIndex}"

    def html = renderStatBlock("Cluster ${displayIndex}: ${status}", [
            "Center": "(${round2(cluster.center.x)}, ${round2(cluster.center.y)}, ${round2(cluster.center.z)}) cm",
            "X range": "${round2(bounds.xmin)} to ${round2(bounds.xmax)} cm",
            "Y range": "${round2(bounds.ymin)} to ${round2(bounds.ymax)} cm",
            "Z range": "${round2(bounds.zmin)} to ${round2(bounds.zmax)} cm",
            "Radius": "${round2(cluster.radius)} cm",
            "Density": cluster.density ?: 0,
            "Days in window": cluster.daysSeen ?: 0,
            "Consecutive days": cluster.consecutiveSeen ?: 0,
            "Missing streak": cluster.absentStreak ?: 0,
            "Stability": "${round2(cluster.stabilityPct ?: calculateStabilityPercent(cluster))}%",
            "Target area index": cluster.targetAreaIndex != null ? cluster.targetAreaIndex : "None",
            "Target source": cluster.targetSource ? describeTargetSource(cluster.targetSource) : "None",
            "Targeting": describeBustMode(cluster)
    ])

    // Add cluster splitting controls if the cluster has points
    if (hasPoints && cluster.density >= safeMinClusterEvents() * 2) {
        html += "<div style='margin-top:8px;'>"
        html += "<input name='splitClusterRadius_${clusterKey}' type='decimal' title='Split radius (cm)' value='${round2(cluster.radius * 0.5)}' style='width:80px;'/> "
        html += "<input name='splitCluster_${clusterKey}' type='button' value='Split Cluster ${displayIndex}' style='margin-left:4px;'/>"
        html += "</div>"
    }

    html
}

private String describeClusterOption(Map cluster, Integer idx) {
    def bounds = cluster.bounds ?: [:]
    "Cluster ${idx + 1}: X ${round2(bounds.xmin)}..${round2(bounds.xmax)} cm, Y ${round2(bounds.ymin)}..${round2(bounds.ymax)} cm, Z ${round2(bounds.zmin)}..${round2(bounds.zmax)} cm"
}

private String renderRecommendationSummary(def recommendation) {
    if (recommendation?.message) {
        return renderNoteCard("Recommendation", recommendation.message as String)
    }

    renderStatBlock("Recommended Exclusion Zone", [
            "Device": recommendation.deviceName,
            "Center": "(${round2(recommendation.center.x)}, ${round2(recommendation.center.y)}, ${round2(recommendation.center.z)}) cm",
            "Radius": "${round2(recommendation.radius)} cm",
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
    if (isClusterBusted(cluster)) {
        return "Area ${cluster.targetAreaIndex} (${describeTargetSource(cluster.targetSource)}) eliminated this ghost for ${cluster.absentStreak ?: 0} active day(s)"
    }

    if (isClusterTargeted(cluster)) {
        return "Area ${cluster.targetAreaIndex} (${describeTargetSource(cluster.targetSource)}) is targeting this ghost"
    }

    if (isClusterPersistent(cluster)) {
        return "Persistent but not targeted"
    }

    "Detected but not persistent"
}

private String determineClusterState(Map cluster) {
    if (isClusterBusted(cluster)) {
        return "Busted"
    }
    if (isClusterTargeted(cluster)) {
        return "Targeted"
    }
    if (isClusterPersistent(cluster)) {
        return "Persistent"
    }
    "Detected"
}

private String describeTargetSource(String source) {
    switch (source) {
        case "auto":
            return "Automatic"
        case "manual":
            return "App-applied"
        case "manual-entry":
            return "Remembered manually"
        default:
            return source ?: "Unknown"
    }
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
            "Clustering": "${clusteringAlgorithm ?: 'DBSCAN'} / r=${clusterRadius ?: 50}cm / min=${minClusterEvents ?: 5}",
            "Persistence": "history ${historyDays ?: 14}d / persistent ${persistentGhostDays ?: 2}d / busted ${bustedGhostDays ?: 2}d",
            "Auto busting": enableAutoGhostBusting ? describeAutoBustConfiguration() : "Disabled",
            "Notifications": sendPush ? "Enabled" : "Disabled"
    ]
}

private Map getMainPageStatsSummary() {
    def ghosts = 0
    def detected = 0
    def persistent = 0
    def targeted = 0
    def busted = 0
    def pointsProcessed = 0

    mmwaveDevices?.each { dev ->
        def displayCounts = getDisplayCounts(dev.id)
        def summary = getSummaryForDevice(dev.id)
        ghosts += displayCounts.ghostsToday ?: 0
        detected += displayCounts.detectedGhosts ?: 0
        persistent += displayCounts.persistentGhosts ?: 0
        targeted += displayCounts.targetedGhosts ?: 0
        busted += displayCounts.bustedGhosts ?: 0
        pointsProcessed += summary.pointCount ?: 0
    }

    [
            "Last processed ghosts": ghosts,
            "Detected, not persistent": detected,
            "Persistent ghosts": persistent,
            "Targeted ghosts": targeted,
            "Busted ghosts": busted,
            "Points in last run": pointsProcessed
    ]
}

private List getSortedMmwaveDevices() {
    ((mmwaveDevices ?: []) as List).sort { a, b ->
        (a?.displayName ?: "").toLowerCase() <=> (b?.displayName ?: "").toLowerCase()
    }
}

private String summarizeDeviceNames(devices) {
    if (!devices) {
        return "None selected"
    }

    def names = devices.collect { it.displayName }.sort { it?.toLowerCase() ?: "" }
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

private void scheduleStatsPageRefresh() {
    state.statsPageRefreshUntil = now() + 3000L
}

private List filterPointsToDeviceBounds(List points, String devKey) {
    if (!filterPointsByDeviceBounds) {
        return (points ?: []).collect { clonePoint(it) }
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def bounds = dev ? getDeviceBounds(dev) : null
    if (!isValidBounds(bounds)) {
        return (points ?: []).collect { clonePoint(it) }
    }

    (points ?: []).findAll { point ->
        isPointWithinBounds(point.x, point.y, point.z, bounds)
    }.collect { clonePoint(it) }
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
            activeDayHistory: ((cluster.activeDayHistory ?: []) as List).collect { it },
            consecutiveSeen: cluster.consecutiveSeen ?: 0,
            absentStreak: cluster.absentStreak ?: 0,
            lastMatchedDay: cluster.lastMatchedDay ?: 0,
            bustMode: cluster.bustMode,
            targetSource: cluster.targetSource ?: cluster.bustMode,
            targetAreaIndex: cluster.targetAreaIndex,
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
            activeDayHistory: ((cluster.activeDayHistory ?: []) as List).collect { it },
            consecutiveSeen: cluster.consecutiveSeen ?: 0,
            absentStreak: cluster.absentStreak ?: 0,
            lastMatchedDay: cluster.lastMatchedDay ?: 0,
            bustMode: cluster.bustMode,
            targetSource: cluster.targetSource ?: cluster.bustMode,
            targetAreaIndex: cluster.targetAreaIndex,
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
        debugLog("getDeviceBounds: no device provided")
        return null
    }

    try {
        // parameter103 = Width Minimum (Left), parameter104 = Width Maximum (Right)
        def currentXMin = toDouble(dev.currentValue("parameter103"))
        def settingXMin = toDouble(dev.getSetting("parameter103"))
        def currentXMax = toDouble(dev.currentValue("parameter104"))
        def settingXMax = toDouble(dev.getSetting("parameter104"))
        def xMin = firstNonNullDouble(firstNonNullDouble(currentXMin, settingXMin), -600.0d)
        def xMax = firstNonNullDouble(firstNonNullDouble(currentXMax, settingXMax), 600.0d)

        // parameter105 = Depth Minimum (Near), parameter106 = Depth Maximum (Far)
        def currentYMin = toDouble(dev.currentValue("parameter105"))
        def settingYMin = toDouble(dev.getSetting("parameter105"))
        def currentYMax = toDouble(dev.currentValue("parameter106"))
        def settingYMax = toDouble(dev.getSetting("parameter106"))
        def yMin = firstNonNullDouble(firstNonNullDouble(currentYMin, settingYMin), 0.0d)
        def yMax = firstNonNullDouble(firstNonNullDouble(currentYMax, settingYMax), 600.0d)

        // parameter101 = Height Minimum (Floor), parameter102 = Height Maximum (Ceiling)
        def currentZMin = toDouble(dev.currentValue("parameter101"))
        def settingZMin = toDouble(dev.getSetting("parameter101"))
        def currentZMax = toDouble(dev.currentValue("parameter102"))
        def settingZMax = toDouble(dev.getSetting("parameter102"))
        def zMin = firstNonNullDouble(firstNonNullDouble(currentZMin, settingZMin), -300.0d)
        def zMax = firstNonNullDouble(firstNonNullDouble(currentZMax, settingZMax), 300.0d)

        debugLog("getDeviceBounds: ${dev.displayName} raw values " +
                JsonOutput.toJson([
                        current: [xmin: currentXMin, xmax: currentXMax, ymin: currentYMin, ymax: currentYMax, zmin: currentZMin, zmax: currentZMax],
                        settings: [xmin: settingXMin, xmax: settingXMax, ymin: settingYMin, ymax: settingYMax, zmin: settingZMin, zmax: settingZMax],
                        resolved: [xmin: xMin, xmax: xMax, ymin: yMin, ymax: yMax, zmin: zMin, zmax: zMax]
                ]))

        def bounds = [
                xmin: xMin,
                xmax: xMax,
                ymin: yMin,
                ymax: yMax,
                zmin: zMin,
                zmax: zMax
        ]
        debugLog("getDeviceBounds: ${dev.displayName} returning ${JsonOutput.toJson(bounds)}")
        return bounds
    } catch (Exception ex) {
        warnLog("Unable to retrieve device bounds for ${dev.displayName}: ${ex.message}")
        return null
    }
}

private Double firstNonNullDouble(Double primary, Double fallback) {
    primary != null ? primary : fallback
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
    (clusterRadius ?: 50) as BigDecimal
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

private String renderSideBySidePlots(String leftTitle, String leftPlot, String rightTitle, String rightPlot) {
    """<table style='width:100%; border-collapse:separate; border-spacing:8px 0;'>
<tr>
<td style='width:50%; vertical-align:top;'>${getInterface("subHeader", leftTitle)}${leftPlot}</td>
<td style='width:50%; vertical-align:top;'>${getInterface("subHeader", rightTitle)}${rightPlot}</td>
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
