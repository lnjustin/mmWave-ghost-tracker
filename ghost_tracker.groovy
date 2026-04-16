import groovy.json.JsonOutput
import groovy.json.JsonSlurper

definition(
        name: "mmWave Ghost Buster",
        namespace: "lnjustin",
        author: "lnjustin",
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
    refreshRecommendation()
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section() {
            paragraph renderHomeHeroCard()
        }
        section(getInterface("header", "Ghost Detection")) {

            paragraph renderMetricDashboard([
                    [title: "Summary", stats: getMainPageHeadlineSummary()]
            ], 1)
            href "statsPage", title: "View Analysis And Interference Controls", description: "Per-device graphs, ghost states, and interference area controls"
            if (state.recommendation) {
                paragraph renderRecommendationSummary(state.recommendation)
            }
        }

        section(getInterface("header", "Configuration")) {
            href "settingsPage", title: "Configure Devices and Detection", description: "Choose devices, activation, clustering, persistence, and notifications"
            paragraph renderMetricDashboard([
                    [title: "Configuration Summary", stats: getConfigurationSummaryStats()]
            ], 1)
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

            input "leakRecoveryDays",
                    "number",
                    title: "Consecutive non-leaking days before a leaking ghost becomes busted again",
                    defaultValue: 2

            input "stableThreshold",
                    "number",
                    title: "Recommendation threshold (%)",
                    defaultValue: 70

            input "recommendOnlyPersistentGhosts",
                    "bool",
                    title: "Only recommend interference areas for persistent ghosts",
                    defaultValue: true
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

        section(getInterface("header", "Remembered Interference Areas")) {
            def sortedDevices = getSortedMmwaveDevices()
            if (!sortedDevices) {
                paragraph "Select mmWave switches first to manage remembered interference areas."
            } else {
                paragraph getInterface("note", "Record interference areas already set on the device so the app can track targeted and busted ghosts against those remembered areas. Dynamic activation controls are also below each area row: enable it, choose a device and attribute, then enter the value that should turn that area on.")

                sortedDevices.each { dev ->
                    def devKey = deviceKey(dev.id)
                    paragraph getInterface("subHeader", dev.displayName)
                    paragraph renderInterferenceAreasCard(dev.id)
                    (0..3).each { areaIndex ->
                        input "manualAreaXMin_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} X min", required: false, width: 2
                        input "manualAreaXMax_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} X max", required: false, width: 2
                        input "manualAreaYMin_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} Y min", required: false, width: 2
                        input "manualAreaYMax_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} Y max", required: false, width: 2
                        input "manualAreaZMin_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} Z min", required: false, width: 2
                        input "manualAreaZMax_${devKey}_${areaIndex}", "decimal", title: "A${areaIndex} Z max", required: false, width: 2
                        input "saveManualArea_${devKey}_${areaIndex}", "button", title: "Save A${areaIndex}"
                        input "clearRememberedArea_${devKey}_${areaIndex}", "button", title: "Clear A${areaIndex}"
                    }
                }
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

                input "notifyOnAnyGhostDetected",
                        "bool",
                        title: "Notify when any ghost is detected",
                        defaultValue: false

                input "notifyOnPersistentGhostDetected",
                        "bool",
                        title: "Notify when a persistent ghost is detected",
                        defaultValue: true

                input "notifyOnRecommendation",
                        "bool",
                        title: "Notify when a ghost is recommended to target",
                        defaultValue: true

                input "notifyOnGhostBusted",
                        "bool",
                        title: "Notify when a ghost is busted",
                        defaultValue: true

                input "notifyOnTargetedPersistentGhost",
                        "bool",
                        title: "Notify when a targeted ghost is still persistent",
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
    refreshRecommendation()
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
                paragraph renderMetricDashboard([
                        [title: "Network Summary", stats: [
                                "Detected": sortedDevices.collect { getDisplayCounts(it.id).detectedGhosts ?: 0 }.sum() ?: 0,
                                "Persistent": aggregatePersistent,
                                "Targeted": "${sortedDevices.collect { getDisplayCounts(it.id).targetedGhosts ?: 0 }.sum() ?: 0} (${aggregateBustSources.autoBusted ?: 0} auto)",
                                "Busted": aggregateBusted,
                                "Processed days": state.totalDays ?: 0,
                                "Last processed ghosts": aggregateToday
                        ]]
                ], 1)

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
            def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
            def leakPointCount = (pointBuckets.occupancyAssociatedInterferencePoints ?: []).size()
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
                paragraph renderMetricDashboard([
                        [title: "Ghost Summary", stats: getGhostSummaryStats(dev.id, "Overall Summary")],
                        [title: "Tracking", html: renderTrackingPanel(getTrackingStats(dev.id, displayCounts, lastSummary))]
                ], 2)

                paragraph renderSideBySidePlots(
                        "X / Y View",
                        renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "y", xyScale),
                        "X / Z View",
                        renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "z", xyScale)
                )

                if ((displayCounts.leakingGhosts ?: 0) > 0 || leakPointCount > 0) {
                    paragraph renderNoteCard(
                            "Interference Leakage",
                            "${leakPointCount} point(s) inside configured interference areas may still be contributing to occupancy. ${displayCounts.leakingGhosts ?: 0} targeted ghost(s) are currently flagged as leaking through their configured areas."
                    )
                }

                def correlationSummary = renderCorrelationSummary(dev.id)
                if (correlationSummary) {
                    paragraph getInterface("subHeader", "Correlation Tracking")
                    paragraph correlationSummary
                }

                if (stableClusters) {
                    paragraph getInterface("subHeader", "Tracked Ghosts")
                    stableClusters.eachWithIndex { cluster, idx ->
                        paragraph renderClusterDetails(cluster, idx + 1, devKey)
                    }
                } else {
                    paragraph "No ghost history yet for this device."
                }

                if ((lastSummary.unclusteredPointCount ?: 0) > 0) {
                    paragraph renderNoteCard("Detection Note", "Last processed run had ${lastSummary.unclusteredPointCount} point(s) that did not form a ghost cluster.")
                }

                paragraph getInterface("subHeader", "Interference Area Controls")

                if (displayData.selectableClusters) {
                    input "targetCluster_${devKey}_0",
                            "enum",
                            title: buildAreaSelectorTitle(dev.id, 0, displayData.selectableClusters),
                            multiple: false,
                            required: false,
                            options: buildSelectableClusterOptions(displayData.selectableClusters),
                            defaultValue: getAssignedClusterOption(dev.id, 0, displayData.selectableClusters),
                            width: 4
                    input "dynamicActivation_${devKey}_0",
                            "enum",
                            title: "A0 dynamic",
                            multiple: false,
                            required: false,
                            options: getDynamicActivationOptions(dev.id),
                            defaultValue: getDynamicActivationSelection(dev.id, 0),
                            width: 2
                    input "targetCluster_${devKey}_1",
                            "enum",
                            title: buildAreaSelectorTitle(dev.id, 1, displayData.selectableClusters),
                            multiple: false,
                            required: false,
                            options: buildSelectableClusterOptions(displayData.selectableClusters),
                            defaultValue: getAssignedClusterOption(dev.id, 1, displayData.selectableClusters),
                            width: 4
                    input "dynamicActivation_${devKey}_1",
                            "enum",
                            title: "A1 dynamic",
                            multiple: false,
                            required: false,
                            options: getDynamicActivationOptions(dev.id),
                            defaultValue: getDynamicActivationSelection(dev.id, 1),
                            width: 2
                    input "targetCluster_${devKey}_2",
                            "enum",
                            title: buildAreaSelectorTitle(dev.id, 2, displayData.selectableClusters),
                            multiple: false,
                            required: false,
                            options: buildSelectableClusterOptions(displayData.selectableClusters),
                            defaultValue: getAssignedClusterOption(dev.id, 2, displayData.selectableClusters),
                            width: 4
                    input "dynamicActivation_${devKey}_2",
                            "enum",
                            title: "A2 dynamic",
                            multiple: false,
                            required: false,
                            options: getDynamicActivationOptions(dev.id),
                            defaultValue: getDynamicActivationSelection(dev.id, 2),
                            width: 2
                    input "targetCluster_${devKey}_3",
                            "enum",
                            title: buildAreaSelectorTitle(dev.id, 3, displayData.selectableClusters),
                            multiple: false,
                            required: false,
                            options: buildSelectableClusterOptions(displayData.selectableClusters),
                            defaultValue: getAssignedClusterOption(dev.id, 3, displayData.selectableClusters),
                            width: 4
                    input "dynamicActivation_${devKey}_3",
                            "enum",
                            title: "A3 dynamic",
                            multiple: false,
                            required: false,
                            options: getDynamicActivationOptions(dev.id),
                            defaultValue: getDynamicActivationSelection(dev.id, 3),
                            width: 2
                    input "applyAllAreas_${devKey}", "button", title: "Apply Selected Area Assignments"
                } else {
                    paragraph "No ghosts are available for interference area targeting."
                }
            }
            section {
                input "reclusterDevice_${devKey}", "button", title: "Re-evaluate Points"
                input "clearDeviceStats_${devKey}", "button", title: "Clear Device Stats"
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

    if (btn.startsWith("assignGhostArea_")) {
        def parts = btn.substring("assignGhostArea_".length()).split("_")
        if (parts.size() >= 2) {
            applySelectedZoneForArea(parts[0], safeInterferenceAreaIndex(parts[1]))
            updateDynamicInterferenceAreas()
        }
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("applyAllAreas_")) {
        def devKey = btn.substring("applyAllAreas_".length())
        applyAllSelectedZones(devKey)
        updateDynamicInterferenceAreas()
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("saveManualArea_")) {
        def parts = btn.substring("saveManualArea_".length()).split("_")
        if (parts.size() >= 2) {
            saveManualInterferenceArea(parts[0], safeInterferenceAreaIndex(parts[1]))
            updateDynamicInterferenceAreas()
        }
        scheduleStatsPageRefresh()
        return
    }

    if (btn.startsWith("clearRememberedArea_")) {
        def parts = btn.substring("clearRememberedArea_".length()).split("_")
        if (parts.size() >= 2) {
            clearRememberedInterferenceArea(parts[0], safeInterferenceAreaIndex(parts[1]), true)
            updateDynamicInterferenceAreas()
        }
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

    getAllConfiguredDynamicAreaTrackers().each { tracker ->
        subscribe(tracker.device, tracker.attribute, correlationAttributeHandler)
        seedCorrelationAttributeState(tracker.device, tracker.attribute)
    }

    if (activationMode == "Conditioned On Virtual Switch") {
        getChildDevices()?.each { child ->
            subscribe(child, "switch", activatorSwitchHandler)
        }
    }

    updateDetectionState()
    updateDynamicInterferenceAreas()
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
    state.dynamicAreaStatus = state.dynamicAreaStatus ?: [:]
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

private String getDeviceStatus(dev) {
    if (!dev) {
        return "Inactive"
    }
    if (isDeviceActive(dev)) {
        return "Active"
    }
    if ((ghostModes ?: []) || activationMode == "Conditioned On Virtual Switch") {
        return "Conditionally active"
    }
    "Inactive"
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
    updateDynamicInterferenceAreas()
}

def targetInfoHandler(evt) {
    def dev = evt?.device
    if (!dev) {
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

    // Store all in-bounds points for visualization; ghost detection uses only eligible points.
    def currentPoints = ((state.dailyPoints[devKey] ?: []) as List).collect { it }
    currentPoints.addAll(result.inBounds)
    state.dailyPoints[devKey] = currentPoints

    // Store out-of-bounds points (for graphing only, if enabled)
    if (result.outOfBounds) {
        def currentOutOfBounds = ((state.dailyOutOfBoundsPoints[devKey] ?: []) as List).collect { it }
        currentOutOfBounds.addAll(result.outOfBounds)
        state.dailyOutOfBoundsPoints[devKey] = currentOutOfBounds
        def eligibleCount = result.inBounds.count { it.ghostEligible != false }
        debugLog("Stored ${result.inBounds.size()} in-bounds (${eligibleCount} ghost-eligible) and ${result.outOfBounds.size()} out-of-bounds points for ${dev.displayName}")
    } else {
        def eligibleCount = result.inBounds.count { it.ghostEligible != false }
        debugLog("Stored ${result.inBounds.size()} in-bounds points (${eligibleCount} ghost-eligible) for ${dev.displayName}; total=${currentPoints.size()}")
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
    def capturedAt = now()

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
    def detectionActive = isDeviceActive(dev)
    def occupancyActive = isDeviceOccupancyActive(dev)
    def devKey = deviceKey(dev?.id)

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
                z: z,
                ts: capturedAt
        ]

        // Apply bounds filtering if enabled
        if (bounds) {
            if (isPointWithinBounds(x, y, z, bounds)) {
                markPointGhostEligibility(point, devKey, detectionActive, occupancyActive)
                inBoundsPoints << point
            } else if (captureOOB) {
                // Capture out-of-bounds points for visualization
                outOfBoundsPoints << point
            }
            // else: completely discard out-of-bounds points
        } else {
            // No filtering enabled, all points are in-bounds
            markPointGhostEligibility(point, devKey, detectionActive, occupancyActive)
            inBoundsPoints << point
        }
    }

    [inBounds: inBoundsPoints, outOfBounds: outOfBoundsPoints]
}

private void markPointGhostEligibility(Map point, String devKey, boolean detectionActive, boolean occupancyActive) {
    if (!point) {
        return
    }

    if (!detectionActive) {
        point.ghostEligible = false
        point.ghostIgnoreReason = "device-inactive"
        return
    }

    if (!occupancyActive) {
        point.ghostEligible = false
        point.ghostIgnoreReason = "switch-inactive"
        return
    }

    if (isPointBlockedByRememberedInterferenceArea(devKey, point)) {
        point.ghostEligible = false
        point.ghostIgnoreReason = "interference-area"
        return
    }

    point.ghostEligible = true
    point.ghostIgnoreReason = null
}

private boolean isDeviceOccupancyActive(dev) {
    if (!dev) {
        return false
    }

    def activeDetected = false
    def explicitInactiveDetected = false

    [
            occupancy: ["occupied", "active", "present", "detected", "on", "true", "1"],
            motion: ["active", "moving", "motion", "detected", "on", "true", "1"],
            switch: ["on", "active", "true", "1"],
            presence: ["present", "on", "true", "1"]
    ].each { attr, activeValues ->
        def rawValue = null
        try {
            rawValue = dev.currentValue(attr)
        } catch (Exception ignored) {
            rawValue = null
        }

        if (rawValue == null || rawValue.toString().trim() == "") {
            return
        }

        def normalized = rawValue.toString().trim().toLowerCase()
        if (activeValues.contains(normalized)) {
            activeDetected = true
            return
        }

        if (["inactive", "inactive clear", "clear", "unoccupied", "not present", "off", "false", "0"].contains(normalized)) {
            explicitInactiveDetected = true
        }
    }

    if (activeDetected) {
        return true
    }
    if (explicitInactiveDetected) {
        return false
    }

    true
}

private boolean isPointBlockedByRememberedInterferenceArea(String devKey, Map point) {
    if (!devKey || !point) {
        return false
    }

    def pointTs = (point.ts ?: 0L) as Long
    getRememberedInterferenceAreas(devKey).any { area ->
        pointTs >= ((area.updatedAt ?: 0L) as Long) &&
                isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, area.bounds)
    }
}

def endOfDay() {
    state.dayIndex = (state.dayIndex ?: 0) + 1
    state.totalDays = Math.min((state.totalDays ?: 0) + 1, safeHistoryDays())

    def summaries = [:]
    def previousRecommendationKey = recommendationKey(state.recommendation)

    mmwaveDevices?.each { dev ->
        def devKey = deviceKey(dev.id)
        def previousStableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
        recordActiveTrackingDay(devKey, state.dayIndex as Integer, (state.deviceActiveToday[devKey] ?: false) || isDeviceActive(dev))
        def rawPoints = getPointsForDevice(dev.id)
        def points = filterPointsForGhostDetection(rawPoints, dev)
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
        state.lastPointsSnapshot[devKey] = rawPoints.collect { clonePoint(it) }
        state.lastOutOfBoundsSnapshot[devKey] = outOfBoundsPoints.collect { clonePoint(it) }
        state.lastClustersSnapshot[devKey] = clustersToday.collect { snapshotCluster(it) }
        state.correlationGhostPresence[devKey] = false

        debugLog("Processed ${dev.displayName}: points=${points.size()}, out-of-bounds=${outOfBoundsPoints.size()}, unclustered=${unclusteredPointCount}, clusters=${clustersToday.size()}, persistent=${counts.persistentGhosts}, busted=${counts.bustedGhosts}")
        sendGhostLifecycleNotifications(dev, previousStableClusters)
    }

    state.dailySummary = summaries
    refreshRecommendation(true, previousRecommendationKey)
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
        lines << "Leaking targeted ghosts: ${summary.leakingGhosts ?: 0}"
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

private List getAllConfiguredDynamicAreaTrackers() {
    def trackers = []
    getSortedMmwaveDevices().each { dev ->
        def devKey = deviceKey(dev.id)
        (0..3).each { areaIndex ->
            def config = getDynamicInterferenceAreaConfig(dev.id, areaIndex)
            def trackedDevice = findDeviceByKey(config.deviceId)
            if (config.enabled && trackedDevice && config.attribute) {
                trackers << [
                        mmwaveKey: devKey,
                        areaIndex: areaIndex,
                        device: trackedDevice,
                        deviceKey: deviceKey(trackedDevice.id),
                        deviceName: trackedDevice.displayName,
                        attribute: config.attribute
                ]
            }
        }
    }
    trackers.unique { tracker -> "${tracker.mmwaveKey}:${tracker.areaIndex}:${tracker.deviceKey}:${tracker.attribute}" }
}

private Map getDynamicActivationOptions(deviceId) {
    def devKey = deviceKey(deviceId)
    def options = ["": "Off"]
    def configuredTrackers = getConfiguredCorrelationTrackersForDevice(devKey)
    configuredTrackers.each { tracker ->
        def aggregate = emptyCorrelationAggregate(tracker.deviceName, tracker.attribute)
        (((state.correlationHistory[devKey] ?: []) as List)).each { entry ->
            def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
            mergeCorrelationAggregate(aggregate, (((entry.trackers ?: [:]) as Map)[trackerKey] ?: [:]) as Map)
        }
        mergeCorrelationAggregate(aggregate, (((state.correlationDaily[devKey] ?: [:]) as Map)["${tracker.deviceKey}:${tracker.attribute}"] ?: [:]) as Map)
        def correlatedValue = getCorrelatedActivationValue(aggregate)
        if (correlatedValue != null && correlatedValue.toString() != "unknown") {
            options["${tracker.deviceKey}|${tracker.attribute}|${correlatedValue}"] = "${tracker.deviceName} / ${tracker.attribute} = ${correlatedValue}"
        }
    }
    options
}

private String getCorrelatedActivationValue(Map aggregate) {
    def values = ((aggregate?.values ?: [:]) as Map)
    if (!values) {
        return null
    }

    def rankedValues = values.collect { value, stats ->
        def total = (stats.ghostSamples ?: 0) + (stats.clearSamples ?: 0)
        def ghostPctForValue = ((stats.ghostSamples ?: 0) as Double) / Math.max(1.0d, total as Double)
        def clearPctForValue = ((stats.clearSamples ?: 0) as Double) / Math.max(1.0d, total as Double)
        [
                value: value,
                stats: stats,
                total: total,
                bias: ghostPctForValue - clearPctForValue
        ]
    }.sort { a, b -> b.bias <=> a.bias }

    def strongest = rankedValues.find { (it.total ?: 0) >= 3 }
    if (strongest && strongest.bias >= 0.55d && (strongest.stats.ghostSamples ?: 0) >= 5) {
        return strongest.value?.toString()
    }

    null
}

private String getDynamicActivationSelection(deviceId, Integer areaIndex) {
    def devKey = deviceKey(deviceId)
    def config = (((state.interferenceAreas ?: [:])[devKey] ?: [:])[areaIndex.toString()] ?: [:]) as Map
    def dynamic = config.dynamic as Map
    if (!(dynamic?.deviceId) || !(dynamic?.attribute) || dynamic?.activeValue == null) {
        return ""
    }
    "${dynamic.deviceId}|${dynamic.attribute}|${dynamic.activeValue}"
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
    def dev = mmwaveDevices?.find { deviceKey(it?.id) == deviceKey(deviceId) }
    filterPointsForGhostDetection(getPointsForDevice(deviceId), dev)
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
    def bustCounts = getPersistentBustSourceCountsForDevice(devKey)
    def activeGhosts = stableClusters.findAll { !isGhostBusted(devKey, it) && ((it.daysSeen ?: 0) > 0) }
    def leakingTargets = getLeakingTargetCountForDeviceKey(devKey)

    [
            ghostsToday: todayClusters?.size() ?: 0,
            detectedGhosts: activeGhosts.size(),
            persistentGhosts: activeGhosts.count { isClusterPersistent(it) },
            targetedGhosts: activeGhosts.count { isGhostTargeted(devKey, it) },
            leakingGhosts: leakingTargets,
            bustedGhosts: stableClusters.count { isGhostBusted(devKey, it) },
            autoBusted: bustCounts.autoBusted,
            manualBusted: bustCounts.manualBusted,
            unbusted: bustCounts.unbusted
    ]
}

def generateRecommendation() {
    def totalDays = state.totalDays ?: 0
    if (totalDays <= 0) {
        state.recommendation = [message: "Insufficient data to generate a recommendation."]
        return state.recommendation
    }

    def best = null
    def bestScore = -1.0d

    def blockedByCapacity = false

    (state.stabilityData ?: [:]).each { devKey, clusters ->
        def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
        (clusters ?: []).each { cluster ->
            def stabilityPct = calculateStabilityPercent(cluster)
            def plan = buildRecommendationPlan(dev, cluster, stabilityPct)
            if (plan?.blockedByCapacity) {
                blockedByCapacity = true
            }
            if (plan && !plan.blockedByCapacity && stabilityPct >= safeStableThreshold() && stabilityPct > bestScore) {
                best = [
                        deviceId: devKey,
                        center: clonePoint(cluster.center),
                        bounds: cloneBounds(plan.bounds),
                        radius: cluster.radius,
                        density: cluster.density,
                        stabilityPct: Math.min(100.0d, stabilityPct as Double),
                        action: plan.action,
                        areaIndex: plan.areaIndex
                ]
                bestScore = stabilityPct
            }
        }
    }

    if (!best) {
        state.recommendation = [message: blockedByCapacity ? "Recommendation available for a persistent ghost, but there is no free interference area and no overlapping area that can be expanded automatically." : "No recommendation available right now because no persistent ghost currently qualifies for targeting."]
        return state.recommendation
    }

    def dev = mmwaveDevices?.find { deviceKey(it.id) == best.deviceId }
    best.deviceName = dev?.displayName ?: best.deviceId
    state.recommendation = best
    best
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

    def clampedBounds = clampBoundsToDevice(selectedCluster.bounds, dev)
    if (!isValidBounds(clampedBounds)) {
        warnLog("Selected ghost bounds are outside the device boundary for ${dev.displayName}.")
        return
    }

    def zoneSpec = [cluster: selectedCluster, bounds: cloneBounds(clampedBounds), areaIndex: areaIndex]
    def appliedZoneSpecs = applyZonesToDevice(dev, [zoneSpec], "manual cluster selection")
    rememberAppliedZones(devKey, appliedZoneSpecs, "manual")
    updateClusterBustMode(dev.id, [selectedCluster], appliedZoneSpecs, "manual")
    syncClusterTargetsForDevice(devKey)
}

private void applySelectedZoneForArea(String devKey, Integer areaIndex) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
    if (!dev || areaIndex == null) {
        return
    }

    def selectedIndex = settings["targetCluster_${devKey}_${areaIndex}"]
    if (selectedIndex == null || selectedIndex == "") {
        warnLog("Choose a ghost before assigning interference area ${areaIndex} for ${dev.displayName}.")
        return
    }

    def clusters = getSelectableClusters(dev.id)
    def selectedCluster = clusters[(selectedIndex as Integer)]
    if (!selectedCluster?.bounds || !isValidBounds(selectedCluster.bounds)) {
        warnLog("Selected ghost is not available for area ${areaIndex} on ${dev.displayName}.")
        return
    }

    def clampedBounds = clampBoundsToDevice(selectedCluster.bounds, dev)
    if (!isValidBounds(clampedBounds)) {
        warnLog("Selected ghost bounds are outside the device boundary for ${dev.displayName}.")
        return
    }

    def zoneSpec = [cluster: selectedCluster, bounds: cloneBounds(clampedBounds), areaIndex: areaIndex]
    def appliedZoneSpecs = applyZonesToDevice(dev, [zoneSpec], "manual cluster selection")
    rememberAppliedZones(devKey, appliedZoneSpecs, "manual")
    updateClusterBustMode(dev.id, [selectedCluster], appliedZoneSpecs, "manual")
    syncClusterTargetsForDevice(devKey)
}

private void applyAllSelectedZones(String devKey) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
    if (!dev) {
        return
    }

    def selectableClusters = getSelectableClusters(dev.id)
    def zoneSpecs = []
    def sourceClusters = []

    (0..3).each { areaIndex ->
        def selectedIndex = settings["targetCluster_${devKey}_${areaIndex}"]
        if (selectedIndex == null || selectedIndex == "") {
            return
        }

        if (selectedIndex == "__none__") {
            clearRememberedInterferenceArea(devKey, areaIndex, true)
            applyDynamicInterferenceAreaState(dev, areaIndex, [xmin: 0, xmax: 0, ymin: 0, ymax: 0, zmin: -600, zmax: 600], false)
            return
        }

        def selectedCluster = selectableClusters[(selectedIndex as Integer)]
        if (!selectedCluster?.bounds || !isValidBounds(selectedCluster.bounds)) {
            return
        }

        def clampedBounds = clampBoundsToDevice(selectedCluster.bounds, dev)
        if (!isValidBounds(clampedBounds)) {
            return
        }

        zoneSpecs << [cluster: selectedCluster, bounds: cloneBounds(clampedBounds), areaIndex: areaIndex]
        sourceClusters << selectedCluster
    }

    if (!zoneSpecs) {
        warnLog("Choose at least one ghost assignment before applying interference areas for ${dev.displayName}.")
        return
    }

    def appliedZoneSpecs = applyZonesToDevice(dev, zoneSpecs, "manual cluster selection")
    rememberAppliedZones(devKey, appliedZoneSpecs, "manual")
    updateClusterBustMode(dev.id, sourceClusters, appliedZoneSpecs, "manual")
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
    def deviceBounds = clampReferenceBounds(dev)

    (zoneSpecs ?: []).each { zoneSpec ->
        def zoneBounds = clampBoundsToReference(zoneSpec.bounds, deviceBounds)
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

private Map buildRecommendationPlan(dev, Map cluster, BigDecimal stabilityPct) {
    if (!dev || !cluster?.bounds) {
        return null
    }
    if ((recommendOnlyPersistentGhosts == null ? true : recommendOnlyPersistentGhosts) && !isClusterPersistent(cluster)) {
        return null
    }

    def devKey = deviceKey(dev.id)
    def deviceBounds = clampReferenceBounds(dev)
    def clampedClusterBounds = clampBoundsToReference(cluster.bounds, deviceBounds)
    if (!isValidBounds(clampedClusterBounds)) {
        return null
    }

    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    def containingArea = rememberedAreas.find { area ->
        isBoundsWithinBounds(clampedClusterBounds, area.bounds)
    }
    if (containingArea) {
        return null
    }

    def overlappingArea = rememberedAreas.find { area ->
        boundsOverlap(clampedClusterBounds, area.bounds)
    }
    if (overlappingArea) {
        def expandedBounds = clampBoundsToReference(unionBounds(overlappingArea.bounds, clampedClusterBounds), deviceBounds)
        if (!isValidBounds(expandedBounds) || sameBounds(expandedBounds, overlappingArea.bounds)) {
            return null
        }
        return [
                action: "expand",
                areaIndex: overlappingArea.areaIndex,
                bounds: expandedBounds,
                stabilityPct: stabilityPct
        ]
    }

    def availableIndexes = (0..3).findAll { idx ->
        !rememberedAreas.any { it.areaIndex == idx }
    }
    if (!availableIndexes) {
        return [blockedByCapacity: true]
    }

    [
            action: "set",
            areaIndex: availableIndexes[0],
            bounds: clampedClusterBounds,
            stabilityPct: stabilityPct
    ]
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
                updatedAt: area.updatedAt ?: 0L,
                dynamic: cloneDynamicAreaConfig(area.dynamic as Map),
                dynamicActive: area.dynamicActive == true
        ]
    }.sort { a, b -> (a.areaIndex as Integer) <=> (b.areaIndex as Integer) }
}

private void rememberInterferenceArea(String devKey, Integer areaIndex, Map bounds, String source, Map dynamicConfig = null) {
    if (!devKey || areaIndex == null || !isValidBounds(bounds)) {
        return
    }

    def current = (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): [
                bounds: cloneBounds(value.bounds),
                source: value.source,
                updatedAt: value.updatedAt,
                dynamic: cloneDynamicAreaConfig(value.dynamic as Map),
                dynamicActive: value.dynamicActive == true
        ]]
    }
    current[areaIndex.toString()] = [
            bounds: cloneBounds(bounds),
            source: source,
            updatedAt: now(),
            dynamic: cloneDynamicAreaConfig(dynamicConfig ?: current[areaIndex.toString()]?.dynamic as Map),
            dynamicActive: current[areaIndex.toString()]?.dynamicActive == true
    ]
    state.interferenceAreas[devKey] = current
}

private void rememberAppliedZones(String devKey, List appliedZoneSpecs, String source) {
    (appliedZoneSpecs ?: []).each { zoneSpec ->
        rememberInterferenceArea(
                devKey,
                zoneSpec.areaIndex as Integer,
                zoneSpec.bounds as Map,
                source,
                getDynamicInterferenceAreaConfig(devKey, zoneSpec.areaIndex as Integer)
        )
    }
}

private List getAutoAssignableIndexes(String devKey, Integer neededCount) {
    def remembered = getRememberedInterferenceAreas(devKey)
    def reserved = remembered.findAll { it.source != "auto" }.collect { it.areaIndex as Integer }
    def existingAuto = remembered.findAll { it.source == "auto" }.collect { it.areaIndex as Integer }.sort()
    def free = (0..3).findAll { !reserved.contains(it) && !existingAuto.contains(it) }
    (existingAuto + free).take(Math.max(0, neededCount ?: 0))
}

private Map getManualInterferenceAreaInput(String devKey, Integer areaIndex) {
    [
            areaIndex: areaIndex,
            bounds: [
                    xmin: toDouble(settings["manualAreaXMin_${devKey}_${areaIndex}"]),
                    xmax: toDouble(settings["manualAreaXMax_${devKey}_${areaIndex}"]),
                    ymin: toDouble(settings["manualAreaYMin_${devKey}_${areaIndex}"]),
                    ymax: toDouble(settings["manualAreaYMax_${devKey}_${areaIndex}"]),
                    zmin: toDouble(settings["manualAreaZMin_${devKey}_${areaIndex}"]),
                    zmax: toDouble(settings["manualAreaZMax_${devKey}_${areaIndex}"])
            ],
            dynamic: getDynamicInterferenceAreaConfig(devKey, areaIndex)
    ]
}

private void saveManualInterferenceArea(String devKey, Integer areaIndex) {
    def input = getManualInterferenceAreaInput(devKey, areaIndex)
    if (input.areaIndex == null || !isValidBounds(input.bounds as Map)) {
        warnLog("Enter valid bounds before saving interference area ${areaIndex} for ${devKey}.")
        return
    }

    rememberInterferenceArea(devKey, input.areaIndex as Integer, input.bounds as Map, "manual-entry", input.dynamic as Map)
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

private Map getDynamicInterferenceAreaConfig(deviceId, Integer areaIndex) {
    def devKey = deviceKey(deviceId)
    def selection = settings["dynamicActivation_${devKey}_${areaIndex}"]?.toString()
    def parts = selection ? selection.split("\\|", 3) : []
    [
            enabled: parts.size() == 3,
            deviceId: parts.size() == 3 ? parts[0] : null,
            deviceName: findDeviceByKey(parts.size() == 3 ? parts[0] : null)?.displayName,
            attribute: parts.size() == 3 ? parts[1] : null,
            activeValue: parts.size() == 3 ? parts[2] : null,
            currentActive: false
    ]
}

private Map cloneDynamicAreaConfig(Map config) {
    if (!config) {
        return null
    }
    [
            enabled: config.enabled == true,
            deviceId: config.deviceId,
            deviceName: config.deviceName,
            attribute: config.attribute,
            activeValue: config.activeValue,
            currentActive: config.currentActive == true
    ]
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

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def filteredPoints = filterPointsForGhostDetection(allPoints, dev)
    if (filterPointsByDeviceBounds) {
        infoLog("Reclustering ${filteredPoints.size()} ghost-eligible/problem in-bounds points for device ${devKey} (from ${allPoints.size()} total)")
    } else {
        infoLog("Reclustering ${filteredPoints.size()} ghost-eligible/problem points for device ${devKey}")
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
    def persistentClusters = stableClusters.findAll { isClusterPersistent(it) && !isGhostTargeted(devKey, it) && !isGhostBusted(devKey, it) }
    def targetedClusters = stableClusters.findAll { isGhostTargeted(devKey, it) && !isGhostBusted(devKey, it) }
    def bustedClusters = stableClusters.findAll { isGhostBusted(devKey, it) }
    def detectedClusters = stableClusters.findAll { !isClusterPersistent(it) && !isGhostTargeted(devKey, it) && !isGhostBusted(devKey, it) }
    def bustCounts = getPersistentBustSourceCountsForDevice(devKey)

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

private Map getGhostSummaryStats(deviceId, String summaryView) {
    def leakPoints = getLeakingPointCount(deviceId)
    if (summaryView == "Last Processed Day") {
        def summary = getSummaryForDevice(deviceId)
        return [
                "Detected": summary.detectedGhosts ?: 0,
                "Persistent": summary.persistentGhosts ?: 0,
                "Targeted": "${summary.targetedGhosts ?: 0} (${summary.autoBusted ?: 0} auto)",
                "Leaking": "${summary.leakingGhosts ?: 0} ghost / ${leakPoints} pt",
                "Busted": summary.bustedGhosts ?: 0
        ]
    }

    def counts = getDisplayCounts(deviceId)
    [
            "Detected": counts.detectedGhosts ?: 0,
            "Persistent": counts.persistentGhosts ?: 0,
            "Targeted": "${counts.targetedGhosts ?: 0} (${counts.autoBusted ?: 0} auto)",
            "Leaking": "${counts.leakingGhosts ?: 0} ghost / ${leakPoints} pt",
            "Busted": counts.bustedGhosts ?: 0
    ]
}

private Map getTrackingStats(deviceId, Map displayCounts, Map lastSummary) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    def outOfBounds = lastSummary.outOfBoundsPointCount ?: 0
    def leaking = displayCounts.leakingGhosts ?: 0
    def leakPoints = getLeakingPointCount(deviceId)
    [
            "Detection": getDeviceStatus(dev),
            "Tracking days": getActiveTrackingDaysForDevice(deviceId),
            "Points processed": "${lastSummary.pointCount ?: 0} (${outOfBounds} OOB)",
            "Leak alerts": (leaking > 0 || leakPoints > 0) ? "${leaking} ghost / ${leakPoints} pt" : "None"
    ]
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

private Integer getLeakingPointCount(deviceId) {
    def displayData = getDisplayData(deviceId)
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    ((pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List).size()
}

private Integer getLeakingTargetCountForDeviceKey(String devKey) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
    if (!dev) {
        return 0
    }

    def displayData = getDisplayData(dev.id)
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    def leakingPoints = (pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List
    if (!leakingPoints) {
        return 0
    }

    getRememberedInterferenceAreas(devKey).findAll { area ->
        leakingPoints.any { point ->
            isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, area.bounds)
        }
    }.size()
}

private Map getSummaryForDevice(deviceId) {
    def summary = state.dailySummary?.get(deviceKey(deviceId)) ?: [
            ghostsToday: 0,
            detectedGhosts: 0,
            persistentGhosts: 0,
            targetedGhosts: 0,
            leakingGhosts: 0,
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
    def summary = getSummaryForDevice(deviceId)
    def currentCounts = getGhostCounts(devKey, liveClusters)
    currentCounts.ghostsToday = (getPointsForDevice(deviceId) || liveClusters) ? (liveClusters?.size() ?: 0) : (summary.ghostsToday ?: 0)
    currentCounts
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

private Map buildSelectableClusterOptions(List selectableClusters) {
    def options = ["__none__": "None"]
    options + (selectableClusters ?: []).collectEntries { cluster ->
        def idx = selectableClusters.indexOf(cluster)
        [(idx.toString()): describeClusterOption(cluster, idx)]
    }
}

private String getAssignedClusterOption(deviceId, Integer areaIndex, List selectableClusters) {
    def devKey = deviceKey(deviceId)
    def rememberedArea = getRememberedInterferenceAreas(devKey).find { (it.areaIndex as Integer) == areaIndex }
    if (!rememberedArea?.bounds) {
        return "__none__"
    }

    def matchedCluster = (selectableClusters ?: []).find { cluster ->
        boundsOverlap(cluster.bounds, rememberedArea.bounds)
    }
    matchedCluster != null ? selectableClusters.indexOf(matchedCluster).toString() : "__none__"
}

private String buildAreaSelectorTitle(deviceId, Integer areaIndex, List selectableClusters) {
    "Area ${areaIndex}: Ghost to target"
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

private Map unionBounds(Map a, Map b) {
    if (!isValidBounds(a) || !isValidBounds(b)) {
        return null
    }
    [
            xmin: Math.min(a.xmin ?: 0.0d, b.xmin ?: 0.0d),
            xmax: Math.max(a.xmax ?: 0.0d, b.xmax ?: 0.0d),
            ymin: Math.min(a.ymin ?: 0.0d, b.ymin ?: 0.0d),
            ymax: Math.max(a.ymax ?: 0.0d, b.ymax ?: 0.0d),
            zmin: Math.min(a.zmin ?: 0.0d, b.zmin ?: 0.0d),
            zmax: Math.max(a.zmax ?: 0.0d, b.zmax ?: 0.0d)
    ]
}

private Map clampReferenceBounds(dev) {
    def bounds = dev ? getDeviceBounds(dev) : null
    isValidBounds(bounds) ? bounds : null
}

private Map clampBoundsToDevice(Map bounds, dev) {
    clampBoundsToReference(bounds, clampReferenceBounds(dev))
}

private Map clampBoundsToReference(Map bounds, Map referenceBounds) {
    if (!isValidBounds(bounds)) {
        return null
    }
    if (!isValidBounds(referenceBounds)) {
        return cloneBounds(bounds)
    }
    intersectBounds(bounds, referenceBounds)
}

private boolean sameBounds(Map a, Map b) {
    if (!isValidBounds(a) || !isValidBounds(b)) {
        return false
    }
    def left = integerBounds(a)
    def right = integerBounds(b)
    left.xmin == right.xmin &&
            left.xmax == right.xmax &&
            left.ymin == right.ymin &&
            left.ymax == right.ymax &&
            left.zmin == right.zmin &&
            left.zmax == right.zmax
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
        def counts = getPersistentBustSourceCountsForDevice(deviceKey(dev.id))
        totals.autoBusted += counts.autoBusted
        totals.manualBusted += counts.manualBusted
        totals.unbusted += counts.unbusted
    }
    totals
}

private Map getPersistentBustSourceCountsForDevice(String devKey) {
    def stableClusters = (state.stabilityData[devKey] ?: []) as List
    def persistentClusters = (stableClusters ?: []).findAll { isClusterPersistent(it) || isGhostTargeted(devKey, it) || isGhostBusted(devKey, it) }
    [
            autoBusted: persistentClusters.count { ghost ->
                def rememberedArea = getRememberedAreaForGhost(devKey, ghost)
                def source = rememberedArea?.source ?: ghost.targetSource ?: ghost.bustMode
                source == "auto"
            },
            manualBusted: persistentClusters.count { ghost ->
                def rememberedArea = getRememberedAreaForGhost(devKey, ghost)
                def source = rememberedArea?.source ?: ghost.targetSource ?: ghost.bustMode
                source in ["manual", "manual-entry"]
            },
            unbusted: persistentClusters.count { ghost ->
                isClusterPersistent(ghost) && !isGhostTargeted(devKey, ghost) && !isGhostBusted(devKey, ghost)
            }
    ]
}

private BigDecimal calculateStabilityPercent(Map cluster) {
    def activeDays = getActiveTrackingDaysForCluster(cluster)
    if (activeDays <= 0) {
        return 0.0d
    }

    def seenDays = Math.min((cluster.daysSeen ?: 0) as Integer, activeDays)
    return (((seenDays ?: 0) as BigDecimal) / activeDays) * 100.0d
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
    cluster?.targetAreaIndex != null
}

private boolean isClusterBusted(Map cluster) {
    isClusterTargeted(cluster) && ((cluster?.absentStreak ?: 0) >= safeBustedGhostDays())
}

private Map getRememberedAreaForGhost(String devKey, Map cluster) {
    if (!devKey || !cluster?.bounds) {
        return null
    }
    getRememberedInterferenceAreas(devKey).find { area ->
        boundsOverlap(cluster.bounds, area.bounds)
    }
}

private boolean isGhostTargeted(String devKey, Map cluster) {
    isClusterTargeted(cluster) || getRememberedAreaForGhost(devKey, cluster) != null
}

private boolean isGhostBusted(String devKey, Map cluster) {
    if (!isGhostTargeted(devKey, cluster)) {
        return false
    }

    def leakActive = hasProblemInterferencePointsForGhost(devKey, cluster)
    def requiredAbsentDays = leakActive ? Integer.MAX_VALUE : safeLeakRecoveryDays()
    ((cluster?.absentStreak ?: 0) >= requiredAbsentDays)
}

private boolean hasProblemInterferencePointsForGhost(String devKey, Map cluster) {
    if (!devKey || !cluster) {
        return false
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    if (!dev || !targetArea?.bounds) {
        return false
    }

    def displayData = getDisplayData(dev.id)
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    ((pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List).any { point ->
        isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, targetArea.bounds)
    }
}

private Map plotStateStyle(Map cluster, List historicalClusters, List currentClusters = [], List rememberedAreas = [], boolean historicalOnly = false) {
    def matched = (historicalClusters ?: []).find { existing ->
        distance3D(existing.center, cluster.center) <= safeClusterRadius()
    } ?: cluster
    def matchedRememberedArea = (rememberedAreas ?: []).find { area ->
        boundsOverlap(matched.bounds ?: cluster.bounds, area.bounds)
    }

    if (isClusterBusted(matched)) {
        return [stroke: "#2e7d32", fill: "rgba(46,125,50,0.14)", dash: null]
    }
    if (isClusterTargeted(matched) || matchedRememberedArea) {
        return [stroke: "#c62828", fill: "rgba(198,40,40,0.14)", dash: null]
    }
    if (isClusterPersistent(matched)) {
        return [stroke: "#ef6c00", fill: historicalOnly ? "rgba(239,108,0,0.14)" : "none", dash: "3,2"]
    }
    return [stroke: "#1565c0", fill: historicalOnly ? "rgba(21,101,192,0.14)" : "none", dash: "3,2"]
}

private Map classifyPlotPoints(List points, List outOfBoundsPoints, dev) {
    def deviceBounds = dev ? getDeviceBounds(dev) : null
    def validDeviceBounds = isValidBounds(deviceBounds)
    def rememberedAreas = dev ? getRememberedInterferenceAreas(deviceKey(dev.id)) : []
    def occupancyAssociationWindowMs = getOccupancyAssociationWindowMs(dev)

    def inBoundsPoints = []
    def ignoredPoints = []
    def occupancyAssociatedInterferencePoints = []

    def allDisplayPoints = []
    allDisplayPoints.addAll(points ?: [])
    allDisplayPoints.addAll(outOfBoundsPoints ?: [])

    allDisplayPoints.each { point ->
        if (shouldPlotInterferenceAreaPointAsRed(point, allDisplayPoints, rememberedAreas, deviceBounds, validDeviceBounds, occupancyAssociationWindowMs)) {
            occupancyAssociatedInterferencePoints << point
            return
        }

        if (point?.ghostEligible == false) {
            ignoredPoints << point
            return
        }

        if (validDeviceBounds) {
            if (isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, deviceBounds)) {
                inBoundsPoints << point
            } else {
                ignoredPoints << point
            }
        } else if ((outOfBoundsPoints ?: []).contains(point)) {
            ignoredPoints << point
        } else {
            inBoundsPoints << point
        }
    }

    [
            inBoundsPoints: inBoundsPoints,
            ignoredPoints: ignoredPoints,
            occupancyAssociatedInterferencePoints: occupancyAssociatedInterferencePoints,
            deviceBounds: deviceBounds,
            validDeviceBounds: validDeviceBounds,
            rememberedAreas: rememberedAreas
    ]
}

private boolean shouldPlotInterferenceAreaPointAsRed(Map point, List allDisplayPoints, List rememberedAreas, Map deviceBounds, boolean validDeviceBounds, Long windowMs) {
    if (!isPointInsideActiveRememberedInterferenceArea(point, rememberedAreas)) {
        return false
    }

    if (point?.ghostIgnoreReason in ["switch-inactive", "device-inactive"]) {
        return false
    }

    if (point?.ghostIgnoreReason != "interference-area") {
        return false
    }

    !hasCompetingNonInterferenceInBoundsPoint(point, allDisplayPoints, rememberedAreas, deviceBounds, validDeviceBounds, windowMs)
}

private boolean hasCompetingNonInterferenceInBoundsPoint(Map point, List allDisplayPoints, List rememberedAreas, Map deviceBounds, boolean validDeviceBounds, Long windowMs) {
    if (!point || !allDisplayPoints) {
        return false
    }

    def pointTs = point.ts instanceof Number ? (point.ts as Long) : null
    if (pointTs == null) {
        return false
    }

    allDisplayPoints.any { candidate ->
        if (!candidate || candidate.is(point)) {
            return false
        }

        def candidateTs = candidate.ts instanceof Number ? (candidate.ts as Long) : null
        if (candidateTs == null || Math.abs(candidateTs - pointTs) > windowMs) {
            return false
        }

        if (isPointInsideActiveRememberedInterferenceArea(candidate, rememberedAreas)) {
            return false
        }

        if (validDeviceBounds && !isPointWithinBounds(candidate.x as Double, candidate.y as Double, candidate.z as Double, deviceBounds)) {
            return false
        }

        true
    }
}

private boolean isPointInsideActiveRememberedInterferenceArea(Map point, List rememberedAreas) {
    if (!point || !rememberedAreas) {
        return false
    }

    def pointTs = point.ts instanceof Number ? (point.ts as Long) : null
    if (pointTs == null) {
        return false
    }

    rememberedAreas.any { area ->
        pointTs >= ((area.updatedAt ?: 0L) as Long) &&
                isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, area.bounds)
    }
}

private Long getOccupancyAssociationWindowMs(dev) {
    if (!dev) {
        return 0L
    }

    try {
        def stayLifeSeconds = firstNonNullDouble(toDouble(dev.currentValue("parameter108")), toDouble(dev.getSetting("parameter108"))) ?: 0.0d
        def detectionTimeoutSeconds = firstNonNullDouble(toDouble(dev.currentValue("parameter114")), toDouble(dev.getSetting("parameter114"))) ?: 0.0d

        // Use the larger timer as the occupancy association window for plot-only coloring.
        def windowSeconds = Math.max(stayLifeSeconds, detectionTimeoutSeconds)
        return Math.max(0L, Math.round(windowSeconds * 1000.0d))
    } catch (Exception ex) {
        debugLog("Unable to retrieve occupancy association window for ${dev.displayName}: ${ex.message}")
        return 0L
    }
}

private Map calculatePlotScale(List points, List outOfBoundsPoints, List currentClusters, List historicalClusters, dev = null, String horizontalAxis = "x", String verticalAxis = "y", Map preferredScale = null) {
    def pointBuckets = classifyPlotPoints(points, outOfBoundsPoints, dev)
    def deviceBounds = pointBuckets.deviceBounds
    def validDeviceBounds = pointBuckets.validDeviceBounds
    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"

    if (!points && !outOfBoundsPoints && !currentClusters && !historicalClusters && !validDeviceBounds) {
        return null
    }

    def displayInBoundsPoints = pointBuckets.inBoundsPoints
    def ignoredPoints = pointBuckets.ignoredPoints
    def occupancyAssociatedInterferencePoints = pointBuckets.occupancyAssociatedInterferencePoints

    def allPointsForScale = []
    allPointsForScale.addAll(displayInBoundsPoints.collect { [(horizontalAxis): it[horizontalAxis], (verticalAxis): it[verticalAxis]] })
    allPointsForScale.addAll(ignoredPoints.collect { [(horizontalAxis): it[horizontalAxis], (verticalAxis): it[verticalAxis]] })
    allPointsForScale.addAll(occupancyAssociatedInterferencePoints.collect { [(horizontalAxis): it[horizontalAxis], (verticalAxis): it[verticalAxis]] })

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

    def pointBuckets = classifyPlotPoints(points, outOfBoundsPoints, dev)
    def deviceBounds = pointBuckets.deviceBounds
    def validDeviceBounds = pointBuckets.validDeviceBounds
    def displayInBoundsPoints = pointBuckets.inBoundsPoints
    def ignoredPoints = pointBuckets.ignoredPoints
    def occupancyAssociatedInterferencePoints = pointBuckets.occupancyAssociatedInterferencePoints
    def rememberedAreas = pointBuckets.rememberedAreas ?: []
    def legendItems = ["<tspan fill='#ff9800'>Orange</tspan><tspan fill='#333333'> = in-bounds</tspan>"]
    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"
    def annotationAxis = ["x", "y", "z"].find { it != horizontalAxis && it != verticalAxis }

    if (ignoredPoints) {
        legendItems << "<tspan fill='#888888'>Gray</tspan><tspan fill='#333333'> = ignored</tspan>"
    }
    if (occupancyAssociatedInterferencePoints) {
        legendItems << "<tspan fill='#d32f2f'>Red</tspan><tspan fill='#333333'> = leaking</tspan>"
    }
    if (validDeviceBounds) {
        legendItems << "<tspan fill='#888888'>Gray box</tspan><tspan fill='#333333'> = device bounds</tspan>"
    }
    legendItems << "<tspan fill='#1565c0'>Blue box</tspan><tspan fill='#333333'> = detected ghost</tspan>"
    legendItems << "<tspan fill='#ef6c00'>Orange box</tspan><tspan fill='#333333'> = persistent ghost</tspan>"
    legendItems << "<tspan fill='#c62828'>Red box</tspan><tspan fill='#333333'> = targeted ghost</tspan>"
    legendItems << "<tspan fill='#2e7d32'>Green box</tspan><tspan fill='#333333'> = busted ghost</tspan>"
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
    svg << "<svg class='gt-plot-svg' viewBox='0 0 ${width} ${height}' preserveAspectRatio='xMidYMid meet' style='border:1px solid #d0d0d0;background:#fafafa'>"
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

    // Draw current ghost boxes plus unmatched historical ghosts behind points.
    def drawClusters = []
    drawClusters.addAll((currentClusters ?: []).collect { [cluster: it, historicalOnly: false] })
    (historicalClusters ?: []).each { historicalCluster ->
        def hasCurrentMatch = (currentClusters ?: []).any { currentCluster ->
            distance3D(currentCluster.center, historicalCluster.center) <= safeClusterRadius()
        }
        if (!hasCurrentMatch) {
            drawClusters << [cluster: historicalCluster, historicalOnly: true]
        }
    }

    drawClusters.each { entry ->
        def cluster = entry.cluster as Map
        if (cluster.bounds) {
            def x1 = normalizeX(cluster.bounds[hMinField])
            def x2 = normalizeX(cluster.bounds[hMaxField])
            def y1 = normalizeY(cluster.bounds[vMaxField])
            def y2 = normalizeY(cluster.bounds[vMinField])
            def style = plotStateStyle(cluster, historicalClusters, currentClusters, rememberedAreas, entry.historicalOnly as boolean)
            def dashAttr = style.dash ? " stroke-dasharray='${style.dash}'" : ""
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='${style.fill}' stroke='${style.stroke}' stroke-width='1.6'${dashAttr} />"
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

    // Draw all points, using gray for any point ignored by occupancy/ghost detection.
    ignoredPoints.each { point ->
        svg << "<circle cx='${normalizeX(point[horizontalAxis])}' cy='${normalizeY(point[verticalAxis])}' r='2' fill='#888888' />"
    }

    displayInBoundsPoints.each { point ->
        svg << "<circle cx='${normalizeX(point[horizontalAxis])}' cy='${normalizeY(point[verticalAxis])}' r='2' fill='#ff9800' />"
    }

    occupancyAssociatedInterferencePoints.each { point ->
        svg << "<circle cx='${normalizeX(point[horizontalAxis])}' cy='${normalizeY(point[verticalAxis])}' r='3' fill='#d32f2f' stroke='#7f1d1d' stroke-width='0.5' />"
    }

    // Draw current cluster centers (red dots)
    (currentClusters ?: []).each { cluster ->
        def centerX = normalizeX(cluster.center[horizontalAxis])
        def centerY = normalizeY(cluster.center[verticalAxis])
        svg << "<circle cx='${centerX}' cy='${centerY}' r='4' fill='#7f1d1d' />"
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

        def assessment = assessCorrelation(aggregate)

        def body = new StringBuilder()
        body << "<div style='margin-bottom:10px;'>"
        body << "<div style='font-weight:bold; color:#333333; margin-bottom:8px;'>${tracker.deviceName} / ${tracker.attribute}</div>"
        body << "<div style='border:1px solid ${assessment.border}; background:${assessment.background}; padding:10px; margin-bottom:8px;'>"
        body << "<div style='font-weight:bold; color:${assessment.color}; font-size:15px; margin-bottom:4px;'>${assessment.headline}</div>"
        body << "<div style='color:#444444;'>${assessment.summary}</div>"
        body << "</div>"
        body << "<div style='color:#555555; margin-bottom:4px;'>Ghost-present samples: ${aggregate.ghostSamples ?: 0} / ${aggregate.samples ?: 0} (${percent(aggregate.ghostSamples, aggregate.samples)})</div>"
        body << "<div style='color:#555555; margin-bottom:4px;'>Ghost appearance events within ${coincidenceWindow}s after an attribute change: ${aggregate.ghostAppearancesNearAnyChange ?: 0} / ${aggregate.ghostAppearances ?: 0} (${percent(aggregate.ghostAppearancesNearAnyChange, aggregate.ghostAppearances)})</div>"
        if (aggregate.lastValue != null) {
            body << "<div style='color:#555555; margin-bottom:4px;'>Latest sampled value: ${aggregate.lastValue}</div>"
        }
        if (assessment.bestValueLine) {
            body << "<div style='color:#555555; margin-bottom:4px;'>Strongest value pattern: ${assessment.bestValueLine}</div>"
        }
        if (assessment.changeLine) {
            body << "<div style='color:#555555;'>${assessment.changeLine}</div>"
        }
        body << "</div>"
        cards << "<div style='border-left:4px solid ${assessment.border}; background:#ffffff; padding:10px; margin:4px 0;'>${body}</div>"
    }

    cards.toString()
}

private Map assessCorrelation(Map aggregate) {
    def totalSamples = (aggregate.samples ?: 0) as Integer
    def ghostSamples = (aggregate.ghostSamples ?: 0) as Integer
    def clearSamples = (aggregate.clearSamples ?: 0) as Integer
    def appearanceCount = (aggregate.ghostAppearances ?: 0) as Integer
    def nearChangeCount = (aggregate.ghostAppearancesNearAnyChange ?: 0) as Integer
    def values = ((aggregate.values ?: [:]) as Map)

    def rankedValues = values.collect { value, stats ->
        def ghostPctForValue = ((stats.ghostSamples ?: 0) as Double) / Math.max(1.0d, ((stats.ghostSamples ?: 0) + (stats.clearSamples ?: 0)) as Double)
        def clearPctForValue = ((stats.clearSamples ?: 0) as Double) / Math.max(1.0d, ((stats.ghostSamples ?: 0) + (stats.clearSamples ?: 0)) as Double)
        [
                value: value,
                stats: stats,
                total: (stats.ghostSamples ?: 0) + (stats.clearSamples ?: 0),
                ghostPctForValue: ghostPctForValue,
                clearPctForValue: clearPctForValue,
                bias: ghostPctForValue - clearPctForValue
        ]
    }.sort { a, b -> b.bias <=> a.bias }

    def strongest = rankedValues.find { (it.total ?: 0) >= 3 }
    def changePct = appearanceCount > 0 ? (nearChangeCount as Double) / appearanceCount.toDouble() : 0.0d

    def headline = "No clear correlation signal"
    def color = "#424242"
    def border = "#cfd8dc"
    def background = "#f8fafc"
    def summary = "So far, ghost activity does not line up clearly with this device or attribute."

    if (strongest && strongest.bias >= 0.55d && (strongest.stats.ghostSamples ?: 0) >= 5) {
        headline = "Possible correlation with value '${strongest.value}'"
        color = "#c62828"
        border = "#ef9a9a"
        background = "#ffebee"
        summary = "Ghosts are concentrated when this attribute is '${strongest.value}', and much less common in the other observed values."
    } else if (changePct >= 0.5d && appearanceCount >= 4) {
        headline = "Possible correlation with recent changes"
        color = "#ef6c00"
        border = "#ffcc80"
        background = "#fff3e0"
        summary = "Ghost appearances often happen shortly after this attribute changes."
    } else if (ghostSamples >= 10 && clearSamples >= 10 && rankedValues.size() > 1) {
        headline = "Weak correlation signal"
        color = "#ef6c00"
        border = "#ffcc80"
        background = "#fff8e1"
        summary = "There is some separation by value, but not enough yet to call it a strong correlation."
    }

    def bestValueLine = strongest ?
            "${strongest.value}: ${percent(strongest.stats.ghostSamples, strongest.total)} of samples at this value were ghost-present" :
            null
    def changeLine = appearanceCount > 0 ?
            "Change signal: ${nearChangeCount} of ${appearanceCount} ghost appearance(s) happened within the coincidence window." :
            null

    [
            headline: headline,
            color: color,
            border: border,
            background: background,
            summary: summary,
            bestValueLine: bestValueLine,
            changeLine: changeLine
    ]
}

private String renderInterferenceAreasCard(deviceId) {
    def devKey = deviceKey(deviceId)
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    if (!rememberedAreas) {
        return renderNoteCard("Remembered Interference Areas", "No interference areas are currently remembered for this device.")
    }

    def rows = rememberedAreas.collect { area ->
        def dynamicSummary = area.dynamic?.enabled ?
                " / dynamic: ${area.dynamic.deviceName ?: area.dynamic.deviceId} ${area.dynamic.attribute}=${area.dynamic.activeValue} (${area.dynamicActive ? 'active' : 'inactive'})" :
                ""
        "Area ${area.areaIndex}: X ${round2(area.bounds.xmin)}..${round2(area.bounds.xmax)}, Y ${round2(area.bounds.ymin)}..${round2(area.bounds.ymax)}, Z ${round2(area.bounds.zmin)}..${round2(area.bounds.zmax)} (${describeTargetSource(area.source)})${dynamicSummary}"
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
    def status = determineGhostState(devKey, cluster)
    def rememberedArea = getRememberedAreaForGhost(devKey, cluster)
    def effectiveTargetIndex = cluster.targetAreaIndex != null ? cluster.targetAreaIndex : rememberedArea?.areaIndex
    def effectiveTargetSource = cluster.targetSource ?: rememberedArea?.source

    def hasPoints = cluster.points && cluster.points.size() > 0
    def clusterKey = "${devKey}_cluster_${displayIndex}"

    def html = renderStatBlock("Ghost ${displayIndex}: ${status}", [
            "X range": "${round2(bounds.xmin)} to ${round2(bounds.xmax)} cm",
            "Y range": "${round2(bounds.ymin)} to ${round2(bounds.ymax)} cm",
            "Z range": "${round2(bounds.zmin)} to ${round2(bounds.zmax)} cm",
            "Density": cluster.density ?: 0,
            "Days in window": cluster.daysSeen ?: 0,
            "Consecutive days": cluster.consecutiveSeen ?: 0,
            "Missing streak": cluster.absentStreak ?: 0,
            "Stability": "${round2(cluster.stabilityPct ?: calculateStabilityPercent(cluster))}%",
            "Targeting": describeGhostTargeting(devKey, cluster)
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
    "Ghost ${idx + 1}: X ${round2(bounds.xmin)}..${round2(bounds.xmax)} cm, Y ${round2(bounds.ymin)}..${round2(bounds.ymax)} cm, Z ${round2(bounds.zmin)}..${round2(bounds.zmax)} cm"
}

private String describeInterferenceAreaAssignment(deviceId, Integer areaIndex) {
    def devKey = deviceKey(deviceId)
    def rememberedArea = getRememberedInterferenceAreas(devKey).find { (it.areaIndex as Integer) == areaIndex }
    if (!rememberedArea) {
        return "No remembered bounds"
    }

    def matchedGhosts = []
    getStableClusters(deviceId).findAll { ghost ->
        boundsOverlap(ghost.bounds, rememberedArea.bounds)
    }.eachWithIndex { ghost, idx ->
        matchedGhosts << "Ghost ${idx + 1} (${determineGhostState(devKey, ghost)})"
    }

    def ghostSummary = matchedGhosts ? matchedGhosts.join(", ") : "no tracked ghost overlap"
    "Area ${areaIndex}: ${ghostSummary}"
}

private String renderRecommendationSummary(def recommendation) {
    if (recommendation?.message) {
        return renderNoteCard("Recommendation", recommendation.message as String)
    }

    def bounds = recommendation.bounds ?: [:]
    renderStatBlock("Recommended Interference Area", [
            "Device": recommendation.deviceName,
            "Action": describeRecommendationAction(recommendation),
            "X bounds": "${round2(bounds.xmin)} to ${round2(bounds.xmax)} cm",
            "Y bounds": "${round2(bounds.ymin)} to ${round2(bounds.ymax)} cm",
            "Z bounds": "${round2(bounds.zmin)} to ${round2(bounds.zmax)} cm",
            "Stability": "${round2(Math.min(100.0d, (recommendation.stabilityPct ?: 0.0d) as Double))}%"
    ])
}

private String describeRecommendationAction(def recommendation) {
    if (!recommendation) {
        return "Set a new interference area"
    }
    if (recommendation.action == "expand" && recommendation.areaIndex != null) {
        return "Expand interference area ${recommendation.areaIndex}"
    }
    if (recommendation.areaIndex != null) {
        return "Set interference area ${recommendation.areaIndex}"
    }
    "Set a new interference area"
}

private void refreshRecommendation(boolean notifyIfChanged = false, String previousRecommendationKey = null) {
    def priorKey = previousRecommendationKey != null ? previousRecommendationKey : recommendationKey(state.recommendation)
    generateRecommendation()
    def newKey = recommendationKey(state.recommendation)
    if (notifyIfChanged && sendPush && notifyOnRecommendation && newKey && newKey != priorKey) {
        sendNotification(buildRecommendationNotification(state.recommendation))
    }
}

private String recommendationKey(def recommendation) {
    if (!recommendation || recommendation.message || !recommendation.deviceId || !recommendation.bounds) {
        return null
    }
    def bounds = recommendation.bounds ?: [:]
    "${recommendation.deviceId}:${round2(bounds.xmin)}:${round2(bounds.xmax)}:${round2(bounds.ymin)}:${round2(bounds.ymax)}:${round2(bounds.zmin)}:${round2(bounds.zmax)}"
}

private String buildRecommendationNotification(def recommendation) {
    if (!recommendation || recommendation.message) {
        return recommendation?.message ?: "A new ghost recommendation is available."
    }
    def bounds = recommendation.bounds ?: [:]
    """Recommendation: ${describeRecommendationAction(recommendation)} on ${recommendation.deviceName}
X ${round2(bounds.xmin)}..${round2(bounds.xmax)} cm
Y ${round2(bounds.ymin)}..${round2(bounds.ymax)} cm
Z ${round2(bounds.zmin)}..${round2(bounds.zmax)} cm
Stability ${round2(recommendation.stabilityPct ?: 0)}%"""
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

private void sendGhostLifecycleNotifications(dev, List previousStableClusters) {
    if (!sendPush || !dev) {
        return
    }

    def devKey = deviceKey(dev.id)
    def currentStableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }
    def newDetectedGhosts = countNewGhostState(previousStableClusters, currentStableClusters) { ghost ->
        !isGhostBusted(devKey, ghost) && ((ghost.daysSeen ?: 0) > 0)
    }
    def newPersistentGhosts = countNewGhostState(previousStableClusters, currentStableClusters) { ghost ->
        isClusterPersistent(ghost) && !isGhostBusted(devKey, ghost)
    }
    def newBustedGhosts = countNewGhostState(previousStableClusters, currentStableClusters, { ghost ->
        isGhostBusted(devKey, ghost)
    }, { ghost ->
        isGhostBusted(devKey, ghost)
    })
    def newTargetedPersistentGhosts = countNewGhostState(previousStableClusters, currentStableClusters) { ghost ->
        isClusterPersistent(ghost) && isGhostTargeted(devKey, ghost) && !isGhostBusted(devKey, ghost)
    }

    if (notifyOnAnyGhostDetected && newDetectedGhosts > 0) {
        sendNotification("${dev.displayName}: detected ${newDetectedGhosts} new ghost${newDetectedGhosts == 1 ? '' : 's'}.")
    }
    if (notifyOnPersistentGhostDetected && newPersistentGhosts > 0) {
        sendNotification("${dev.displayName}: detected ${newPersistentGhosts} persistent ghost${newPersistentGhosts == 1 ? '' : 's'}.")
    }
    if (notifyOnGhostBusted && newBustedGhosts > 0) {
        sendNotification("${dev.displayName}: ${newBustedGhosts} ghost${newBustedGhosts == 1 ? '' : 's'} ${newBustedGhosts == 1 ? 'was' : 'were'} busted.")
    }
    if (notifyOnTargetedPersistentGhost && newTargetedPersistentGhosts > 0) {
        sendNotification("${dev.displayName}: ${newTargetedPersistentGhosts} targeted persistent ghost${newTargetedPersistentGhosts == 1 ? ' is' : 's are'} still active, so the interference area may not be working.")
    }
}

private Integer countNewGhostState(List previousStableClusters, List currentStableClusters, Closure currentPredicate, Closure previousPredicate = null) {
    def priorPredicate = previousPredicate ?: currentPredicate
    def remainingPrevious = ((previousStableClusters ?: []).findAll { priorPredicate(it) }).collect { cloneCluster(it) }
    def count = 0
    (currentStableClusters ?: []).findAll { currentPredicate(it) }.each { currentGhost ->
        def match = findBestMatch(currentGhost, remainingPrevious)
        if (match) {
            remainingPrevious.remove(match)
        } else {
            count += 1
        }
    }
    count
}

private String describeGhostTargeting(String devKey, Map cluster) {
    def rememberedArea = getRememberedAreaForGhost(devKey, cluster)
    def effectiveTargetIndex = cluster.targetAreaIndex != null ? cluster.targetAreaIndex : rememberedArea?.areaIndex

    if (isGhostBusted(devKey, cluster)) {
        return "Area ${effectiveTargetIndex} eliminated this ghost for ${cluster.absentStreak ?: 0} active day(s)"
    }

    if (isGhostLeakProblem(devKey, cluster)) {
        return "Area ${effectiveTargetIndex} is configured, but this ghost is still being detected from interference-area leakage"
    }

    if (isGhostTargeted(devKey, cluster)) {
        return "Area ${effectiveTargetIndex} is targeting this ghost"
    }

    if (isClusterPersistent(cluster)) {
        return "Persistent but not targeted"
    }

    "Detected but not persistent"
}

private String determineGhostState(String devKey, Map cluster) {
    if (isGhostBusted(devKey, cluster)) {
        return "Busted"
    }
    if (isGhostLeakProblem(devKey, cluster)) {
        return isClusterPersistent(cluster) ? "Problem Persistent" : "Problem Detected"
    }
    if (isGhostTargeted(devKey, cluster)) {
        return "Targeted"
    }
    if (isClusterPersistent(cluster)) {
        return "Persistent"
    }
    "Detected"
}

private boolean isGhostLeakProblem(String devKey, Map cluster) {
    isGhostTargeted(devKey, cluster) &&
            !isGhostBusted(devKey, cluster) &&
            hasProblemInterferencePointsForGhost(devKey, cluster)
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
            "Detected ghosts": detected,
            "Persistent ghosts": persistent,
            "Targeted ghosts": targeted,
            "Busted ghosts": busted,
            "Last processed ghosts": ghosts,
            "Points in last run": pointsProcessed
    ]
}

private Map getMainPageHeadlineSummary() {
    def aggregateBustSources = getAggregateBustSourceCounts()
    def detected = 0
    def persistent = 0
    def targeted = 0
    def busted = 0
    def lastProcessedGhosts = 0

    mmwaveDevices?.each { dev ->
        def displayCounts = getDisplayCounts(dev.id)
        detected += displayCounts.detectedGhosts ?: 0
        persistent += displayCounts.persistentGhosts ?: 0
        targeted += displayCounts.targetedGhosts ?: 0
        busted += displayCounts.bustedGhosts ?: 0
        lastProcessedGhosts += displayCounts.ghostsToday ?: 0
    }

    [
            "Detected": detected,
            "Persistent": persistent,
            "Targeted": "${targeted} (${aggregateBustSources.autoBusted ?: 0} auto)",
            "Busted": busted,
            "Processed days": state.totalDays ?: 0,
            "Last processed ghosts": lastProcessedGhosts
    ]
}

private String renderHomeHeroCard() {
    """<div style='text-align:center;'>
<div style='display:flex; flex-direction:column; align-items:center; gap:0px;'>
<img src='https://github.com/lnjustin/App-Images/blob/master/ghostBuster/Gemini_Generated_Image_jly61ajly61ajly6.png?raw=true' alt='Ghost Buster logo' style='display:block; width:115px; max-width:100%; height:auto; border-radius:10px;' />
</div>
<div style='font-weight:bold; color:#223043; font-size:16px; line-height:1.0;'>Bust ghosts. No calls needed.</div>
</div>
</div>"""
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

private List filterPointsForGhostDetection(List points, dev) {
    def devKey = deviceKey(dev?.id)
    def eligiblePoints = (points ?: []).findAll { point ->
        point?.ghostEligible != false
    }.collect { clonePoint(it) }
    def problemInterferencePoints = getProblemInterferencePoints(points, dev)
    def combinedPoints = dedupePointsForGhostDetection(eligiblePoints + problemInterferencePoints)

    if (!filterPointsByDeviceBounds) {
        return combinedPoints
    }

    filterPointsToDeviceBounds(combinedPoints, devKey)
}

private List getProblemInterferencePoints(List points, dev) {
    if (!dev || !points) {
        return []
    }

    def deviceBounds = getDeviceBounds(dev)
    def validDeviceBounds = isValidBounds(deviceBounds)
    def rememberedAreas = getRememberedInterferenceAreas(deviceKey(dev.id))
    def occupancyAssociationWindowMs = getOccupancyAssociationWindowMs(dev)

    (points ?: []).findAll { point ->
        shouldPlotInterferenceAreaPointAsRed(point, points, rememberedAreas, deviceBounds, validDeviceBounds, occupancyAssociationWindowMs)
    }.collect { clonePoint(it) }
}

private List dedupePointsForGhostDetection(List points) {
    def seen = [] as Set
    (points ?: []).findAll { point ->
        def key = "${point?.ts}:${round2(point?.x)}:${round2(point?.y)}:${round2(point?.z)}"
        if (seen.contains(key)) {
            return false
        }
        seen << key
        true
    }
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
    [
            x: point?.x ?: 0.0d,
            y: point?.y ?: 0.0d,
            z: point?.z ?: 0.0d,
            ts: point?.ts,
            ghostEligible: point?.ghostEligible,
            ghostIgnoreReason: point?.ghostIgnoreReason
    ]
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

private def findDeviceByKey(String devKey) {
    if (!devKey) {
        return null
    }

    def correlationConfigured = getSortedMmwaveDevices().collectMany { mmwaveDev ->
        ((settings["correlationDevices_${deviceKey(mmwaveDev.id)}"] ?: []) as List)
    }.find { deviceKey(it?.id) == devKey }

    correlationConfigured ?: ((settings.notifyDevices ?: []) as List).find { deviceKey(it?.id) == devKey } ?: null
}

private void updateDynamicInterferenceAreas() {
    getSortedMmwaveDevices().each { dev ->
        def devKey = deviceKey(dev.id)
        (0..3).each { areaIndex ->
            def rememberedArea = getRememberedInterferenceAreas(devKey).find { (it.areaIndex as Integer) == areaIndex }
            if (!rememberedArea?.bounds) {
                return
            }

            def config = rememberedArea.dynamic ?: getDynamicInterferenceAreaConfig(devKey, areaIndex)
            if (!(config?.enabled) || !config.deviceId || !config.attribute) {
                return
            }

            def trackedDevice = findDeviceByKey(config.deviceId as String)
            if (!trackedDevice) {
                return
            }

            def currentValue = normalizeCorrelationValue(getTrackedCorrelationValue(trackedDevice, config.attribute as String))
            def activeValue = normalizeCorrelationValue(config.activeValue)
            def shouldBeActive = currentValue != null && activeValue != null && currentValue == activeValue
            def statusKey = "${devKey}:${areaIndex}"
            def wasActive = (state.dynamicAreaStatus ?: [:])[statusKey] == true

            if (shouldBeActive != wasActive) {
                applyDynamicInterferenceAreaState(dev, areaIndex, rememberedArea.bounds, shouldBeActive)
                state.dynamicAreaStatus[statusKey] = shouldBeActive
            }

            def current = (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map).collectEntries { key, value ->
                [(key): value]
            }
            def existing = (current[areaIndex.toString()] ?: [:]) as Map
            existing.dynamicActive = shouldBeActive
            existing.dynamic = cloneDynamicAreaConfig(config + [currentActive: shouldBeActive])
            current[areaIndex.toString()] = existing
            state.interferenceAreas[devKey] = current
        }
    }
}

private void applyDynamicInterferenceAreaState(dev, Integer areaIndex, Map bounds, boolean active) {
    if (!dev || areaIndex == null) {
        return
    }

    def appliedBounds = active ? cloneBounds(bounds) : [xmin: 0, xmax: 0, ymin: 0, ymax: 0, zmin: -600, zmax: 600]
    dev.mmWaveSetInterferenceArea(
            areaIndex,
            appliedBounds.xmin,
            appliedBounds.xmax,
            appliedBounds.ymin,
            appliedBounds.ymax,
            appliedBounds.zmin,
            appliedBounds.zmax
    )
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

private Integer safeLeakRecoveryDays() {
    Math.max(1, (leakRecoveryDays ?: bustedGhostDays ?: 2) as Integer)
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

private String renderMetricDashboard(List panels, Integer columnCount = 3) {
    def safeColumns = Math.max(1, columnCount ?: 3)
    def panelBasis = safeColumns == 1 ? "100%" : safeColumns == 2 ? "calc(50% - 6px)" : "calc(33.333% - 7px)"
    def cards = new StringBuilder()
    cards << renderResponsiveUiStyles()
    cards << "<div class='gt-dashboard'>"
    (panels ?: []).each { panel ->
        def panelHtml = panel.html != null ? renderHtmlPanel(panel.title as String, panel.html as String) : renderMetricPanel(panel.title as String, panel.stats as Map)
        cards << "<div class='gt-dashboard-panel' style='flex-basis:${panelBasis}; max-width:${panelBasis};'>${panelHtml}</div>"
    }
    cards << "</div>"
    cards.toString()
}

private String renderMetricPanel(String title, Map stats) {
    def cards = new StringBuilder()
    cards << "<div class='gt-panel'>"
    cards << "<div style='font-weight:bold; color:#333333; margin-bottom:8px;'>${title}</div>"
    cards << renderInlineMetricTiles(stats)
    cards << "</div>"
    cards.toString()
}

private String renderHtmlPanel(String title, String html) {
    "<div class='gt-panel'><div style='font-weight:bold; color:#333333; margin-bottom:8px;'>${title}</div>${html}</div>"
}

private String renderInlineMetricTiles(Map stats) {
    def cards = new StringBuilder("<div class='gt-metric-grid'>")
    (stats ?: [:]).each { label, value ->
        if (label == "_html") {
            cards << "<div class='gt-metric-full'>${value}</div>"
        } else {
            cards << "<div class='gt-metric-half'>${renderMetricTile(label?.toString(), value)}</div>"
        }
    }
    cards << "</div>"
    cards.toString()
}

private String renderMetricTile(String label, value) {
    def style = metricTileStyle(label)
    "<div style='border:1px solid ${style.border}; background:${style.background}; padding:8px 10px; min-height:54px; box-sizing:border-box;'>" +
            "<div style='color:#6b7280; font-size:10px; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:4px;'>${label}</div>" +
            "<div style='color:#111111; font-size:16px; font-weight:bold;'>${value}</div>" +
            "</div>"
}

private Map metricTileStyle(String label) {
    switch ((label ?: "").toLowerCase()) {
        case "detected":
            return [border: "#90caf9", background: "#e3f2fd"]
        case "persistent":
            return [border: "#ffcc80", background: "#fff3e0"]
        case "targeted":
            return [border: "#ef9a9a", background: "#ffebee"]
        case "busted":
            return [border: "#a5d6a7", background: "#e8f5e9"]
        case "detection":
            return [border: "#cfd8dc", background: "#f8fafc"]
        default:
            return [border: "#e1e5e8", background: "#ffffff"]
    }
}

private String renderTrackingPanel(Map stats) {
    def detectionValue = stats["Detection"]
    def trackingDays = stats["Tracking days"]
    def pointsProcessed = stats["Points processed"]
    def leakAlerts = stats["Leak alerts"]
    """<div class='gt-metric-grid'>
<div class='gt-metric-full'>${renderMetricTile("Detection", detectionValue)}</div>
<div class='gt-metric-half'>${renderMetricTile("Tracking days", trackingDays)}</div>
<div class='gt-metric-half'>${renderMetricTile("Points processed", pointsProcessed)}</div>
<div class='gt-metric-full'>${renderMetricTile("Leak alerts", leakAlerts)}</div>
</div>"""
}

private String renderActionHintCard(String title, String message) {
    "<div style='border:1px dashed #cbd5e1; background:#f8fafc; padding:10px; margin:6px 0; color:#334155;'><div style='font-weight:bold; margin-bottom:4px;'>${title}</div><div>${message}</div></div>"
}

private String renderSideBySidePlots(String leftTitle, String leftPlot, String rightTitle, String rightPlot) {
    """${renderResponsiveUiStyles()}<div class='gt-plot-grid'>
<div class='gt-plot-card'>${getInterface("subHeader", leftTitle)}${leftPlot}</div>
<div class='gt-plot-card'>${getInterface("subHeader", rightTitle)}${rightPlot}</div>
</div>"""
}

private String renderResponsiveUiStyles() {
    """<style>
.gt-dashboard{display:flex;flex-wrap:wrap;gap:12px;width:100%;box-sizing:border-box}
.gt-dashboard-panel{flex:1 1 100%;min-width:0;box-sizing:border-box}
.gt-panel{border:1px solid #d7d7d7;background:#fbfbfb;padding:10px;margin:4px 0;box-sizing:border-box;width:100%}
.gt-metric-grid{display:flex;flex-wrap:wrap;gap:8px;width:100%;box-sizing:border-box}
.gt-metric-full{flex:1 1 100%;min-width:0;box-sizing:border-box}
.gt-metric-half{flex:1 1 calc(50% - 4px);min-width:0;box-sizing:border-box}
.gt-plot-grid{display:flex;flex-wrap:wrap;gap:12px;width:100%;box-sizing:border-box}
.gt-plot-card{flex:1 1 calc(50% - 6px);min-width:0;box-sizing:border-box}
.gt-plot-svg{display:block;width:100%;height:auto;max-width:100%}
@media (max-width: 740px){
  .gt-dashboard-panel,.gt-metric-half,.gt-plot-card{flex-basis:100% !important;max-width:100% !important}
}
</style>"""
}

private String renderStatBlock(String title, Map stats) {
    def rows = new StringBuilder()
    rows << "<div style='border:1px solid #d7d7d7; background:#fbfbfb; padding:8px 10px; margin:4px 0;'>"
    rows << "<div style='font-weight:bold; color:#333333; margin-bottom:6px;'>${title}</div>"
    rows << "<table style='width:100%; border-collapse:collapse;'>"

    stats.each { label, value ->
        rows << "<tr>"
        rows << "<td style='padding:3px 0; color:#666666; width:40%;'>${label}</td>"
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
