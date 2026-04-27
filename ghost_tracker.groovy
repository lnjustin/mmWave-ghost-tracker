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
    page(name: "deviceAreaDispatchPage")
    page(name: "deviceAreaPage")
}

def mainPage() {
    try {
        refreshRecommendation()
        return dynamicPage(name: "mainPage", install: true, uninstall: true) {
            section() {
                paragraph renderHomeHeroCard()
            }
            section() {
                def landingSummary = getMainPageHeadlineSummary()
                def body = new StringBuilder()
                body << renderMetricDashboard([
                        [title: "Summary", stats: ((landingSummary ?: [:]) as Map).findAll { key, value -> key != "_html" }]
                ], 1)
                if (landingSummary._html) {
                    body << (landingSummary._html as String)
                }
                if (state.recommendation) {
                    body << renderRecommendationSummary(state.recommendation)
                }
                body << renderActionHintCard(
                        "Next Step",
                        "Open the device analysis page for per-device graphs, ghost states, and interference area controls.<div style='margin-top:8px; text-align:right;'><a href='${buildInstalledAppPageUrl("statsPage")}' style='color:#1A77C9; font-weight:bold; text-decoration:none;'>View Analysis And Interference Controls</a></div>"
                )
                paragraph renderFramedPageCard("Ghost Detection", body.toString())
            }

            section() {
                def body = new StringBuilder()
                body << renderMetricDashboard([
                        [title: "Configuration Summary", stats: getConfigurationSummaryStats()]
                ], 1)
                body << renderActionHintCard(
                        "Next Step",
                        "Adjust device selection, activation, clustering, persistence, and notifications.<div style='margin-top:8px; text-align:right;'><a href='${buildInstalledAppPageUrl("settingsPage")}' style='color:#1A77C9; font-weight:bold; text-decoration:none;'>Configure Devices And Detection</a></div>"
                )
                paragraph renderFramedPageCard("Configuration", body.toString())
            }
        }
    } catch (Throwable t) {
        logThrowable("mainPage()", t)
        throw t
    }
}

def settingsPage() {
    try {
        def shouldAutoRefresh = (state.settingsPageRefreshUntil ?: 0L) > now()
        if (!shouldAutoRefresh) {
            state.settingsPageRefreshUntil = null
        }
        return dynamicPage(name: "settingsPage", install: false, uninstall: false, refreshInterval: shouldAutoRefresh ? 1 : null) {
            section() {
                paragraph renderHomeHeroCard()
            }
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

                input "processPointsOnDetectionInactive",
                        "bool",
                        title: "Process points when detection becomes inactive",
                        defaultValue: false
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
                    input "correlationEpisodeGapSeconds",
                            "number",
                            title: "Treat ghost points separated by at least this many seconds as a new ghost episode",
                            defaultValue: 180

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
                paragraph getInterface("note", "These settings control positional clustering of ghost points in X/Y/Z space. They do not affect ghost episode grouping over time.")
                input "clusteringAlgorithm",
                        "enum",
                        title: "Primary positional clustering algorithm",
                        options: ["DBSCAN", "K-Means"],
                        defaultValue: "DBSCAN",
                        required: true

                input "clusterRadius",
                        "decimal",
                        title: "Positional cluster radius / match threshold (cm)",
                        defaultValue: 50

                input "minClusterEvents",
                        "number",
                        title: "Minimum ghost points per positional cluster",
                        defaultValue: 5

                input "pointBinSizeCm",
                        "decimal",
                        title: "Point bin size before clustering and plotting (cm)",
                        defaultValue: 1.0d

                input "maxClusters",
                        "number",
                        title: "Maximum K-Means clusters",
                        defaultValue: 5

                paragraph renderNoteCard("Point Binning", "Points are merged into X/Y/Z bins before clustering and graphing. Smaller bins preserve more shape detail; larger bins reduce load more aggressively.")
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
                        title: "Consecutive clear days before an escaping or targeted ghost becomes busted again",
                        defaultValue: 2

                input "displayGraceDays",
                        "number",
                        title: "Processed days to keep non-persistent detected ghosts visible",
                        defaultValue: 1

                input "stableThreshold",
                        "number",
                        title: "Recommendation stability threshold (%)",
                        defaultValue: 70

                input "recommendOnlyPersistentGhosts",
                        "bool",
                        title: "Only recommend interference areas for persistent ghosts",
                        defaultValue: true

                input "enableAutoExpandTargetedAreas",
                        "bool",
                        title: "Automatically expand targeted interference areas when a ghost leaks beyond them",
                        defaultValue: false,
                        submitOnChange: true

                if (enableAutoExpandTargetedAreas) {
                    input "maxLeakExpandX",
                            "decimal",
                            title: "Maximum total X expansion from the current area (cm)",
                            defaultValue: 12
                    input "maxLeakExpandY",
                            "decimal",
                            title: "Maximum total Y expansion from the current area (cm)",
                            defaultValue: 12
                    input "maxLeakExpandZ",
                            "decimal",
                            title: "Maximum total Z expansion from the current area (cm)",
                            defaultValue: 12
                }
            }

            section(getInterface("header", "Display")) {
                input "showHistoricalGhostOverlays",
                        "bool",
                        title: "Show historical ghost overlays on stats graphs",
                        defaultValue: true

                input "maxGraphPointsPerDevice",
                        "number",
                        title: "Maximum total points to graph per device",
                        defaultValue: 500
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

            section(getInterface("header", "Device Interference Areas")) {
                def sortedDevices = getSortedMmwaveDevices()
                if (!sortedDevices) {
                    paragraph "Select mmWave switches first to manage remembered interference areas."
                } else {
                    paragraph getInterface("note", "These interference areas are read directly from each device's interferenceArea0..3 attributes.")

                    sortedDevices.each { dev ->
                        paragraph getInterface("subHeader", dev.displayName)
                        paragraph renderInterferenceAreasCard(dev.id)
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

                    input "notifyOnEscapingGhost",
                            "bool",
                            title: "Notify when a targeted ghost is escaping from inside its interference area",
                            defaultValue: true
                }
            }

            section(getInterface("header", "Logging")) {
                input "enableDebugLogging",
                        "bool",
                        title: "Enable debug logging",
                        defaultValue: false
            }

            section(getInterface("header", "Done")) {
                href "mainPage", title: "Return To Landing Page", description: "Back to the app home page"
            }
        }
    } catch (Throwable t) {
        logThrowable("settingsPage()", t)
        throw t
    }
}

def deviceAreaPage(params = [:]) {
    try {
        initializeState()
        def requestedDeviceId = params?.deviceId ?: state.deviceAreaPageDeviceId
        if (requestedDeviceId) {
            state.deviceAreaPageDeviceId = requestedDeviceId
        }

        def shouldAutoRefresh = (state.deviceAreaPageRefreshUntil ?: 0L) > now()
        def refreshIntervalSeconds = Math.max(1, (state.deviceAreaPageRefreshIntervalSeconds ?: 1) as Integer)
        if (!shouldAutoRefresh) {
            state.deviceAreaPageRefreshUntil = null
            state.deviceAreaPageRefreshIntervalSeconds = null
        }

        def dev = mmwaveDevices?.find { deviceKey(it?.id) == deviceKey(requestedDeviceId) }
        return dynamicPage(name: "deviceAreaPage", install: false, uninstall: false, refreshInterval: shouldAutoRefresh ? refreshIntervalSeconds : null) {
            section() {
                paragraph renderHomeHeroCard()
            }
            if (!dev) {
                section { paragraph "Device not found." }
                return
            }

            def devKey = deviceKey(dev.id)
            def displayData = getDisplayData(dev.id)
            def trackedGhosts = getTrackedGhostsForDisplay(dev.id)
            def recommendation = state.recommendation?.deviceId == devKey ? state.recommendation : null

            section() {
                def body = new StringBuilder()
                body << renderInterferenceAreasControlCard(dev.id, displayData.selectableClusters)
                if (recommendation) {
                    body << renderRecommendationSummary(recommendation)
                }
                paragraph renderFramedPageCard(
                        "Interference Areas: ${dev.displayName}",
                        body.toString(),
                        buttonLink("refreshDeviceAreas_${devKey}", "<div style='float:right;vertical-align:middle; margin:4px;'><b><font size=3>Refresh Areas</font></b></div>", "#1A77C9", 14)
                )
            }

            section() {
                if (!trackedGhosts) {
                    paragraph renderFramedPageCard("Ghost Bounds For Assignment", renderActionHintCard("No Active Ghosts", "No active ghosts are currently available for interference area assignment."))
                } else {
                    def body = new StringBuilder()
                    trackedGhosts.eachWithIndex { cluster, idx ->
                        body << renderClusterDetails(cluster, idx + 1, devKey)
                    }
                    paragraph renderFramedPageCard("Ghost Bounds For Assignment", body.toString())
                }
            }

            section(getInterface("subHeader", "Area Assignments")) {
                if (displayData.selectableClusters) {
                    def eventDefinitions = renderCorrelationEventDefinitions(dev.id)
                    if (eventDefinitions) {
                        paragraph eventDefinitions
                    }
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
                            title: "Dynamic Activation",
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
                            title: "Dynamic Activation",
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
                            title: "Dynamic Activation",
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
                            title: "Dynamic Activation",
                            multiple: false,
                            required: false,
                            options: getDynamicActivationOptions(dev.id),
                            defaultValue: getDynamicActivationSelection(dev.id, 3),
                            width: 2
                    input "applyAllAreas_${devKey}", "button", title: "Done"
                } else {
                    paragraph "No ghosts are available for interference area targeting."
                }
            }
        }
    } catch (Throwable t) {
        logThrowable("deviceAreaPage()", t)
        throw t
    }
}

def deviceAreaDispatchPage(params = [:]) {
    try {
        def requestedDeviceId = params?.deviceId
        if (requestedDeviceId) {
            state.deviceAreaPageDeviceId = requestedDeviceId
        }

        return dynamicPage(name: "deviceAreaDispatchPage", install: false, uninstall: false, nextPage: "deviceAreaPage") {
            section() {
                paragraph renderHomeHeroCard()
                paragraph "Loading interference area controls..."
            }
        }
    } catch (Throwable t) {
        logThrowable("deviceAreaDispatchPage()", t)
        throw t
    }
}

def statsPage() {
    try {
        initializeState()
        refreshRecommendation()
        def sortedDevices = getSortedMmwaveDevices()

        def refreshStartAt = (state.statsPageRefreshStartAt ?: 0L) as Long
        def refreshUntil = (state.statsPageRefreshUntil ?: 0L) as Long
        def refreshIntervalSeconds = (state.statsPageRefreshIntervalSeconds ?: 1) as Integer
        def shouldAutoRefresh = refreshUntil > now()
        def activeRefreshInterval = (shouldAutoRefresh && now() >= refreshStartAt) ? Math.max(1, refreshIntervalSeconds) : null
        if (!shouldAutoRefresh) {
            state.statsPageRefreshStartAt = null
            state.statsPageRefreshUntil = null
            state.statsPageRefreshIntervalSeconds = null
        }

        return dynamicPage(name: "statsPage", install: false, uninstall: false, refreshInterval: activeRefreshInterval) {
            section() {
                paragraph renderHomeHeroCard()
            }
            if (!sortedDevices) {
                section { paragraph "No mmWave devices configured." }
                return
            }

            def aggregatePersistent = 0
            def aggregateBusted = 0

            sortedDevices.each { dev ->
                refreshStoredGhostSnapshotsForDevice(dev.id)
                def displayCounts = getDisplayCounts(dev.id)
                aggregatePersistent += displayCounts.persistentGhosts
                aggregateBusted += displayCounts.bustedGhosts
            }

            section() {
                def networkSummary = getNetworkSummaryStats(sortedDevices, aggregatePersistent, aggregateBusted)
                paragraph renderAllDevicesSummaryCard(networkSummary, state.recommendation)
            }

            sortedDevices.each { dev ->
                def devKey = deviceKey(dev.id)
                refreshStoredGhostSnapshotsForDevice(dev.id)
                def stableClusters = getStableClusters(dev.id)
                def trackedGhosts = getTrackedGhostsForDisplay(dev.id)
                def displayCounts = getDisplayCounts(dev.id)
                def displayData = getDisplayData(dev.id)
                def lastSummary = getSummaryForDevice(dev.id)
                def archivedGhostCount = displayData.archivedGhostCount ?: 0
                def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
                def escapingPointCount = (pointBuckets.occupancyAssociatedInterferencePoints ?: []).size()
                def xyScale = calculatePlotScale(
                        displayData.points,
                        displayData.outOfBoundsPoints,
                        displayData.currentClusters,
                        displayData.historicalClusters,
                        dev,
                        "x",
                        "y"
                )

                section() {
                    paragraph renderDeviceStatsCard(dev, devKey, displayCounts, displayData, lastSummary, trackedGhosts, archivedGhostCount, escapingPointCount, xyScale)
                    href(
                            name: "manageDeviceAreas_${devKey}",
                            page: "deviceAreaPage",
                            title: "Manage Interference Areas",
                            description: "Review current device areas, compare ghost bounds, and update assignments",
                            params: [deviceId: dev.id?.toString()],
                            state: "complete"
                    )
                }
            }

            section(getInterface("header", "Plot Legend")) {
                paragraph renderCommonPlotLegend()
            }
        }
    } catch (Throwable t) {
        logThrowable("statsPage()", t)
        throw t
    }
}

def appButtonHandler(btn) {
    if (!btn) {
        return
    }

    debugLog("appButtonHandler(): btn=${btn}")

    if (btn == "recommendNow") {
        generateRecommendation()
        return
    }

    if (btn.startsWith("clearDeviceStats_")) {
        def devKey = btn.substring("clearDeviceStats_".length())
        clearDeviceStats(devKey)
        return
    }

    if (btn.startsWith("clearPendingPoints_")) {
        def devKey = btn.substring("clearPendingPoints_".length())
        clearPendingPoints(devKey)
        return
    }

    if (btn.startsWith("processPendingPoints_")) {
        def devKey = btn.substring("processPendingPoints_".length())
        processPendingPointsNow(devKey)
        return
    }

    if (btn.startsWith("refreshDeviceAreas_")) {
        def devKey = btn.substring("refreshDeviceAreas_".length())
        refreshDeviceInterferenceAreas(devKey)
        return
    }

    if (btn.startsWith("clearGhost_")) {
        def remainder = btn.substring("clearGhost_".length())
        def splitIndex = remainder.lastIndexOf("_")
        if (splitIndex > 0) {
            def devKey = remainder.substring(0, splitIndex)
            def ghostIndex = remainder.substring(splitIndex + 1)
            clearTrackedGhost(devKey, ghostIndex?.isInteger() ? (ghostIndex as Integer) : null)
        }
        return
    }

    if (btn.startsWith("expandGhost_")) {
        def remainder = btn.substring("expandGhost_".length())
        def splitIndex = remainder.lastIndexOf("_")
        if (splitIndex > 0) {
            def devKey = remainder.substring(0, splitIndex)
            def ghostIndex = remainder.substring(splitIndex + 1)
            expandTrackedGhost(devKey, ghostIndex?.isInteger() ? (ghostIndex as Integer) : null)
            refreshDeviceInterferenceAreas(devKey)
        }
        return
    }

    if (btn.startsWith("clearCorrelationEvents_")) {
        def devKey = btn.substring("clearCorrelationEvents_".length())
        clearCorrelationEvents(devKey)
        return
    }

    if (btn.startsWith("showArchivedGhosts_")) {
        def devKey = btn.substring("showArchivedGhosts_".length())
        setArchivedGhostVisibility(devKey, true)
        return
    }

    if (btn.startsWith("hideArchivedGhosts_")) {
        def devKey = btn.substring("hideArchivedGhosts_".length())
        setArchivedGhostVisibility(devKey, false)
        return
    }

    if (btn.startsWith("applyZones_")) {
        def devKey = btn.substring("applyZones_".length())
        applySelectedZones(devKey)
        return
    }

    if (btn.startsWith("assignGhostArea_")) {
        def parts = btn.substring("assignGhostArea_".length()).split("_")
        if (parts.size() >= 2) {
            applySelectedZoneForArea(parts[0], safeInterferenceAreaIndex(parts[1]))
            updateDynamicInterferenceAreas()
            refreshDeviceInterferenceAreas(parts[0])
        }
        return
    }

    if (btn.startsWith("applyAllAreas_")) {
        def devKey = btn.substring("applyAllAreas_".length())
        applyAllSelectedZones(devKey)
        updateDynamicInterferenceAreas()
        refreshDeviceInterferenceAreas(devKey)
        scheduleStatsPageRefresh(12000L, 3000L, 3)
        return
    }

    if (btn.startsWith("saveManualArea_")) {
        def parts = btn.substring("saveManualArea_".length()).split("_")
        if (parts.size() >= 2) {
            saveManualInterferenceArea(parts[0], safeInterferenceAreaIndex(parts[1]))
            updateDynamicInterferenceAreas()
            refreshDeviceInterferenceAreas(parts[0])
        }
        return
    }

    if (btn.startsWith("clearRememberedArea_")) {
        def parts = btn.substring("clearRememberedArea_".length()).split("_")
        if (parts.size() >= 2) {
            clearRememberedInterferenceArea(parts[0], safeInterferenceAreaIndex(parts[1]), true)
            updateDynamicInterferenceAreas()
            refreshDeviceInterferenceAreas(parts[0])
        }
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

private void refreshDeviceInterferenceAreas(String devKey, boolean scheduleRefresh = true) {
    if (!devKey) {
        return
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return
    }

    if (scheduleRefresh) {
        scheduleDeviceAreaPageRefresh(dev.id?.toString(), 12000L, 1)
    }

    requestDeviceInterferenceAreaPopulate(dev)
}

def installed() {
    initializeState()
    syncChildDevices()
    initialize()
}

def updated() {
    log.trace "updated() start"
    debugLog("updated() start")
    try {
        debugLog("updated(): unsubscribe")
        unsubscribe()
        debugLog("updated(): unschedule")
        unschedule()
        initializeState()
        debugLog("updated(): state initialized")
        reconcileCorrelationEpisodeSettings()
        debugLog("updated(): correlation episode settings reconciled")
        syncChildDevices()
        debugLog("updated(): child devices synced")
        initialize()
        debugLog("updated() complete")
    } catch (Throwable t) {
        logThrowable("updated()", t)
        throw t
    }
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

    updateDetectionState(false)
    updateDynamicInterferenceAreas()
}

private initializeState() {
    state.dailyPoints = state.dailyPoints ?: [:]
    state.dailyOutOfBoundsPoints = state.dailyOutOfBoundsPoints ?: [:]
    state.ignoredOutsideWindowPoints = state.ignoredOutsideWindowPoints ?: [:]
    state.lastIgnoredOutsideWindowAt = state.lastIgnoredOutsideWindowAt ?: [:]
    state.stabilityData = state.stabilityData ?: [:]
    state.archivedGhosts = state.archivedGhosts ?: [:]
    state.archivedGhostVisibility = state.archivedGhostVisibility ?: [:]
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
    state.correlationEpisodeState = state.correlationEpisodeState ?: [:]
    state.correlationChangeHistory = state.correlationChangeHistory ?: [:]
    state.correlationEpisodeGapApplied = state.correlationEpisodeGapApplied ?: null
    state.deviceActiveDayHistory = state.deviceActiveDayHistory ?: [:]
    state.deviceActiveToday = state.deviceActiveToday ?: [:]
    state.dynamicAreaStatus = state.dynamicAreaStatus ?: [:]
    state.lastMode = state.lastMode ?: location.mode
    state.dayIndex = state.dayIndex ?: 0
    state.totalDays = state.totalDays ?: 0
}

private void reconcileCorrelationEpisodeSettings() {
    def currentGap = safeCorrelationEpisodeGapSeconds()
    def priorGap = state.correlationEpisodeGapApplied instanceof Number ? (state.correlationEpisodeGapApplied as Integer) : null
    debugLog("reconcileCorrelationEpisodeSettings(): priorGap=${priorGap}, currentGap=${currentGap}")
    if (priorGap != null && priorGap != currentGap) {
        recomputeRetainedTransitionCorrelationStats()
        infoLog("Correlation episode gap changed from ${priorGap}s to ${currentGap}s. Retained transition-correlation episode counts were recomputed from stored point snapshots where possible.")
    }
    state.correlationEpisodeGapApplied = currentGap
}

private void recomputeRetainedTransitionCorrelationStats() {
    debugLog("recomputeRetainedTransitionCorrelationStats(): start")
    def newDaily = ((state.correlationDaily ?: [:]) as Map).collectEntries { devKey, trackerMap ->
        [(devKey): zeroTransitionStatsForTrackerMap(trackerMap as Map)]
    }
    def newHistory = ((state.correlationHistory ?: [:]) as Map).collectEntries { devKey, entries ->
        [(devKey): ((entries ?: []) as List).collect { entry ->
            [
                    day: entry.day,
                    trackers: zeroTransitionStatsForTrackerMap((entry.trackers ?: [:]) as Map)
            ]
        }]
    }

    mmwaveDevices?.each { dev ->
        def devKey = deviceKey(dev.id)
        def retainedPoints = getRetainedPointsForEpisodeRecompute(dev.id)
        debugLog("recomputeRetainedTransitionCorrelationStats(): ${dev.displayName} retainedPoints=${(retainedPoints ?: []).size()}")
        if (!retainedPoints) {
            return
        }

        def episodeStarts = getGhostEpisodeStartsFromPoints(dev, retainedPoints)
        def episodeCount = episodeStarts.size()
        def trackerChanges = (((state.correlationChangeHistory ?: [:])[devKey] ?: [:]) as Map)

        newDaily[devKey] = recomputeTransitionStatsForTrackerMap((newDaily[devKey] ?: [:]) as Map, trackerChanges, episodeStarts, true)

        def historyEntries = ((newHistory[devKey] ?: []) as List)
        if (historyEntries) {
            def lastEntry = historyEntries[-1] as Map
            lastEntry.trackers = recomputeTransitionStatsForTrackerMap((lastEntry.trackers ?: [:]) as Map, trackerChanges, episodeStarts, false)
            historyEntries[-1] = lastEntry
            newHistory[devKey] = historyEntries
        }
    }

    state.correlationDaily = newDaily
    state.correlationHistory = newHistory
    state.correlationGhostPresence = [:]
    state.correlationEpisodeState = [:]
    debugLog("recomputeRetainedTransitionCorrelationStats(): complete")
}

private Map zeroTransitionStatsForTrackerMap(Map trackerMap) {
    ((trackerMap ?: [:]) as Map).collectEntries { trackerKey, value ->
        def stats = cloneCorrelationTrackerStats((value ?: [:]) as Map)
        stats.ghostAppearances = 0
        stats.ghostAppearancesNearAnyChange = 0
        stats.changeToValues = [:]
        [(trackerKey): stats]
    }
}

private Map recomputeTransitionStatsForTrackerMap(Map trackerMap, Map trackerChanges, List episodeStarts, boolean canRecomputeNearChange) {
    def changeWindowMs = safeCorrelationChangeWindowSeconds() * 1000L
    ((trackerMap ?: [:]) as Map).collectEntries { trackerKey, value ->
        def stats = cloneCorrelationTrackerStats((value ?: [:]) as Map)
        stats.ghostAppearances = episodeStarts.size()
        if (canRecomputeNearChange) {
            def changeTsList = ((trackerChanges[trackerKey] ?: []) as List).collect { it as Long }.sort()
            stats.ghostAppearancesNearAnyChange = episodeStarts.count { episodeStart ->
                changeTsList.any { changeTs ->
                    changeTs <= episodeStart && (episodeStart - changeTs) <= changeWindowMs
                }
            }
        } else {
            stats.ghostAppearancesNearAnyChange = Math.min((stats.ghostAppearancesNearAnyChange ?: 0) as Integer, episodeStarts.size())
        }
        stats.changeToValues = [:]
        [(trackerKey): stats]
    }
}

private List getRetainedPointsForEpisodeRecompute(deviceId) {
    def currentPoints = getPointsForDevice(deviceId)
    def snapshotPoints = getSnapshotPoints(deviceId)
    ((snapshotPoints ?: []).size() > (currentPoints ?: []).size()) ? snapshotPoints : currentPoints
}

private updateDetectionState(boolean allowDeactivateProcessing = false) {
    mmwaveDevices?.each { dev ->
        def devKey = deviceKey(dev.id)
        def shouldBeActive = isDeviceActive(dev)
        def wasActive = state.activeDevices[devKey] ?: false

        debugLog("${dev.displayName} shouldBeActive=${shouldBeActive}, wasActive=${wasActive}")

        if (shouldBeActive && !wasActive) {
            activateDetection(dev)
        }

        if (!shouldBeActive && wasActive) {
            deactivateDetection(dev, allowDeactivateProcessing)
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

private deactivateDetection(dev, boolean allowDeactivateProcessing = false) {
    sendMmWaveUnBind(dev)
    def devKey = deviceKey(dev.id)
    state.activeDevices[devKey] = false

    if (allowDeactivateProcessing && shouldProcessPointsOnDetectionInactive()) {
        processPendingPointsNow(devKey)
    }

    infoLog("Ghost detection deactivated for ${dev.displayName}")

    if (notifyOnDeactivate) {
        def summary = getSummaryForDevice(dev.id)
        def counts = summary ?: getGhostCounts(devKey, getTodayClusters(dev.id))
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
    updateDetectionState(true)
}

def activatorSwitchHandler(evt) {
    debugLog("Activator switch changed: ${evt.device?.displayName} -> ${evt.value}")
    updateDetectionState(true)
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
    registerCorrelationChangeEvent(trackedDevice, evt.name as String, now())
    debugLog("Correlation attribute changed: ${trackedDevice.displayName} ${evt.name}=${evt.value}")
    updateDynamicInterferenceAreas()
}

private void registerCorrelationChangeEvent(dev, String attribute, Long changedAt) {
    def trackedKey = deviceKey(dev?.id)
    if (!trackedKey || !attribute || changedAt == null) {
        return
    }

    mmwaveDevices?.each { mmwaveDev ->
        def mmwaveKey = deviceKey(mmwaveDev.id)
        def matchingTrackers = getConfiguredCorrelationTrackersForDevice(mmwaveKey).findAll { tracker ->
            tracker.deviceKey == trackedKey && tracker.attribute == attribute
        }
        if (!matchingTrackers) {
            return
        }

        def deviceHistory = (((state.correlationChangeHistory ?: [:])[mmwaveKey] ?: [:]) as Map).collectEntries { key, value ->
            [(key): ((value ?: []) as List).collect { it as Long }]
        }
        matchingTrackers.each { tracker ->
            def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
            def trackerHistory = ((deviceHistory[trackerKey] ?: []) as List).collect { it as Long }
            trackerHistory << changedAt
            deviceHistory[trackerKey] = trackerHistory.unique().sort()
        }
        state.correlationChangeHistory[mmwaveKey] = deviceHistory
    }
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

    if (!isDeviceActive(dev)) {
        recordIgnoredOutsideWindowPoints(dev, (((result.inBounds ?: []) as List).size() + ((result.outOfBounds ?: []) as List).size()) as Integer)
        debugLog("Ignoring targetInfo for ${dev.displayName}: detection conditions not active")
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

private void recordIgnoredOutsideWindowPoints(dev, Integer count) {
    def devKey = deviceKey(dev?.id)
    def ignoredCount = Math.max(0, count ?: 0)
    if (!devKey || ignoredCount <= 0) {
        return
    }

    state.ignoredOutsideWindowPoints[devKey] = ((state.ignoredOutsideWindowPoints[devKey] ?: 0) as Integer) + ignoredCount
    state.lastIgnoredOutsideWindowAt[devKey] = now()
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
        applyAutomaticTargetedAreaExpansion(dev)
        refreshStoredGhostSnapshotsForDevice(dev.id, clustersToday, rawPoints, outOfBoundsPoints)
        updateClusterMaxStatesForDevice(dev.id)
        recomputeDailyCorrelationEpisodesForDevice(dev)

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
        state.correlationEpisodeState?.remove(devKey)

        debugLog("Processed ${dev.displayName}: points=${points.size()}, out-of-bounds=${outOfBoundsPoints.size()}, unclustered=${unclusteredPointCount}, clusters=${clustersToday.size()}, persistent=${counts.persistentGhosts}, busted=${counts.bustedGhosts}")
        sendGhostLifecycleNotifications(dev, previousStableClusters)
    }

    state.dailySummary = summaries
    refreshRecommendation(true, previousRecommendationKey)
    state.dailyPoints = [:]
    state.dailyOutOfBoundsPoints = [:]
    state.deviceActiveToday = [:]
    state.correlationDaily = [:]
    state.correlationChangeHistory = [:]
    pruneOldActivationStarts()

    if (sendPush && notifyDailySummary) {
        sendNotification(buildDailySummary())
    }
}

private void processPendingPointsNow(String devKey) {
    if (!devKey) {
        return
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return
    }

    def pendingCount = (((state.dailyPoints[devKey] ?: []) as List).size() + ((state.dailyOutOfBoundsPoints[devKey] ?: []) as List).size()) as Integer
    if (pendingCount <= 0) {
        return
    }

    def previousDayIndex = state.dayIndex ?: 0
    def processingDay = Math.max(1, previousDayIndex)
    def previousRecommendationKey = recommendationKey(state.recommendation)
    def previousStableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }

    try {
        state.dayIndex = processingDay
        recordActiveTrackingDay(devKey, processingDay as Integer, (state.deviceActiveToday[devKey] ?: false) || isDeviceActive(dev))

        def rawPoints = getPointsForDevice(dev.id)
        def points = filterPointsForGhostDetection(rawPoints, dev)
        def outOfBoundsPoints = getOutOfBoundsPointsForDevice(dev.id)
        def clustersToday = detectClusters(points)
        def unclusteredPointCount = calculateUnclusteredPointCount(points, clustersToday)

        updateStabilityForDay(dev.id, clustersToday)
        applyAutomaticGhostBusting(dev)
        applyAutomaticTargetedAreaExpansion(dev)
        refreshStoredGhostSnapshotsForDevice(dev.id, clustersToday, rawPoints, outOfBoundsPoints)
        updateClusterMaxStatesForDevice(dev.id)
        recomputeDailyCorrelationEpisodesForDevice(dev)

        def counts = getGhostCounts(devKey, clustersToday)
        state.dailySummary[devKey] = counts + [
                pointCount: points.size(),
                unclusteredPointCount: unclusteredPointCount,
                outOfBoundsPointCount: outOfBoundsPoints.size()
        ]
        recordCorrelationDay(devKey, counts)
        state.lastPointsSnapshot[devKey] = rawPoints.collect { clonePoint(it) }
        state.lastOutOfBoundsSnapshot[devKey] = outOfBoundsPoints.collect { clonePoint(it) }
        state.lastClustersSnapshot[devKey] = clustersToday.collect { snapshotCluster(it) }
        state.correlationGhostPresence[devKey] = false
        state.correlationEpisodeState?.remove(devKey)
        state.dailyPoints?.remove(devKey)
        state.dailyOutOfBoundsPoints?.remove(devKey)
        state.deviceActiveToday?.remove(devKey)
        refreshRecommendation(true, previousRecommendationKey)
        sendGhostLifecycleNotifications(dev, previousStableClusters)
        infoLog("Processed ${pendingCount} pending point(s) immediately for ${dev.displayName}")
    } finally {
        state.dayIndex = previousDayIndex
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

    def retiringClusters = stableClusters.findAll { cluster ->
        !shouldRetainActiveGhost(cluster)
    }
    archiveExpiredGhosts(devKey, retiringClusters)
    state.stabilityData[devKey] = stableClusters.findAll { cluster ->
        shouldRetainActiveGhost(cluster)
    }
    syncClusterTargetsForDevice(devKey)
}

private boolean shouldRetainActiveGhost(Map cluster) {
    (cluster?.daysSeen ?: 0) > 0 || ((cluster?.absentStreak ?: 0) < safeHistoryDays())
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
    stableCluster.points = representativeClusterPoints(dailyCluster.points)
    stableCluster.lastSeen = currentDay
    stableCluster.seenHistory = ((stableCluster.seenHistory ?: []) + currentDay).unique().sort()
    pruneSeenHistory(stableCluster, historyCutoff)
    stableCluster.daysSeen = stableCluster.seenHistory.size()
    stableCluster.absentStreak = 0
    stableCluster.consecutiveSeen = ((stableCluster.lastMatchedDay ?: 0) == currentDay - 1) ?
            ((stableCluster.consecutiveSeen ?: 0) + 1) : 1
    stableCluster.lastMatchedDay = currentDay
    copyGhostSnapshotFields(stableCluster, dailyCluster)
}

private Map buildStableCluster(Map dailyCluster, Integer currentDay) {
    [
            center: clonePoint(dailyCluster.center),
            bounds: cloneBounds(dailyCluster.bounds),
            radius: dailyCluster.radius,
            density: dailyCluster.density,
            points: representativeClusterPoints(dailyCluster.points),
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
            appliedBounds: null,
            maxStateReached: "Detected"
    ] + ghostSnapshotFields(dailyCluster)
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
        lines << "Escaping targeted ghosts: ${summary.escapingGhosts ?: 0}"
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
    def devKey = deviceKey(deviceId)
    def clusterLocks = (state.todayClusterLocks ?: [:]) as Map
    if (clusterLocks[devKey] == true) {
        return []
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    try {
        state.todayClusterLocks = clusterLocks + [(devKey): true]
        def effectivePoints = filterPointsForGhostDetection(getPointsForDevice(deviceId), dev)
        return detectClusters(effectivePoints)
    } finally {
        def updatedLocks = ((state.todayClusterLocks ?: [:]) as Map).findAll { key, value ->
            key != devKey
        }
        state.todayClusterLocks = updatedLocks
    }
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
    def options = ["": "Off"]
    getCorrelatedActivationEvents(deviceId).each { event ->
        options[event.selectionKey as String] = "Event ${event.index}"
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

private List getCorrelatedActivationEvents(deviceId) {
    buildCorrelationTrackerCards(deviceId).collectMany { card ->
        ((card.events ?: []) as List).findAll { (it.type ?: "") == "value" && it.selectionKey }.collect { event ->
            event + [cardTitle: card.title]
        }
    }
}

private List buildCorrelationTrackerCards(deviceId) {
    def devKey = deviceKey(deviceId)
    def configuredTrackers = getConfiguredCorrelationTrackersForDevice(devKey)
    def cards = []

    configuredTrackers.each { tracker ->
        def aggregate = buildCorrelationAggregateForTracker(devKey, tracker)
        def events = []
        events.addAll(buildValueCorrelationEvents(tracker, aggregate))
        events.addAll(buildTransitionCorrelationEvents(tracker, aggregate))
        cards << [
                trackerKey: "${tracker.deviceKey}:${tracker.attribute}",
                title: "${tracker.deviceName} / ${tracker.attribute}",
                deviceName: tracker.deviceName,
                attribute: tracker.attribute,
                events: events,
                hasEvents: !events.isEmpty()
        ]
    }

    def nextIndex = 1
    cards.each { card ->
        ((card.events ?: []) as List).each { event ->
            event.index = nextIndex++
        }
    }

    cards
}

private Map buildCorrelationAggregateForTracker(String devKey, Map tracker) {
    def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
    def aggregate = emptyCorrelationAggregate(tracker.deviceName, tracker.attribute)
    (((state.correlationHistory[devKey] ?: []) as List)).each { entry ->
        mergeCorrelationAggregate(aggregate, (((entry.trackers ?: [:]) as Map)[trackerKey] ?: [:]) as Map)
    }
    mergeCorrelationAggregate(aggregate, (((state.correlationDaily[devKey] ?: [:]) as Map)[trackerKey] ?: [:]) as Map)
    aggregate
}

private List buildValueCorrelationEvents(Map tracker, Map aggregate) {
    getQualifiedValueCorrelationCandidates(aggregate).collect { candidate ->
        [
                type: "value",
                shortLabel: formatCorrelationEventValue(candidate.value as String),
                title: "${tracker.attribute} = ${candidate.value}",
                headline: "Possible value correlation",
                summary: "Ghosts are concentrated when this attribute is '${candidate.value}', and much less common in the other observed values.",
                supportLine: "${candidate.stats.ghostSamples ?: 0} / ${candidate.total ?: 0} samples were ghost-present while the value was '${candidate.value}' (${percent(candidate.stats.ghostSamples, candidate.total)}).",
                selectionKey: "${tracker.deviceKey}|${tracker.attribute}|${candidate.value}",
                activeValue: candidate.value?.toString(),
                border: "#ef9a9a",
                background: "#ffebee",
                color: "#c62828"
        ]
    }
}

private List buildTransitionCorrelationEvents(Map tracker, Map aggregate) {
    def assessment = assessTransitionCorrelation(aggregate)
    if (!(assessment?.isDetected)) {
        return []
    }
    [[
             type: "transition",
             shortLabel: "Recent Attribute Change",
             title: "Attribute changed, then ghost episode started within ${safeCorrelationChangeWindowSeconds()}s",
             headline: assessment.headline,
             summary: assessment.summary,
             supportLine: assessment.supportLine,
             selectionKey: null,
             activeValue: null,
             border: assessment.border,
             background: assessment.background,
             color: assessment.color
     ]]
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
    def nowMs = now()
    def changeWindowMs = safeCorrelationChangeWindowSeconds() * 1000L
    def episodeSample = getGhostEpisodeSample(dev, result)
    def ghostPresent = episodeSample.ghostPresentNow
    def ghostEpisodeStart = episodeSample.episodeStart
    state.correlationGhostPresence[mmwaveKey] = ghostPresent

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

        if (ghostEpisodeStart) {
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

private Map getGhostEpisodeSample(dev, Map result) {
    def devKey = deviceKey(dev?.id)
    def referenceTs = extractCorrelationReferenceTs(result)
    def gapMs = safeCorrelationEpisodeGapSeconds() * 1000L
    def rawPoints = (getPointsForDevice(dev?.id) ?: []) as List
    def recentRawPoints = rawPoints.findAll { point ->
        def pointTs = point?.ts instanceof Number ? (point.ts as Long) : null
        pointTs != null && referenceTs != null && (referenceTs - pointTs) <= gapMs
    }
    def recentGhostPoints = filterPointsForGhostDetection(recentRawPoints, dev)
    def recentClusters = detectClusters(recentGhostPoints)
    def ghostPresentNow = (recentClusters ?: []).size() > 0
    def latestGhostPointTs = recentGhostPoints.collect { it?.ts instanceof Number ? (it.ts as Long) : null }.findAll { it != null }.max()
    def priorEpisodeState = ((state.correlationEpisodeState ?: [:])[devKey] ?: [:]) as Map
    def priorGhostPointTs = priorEpisodeState.lastGhostPointTs instanceof Number ? (priorEpisodeState.lastGhostPointTs as Long) : null
    def episodeStart = ghostPresentNow && latestGhostPointTs != null &&
            (priorGhostPointTs == null || (latestGhostPointTs - priorGhostPointTs) > gapMs)

    if (ghostPresentNow && latestGhostPointTs != null) {
        state.correlationEpisodeState[devKey] = [lastGhostPointTs: latestGhostPointTs]
    }

    [
            ghostPresentNow: ghostPresentNow,
            episodeStart: episodeStart,
            latestGhostPointTs: latestGhostPointTs,
            recentPointCount: recentGhostPoints.size()
    ]
}

private List getGhostEpisodeStartsFromPoints(dev, List rawPoints) {
    def eligiblePoints = filterPointsForGhostDetection(rawPoints ?: [], dev)
            .findAll { it?.ts instanceof Number }
            .sort { a, b -> (a.ts as Long) <=> (b.ts as Long) }
    if (!eligiblePoints) {
        return []
    }

    def gapMs = safeCorrelationEpisodeGapSeconds() * 1000L
    def episodes = []
    def currentEpisode = []

    eligiblePoints.each { point ->
        def pointTs = point.ts as Long
        def priorTs = currentEpisode ? (currentEpisode[-1].ts as Long) : null
        if (!currentEpisode || priorTs == null || (pointTs - priorTs) <= gapMs) {
            currentEpisode << point
        } else {
            if (episodeHasGhostPresence(currentEpisode)) {
                episodes << ((currentEpisode[0].ts ?: 0L) as Long)
            }
            currentEpisode = [point]
        }
    }

    if (episodeHasGhostPresence(currentEpisode)) {
        episodes << ((currentEpisode[0].ts ?: 0L) as Long)
    }

    episodes
}

private boolean episodeHasGhostPresence(List episodePoints) {
    if (!(episodePoints ?: [])) {
        return false
    }
    if ((episodePoints.size() ?: 0) < safeMinClusterEvents()) {
        return false
    }
    (detectClusters(episodePoints) ?: []).size() > 0
}

private Long extractCorrelationReferenceTs(Map result) {
    def pointTs = []
    pointTs.addAll(((result?.inBounds ?: []) as List).collect { point ->
        point?.ts instanceof Number ? (point.ts as Long) : null
    })
    pointTs.addAll(((result?.outOfBounds ?: []) as List).collect { point ->
        point?.ts instanceof Number ? (point.ts as Long) : null
    })
    pointTs = pointTs.findAll { it != null }
    pointTs ? pointTs.max() as Long : now()
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

private List detectClusters(List points) {
    def binnedPoints = binPointsForClustering(points)
    if (!binnedPoints || totalPointWeight(binnedPoints) < safeMinClusterEvents()) {
        return []
    }

    def algorithm = clusteringAlgorithm ?: "DBSCAN"
    def clusters = []

    if (algorithm == "K-Means") {
        clusters = detectClustersKMeans(binnedPoints, safeMaxClusters(), 40)
        if (!clusters) {
            clusters = detectClustersDBSCAN(binnedPoints, safeClusterRadius(), safeMinClusterEvents())
        }
    } else {
        clusters = detectClustersDBSCAN(binnedPoints, safeClusterRadius(), safeMinClusterEvents())
        if (!clusters) {
            clusters = detectClustersKMeans(binnedPoints, safeMaxClusters(), 40)
        }
    }

    mergeOverlappingClusters(clusters)
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

            def totalWeight = totalPointWeight(clusterPoints)

            [
                    x: weightedAxisAverage(clusterPoints, "x", totalWeight),
                    y: weightedAxisAverage(clusterPoints, "y", totalWeight),
                    z: weightedAxisAverage(clusterPoints, "z", totalWeight)
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

        if (totalPointWeight(clusterPoints) >= safeMinClusterEvents()) {
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
        if (totalPointWeight(neighbors.collect { points[it] }) < minPts) {
            return
        }

        def clusterIndexes = [] as Set
        expandCluster(points, idx, neighbors, clusterIndexes, visited, assigned, eps, minPts)

        def clusterPoints = clusterIndexes.collect { points[it] }
        if (totalPointWeight(clusterPoints) >= minPts) {
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
            if (totalPointWeight(extraNeighbors.collect { points[it] }) >= minPts) {
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
    def totalWeight = totalPointWeight(points)
    def center = [
            x: weightedAxisAverage(points, "x", totalWeight),
            y: weightedAxisAverage(points, "y", totalWeight),
            z: weightedAxisAverage(points, "z", totalWeight)
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
            density: totalWeight,
            points: points.collect { clonePoint(it) }
    ]
}

private List mergeOverlappingClusters(List clusters) {
    def remaining = (clusters ?: []).collect { snapshotCluster(it) }
    def merged = []

    while (remaining) {
        def seed = remaining.remove(0)
        def combinedPoints = (seed.points ?: []).collect { clonePoint(it) }
        def changed = true

        while (changed) {
            changed = false
            def matches = remaining.findAll { candidate ->
                shouldMergeClusters(buildCluster(combinedPoints), candidate)
            }
            if (matches) {
                matches.each { match ->
                    combinedPoints.addAll((match.points ?: []).collect { clonePoint(it) })
                    remaining.remove(match)
                }
                changed = true
            }
        }

        merged << buildCluster(combinedPoints.unique { point -> "${point.x}:${point.y}:${point.z}:${point.ts}" })
    }

    merged
}

private boolean shouldMergeClusters(Map left, Map right) {
    if (!left?.bounds || !right?.bounds) {
        return false
    }

    def intersection = intersectBounds(left.bounds, right.bounds)
    if (!isValidBounds(intersection)) {
        return false
    }

    def leftVolume = boundsVolume(left.bounds)
    def rightVolume = boundsVolume(right.bounds)
    def intersectionVolume = boundsVolume(intersection)
    def smallerVolume = Math.max(1.0d, Math.min(leftVolume, rightVolume))
    def overlapRatio = intersectionVolume / smallerVolume

    overlapRatio >= 0.55d || (overlapRatio >= 0.30d && distance3D(left.center, right.center) <= safeClusterRadius() * 0.75d)
}

private Double boundsVolume(Map bounds) {
    if (!isValidBounds(bounds)) {
        return 0.0d
    }
    Math.max(0.0d, ((bounds.xmax ?: 0.0d) - (bounds.xmin ?: 0.0d)) *
            ((bounds.ymax ?: 0.0d) - (bounds.ymin ?: 0.0d)) *
            ((bounds.zmax ?: 0.0d) - (bounds.zmin ?: 0.0d)))
}

private Map getGhostCounts(String devKey, List todayClusters) {
    def stableClusters = (state.stabilityData[devKey] ?: []) as List
    def bustCounts = getPersistentBustSourceCountsForDevice(devKey)
    def activeGhosts = stableClusters.findAll { !isGhostHistoricallyBusted(devKey, it) && ((it.daysSeen ?: 0) > 0) }
    def displayStates = activeGhosts.collect { determineGhostState(devKey, it) }

    [
            ghostsToday: todayClusters?.size() ?: 0,
            detectedGhosts: displayStates.count { it == "Detected" },
            persistentGhosts: displayStates.count { it == "Persistent" },
            targetedGhosts: displayStates.count { it == "Targeted" },
            leakingGhosts: activeGhosts.count { getEscapingGhostMode(devKey, it) in ["adjacent", "both"] },
            escapingGhosts: displayStates.count { it == "Escaping" },
            bustedGhosts: stableClusters.count { determineGhostState(devKey, it) == "Busted" || isGhostHistoricallyBusted(devKey, it) },
            autoBusted: bustCounts.autoBusted,
            manualBusted: bustCounts.manualBusted,
            unbusted: bustCounts.unbusted
    ]
}

private Map getDisplayGhostCounts(String devKey, List todayClusters) {
    def stableClusters = (state.stabilityData[devKey] ?: []) as List
    def bustCounts = getPersistentBustSourceCountsForDevice(devKey)
    def visibleGhosts = stableClusters.findAll { shouldDisplayActiveGhost(devKey, it as Map) }
    def visibleStates = visibleGhosts.collect { determineGhostState(devKey, it as Map) }

    [
            ghostsToday: todayClusters?.size() ?: 0,
            detectedGhosts: visibleStates.count { it == "Detected" },
            persistentGhosts: visibleStates.count { it == "Persistent" },
            targetedGhosts: visibleStates.count { it == "Targeted" },
            leakingGhosts: visibleGhosts.count { getEscapingGhostMode(devKey, it as Map) in ["adjacent", "both"] },
            escapingGhosts: visibleStates.count { it == "Escaping" },
            bustedGhosts: stableClusters.count { determineGhostState(devKey, it as Map) == "Busted" || isGhostHistoricallyBusted(devKey, it as Map) },
            autoBusted: bustCounts.autoBusted,
            manualBusted: bustCounts.manualBusted,
            unbusted: bustCounts.unbusted
    ]
}

private boolean shouldDisplayActiveGhost(String devKey, Map cluster) {
    if (!cluster || (cluster.daysSeen ?: 0) <= 0 || isGhostHistoricallyBusted(devKey, cluster)) {
        return false
    }

    def state = determineGhostState(devKey, cluster)
    if (state == "Detected") {
        return isWithinDisplayGrace(cluster)
    }

    state in ["Persistent", "Targeted", "Escaping"]
}

private boolean shouldDisplayHistoricalGhost(String devKey, Map cluster) {
    if (!cluster) {
        return false
    }

    def state = determineGhostState(devKey, cluster)
    if (state == "Detected") {
        return isWithinDisplayGrace(cluster)
    }

    state in ["Persistent", "Targeted", "Escaping", "Busted"]
}

private List getTrackedGhostsForDisplay(deviceId) {
    def devKey = deviceKey(deviceId)
    getStableClusters(deviceId).findAll { cluster ->
        shouldDisplayHistoricalGhost(devKey, cluster as Map)
    }.collect { cloneCluster(it as Map) }
}

private boolean isWithinDisplayGrace(Map cluster) {
    ((cluster?.daysSeen ?: 0) > 0) && ((cluster?.absentStreak ?: 0) <= safeDisplayGraceDays())
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
    def clearedAreaIndexes = []

    debugLog("applyAllSelectedZones(${dev.displayName}): starting")

    (0..3).each { areaIndex ->
        def selectedIndex = settings["targetCluster_${devKey}_${areaIndex}"]
        debugLog("applyAllSelectedZones(${dev.displayName}): areaIndex=${areaIndex}, selectedIndex=${selectedIndex}")
        if (selectedIndex == null || selectedIndex == "") {
            return
        }

        if (selectedIndex == "__none__") {
            clearRememberedInterferenceArea(devKey, areaIndex, true)
            applyDynamicInterferenceAreaState(dev, areaIndex, inactiveInterferenceAreaBounds(), false)
            clearedAreaIndexes << areaIndex
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

    if (!zoneSpecs && !clearedAreaIndexes) {
        warnLog("Choose at least one ghost assignment before applying interference areas for ${dev.displayName}.")
        return
    }

    def appliedZoneSpecs = zoneSpecs ? applyZonesToDevice(dev, zoneSpecs, "manual cluster selection") : []
    if (appliedZoneSpecs) {
        rememberAppliedZones(devKey, appliedZoneSpecs, "manual")
        updateClusterBustMode(dev.id, sourceClusters, appliedZoneSpecs, "manual")
    }
    debugLog("applyAllSelectedZones(${dev.displayName}): applied=${appliedZoneSpecs.size()}, cleared=${clearedAreaIndexes}")
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
    def targetedArea = getRememberedAreaForGhost(devKey, cluster)
    if (targetedArea && isGhostLeaking(devKey, cluster)) {
        def expandedBounds = clampBoundsToReference(unionBounds(targetedArea.bounds, clampedClusterBounds), deviceBounds)
        if (isValidBounds(expandedBounds) && !sameBounds(expandedBounds, targetedArea.bounds)) {
            return [
                    action: "expand",
                    areaIndex: targetedArea.areaIndex,
                    bounds: expandedBounds,
                    stabilityPct: stabilityPct,
                    reason: "leaking"
            ]
        }
    }

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
                stabilityPct: stabilityPct,
                reason: "overlap"
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

private void applyAutomaticTargetedAreaExpansion(dev) {
    if (!dev || !enableAutoExpandTargetedAreas) {
        return
    }

    def devKey = deviceKey(dev.id)
    def stableClusters = getStableClusters(dev.id)
    def deviceBounds = clampReferenceBounds(dev)
    if (!deviceBounds) {
        return
    }

    stableClusters.findAll { cluster ->
        !isGhostBusted(devKey, cluster) && isGhostLeaking(devKey, cluster)
    }.each { cluster ->
        def rememberedArea = getRememberedAreaForGhost(devKey, cluster)
        if (!rememberedArea?.bounds) {
            return
        }

        def expandedBounds = getAllowedExpansionForLeakingGhost(dev, rememberedArea.bounds, cluster.bounds)
        if (!isValidBounds(expandedBounds) || sameBounds(expandedBounds, rememberedArea.bounds)) {
            return
        }

        def appliedZoneSpecs = applyZonesToDevice(dev, [[areaIndex: rememberedArea.areaIndex, bounds: expandedBounds, cluster: cluster]], "targeted ghost expansion")
        if (appliedZoneSpecs) {
            rememberInterferenceArea(devKey, rememberedArea.areaIndex as Integer, expandedBounds, rememberedArea.source ?: "manual", rememberedArea.dynamic as Map)
        }
    }

    syncClusterTargetsForDevice(devKey)
}

private Map getAllowedExpansionForLeakingGhost(dev, Map areaBounds, Map clusterBounds) {
    if (!isValidBounds(areaBounds) || !isValidBounds(clusterBounds)) {
        return null
    }

    def deviceBounds = clampReferenceBounds(dev)
    def union = unionBounds(areaBounds, clusterBounds)
    if (!isValidBounds(union)) {
        return null
    }

    def expansionCap = [
            xmin: (areaBounds.xmin ?: 0.0d) - safeMaxLeakExpandX(),
            xmax: (areaBounds.xmax ?: 0.0d) + safeMaxLeakExpandX(),
            ymin: (areaBounds.ymin ?: 0.0d) - safeMaxLeakExpandY(),
            ymax: (areaBounds.ymax ?: 0.0d) + safeMaxLeakExpandY(),
            zmin: (areaBounds.zmin ?: 0.0d) - safeMaxLeakExpandZ(),
            zmax: (areaBounds.zmax ?: 0.0d) + safeMaxLeakExpandZ()
    ]

    clampBoundsToReference(intersectBounds(union, expansionCap), deviceBounds)
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
        def clearedBounds = inactiveInterferenceAreaBounds()
        dev.mmWaveSetInterferenceArea(
                zoneIdx,
                clearedBounds.xmin,
                clearedBounds.xmax,
                clearedBounds.ymin,
                clearedBounds.ymax,
                clearedBounds.zmin,
                clearedBounds.zmax
        )
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
    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def metadata = (((state.interferenceAreas ?: [:])[devKey] ?: [:]) as Map)

    if (dev) {
        def areas = readDeviceInterferenceAreas(dev)
        if (!areas && !hasAnyDeviceInterferenceAreaAttribute(dev)) {
            requestDeviceInterferenceAreaPopulate(dev)
        }
        if (areas) {
            return mergeRememberedAreaMetadata(areas, metadata)
        }
    }

    rememberedInterferenceAreasFromMetadata(metadata)
}

private List rememberedInterferenceAreasFromMetadata(Map metadata) {
    (metadata ?: [:]).collect { idx, area ->
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

private List readDeviceInterferenceAreas(dev) {
    def readAt = now()
    def areas = (0..3).collect { areaIndex ->
        def raw = dev.currentValue("interferenceArea${areaIndex}")?.toString()
        def bounds = parseJsonMap(raw)
        if (!isValidBounds(bounds) && !isInactiveInterferenceAreaBounds(bounds)) {
            return null
        }
        [
                areaIndex: areaIndex,
                bounds: cloneBounds(bounds),
                source: "device",
                updatedAt: readAt,
                dynamic: null,
                dynamicActive: false
        ]
    }.findAll { it }
    if (areas) {
        debugLog("readDeviceInterferenceAreas(${dev.displayName}): found ${areas.size()} area attribute(s)")
    } else {
        debugLog("readDeviceInterferenceAreas(${dev.displayName}): no populated interferenceArea attributes found")
    }
    areas
}

private List mergeRememberedAreaMetadata(List deviceAreas, Map metadata) {
    (deviceAreas ?: []).collect { area ->
        def meta = ((metadata ?: [:])[((area.areaIndex ?: 0) as Integer).toString()] ?: [:]) as Map
        area + [
                source: meta.source ?: area.source ?: "device",
                updatedAt: meta.updatedAt ?: area.updatedAt ?: 0L,
                dynamic: cloneDynamicAreaConfig(meta.dynamic as Map),
                dynamicActive: meta.dynamicActive == true
        ]
    }.sort { a, b -> (a.areaIndex as Integer) <=> (b.areaIndex as Integer) }
}

private boolean hasAnyDeviceInterferenceAreaAttribute(dev) {
    if (!dev) {
        return false
    }
    (0..3).any { areaIndex ->
        def raw = dev.currentValue("interferenceArea${areaIndex}")?.toString()
        raw?.trim()
    }
}

private void requestDeviceInterferenceAreaPopulate(dev) {
    if (!dev) {
        return
    }
    def devKey = deviceKey(dev.id)
    def nowMs = now()
    def lastRequested = (((state.interferenceAreaPopulateAt ?: [:])[devKey] ?: 0L) as Long)
    if ((nowMs - lastRequested) < 5000L) {
        debugLog("requestDeviceInterferenceAreaPopulate(${dev.displayName}): skipped; requested too recently")
        return
    }
    try {
        debugLog("requestDeviceInterferenceAreaPopulate(${dev.displayName}): requesting mmWaveControlInstruction(2)")
        dev.mmWaveControlInstruction(2)
        state.interferenceAreaPopulateAt = (state.interferenceAreaPopulateAt ?: [:]) + [(devKey): nowMs]
    } catch (Exception ex) {
        warnLog("Unable to request interference area attributes from ${dev.displayName}: ${ex.message}")
    }
}

private Map parseJsonMap(String rawValue) {
    if (!rawValue) {
        return null
    }
    try {
        def parsed = new JsonSlurper().parseText(rawValue)
        parsed instanceof Map ? (parsed as Map) : null
    } catch (Exception ignored) {
        null
    }
}

private Map normalizeInterferenceAreaBounds(Map rawBounds) {
    if (!rawBounds) {
        return null
    }
    [
            xmin: toDouble(rawBounds.xmin),
            xmax: toDouble(rawBounds.xmax),
            ymin: toDouble(rawBounds.ymin),
            ymax: toDouble(rawBounds.ymax),
            zmin: toDouble(rawBounds.zmin),
            zmax: toDouble(rawBounds.zmax)
    ]
}

private boolean isInactiveInterferenceAreaBounds(Map bounds) {
    bounds &&
            (bounds.xmin ?: 0.0d) == 0.0d &&
            (bounds.xmax ?: 0.0d) == 0.0d &&
            (bounds.ymin ?: 0.0d) == 0.0d &&
            (bounds.ymax ?: 0.0d) == 0.0d &&
            (bounds.zmin ?: 0.0d) == -600.0d &&
            (bounds.zmax ?: 0.0d) == 600.0d
}

private void rememberInterferenceArea(String devKey, Integer areaIndex, Map bounds, String source, Map dynamicConfig = null, Long updatedAt = null) {
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
            updatedAt: updatedAt ?: now(),
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
    def remembered = getRememberedInterferenceAreas(devKey).findAll { !isInactiveInterferenceAreaBounds(it.bounds as Map) }
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

private void recomputeDailyCorrelationEpisodesForDevice(dev) {
    def devKey = deviceKey(dev?.id)
    if (!devKey) {
        return
    }

    def trackers = getConfiguredCorrelationTrackersForDevice(devKey)
    def dailyStats = ((state.correlationDaily[devKey] ?: [:]) as Map).collectEntries { key, value ->
        [(key): cloneCorrelationTrackerStats(value as Map)]
    }
    if (!trackers || !dailyStats) {
        return
    }

    def episodeStarts = getGhostEpisodeStartsFromPoints(dev, getPointsForDevice(dev.id))
    def changeWindowMs = safeCorrelationChangeWindowSeconds() * 1000L
    def changeHistory = (((state.correlationChangeHistory ?: [:])[devKey] ?: [:]) as Map)

    trackers.each { tracker ->
        def trackerKey = "${tracker.deviceKey}:${tracker.attribute}"
        def trackerStats = cloneCorrelationTrackerStats((dailyStats[trackerKey] ?: [:]) as Map)
        def trackerChanges = ((changeHistory[trackerKey] ?: []) as List).collect { it as Long }.sort()
        trackerStats.ghostAppearances = episodeStarts.size()
        trackerStats.ghostAppearancesNearAnyChange = episodeStarts.count { episodeStart ->
            trackerChanges.any { changeTs ->
                changeTs <= episodeStart && (episodeStart - changeTs) <= changeWindowMs
            }
        }
        dailyStats[trackerKey] = trackerStats
    }

    state.correlationDaily[devKey] = dailyStats
}

private void clearDeviceStats(String devKey) {
    state.dailyPoints?.remove(devKey)
    state.dailyOutOfBoundsPoints?.remove(devKey)
    state.ignoredOutsideWindowPoints?.remove(devKey)
    state.lastIgnoredOutsideWindowAt?.remove(devKey)
    state.stabilityData?.remove(devKey)
    state.archivedGhosts?.remove(devKey)
    state.archivedGhostVisibility?.remove(devKey)
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
    state.correlationEpisodeState?.remove(devKey)
    state.correlationChangeHistory?.remove(devKey)
    state.deviceActiveDayHistory?.remove(devKey)
    state.deviceActiveToday?.remove(devKey)

    if (state.recommendation?.deviceId == devKey) {
        state.recommendation = [message: "Recommendation cleared because that device's statistics were reset."]
    }
}

private void clearPendingPoints(String devKey) {
    state.dailyPoints?.remove(devKey)
    state.dailyOutOfBoundsPoints?.remove(devKey)
    state.correlationGhostPresence?.remove(devKey)
    state.correlationEpisodeState?.remove(devKey)
}

private void clearTrackedGhost(String devKey, Integer displayIndex) {
    if (!devKey || displayIndex == null || displayIndex < 1) {
        return
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return
    }

    def displayGhosts = getTrackedGhostsForDisplay(devKey)
    def selectedGhost = (displayGhosts && displayGhosts.size() >= displayIndex) ? (displayGhosts[displayIndex - 1] as Map) : null
    if (!selectedGhost) {
        return
    }

    def currentGhost = getCurrentClusterForGhost(devKey, selectedGhost)
    clearStoredPointsForGhost(dev, selectedGhost, currentGhost)

    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).findAll { cluster ->
        distance3D((cluster?.center ?: [:]) as Map, (selectedGhost?.center ?: [:]) as Map) > 5.0d
    }.collect { cloneCluster(it as Map) }
    state.stabilityData[devKey] = stableClusters

    rebuildProcessedSnapshotForDevice(dev)
    refreshStoredGhostSnapshotsForDevice(dev.id)

    if (state.recommendation?.deviceId == devKey) {
        refreshRecommendation()
    }
}

private void clearStoredPointsForGhost(dev, Map selectedGhost, Map currentGhost = null) {
    def devKey = deviceKey(dev?.id)
    if (!devKey || !selectedGhost) {
        return
    }

    def pointKeys = buildGhostPointKeySet(selectedGhost, currentGhost)
    def boundsCandidates = buildGhostBoundsCandidates(selectedGhost, currentGhost)

    state.dailyPoints[devKey] = removeGhostAssociatedPoints((state.dailyPoints[devKey] ?: []) as List, pointKeys, boundsCandidates)
    state.lastPointsSnapshot[devKey] = removeGhostAssociatedPoints((state.lastPointsSnapshot[devKey] ?: []) as List, pointKeys, boundsCandidates)
    state.dailyOutOfBoundsPoints[devKey] = removeGhostAssociatedPoints((state.dailyOutOfBoundsPoints[devKey] ?: []) as List, pointKeys, boundsCandidates)
    state.lastOutOfBoundsSnapshot[devKey] = removeGhostAssociatedPoints((state.lastOutOfBoundsSnapshot[devKey] ?: []) as List, pointKeys, boundsCandidates)
}

private Set buildGhostPointKeySet(Map selectedGhost, Map currentGhost = null) {
    def keys = [] as Set
    [selectedGhost, currentGhost].findAll { it != null }.each { ghost ->
        ((ghost.points ?: []) as List).each { point ->
            def pointKey = pointIdentityKey(point as Map)
            if (pointKey) {
                keys << pointKey
            }
        }
    }
    keys
}

private List buildGhostBoundsCandidates(Map selectedGhost, Map currentGhost = null) {
    def candidates = []
    [selectedGhost?.bounds, currentGhost?.bounds, currentGhost?.escapingBounds].findAll { isValidBounds(it as Map) }.each { bounds ->
        candidates << cloneBounds(bounds as Map)
    }
    candidates
}

private List removeGhostAssociatedPoints(List points, Set pointKeys, List boundsCandidates) {
    ((points ?: []) as List).findAll { point ->
        !pointMatchesClearedGhost(point as Map, pointKeys, boundsCandidates)
    }.collect { clonePoint(it as Map) }
}

private boolean pointMatchesClearedGhost(Map point, Set pointKeys, List boundsCandidates) {
    if (!point) {
        return false
    }

    def pointKey = pointIdentityKey(point)
    if (pointKey && pointKeys?.contains(pointKey)) {
        return true
    }

    (boundsCandidates ?: []).any { bounds ->
        isValidBounds(bounds as Map) &&
                isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, bounds as Map)
    }
}

private String pointIdentityKey(Map point) {
    if (!point) {
        return null
    }
    "${point.x}:${point.y}:${point.z}:${point.ts}:${point.ghostEligible}:${point.ghostIgnoreReason}"
}

private void rebuildProcessedSnapshotForDevice(dev) {
    def devKey = deviceKey(dev?.id)
    if (!devKey) {
        return
    }

    def rawPoints = getSnapshotPoints(dev.id)
    def rawOutOfBoundsPoints = getSnapshotOutOfBoundsPoints(dev.id)
    def filteredPoints = filterPointsForGhostDetection(rawPoints, dev)
    def snapshotClusters = detectClusters(filteredPoints)
    def counts = getGhostCounts(devKey, snapshotClusters)

    state.lastClustersSnapshot[devKey] = (snapshotClusters ?: []).collect { cluster ->
        snapshotCluster(enrichClusterWithGhostSnapshotData(devKey, cluster as Map, dev, cluster as Map, rawPoints, rawOutOfBoundsPoints))
    }
    state.dailySummary[devKey] = counts + [
            pointCount: filteredPoints.size(),
            unclusteredPointCount: calculateUnclusteredPointCount(filteredPoints, snapshotClusters),
            outOfBoundsPointCount: ((rawOutOfBoundsPoints ?: []) as List).size()
    ]
}

private void expandTrackedGhost(String devKey, Integer displayIndex) {
    if (!devKey || displayIndex == null || displayIndex < 1) {
        return
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return
    }

    def displayGhosts = getTrackedGhostsForDisplay(dev.id)
    def selectedGhost = (displayGhosts && displayGhosts.size() >= displayIndex) ? (displayGhosts[displayIndex - 1] as Map) : null
    if (!selectedGhost) {
        return
    }

    def rememberedArea = getRememberedAreaForGhost(devKey, selectedGhost)
    if (!rememberedArea?.bounds || !isValidBounds(selectedGhost?.bounds)) {
        return
    }

    def expandedBounds = getAllowedExpansionForLeakingGhost(dev, rememberedArea.bounds, selectedGhost.bounds)
    if (!isValidBounds(expandedBounds) || sameBounds(expandedBounds, rememberedArea.bounds)) {
        return
    }

    def appliedZoneSpecs = applyZonesToDevice(dev, [[areaIndex: rememberedArea.areaIndex, bounds: expandedBounds, cluster: selectedGhost]], "manual targeted ghost expansion")
    if (appliedZoneSpecs) {
        rememberInterferenceArea(devKey, rememberedArea.areaIndex as Integer, expandedBounds, rememberedArea.source ?: "manual", rememberedArea.dynamic as Map)
        syncClusterTargetsForDevice(devKey)
        refreshStoredGhostSnapshotsForDevice(dev.id)
    }
}

private void clearCorrelationEvents(String devKey) {
    state.correlationDaily?.remove(devKey)
    state.correlationHistory?.remove(devKey)
    state.correlationChangeHistory?.remove(devKey)
    state.correlationGhostPresence?.remove(devKey)
    state.correlationEpisodeState?.remove(devKey)
}

private void setArchivedGhostVisibility(String devKey, boolean visible) {
    state.archivedGhostVisibility = ((state.archivedGhostVisibility ?: [:]) as Map) + [(devKey): visible]
}

private boolean showArchivedGhostsEnabled(String devKey) {
    (((state.archivedGhostVisibility ?: [:])[devKey]) == true)
}

private void archiveExpiredGhosts(String devKey, List retiringClusters) {
    if (!devKey || !retiringClusters) {
        return
    }

    def archivedGhosts = (((state.archivedGhosts ?: [:])[devKey] ?: []) as List).collect { cloneArchivedGhost(it as Map) }
    (retiringClusters ?: []).each { cluster ->
        def archivedGhost = buildArchivedGhost(devKey, cluster as Map)
        def existing = archivedGhosts.find { current ->
            archivedGhostMatches(current as Map, archivedGhost)
        }
        if (existing) {
            mergeArchivedGhost(existing, archivedGhost)
        } else {
            archivedGhosts << archivedGhost
        }
    }
    state.archivedGhosts[devKey] = archivedGhosts.sort { a, b ->
        ((b?.lastSeenDay ?: 0) as Integer) <=> ((a?.lastSeenDay ?: 0) as Integer)
    }
}

private Map buildArchivedGhost(String devKey, Map cluster) {
    def seenHistory = ((cluster?.seenHistory ?: []) as List).collect { it as Integer }.sort()
    def archivedBounds = isValidBounds(cluster?.bounds) ? integerBounds(cluster.bounds) : cloneBounds(cluster?.bounds)
    def rememberedArea = getRememberedAreaForGhost(devKey, cluster)
    def archivedState = cluster?.maxStateReached ?: cluster?.ghostStateSnapshot ?: (cluster?.targetAreaIndex != null ? "Targeted" : (isClusterPersistent(cluster) ? "Persistent" : "Detected"))
    [
            center: clonePoint(cluster?.center ?: boundsCenter(archivedBounds)),
            bounds: cloneBounds(archivedBounds),
            canonicalBounds: cloneBounds(archivedBounds),
            firstSeenDay: seenHistory ? seenHistory.first() : (cluster?.lastSeen ?: state.dayIndex ?: 0),
            lastSeenDay: cluster?.lastSeen ?: state.dayIndex ?: 0,
            maxStateReached: archivedState,
            daysSeen: cluster?.daysSeen ?: seenHistory.size(),
            episodes: countGhostEpisodesForCluster(devKey, cluster),
            lastTargetedAreaIndex: cluster?.targetAreaIndex != null ? (cluster.targetAreaIndex as Integer) : rememberedArea?.areaIndex,
            lastTargetedAreaBounds: cloneBounds(cluster?.targetedAreaBounds ?: cluster?.appliedBounds ?: rememberedArea?.bounds),
            targetSource: cluster?.targetSource ?: rememberedArea?.source,
            archivedAtDay: state.dayIndex ?: 0
    ]
}

private boolean archivedGhostMatches(Map existing, Map candidate) {
    if (!existing || !candidate) {
        return false
    }

    if (isValidBounds(existing.bounds) && isValidBounds(candidate.bounds) && boundsOverlap(existing.bounds, candidate.bounds)) {
        return true
    }

    distance3D((existing.center ?: [:]) as Map, (candidate.center ?: [:]) as Map) <= safeClusterRadius()
}

private void mergeArchivedGhost(Map existing, Map candidate) {
    if (!existing || !candidate) {
        return
    }

    existing.center = clonePoint(candidate.center ?: existing.center)
    existing.bounds = cloneBounds(candidate.bounds ?: existing.bounds)
    existing.canonicalBounds = cloneBounds(candidate.canonicalBounds ?: candidate.bounds ?: existing.canonicalBounds)
    existing.firstSeenDay = Math.min((existing.firstSeenDay ?: candidate.firstSeenDay ?: 0) as Integer, (candidate.firstSeenDay ?: existing.firstSeenDay ?: 0) as Integer)
    existing.lastSeenDay = Math.max((existing.lastSeenDay ?: 0) as Integer, (candidate.lastSeenDay ?: 0) as Integer)
    existing.maxStateReached = promoteGhostState(existing.maxStateReached as String, candidate.maxStateReached as String)
    existing.daysSeen = Math.max((existing.daysSeen ?: 0) as Integer, (candidate.daysSeen ?: 0) as Integer)
    existing.episodes = Math.max((existing.episodes ?: 0) as Integer, (candidate.episodes ?: 0) as Integer)
    if (candidate.lastTargetedAreaIndex != null) {
        existing.lastTargetedAreaIndex = candidate.lastTargetedAreaIndex
        existing.lastTargetedAreaBounds = cloneBounds(candidate.lastTargetedAreaBounds)
        existing.targetSource = candidate.targetSource ?: existing.targetSource
    }
    existing.archivedAtDay = Math.max((existing.archivedAtDay ?: 0) as Integer, (candidate.archivedAtDay ?: 0) as Integer)
}

private List getArchivedGhosts(deviceId) {
    ((state.archivedGhosts?.get(deviceKey(deviceId)) ?: []) as List).collect { cloneArchivedGhost(it as Map) }
}

private Integer getArchivedGhostCount(deviceId) {
    getArchivedGhosts(deviceId).size()
}

private List getArchivedGhostsForGraph(deviceId) {
    getArchivedGhosts(deviceId).collect { archivedGhost ->
        archivedGhostToDisplayCluster(archivedGhost as Map)
    }
}

private Map archivedGhostToDisplayCluster(Map archivedGhost) {
    [
            center: clonePoint(archivedGhost?.center ?: boundsCenter(archivedGhost?.bounds)),
            bounds: cloneBounds(archivedGhost?.bounds ?: archivedGhost?.canonicalBounds),
            radius: 0.0d,
            density: 0,
            points: [],
            daysSeen: archivedGhost?.daysSeen ?: 0,
            lastSeen: archivedGhost?.lastSeenDay ?: 0,
            seenHistory: [],
            activeDayHistory: [],
            consecutiveSeen: 0,
            absentStreak: 0,
            lastMatchedDay: archivedGhost?.lastSeenDay ?: 0,
            targetAreaIndex: archivedGhost?.lastTargetedAreaIndex,
            targetSource: archivedGhost?.targetSource,
            targetedAreaBounds: cloneBounds(archivedGhost?.lastTargetedAreaBounds),
            ghostStateSnapshot: archivedGhost?.maxStateReached,
            archivedGhost: true,
            archivedGhostSummary: cloneArchivedGhost(archivedGhost)
    ]
}

private Map cloneArchivedGhost(Map archivedGhost) {
    [
            center: clonePoint(archivedGhost?.center),
            bounds: cloneBounds(archivedGhost?.bounds),
            canonicalBounds: cloneBounds(archivedGhost?.canonicalBounds),
            firstSeenDay: archivedGhost?.firstSeenDay ?: 0,
            lastSeenDay: archivedGhost?.lastSeenDay ?: 0,
            maxStateReached: archivedGhost?.maxStateReached,
            daysSeen: archivedGhost?.daysSeen ?: 0,
            episodes: archivedGhost?.episodes ?: 0,
            lastTargetedAreaIndex: archivedGhost?.lastTargetedAreaIndex,
            lastTargetedAreaBounds: cloneBounds(archivedGhost?.lastTargetedAreaBounds),
            targetSource: archivedGhost?.targetSource,
            archivedAtDay: archivedGhost?.archivedAtDay ?: 0
    ]
}

private Integer ghostStateRank(String stateName) {
    switch (stateName) {
        case "Busted":
            return 5
        case "Escaping":
            return 4
        case "Targeted":
            return 3
        case "Persistent":
            return 2
        case "Detected":
            return 1
        default:
            return 0
    }
}

private String promoteGhostState(String priorState, String candidateState) {
    ghostStateRank(candidateState) >= ghostStateRank(priorState) ? candidateState : priorState
}

private void updateClusterMaxStatesForDevice(deviceId) {
    def devKey = deviceKey(deviceId)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it as Map) }
    if (!stableClusters) {
        return
    }

    stableClusters.each { cluster ->
        def currentState = cluster?.ghostStateSnapshot ?: (cluster?.targetAreaIndex != null ? "Targeted" : (isClusterPersistent(cluster as Map) ? "Persistent" : "Detected"))
        cluster.maxStateReached = promoteGhostState(cluster.maxStateReached as String, currentState)
    }
    state.stabilityData[devKey] = stableClusters
}

private Map boundsCenter(Map bounds) {
    if (!isValidBounds(bounds)) {
        return null
    }
    [
            x: (((bounds.xmin ?: 0.0d) + (bounds.xmax ?: 0.0d)) / 2.0d),
            y: (((bounds.ymin ?: 0.0d) + (bounds.ymax ?: 0.0d)) / 2.0d),
            z: (((bounds.zmin ?: 0.0d) + (bounds.zmax ?: 0.0d)) / 2.0d)
    ]
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
    if (summaryView == "Last Processed Day") {
        def summary = getSummaryForDevice(deviceId)
        return [
                "Detected": summary.detectedGhosts ?: 0,
                "Persistent": summary.persistentGhosts ?: 0,
                "Targeted": summary.targetedGhosts ?: 0,
                "Escaping": summary.escapingGhosts ?: 0,
                "Busted": summary.bustedGhosts ?: 0
        ]
    }

    def counts = getDisplayCounts(deviceId)
    [
            "Detected": counts.detectedGhosts ?: 0,
            "Persistent": counts.persistentGhosts ?: 0,
            "Targeted": counts.targetedGhosts ?: 0,
            "Escaping": counts.escapingGhosts ?: 0,
            "Busted": counts.bustedGhosts ?: 0
    ]
}

private Map getTrackingStats(deviceId, Map displayCounts, Map lastSummary, Map displayData = [:]) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    def devKey = deviceKey(deviceId)
    def outOfBounds = lastSummary.outOfBoundsPointCount ?: 0
    def leaking = displayCounts.leakingGhosts ?: 0
    def escapingPoints = getEscapingPointCount(deviceId)
    def pendingPointCount = (displayData?.pointsPendingProcessing ? ((displayData?.pendingPointCount ?: 0) as Integer) : 0) as Integer
    def ignoredOutsideWindow = ((state.ignoredOutsideWindowPoints ?: [:])[devKey] ?: 0) as Integer
    def lastIgnoredOutsideWindowAt = ((state.lastIgnoredOutsideWindowAt ?: [:])[devKey] ?: null)
    def alertText = "None"
    if (leaking > 0 && escapingPoints > 0) {
        alertText = "${leaking} ghost(s) need area expansion and ${escapingPoints} in-area occupancy point(s) remain."
    } else if (leaking > 0) {
        alertText = "${leaking} ghost(s) may need targeted area expansion."
    } else if (escapingPoints > 0) {
        alertText = "${escapingPoints} occupancy-contributing point(s) remain inside targeted areas."
    }
    [
            "Detection": getDeviceStatus(dev),
            "Tracking days": getActiveTrackingDaysForDevice(deviceId),
            "Points processed": "${lastSummary.pointCount ?: 0} (${outOfBounds} OOB)",
            "Pending processing": pendingPointCount,
            "Ghost alerts": alertText,
            "Ignored outside window": ignoredOutsideWindow,
            "Last outside-window point": lastIgnoredOutsideWindowAt ? formatTimeOfDay(lastIgnoredOutsideWindowAt as Long) : "None"
    ]
}

private Map getArchivedGhostSectionStats(deviceId, List visibleArchivedGhosts = null, boolean shownOnGraph = false) {
    def archivedGhosts = getArchivedGhosts(deviceId)
    def visibleGhosts = (visibleArchivedGhosts ?: []) as List
    if (!archivedGhosts) {
        return [
                "Archived ghosts": 0,
                "Status": "No archived ghosts for this device yet"
        ]
    }

    def sortedByLastSeen = archivedGhosts.sort { a, b ->
        ((b?.lastSeenDay ?: 0) as Integer) <=> ((a?.lastSeenDay ?: 0) as Integer)
    }
    def mostRecent = sortedByLastSeen ? (sortedByLastSeen.first() as Map) : null
    def firstArchived = archivedGhosts.min { ghost -> (ghost?.firstSeenDay ?: Integer.MAX_VALUE) as Integer } as Map
    def stateMix = archivedGhosts.collect { it?.maxStateReached ?: "Detected" }.countBy { it }.collect { key, value ->
        "${key} ${value}"
    }.join(", ")

    [
            "Archived ghosts": archivedGhosts.size(),
            "Most recent day": mostRecent?.lastSeenDay ?: 0,
            "First archived day": firstArchived?.firstSeenDay ?: 0,
            "States reached": stateMix ?: "None",
            "Shown on graph": shownOnGraph ? "Yes" : "No",
            "Cards shown": shownOnGraph ? visibleGhosts.size() : 0,
            "View hint": shownOnGraph ? "Archived ghost cards are shown below" : "Use the header button to show archived ghosts"
    ]
}

private Map getDisplayData(deviceId) {
    def devKey = deviceKey(deviceId)
    def displayLocks = (state.displayDataLocks ?: [:]) as Map
    def currentPoints = getPointsForDevice(deviceId)
    def lastPoints = getSnapshotPoints(deviceId)
    def currentOutOfBounds = getOutOfBoundsPointsForDevice(deviceId)
    def lastOutOfBounds = getSnapshotOutOfBoundsPoints(deviceId)
    def preferredPointSet = preferDisplayPointSet(currentPoints, lastPoints, currentOutOfBounds, lastOutOfBounds)
    def limitedPointSet = limitPointsForGraph(preferredPointSet.points, preferredPointSet.outOfBoundsPoints)
    def useLiveComputation = shouldUseLiveDisplayComputation(currentPoints, currentOutOfBounds)
    def snapshotClusters = getSnapshotClusters(deviceId)
    def totalPointCount = (((preferredPointSet.points ?: []) as List).size() + ((preferredPointSet.outOfBoundsPoints ?: []) as List).size()) as Integer
    def pendingPointCount = ((((currentPoints ?: []) as List).size() + ((currentOutOfBounds ?: []) as List).size())) as Integer

    if (displayLocks[devKey] == true) {
        return [
                points: limitedPointSet.points,
                outOfBoundsPoints: limitedPointSet.outOfBoundsPoints,
                pointSource: preferredPointSet.source,
                pointsPendingProcessing: preferredPointSet.pendingProcessing,
                totalPointCount: totalPointCount,
                pendingPointCount: pendingPointCount,
                currentClusters: useLiveComputation ? [] : snapshotClusters,
                historicalClusters: [],
                selectableClusters: useLiveComputation ? [] : buildSelectableClusters(snapshotClusters, getHistoricalClustersForSelection(deviceId)),
                archivedGhosts: [],
                archivedGhostCount: getArchivedGhostCount(deviceId),
                showArchivedGhosts: showArchivedGhostsEnabled(devKey)
        ]
    }

    try {
        state.displayDataLocks = displayLocks + [(devKey): true]
        def currentClusters = useLiveComputation ? getTodayClusters(deviceId) : snapshotClusters
        def historicalClusters = []
        if (showHistoricalGhostOverlaysEnabled()) {
            historicalClusters.addAll(getHistoricalClustersForDisplay(deviceId))
        }
        def archivedGhosts = showArchivedGhostsEnabled(devKey) ? getArchivedGhosts(deviceId) : []
        if (archivedGhosts) {
            historicalClusters.addAll(getArchivedGhostsForGraph(deviceId))
        }
        def selectableHistoricalClusters = getHistoricalClustersForSelection(deviceId)

        return [
                points: limitedPointSet.points,
                outOfBoundsPoints: limitedPointSet.outOfBoundsPoints,
                pointSource: preferredPointSet.source,
                pointsPendingProcessing: preferredPointSet.pendingProcessing,
                totalPointCount: totalPointCount,
                pendingPointCount: pendingPointCount,
                currentClusters: currentClusters,
                historicalClusters: historicalClusters,
                selectableClusters: buildSelectableClusters(currentClusters, selectableHistoricalClusters),
                archivedGhosts: archivedGhosts,
                archivedGhostCount: getArchivedGhostCount(deviceId),
                showArchivedGhosts: showArchivedGhostsEnabled(devKey)
        ]
    } finally {
        def updatedLocks = ((state.displayDataLocks ?: [:]) as Map).findAll { key, value ->
            key != devKey
        }
        state.displayDataLocks = updatedLocks
    }
}

private Map preferDisplayPointSet(List currentPoints, List lastPoints, List currentOutOfBounds, List lastOutOfBounds) {
    def currentTotal = ((currentPoints ?: []) as List).size() + ((currentOutOfBounds ?: []) as List).size()
    def snapshotTotal = ((lastPoints ?: []) as List).size() + ((lastOutOfBounds ?: []) as List).size()
    if (!shouldUseLiveDisplayComputation(currentPoints, currentOutOfBounds) && snapshotTotal > 0) {
        return [
                points: (lastPoints ?: []) as List,
                outOfBoundsPoints: (lastOutOfBounds ?: []) as List,
                source: "processed-snapshot",
                pendingProcessing: currentTotal > 0
        ]
    }
    if (snapshotTotal > currentTotal) {
        return [
                points: (lastPoints ?: []) as List,
                outOfBoundsPoints: (lastOutOfBounds ?: []) as List,
                source: "processed-snapshot",
                pendingProcessing: false
        ]
    }
    [
            points: (currentPoints ?: lastPoints ?: []) as List,
            outOfBoundsPoints: (currentOutOfBounds ?: lastOutOfBounds ?: []) as List,
            source: currentTotal > 0 ? "live" : "processed-snapshot",
            pendingProcessing: currentTotal > 0
    ]
}

private boolean shouldUseLiveDisplayComputation(List currentPoints, List currentOutOfBounds) {
    def currentTotal = ((currentPoints ?: []) as List).size() + ((currentOutOfBounds ?: []) as List).size()
    currentTotal <= safeMaxGraphPointsPerDevice()
}

private List getSnapshotClusters(deviceId) {
    ((state.lastClustersSnapshot?.get(deviceKey(deviceId)) ?: []) as List).collect { snapshotCluster(it) }
}

private void refreshStoredGhostSnapshotsForDevice(deviceId, List currentClustersOverride = null, List pointsOverride = null, List outOfBoundsOverride = null) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    if (!dev) {
        return
    }

    def devKey = deviceKey(deviceId)
    def currentClusters = currentClustersOverride ?: getTodayClusters(deviceId)
    def rawPoints = pointsOverride ?: getPointsForDevice(deviceId)
    def rawOutOfBoundsPoints = outOfBoundsOverride ?: getOutOfBoundsPointsForDevice(deviceId)
    def stableClusters = ((state.stabilityData[devKey] ?: []) as List).collect { cloneCluster(it) }

    stableClusters.each { stableCluster ->
        def liveCluster = findBestMatch(stableCluster, currentClusters ?: [])
        def snapshotData = buildGhostSnapshotData(devKey, stableCluster, dev, liveCluster, rawPoints, rawOutOfBoundsPoints)
        copyGhostSnapshotFields(stableCluster, snapshotData)
    }
    state.stabilityData[devKey] = stableClusters

    state.lastClustersSnapshot[devKey] = ((currentClusters ?: []) as List).collect { cluster ->
        snapshotCluster(enrichClusterWithGhostSnapshotData(devKey, cluster, dev, cluster, rawPoints, rawOutOfBoundsPoints))
    }
}

private Integer getEscapingPointCount(deviceId) {
    def displayData = getDisplayData(deviceId)
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    ((pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List).size()
}

private Integer getEscapingTargetCountForDeviceKey(String devKey) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == devKey }
    if (!dev) {
        return 0
    }

    def displayData = getDisplayData(dev.id)
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    def escapingPoints = (pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List
    if (!escapingPoints) {
        return 0
    }

    getRememberedInterferenceAreas(devKey).findAll { area ->
        escapingPoints.any { point ->
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
            escapingGhosts: 0,
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
    def displayData = getDisplayData(deviceId)
    def liveClusters = (displayData?.currentClusters ?: []) as List
    def summary = getSummaryForDevice(deviceId)
    def currentCounts = getDisplayGhostCounts(devKey, liveClusters)
    def totalPoints = ((displayData?.totalPointCount ?: 0) as Integer)
    currentCounts.ghostsToday = (totalPoints > 0 || liveClusters) ? (liveClusters?.size() ?: 0) : (summary.ghostsToday ?: 0)
    currentCounts
}

private Map getNetworkSummaryStats(List sortedDevices, Integer aggregatePersistent, Integer aggregateBusted) {
    def detected = sortedDevices.collect { getDisplayCounts(it.id).detectedGhosts ?: 0 }.sum() ?: 0
    def targeted = sortedDevices.collect { getDisplayCounts(it.id).targetedGhosts ?: 0 }.sum() ?: 0
    def escaping = sortedDevices.collect { getDisplayCounts(it.id).escapingGhosts ?: 0 }.sum() ?: 0
    def leaking = sortedDevices.collect { getDisplayCounts(it.id).leakingGhosts ?: 0 }.sum() ?: 0
    def escapingPoints = (sortedDevices ?: []).collect { getEscapingPointCount(it.id) ?: 0 }.sum() ?: 0

    def stats = [
            "Detected": detected,
            "Persistent": aggregatePersistent,
            "Targeted": targeted,
            "Escaping": escaping,
            "Busted": aggregateBusted
    ]

    def alertHtml = buildGhostAlertsHtml(leaking as Integer, escapingPoints as Integer)
    if (alertHtml) {
        stats._html = alertHtml
    }
    stats
}

private String buildGhostAlertsHtml(Integer escapingBeyondAreaCount, Integer escapingInsidePointCount) {
    def alertParts = []
    if ((escapingBeyondAreaCount ?: 0) > 0) {
        alertParts << "${escapingBeyondAreaCount} ghost(s) escaping beyond their targeted interference areas and may require interference area expansion."
    }
    if ((escapingInsidePointCount ?: 0) > 0) {
        alertParts << "${escapingInsidePointCount} occupancy-contributing point(s) are still appearing inside targeted interference areas."
    }
    alertParts ? renderNoteCard("Ghost Alerts", alertParts.join(" ")) : null
}

private String buildInterferenceAreaControlNotes(deviceId, List selectableClusters) {
    def devKey = deviceKey(deviceId)
    def notes = []
    def activeGhosts = (getTrackedGhostsForDisplay(deviceId) ?: []) as List
    getRememberedInterferenceAreas(devKey).each { area ->
        if (isInactiveInterferenceAreaBounds(area.bounds as Map)) {
            notes << "Area ${area.areaIndex} is not currently configured on the device."
            return
        }
        def targetedCluster = activeGhosts.find { cluster ->
            (cluster?.targetAreaIndex as Integer) == (area.areaIndex as Integer)
        }
        if (targetedCluster?.bounds && sameBounds(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            notes << "Area ${area.areaIndex} is currently configured to target Ghost ${idx}."
            return
        }
        if (targetedCluster?.bounds && isBoundsWithinBounds(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            notes << "Area ${area.areaIndex} is currently configured to target Ghost ${idx}."
            return
        }
        if (targetedCluster?.bounds && boundsOverlap(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            notes << "Area ${area.areaIndex} is currently configured to target Ghost ${idx}, but Ghost ${idx} has expanded beyond the currently configured area. Reapply area assignments to update Area ${area.areaIndex} to Ghost ${idx}'s current bounds."
            return
        }

        def exactMatch = activeGhosts.find { cluster ->
            cluster?.bounds && sameBounds(cluster.bounds, area.bounds)
        }
        if (exactMatch) {
            def idx = activeGhosts.indexOf(exactMatch) + 1
            notes << "Area ${area.areaIndex} currently matches Ghost ${idx}."
            return
        }

        def overlappingCluster = activeGhosts.find { cluster ->
            cluster?.bounds && boundsOverlap(cluster.bounds, area.bounds)
        }
        if (overlappingCluster) {
            def idx = activeGhosts.indexOf(overlappingCluster) + 1
            if (isBoundsWithinBounds(overlappingCluster.bounds, area.bounds)) {
                notes << "Area ${area.areaIndex} currently contains Ghost ${idx}."
            } else {
                notes << "Area ${area.areaIndex} may be using older bounds than Ghost ${idx}. Reapply area assignments if you want Area ${area.areaIndex} updated to the current bounds of Ghost ${idx}."
            }
        } else {
            notes << "Area ${area.areaIndex} is configured on the device, but no current ghost exactly matches its current bounds."
        }
    }
    notes ? notes.join("<br>") : null
}

private List getSnapshotPoints(deviceId) {
    ((state.lastPointsSnapshot?.get(deviceKey(deviceId)) ?: []) as List).collect { clonePoint(it) }
}

private List getSnapshotOutOfBoundsPoints(deviceId) {
    ((state.lastOutOfBoundsSnapshot?.get(deviceKey(deviceId)) ?: []) as List).collect { clonePoint(it) }
}

private List getHistoricalClustersForDisplay(deviceId) {
    def devKey = deviceKey(deviceId)
    filterHistoricalDisplayClusters(devKey, getHistoricalClustersForSelection(deviceId))
}

private List getHistoricalClustersForSelection(deviceId) {
    def devKey = deviceKey(deviceId)
    def snapshotClusters = ((state.lastClustersSnapshot?.get(devKey) ?: []) as List).collect { snapshotCluster(it) }
    def stableClusters = getStableClusters(deviceId).collect { snapshotCluster(it) }
    dedupeClusters(snapshotClusters + stableClusters)
}

private List filterHistoricalDisplayClusters(String devKey, List clusters) {
    (clusters ?: []).findAll { cluster ->
        shouldDisplayHistoricalGhost(devKey, cluster as Map)
    }.collect { snapshotCluster(it as Map) }
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
    def matchedCluster = (selectableClusters ?: []).find { cluster ->
        (cluster?.targetAreaIndex as Integer) == areaIndex
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
        } else {
            if (duplicate.targetAreaIndex == null && cluster.targetAreaIndex != null) {
                duplicate.targetAreaIndex = cluster.targetAreaIndex
                duplicate.targetSource = cluster.targetSource ?: duplicate.targetSource
                duplicate.appliedBounds = cloneBounds(cluster.appliedBounds)
            }
        }
    }
    deduped
}

private Integer calculateUnclusteredPointCount(List points, List clusters) {
    Math.max(0, totalPointWeight(points) - ((clusters ?: []).collect { totalPointWeight(it.points) }.sum() ?: 0))
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
            xmin: Math.max(toDouble(a.xmin) ?: 0.0d, toDouble(b.xmin) ?: 0.0d),
            xmax: Math.min(toDouble(a.xmax) ?: 0.0d, toDouble(b.xmax) ?: 0.0d),
            ymin: Math.max(toDouble(a.ymin) ?: 0.0d, toDouble(b.ymin) ?: 0.0d),
            ymax: Math.min(toDouble(a.ymax) ?: 0.0d, toDouble(b.ymax) ?: 0.0d),
            zmin: Math.max(toDouble(a.zmin) ?: 0.0d, toDouble(b.zmin) ?: 0.0d),
            zmax: Math.min(toDouble(a.zmax) ?: 0.0d, toDouble(b.zmax) ?: 0.0d)
    ]

    isValidBounds(intersection) ? intersection : null
}

private Map unionBounds(Map a, Map b) {
    if (!isValidBounds(a) || !isValidBounds(b)) {
        return null
    }
    [
            xmin: Math.min(toDouble(a.xmin) ?: 0.0d, toDouble(b.xmin) ?: 0.0d),
            xmax: Math.max(toDouble(a.xmax) ?: 0.0d, toDouble(b.xmax) ?: 0.0d),
            ymin: Math.min(toDouble(a.ymin) ?: 0.0d, toDouble(b.ymin) ?: 0.0d),
            ymax: Math.max(toDouble(a.ymax) ?: 0.0d, toDouble(b.ymax) ?: 0.0d),
            zmin: Math.min(toDouble(a.zmin) ?: 0.0d, toDouble(b.zmin) ?: 0.0d),
            zmax: Math.max(toDouble(a.zmax) ?: 0.0d, toDouble(b.zmax) ?: 0.0d)
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
    if (!devKey || !cluster) {
        return null
    }
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    def explicitIndex = cluster.targetAreaIndex != null ? (cluster.targetAreaIndex as Integer) : null
    if (explicitIndex != null) {
        def indexedArea = rememberedAreas.find { (it.areaIndex as Integer) == explicitIndex }
        if (indexedArea) {
            return indexedArea
        }
    }

    if (cluster?.appliedBounds && isValidBounds(cluster.appliedBounds as Map)) {
        def appliedMatch = rememberedAreas.find { area ->
            sameBounds(cluster.appliedBounds as Map, area.bounds)
        }
        if (appliedMatch) {
            return appliedMatch
        }
    }

    if (!cluster?.bounds) {
        return null
    }

    rememberedAreas.find { area ->
        boundsOverlap(cluster.bounds, area.bounds)
    }
}

private boolean isGhostTargeted(String devKey, Map cluster) {
    isClusterTargeted(cluster) || getRememberedAreaForGhost(devKey, cluster) != null
}

private boolean isGhostHistoricallyBusted(String devKey, Map cluster) {
    if (!isGhostTargeted(devKey, cluster)) {
        return false
    }

    def activeIssue = isGhostLeaking(devKey, cluster) || isGhostEscaping(devKey, cluster)
    def requiredAbsentDays = activeIssue ? Integer.MAX_VALUE : safeLeakRecoveryDays()
    ((cluster?.absentStreak ?: 0) >= requiredAbsentDays)
}

private boolean isGhostBusted(String devKey, Map cluster) {
    if (!isGhostTargeted(devKey, cluster)) {
        return false
    }

    if (isGhostHistoricallyBusted(devKey, cluster)) {
        return true
    }

    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    def currentCluster = getCurrentClusterForGhost(devKey, cluster)
    if (!targetArea?.bounds || !currentCluster?.bounds || !currentCluster?.points) {
        return false
    }

    if (!isBoundsWithinBounds(currentCluster.bounds, targetArea.bounds)) {
        return false
    }

    currentCluster.points.every { point ->
        isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, targetArea.bounds) &&
                !pointContributesToOccupancyInsideInterferenceArea(devKey, point)
    }
}

private boolean isGhostEscaping(String devKey, Map cluster) {
    getEscapingGhostMode(devKey, cluster) != null
}

private String getEscapingGhostMode(String devKey, Map cluster) {
    if (cluster?.escapingModeSnapshot && !hasLiveGhostEvidence(devKey)) {
        return cluster.escapingModeSnapshot as String
    }
    if (!devKey || !cluster) {
        return null
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    if (!dev || !targetArea?.bounds) {
        return null
    }

    def displayData = getDisplayData(dev.id)
    def pointBuckets = classifyPlotPoints(displayData.points, displayData.outOfBoundsPoints, dev)
    def escapingInside = ((pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List).any { point ->
        isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, targetArea.bounds)
    }
    def leakingAdjacent = isGhostLeaking(devKey, cluster)
    if (escapingInside && leakingAdjacent) {
        return "both"
    }
    if (escapingInside) {
        return "inside-area"
    }
    if (leakingAdjacent) {
        return "adjacent"
    }
    null
}

private boolean isGhostLeaking(String devKey, Map cluster) {
    if (!devKey || !cluster || !isGhostTargeted(devKey, cluster)) {
        return false
    }

    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    def activeCluster = getCurrentClusterForGhost(devKey, cluster)
    def clusterBounds = activeCluster?.bounds
    if (!targetArea?.bounds || !isValidBounds(clusterBounds)) {
        return false
    }

    boundsOverlap(clusterBounds, targetArea.bounds) && !isBoundsWithinBounds(clusterBounds, targetArea.bounds)
}

private boolean hasLiveGhostEvidence(String devKey) {
    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return false
    }
    ((getPointsForDevice(dev.id) ?: []) as List) || ((getTodayClusters(dev.id) ?: []) as List)
}

private boolean pointContributesToOccupancyInsideInterferenceArea(String devKey, Map point) {
    if (!devKey || !point) {
        return false
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return false
    }

    def displayData = getDisplayData(dev.id)
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    def deviceBounds = getDeviceBounds(dev)
    def validDeviceBounds = isValidBounds(deviceBounds)
    def allDisplayPoints = []
    allDisplayPoints.addAll(displayData.points ?: [])
    allDisplayPoints.addAll(displayData.outOfBoundsPoints ?: [])
    shouldPlotInterferenceAreaPointAsRed(point, allDisplayPoints, rememberedAreas, deviceBounds, validDeviceBounds, getOccupancyAssociationWindowMs(dev))
}

private Map getCurrentClusterForGhost(String devKey, Map cluster) {
    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev || !cluster) {
        return null
    }

    def displayData = getDisplayData(dev.id)
    def currentClusters = ((displayData.currentClusters ?: []) as List)
    def targetArea = getRememberedAreaForGhost(devKey, cluster)

    if (targetArea?.bounds && currentClusters) {
        def targetedCandidates = currentClusters.findAll { currentCluster ->
            currentCluster?.bounds && boundsOverlap(currentCluster.bounds, targetArea.bounds)
        }
        def targetedMatch = targetedCandidates ? targetedCandidates.min { candidate ->
            distance3D((candidate.center ?: [:]) as Map, (cluster.center ?: [:]) as Map)
        } : null
        if (targetedMatch) {
            return targetedMatch
        }
    }

    def bestCurrent = findBestMatch(cluster, currentClusters as List)
    if (bestCurrent) {
        return bestCurrent
    }

    if (cluster?.bounds) {
        return [
                bounds: cloneBounds(cluster.bounds),
                center: clonePoint(cluster.center),
                points: ((cluster.points ?: []) as List).collect { clonePoint(it) },
                escapingBounds: cloneBounds(cluster.escapingBounds),
                targetedAreaBounds: cloneBounds(cluster.targetedAreaBounds)
        ]
    }
    targetArea?.bounds ? [bounds: cloneBounds(targetArea.bounds), center: clonePoint(cluster.center), points: []] : null
}

private boolean hasProblemInterferencePointsForGhost(String devKey, Map cluster) {
    isGhostEscaping(devKey, cluster) || isGhostLeaking(devKey, cluster)
}

private Map getGhostAreaExpansion(String devKey, Map cluster) {
    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    def clusterBounds = getCurrentClusterForGhost(devKey, cluster)?.bounds ?: cluster?.bounds
    if (!targetArea?.bounds || !isValidBounds(clusterBounds)) {
        return [:]
    }

    [
            xmin: Math.max(0.0d, (targetArea.bounds.xmin ?: 0.0d) - (clusterBounds.xmin ?: 0.0d)),
            xmax: Math.max(0.0d, (clusterBounds.xmax ?: 0.0d) - (targetArea.bounds.xmax ?: 0.0d)),
            ymin: Math.max(0.0d, (targetArea.bounds.ymin ?: 0.0d) - (clusterBounds.ymin ?: 0.0d)),
            ymax: Math.max(0.0d, (clusterBounds.ymax ?: 0.0d) - (targetArea.bounds.ymax ?: 0.0d)),
            zmin: Math.max(0.0d, (targetArea.bounds.zmin ?: 0.0d) - (clusterBounds.zmin ?: 0.0d)),
            zmax: Math.max(0.0d, (clusterBounds.zmax ?: 0.0d) - (targetArea.bounds.zmax ?: 0.0d))
    ]
}

private String describeGhostAreaExpansion(String devKey, Map cluster) {
    def expansion = getGhostAreaExpansion(devKey, cluster)
    if (!expansion || expansion.values().every { (it ?: 0.0d) <= 0.0d }) {
        return "Within target area"
    }

    def parts = []
    if ((expansion.xmin ?: 0.0d) > 0.0d) {
        parts << "X min -${round2(expansion.xmin)}"
    }
    if ((expansion.xmax ?: 0.0d) > 0.0d) {
        parts << "X max +${round2(expansion.xmax)}"
    }
    if ((expansion.ymin ?: 0.0d) > 0.0d) {
        parts << "Y min -${round2(expansion.ymin)}"
    }
    if ((expansion.ymax ?: 0.0d) > 0.0d) {
        parts << "Y max +${round2(expansion.ymax)}"
    }
    if ((expansion.zmin ?: 0.0d) > 0.0d) {
        parts << "Z min -${round2(expansion.zmin)}"
    }
    if ((expansion.zmax ?: 0.0d) > 0.0d) {
        parts << "Z max +${round2(expansion.zmax)}"
    }
    parts.join(", ")
}

private Map plotStateStyle(Map cluster, String devKey, List historicalClusters, List currentClusters = [], List rememberedAreas = [], boolean historicalOnly = false) {
    def matched = (historicalClusters ?: []).find { existing ->
        distance3D(existing.center, cluster.center) <= safeClusterRadius()
    } ?: cluster
    def stateName = matched?.archivedGhost == true ? (matched?.ghostStateSnapshot ?: "Detected") : (devKey ? determineGhostState(devKey, matched) : (matched?.ghostStateSnapshot ?: null))

    if (cluster?.archivedGhost == true) {
        switch (stateName) {
            case "Busted":
                return [stroke: "#2e7d32", fill: "rgba(46,125,50,0.08)", dash: "5,3"]
            case "Escaping":
                return [stroke: "#c62828", fill: "rgba(198,40,40,0.08)", dash: "5,3"]
            case "Targeted":
                return [stroke: "#ef6c00", fill: "rgba(239,108,0,0.08)", dash: "5,3"]
            case "Persistent":
                return [stroke: "#f9a825", fill: "rgba(249,168,37,0.08)", dash: "5,3"]
            default:
                return [stroke: "#1565c0", fill: "rgba(21,101,192,0.08)", dash: "5,3"]
        }
    }

    switch (stateName) {
        case "Busted":
            return [stroke: "#2e7d32", fill: "rgba(46,125,50,0.14)", dash: null]
        case "Escaping":
            return [stroke: "#c62828", fill: "rgba(198,40,40,0.14)", dash: null]
        case "Targeted":
            return [stroke: "#ef6c00", fill: "rgba(239,108,0,0.14)", dash: null]
        case "Persistent":
            return [stroke: "#f9a825", fill: historicalOnly ? "rgba(249,168,37,0.14)" : "none", dash: "3,2"]
        default:
            return [stroke: "#1565c0", fill: historicalOnly ? "rgba(21,101,192,0.14)" : "none", dash: "3,2"]
    }
}

private Map classifyPlotPoints(List points, List outOfBoundsPoints, dev, String horizontalAxis = "x", String verticalAxis = "y") {
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
        def insideActiveArea = isPointInsideActiveRememberedInterferenceArea(point, rememberedAreas)
        def insideProjectedAreaOnly = !insideActiveArea && isPointInsideProjectedRememberedArea(point, rememberedAreas, horizontalAxis, verticalAxis)

        if (insideActiveArea) {
            if (shouldPlotInterferenceAreaPointAsRed(point, allDisplayPoints, rememberedAreas, deviceBounds, validDeviceBounds, occupancyAssociationWindowMs)) {
                occupancyAssociatedInterferencePoints << point
            } else {
                ignoredPoints << point
            }
            return
        }

        if (insideProjectedAreaOnly) {
            ignoredPoints << point
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

private boolean isPointInsideProjectedRememberedArea(Map point, List rememberedAreas, String horizontalAxis, String verticalAxis) {
    if (!point || !rememberedAreas) {
        return false
    }

    def pointTs = point.ts instanceof Number ? (point.ts as Long) : null
    if (pointTs == null) {
        return false
    }

    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"

    rememberedAreas.any { area ->
        pointTs >= ((area.updatedAt ?: 0L) as Long) &&
                point[horizontalAxis] >= area.bounds[hMinField] &&
                point[horizontalAxis] <= area.bounds[hMaxField] &&
                point[verticalAxis] >= area.bounds[vMinField] &&
                point[verticalAxis] <= area.bounds[vMaxField]
    }
}

private boolean shouldPlotInterferenceAreaPointAsRed(Map point, List allDisplayPoints, List rememberedAreas, Map deviceBounds, boolean validDeviceBounds, Long windowMs) {
    if (!isPointInsideActiveRememberedInterferenceArea(point, rememberedAreas)) {
        return false
    }

    if (point?.ghostIgnoreReason in ["switch-inactive", "device-inactive"]) {
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

        if (candidate?.ghostEligible == false || candidate?.ghostIgnoreReason in ["switch-inactive", "device-inactive"]) {
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
    def pointBuckets = classifyPlotPoints(points, outOfBoundsPoints, dev, horizontalAxis, verticalAxis)
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

private String renderClusterPlot(List points, List outOfBoundsPoints, List currentClusters, List historicalClusters, dev = null, String horizontalAxis = "x", String verticalAxis = "y", Map preferredScale = null, boolean pointsPendingProcessing = false) {
    def limitedPointSet = limitPointsForGraph(points, outOfBoundsPoints)
    def plotPoints = limitedPointSet.points
    def plotOutOfBoundsPoints = limitedPointSet.outOfBoundsPoints

    def scale = calculatePlotScale(plotPoints, plotOutOfBoundsPoints, currentClusters, historicalClusters, dev, horizontalAxis, verticalAxis, preferredScale)
    if (!scale) {
        return "No points or cluster history available."
    }

    def pointBuckets = classifyPlotPoints(plotPoints, plotOutOfBoundsPoints, dev, horizontalAxis, verticalAxis)
    def deviceBounds = pointBuckets.deviceBounds
    def validDeviceBounds = pointBuckets.validDeviceBounds
    def displayInBoundsPoints = pointBuckets.inBoundsPoints
    def ignoredPoints = pointBuckets.ignoredPoints
    def occupancyAssociatedInterferencePoints = pointBuckets.occupancyAssociatedInterferencePoints
    def rememberedAreas = pointBuckets.rememberedAreas ?: []
    def hMinField = "${horizontalAxis}min"
    def hMaxField = "${horizontalAxis}max"
    def vMinField = "${verticalAxis}min"
    def vMaxField = "${verticalAxis}max"
    def width = scale.width
    def height = scale.height
    def plotLeft = scale.plotLeft
    def plotRight = scale.plotRight
    def plotTop = 46
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
    svg << "<rect x='${plotLeft}' y='${plotTop}' width='${plotRight - plotLeft}' height='${plotBottom - plotTop}' fill='#ffffff' stroke='#d0d0d0' />"

    // Draw device bounds box (gray dashed box)
    if (validDeviceBounds) {
        def x1 = normalizeX(deviceBounds[hMinField])
        def x2 = normalizeX(deviceBounds[hMaxField])
        def y1 = normalizeY(deviceBounds[vMaxField])
        def y2 = normalizeY(deviceBounds[vMinField])
        svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='none' stroke='#888888' stroke-width='1' stroke-dasharray='4,2' />"
    }

    // Draw remembered interference area boxes separately from ghost boxes.
    (rememberedAreas ?: []).each { area ->
        if (area?.bounds) {
            def x1 = normalizeX(area.bounds[hMinField])
            def x2 = normalizeX(area.bounds[hMaxField])
            def y1 = normalizeY(area.bounds[vMaxField])
            def y2 = normalizeY(area.bounds[vMinField])
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='rgba(197,204,211,0.18)' stroke='#c5ccd3' stroke-width='1.2' />"
        }
    }

    // Draw current ghost boxes plus unmatched historical ghosts behind points.
    def drawClusters = []
    drawClusters.addAll((currentClusters ?: []).collect { currentCluster ->
        [cluster: getPreferredRenderCluster(currentCluster, historicalClusters, dev ? deviceKey(dev.id) : null), historicalOnly: false]
    })
    (historicalClusters ?: []).each { historicalCluster ->
        def hasCurrentMatch = (currentClusters ?: []).any { currentCluster ->
            distance3D(currentCluster.center, historicalCluster.center) <= safeClusterRadius()
        }
        if (historicalCluster?.archivedGhost == true || !hasCurrentMatch) {
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
            def style = plotStateStyle(cluster, dev ? deviceKey(dev.id) : null, historicalClusters, currentClusters, rememberedAreas, entry.historicalOnly as boolean)
            def dashAttr = style.dash ? " stroke-dasharray='${style.dash}'" : ""
            svg << "<rect x='${x1}' y='${y1}' width='${x2 - x1}' height='${y2 - y1}' fill='${style.fill}' stroke='${style.stroke}' stroke-width='1.6'${dashAttr} />"

            def escapingBounds = cluster.escapingBounds
            if (escapingBounds && isValidBounds(escapingBounds) && ((occupancyAssociatedInterferencePoints ?: []).isEmpty() || (entry.historicalOnly as boolean))) {
                def ex1 = normalizeX(escapingBounds[hMinField])
                def ex2 = normalizeX(escapingBounds[hMaxField])
                def ey1 = normalizeY(escapingBounds[vMaxField])
                def ey2 = normalizeY(escapingBounds[vMinField])
                svg << "<rect x='${ex1}' y='${ey1}' width='${ex2 - ex1}' height='${ey2 - ey1}' fill='rgba(198,40,40,0.08)' stroke='#c62828' stroke-width='1.1' stroke-dasharray='2,2' />"
            }
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

    // Draw all points with per-point hover details.
    drawPlotPoints(svg, ignoredPoints, horizontalAxis, verticalAxis, normalizeX, normalizeY, "#888888", null, pointsPendingProcessing)
    drawPlotPoints(svg, displayInBoundsPoints, horizontalAxis, verticalAxis, normalizeX, normalizeY, "#ff9800", null, pointsPendingProcessing)
    drawPlotPoints(svg, occupancyAssociatedInterferencePoints, horizontalAxis, verticalAxis, normalizeX, normalizeY, "#d32f2f", "#7f1d1d", pointsPendingProcessing)

    svg << "</svg>"
    svg.toString()
}

private Map limitPointsForGraph(List points, List outOfBoundsPoints) {
    def inBounds = binPointsForDisplay(points)
    def outOfBounds = binPointsForDisplay(outOfBoundsPoints)
    def total = inBounds.size() + outOfBounds.size()
    def maxPoints = safeMaxGraphPointsPerDevice()

    if (total <= maxPoints) {
        return [
                points: inBounds,
                outOfBoundsPoints: outOfBounds
        ]
    }

    def inBoundsLimit = Math.round((inBounds.size() as Double) * maxPoints / Math.max(1, total)) as Integer
    inBoundsLimit = Math.max(0, Math.min(inBounds.size(), inBoundsLimit))
    def outOfBoundsLimit = Math.max(0, Math.min(outOfBounds.size(), maxPoints - inBoundsLimit))

    if (outOfBoundsLimit == 0 && outOfBounds && (inBoundsLimit < maxPoints)) {
        outOfBoundsLimit = 1
        inBoundsLimit = Math.max(0, maxPoints - outOfBoundsLimit)
    }
    if (inBoundsLimit == 0 && inBounds && (outOfBoundsLimit < maxPoints)) {
        inBoundsLimit = 1
        outOfBoundsLimit = Math.max(0, maxPoints - inBoundsLimit)
    }

    [
            points: evenlySamplePoints(inBounds, inBoundsLimit),
            outOfBoundsPoints: evenlySamplePoints(outOfBounds, outOfBoundsLimit)
    ]
}

private Integer safeMaxGraphPointsPerDevice() {
    Math.max(1, (maxGraphPointsPerDevice ?: 500) as Integer)
}

private List binPointsForClustering(List points) {
    binPointsByRoundedCoordinates(points, false)
}

private List binPointsForDisplay(List points) {
    binPointsByRoundedCoordinates(points, true)
}

private List binPointsByRoundedCoordinates(List points, boolean preserveEligibilityState) {
    def source = (points ?: []) as List
    if (!source) {
        return []
    }

    def bins = [:]
    source.each { point ->
        def roundedX = roundToPointBin(point?.x)
        def roundedY = roundToPointBin(point?.y)
        def roundedZ = roundToPointBin(point?.z)
        def stateKey = preserveEligibilityState ? "${point?.ghostEligible}|${point?.ghostIgnoreReason ?: ''}" : ""
        def key = "${roundedX}|${roundedY}|${roundedZ}|${stateKey}"
        def existing = bins[key]
        if (!existing) {
            bins[key] = [
                    x: roundedX,
                    y: roundedY,
                    z: roundedZ,
                    ts: point?.ts,
                    ghostEligible: point?.ghostEligible,
                    ghostIgnoreReason: point?.ghostIgnoreReason,
                    binCount: pointWeight(point)
            ]
            return
        }

        existing.binCount = (existing.binCount ?: 0) + pointWeight(point)
        if ((point?.ts instanceof Number) && (!(existing.ts instanceof Number) || (point.ts as Long) > (existing.ts as Long))) {
            existing.ts = point.ts
        }
    }

    bins.values().collect { clonePoint(it as Map) }
}

private Double roundToPointBin(value) {
    def numeric = toDouble(value)
    if (numeric == null) {
        return 0.0d
    }
    def binSize = safePointBinSizeCm()
    roundToConfiguredBin(numeric, binSize)
}

private Double safePointBinSizeCm() {
    def configured = toDouble(pointBinSizeCm)
    if (configured == null) {
        return 1.0d
    }

    Math.max(0.1d, Math.min(100.0d, configured))
}

private Double roundToConfiguredBin(Double value, Double binSize) {
    if (value == null) {
        return 0.0d
    }

    def safeBinSize = Math.max(0.1d, binSize ?: 1.0d)
    def rounded = Math.round(value / safeBinSize) * safeBinSize
    round2(rounded).doubleValue()
}

private Integer pointWeight(Map point) {
    Math.max(1, ((point?.binCount ?: point?.pointCount ?: 1) as Integer))
}

private Integer totalPointWeight(List points) {
    ((points ?: []) as List).collect { pointWeight(it as Map) }.sum() ?: 0
}

private Double weightedAxisAverage(List points, String axis, Integer totalWeight = null) {
    def weight = Math.max(1, totalWeight ?: totalPointWeight(points))
    (((points ?: []) as List).collect { point ->
        ((point?."${axis}" ?: 0.0d) as Double) * pointWeight(point as Map)
    }.sum() ?: 0.0d) / weight
}

private List evenlySamplePoints(List points, Integer limit) {
    def source = (points ?: []) as List
    def sampleLimit = Math.max(0, limit ?: 0)
    if (sampleLimit <= 0 || !source) {
        return []
    }
    if (source.size() <= sampleLimit) {
        return source
    }

    def sampled = []
    (0..<sampleLimit).each { idx ->
        def sourceIndex = Math.floor(idx * source.size() / sampleLimit) as Integer
        sampled << source[Math.min(source.size() - 1, sourceIndex)]
    }
    sampled
}

private void drawPlotPoints(StringBuilder svg, List points, String horizontalAxis, String verticalAxis, Closure normalizeX, Closure normalizeY, String fill, String stroke, boolean pendingProcessing = false) {
    (points ?: []).each { point ->
        def effectiveFill = pendingProcessing ? pendingPointFill(fill) : fill
        def effectiveStroke = pendingProcessing ? (stroke ?: "#334155") : stroke
        def strokeWidth = pendingProcessing ? "0.55" : "0.5"
        def strokeDashAttr = pendingProcessing ? " stroke-dasharray='1.0,1.4'" : ""
        def strokeAttr = effectiveStroke ? " stroke='${effectiveStroke}' stroke-width='${strokeWidth}'${strokeDashAttr}" : ""
        def cx = normalizeX(point[horizontalAxis])
        def cy = normalizeY(point[verticalAxis])
        svg << "<circle cx='${cx}' cy='${cy}' r='2.3' fill='${effectiveFill}'${strokeAttr}>"
        svg << "<title>${plotPointTooltip(point as Map)}</title>"
        svg << "</circle>"
    }
}

private String pendingPointFill(String fill) {
    switch (fill) {
        case "#ff9800":
            return "#fcd9a6"
        case "#d32f2f":
            return "#f7b1b1"
        case "#888888":
            return "#d1d5db"
        default:
            return fill
    }
}

private String plotPointTooltip(Map point) {
    def countText = pointWeight(point) > 1 ? "Count ${pointWeight(point)}" : null
    def timestamp = safeTimestamp(point?.ts)
    [
            "X ${round2(point?.x)} cm",
            "Y ${round2(point?.y)} cm",
            "Z ${round2(point?.z)} cm",
            countText,
            "Detected ${timestamp ? formatTimestamp(timestamp) : 'Unknown time'}"
    ].findAll { it }.join("&#10;")
}

private Map getPreferredRenderCluster(Map currentCluster, List historicalClusters, String devKey) {
    def matchedHistorical = findPreferredHistoricalRenderMatch(currentCluster, historicalClusters, devKey)
    if (!matchedHistorical) {
        return currentCluster
    }

    def currentState = matchedHistorical?.archivedGhost == true ? null : (devKey ? determineGhostState(devKey, matchedHistorical) : matchedHistorical?.ghostStateSnapshot)
    if (!(currentState in ["Targeted", "Escaping", "Busted"])) {
        return currentCluster
    }

    def historicalBounds = matchedHistorical?.bounds
    def currentBounds = currentCluster?.bounds
    if (!isValidBounds(historicalBounds) || !isValidBounds(currentBounds)) {
        return matchedHistorical
    }

    if (boundsVolume(historicalBounds) >= boundsVolume(currentBounds) && boundsOverlap(historicalBounds, currentBounds)) {
        def renderCluster = snapshotCluster(matchedHistorical)
        renderCluster.center = clonePoint(currentCluster.center ?: matchedHistorical.center)
        renderCluster.points = ((currentCluster.points ?: matchedHistorical.points ?: []) as List).collect { clonePoint(it) }
        return renderCluster
    }

    currentCluster
}

private Map findPreferredHistoricalRenderMatch(Map currentCluster, List historicalClusters, String devKey) {
    if (!currentCluster) {
        return null
    }

    def directCandidates = (historicalClusters ?: []).findAll { historicalCluster ->
        historicalCluster?.archivedGhost != true &&
                distance3D((historicalCluster.center ?: [:]) as Map, (currentCluster.center ?: [:]) as Map) <= safeClusterRadius()
    }
    if (directCandidates) {
        return directCandidates.min { candidate ->
            distance3D((candidate.center ?: [:]) as Map, (currentCluster.center ?: [:]) as Map)
        }
    }

    def targetArea = devKey ? getRememberedAreaForGhost(devKey, currentCluster) : null
    if (targetArea?.bounds) {
        def areaCandidates = (historicalClusters ?: []).findAll { historicalCluster ->
            historicalCluster?.archivedGhost != true &&
                    historicalCluster?.bounds &&
                    boundsOverlap(historicalCluster.bounds, targetArea.bounds) &&
                    boundsOverlap(currentCluster.bounds, targetArea.bounds)
        }
        if (areaCandidates) {
            return areaCandidates.max { candidate ->
                boundsVolume(candidate?.bounds)
            }
        }
    }

    null
}

private String renderCorrelationSummary(deviceId) {
    def cardsData = buildCorrelationTrackerCards(deviceId)
    if (!cardsData) {
        return null
    }

    def coincidenceWindow = safeCorrelationChangeWindowSeconds()
    def episodeGapSeconds = safeCorrelationEpisodeGapSeconds()
    def displayEpisodeCount = getDisplayGhostEpisodeCount(deviceId)
    def cards = new StringBuilder()

    cardsData.each { card ->
        def transitionEvents = ((card.events ?: []) as List).findAll { it.type == "transition" }
        def valueEvents = ((card.events ?: []) as List).findAll { it.type == "value" }
        def body = new StringBuilder()
        body << "<div style='margin-bottom:10px;'>"
        body << "<div style='margin-bottom:8px;'>"
        body << "<div style='font-weight:bold; color:#111827; font-size:15px; margin-bottom:2px;'>${card.title}</div>"
        body << "<div style='color:#64748b; font-size:11px;'>Detected correlation events for this device / attribute pair are indexed below.</div>"
        body << "</div>"
        if (!card.hasEvents) {
            body << "<div style='border:1px solid #cfd8dc; background:#f8fafc; padding:8px; margin-bottom:8px;'>"
            body << "<div style='color:#424242; font-weight:bold; margin-bottom:4px;'>No clear correlation events detected yet</div>"
            body << "<div style='color:#555555;'>This device / attribute pair does not yet show a strong value-based or transition-based correlation signal.</div>"
            body << "</div>"
            body << "</div>"
            cards << "<div style='border-left:4px solid #cfd8dc; background:#ffffff; padding:10px; margin:4px 0;'>${body}</div>"
            return
        }
        if (valueEvents) {
            body << "<div style='margin-bottom:8px;'>"
            body << "<div style='color:#475569; font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:4px;'>Value Correlation Events</div>"
            valueEvents.each { event ->
                body << renderCorrelationEventBlock(event, null)
            }
            body << "</div>"
        }
        if (transitionEvents) {
            body << "<div style='margin-bottom:8px;'>"
            body << "<div style='color:#475569; font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:4px;'>Transition Correlation Events</div>"
            transitionEvents.each { event ->
                def supportText = "${event.supportLine} Ghost episodes detected with a ${episodeGapSeconds}s inactivity gap: ${displayEpisodeCount}."
                body << renderCorrelationEventBlock(event + [supportLine: supportText], null)
            }
            body << "</div>"
        }
        body << "</div>"
        def accent = ((card.events ?: []) as List).find()?.border ?: "#cfd8dc"
        cards << "<div style='border-left:4px solid ${accent}; background:#ffffff; padding:10px; margin:4px 0;'>${body}</div>"
    }

    cards.toString()
}

private String renderCorrelationEventBlock(Map event, String activationHint = null) {
    def html = new StringBuilder()
    html << "<div style='border:1px solid ${event.border}; background:${event.background}; padding:8px; margin-bottom:8px;'>"
    html << "<div style='font-weight:bold; color:#111827; margin-bottom:4px;'>Event ${event.index}: ${event.shortLabel}</div>"
    html << "<div style='color:${event.color}; font-weight:bold; margin-bottom:4px;'>${event.headline}</div>"
    html << "<div style='color:#444444;'>${event.summary}</div>"
    if (event.supportLine) {
        html << "<div style='color:#555555; margin-top:4px;'>${event.supportLine}</div>"
    }
    if (activationHint) {
        html << "<div style='color:#555555; margin-top:4px;'>${activationHint}</div>"
    }
    html << "</div>"
    html.toString()
}

private String formatCorrelationEventValue(String value) {
    def text = value?.toString()?.trim()
    if (!text) {
        return "Unknown"
    }
    text.split(/\s+/)*.capitalize().join(" ")
}

private List getQualifiedValueCorrelationCandidates(Map aggregate) {
    def values = ((aggregate?.values ?: [:]) as Map)
    if (!values) {
        return []
    }

    values.collect { value, stats ->
        def total = (stats.ghostSamples ?: 0) + (stats.clearSamples ?: 0)
        def ghostPctForValue = ((stats.ghostSamples ?: 0) as Double) / Math.max(1.0d, total as Double)
        def clearPctForValue = ((stats.clearSamples ?: 0) as Double) / Math.max(1.0d, total as Double)
        [
                value: value,
                stats: stats,
                total: total,
                ghostPctForValue: ghostPctForValue,
                clearPctForValue: clearPctForValue,
                bias: ghostPctForValue - clearPctForValue
        ]
    }.findAll { candidate ->
        (candidate.total ?: 0) >= 3 &&
                (candidate.bias ?: 0.0d) >= 0.55d &&
                ((candidate.stats?.ghostSamples ?: 0) as Integer) >= 5 &&
                candidate.value?.toString() != "unknown"
    }.sort { a, b ->
        (b.bias <=> a.bias) ?: ((b.stats?.ghostSamples ?: 0) <=> (a.stats?.ghostSamples ?: 0))
    }
}

private Map assessValueCorrelation(Map aggregate) {
    def totalSamples = (aggregate.samples ?: 0) as Integer
    def ghostSamples = (aggregate.ghostSamples ?: 0) as Integer
    def clearSamples = (aggregate.clearSamples ?: 0) as Integer
    def rankedValues = getQualifiedValueCorrelationCandidates(aggregate)
    def strongest = rankedValues ? rankedValues[0] : null

    def headline = "No clear correlation signal"
    def color = "#424242"
    def border = "#cfd8dc"
    def background = "#f8fafc"
    def summary = "So far, ghost activity does not line up clearly with this device or attribute."
    def supportLine = totalSamples > 0 ?
            "Across all observed values: ${ghostSamples} / ${totalSamples} samples were ghost-present (${percent(ghostSamples, totalSamples)})." :
            "Not enough samples yet."

    if (strongest && strongest.bias >= 0.55d && (strongest.stats.ghostSamples ?: 0) >= 5) {
        headline = "Possible correlation with value '${strongest.value}'"
        color = "#c62828"
        border = "#ef9a9a"
        background = "#ffebee"
        summary = "Ghosts are concentrated when this attribute is '${strongest.value}', and much less common in the other observed values."
        supportLine = "${strongest.stats.ghostSamples ?: 0} / ${strongest.total ?: 0} samples were ghost-present while the value was '${strongest.value}' (${percent(strongest.stats.ghostSamples, strongest.total)})."
    } else if (ghostSamples >= 10 && clearSamples >= 10 && rankedValues.size() > 1) {
        headline = "Weak correlation signal"
        color = "#ef6c00"
        border = "#ffcc80"
        background = "#fff8e1"
        summary = "There is some separation by value, but not enough yet to call it a strong correlation."
    }

    [
            headline: headline,
            color: color,
            border: border,
            background: background,
            summary: summary,
            supportLine: supportLine,
            isDetected: strongest != null
    ]
}

private Map assessTransitionCorrelation(Map aggregate) {
    assessTransitionCorrelation(aggregate, null)
}

private Map assessTransitionCorrelation(Map aggregate, Integer displayEpisodeCountOverride) {
    def episodeStarts = Math.max((aggregate.ghostAppearances ?: 0) as Integer, displayEpisodeCountOverride ?: 0)
    def startsNearChange = (aggregate.ghostAppearancesNearAnyChange ?: 0) as Integer
    def changePct = episodeStarts > 0 ? (startsNearChange as Double) / episodeStarts.toDouble() : 0.0d

    def headline = "No clear transition correlation"
    def color = "#424242"
    def border = "#cfd8dc"
    def background = "#f8fafc"
    def summary = "So far, ghost starts do not clearly line up with recent attribute changes."

    if (changePct >= 0.5d && episodeStarts >= 4) {
        headline = "Possible correlation with recent changes"
        color = "#ef6c00"
        border = "#ffcc80"
        background = "#fff3e0"
        summary = "Ghost starts often happen shortly after this attribute changes."
    } else if (episodeStarts <= 1) {
        summary = "There are not enough ghost starts yet to tell whether changes in this attribute are related."
    }

    [
            headline: headline,
            color: color,
            border: border,
            background: background,
            summary: summary,
            episodeCount: episodeStarts,
            supportLine: "Ghost starts after a recent change: ${startsNearChange} / ${episodeStarts} (${percent(startsNearChange, episodeStarts)}).",
            isDetected: changePct >= 0.5d && episodeStarts >= 4
    ]
}

private Integer getDisplayGhostEpisodeCount(deviceId) {
    def dev = mmwaveDevices?.find { deviceKey(it.id) == deviceKey(deviceId) }
    if (!dev) {
        return 0
    }
    getGhostEpisodeStartsFromPoints(dev, getDisplayData(deviceId).points ?: []).size()
}

private String renderInterferenceAreasCard(deviceId) {
    def devKey = deviceKey(deviceId)
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    if (!rememberedAreas) {
        return renderNoteCard("Device Interference Areas", "No interference area data is currently available for this device.")
    }

    def updatedAt = (rememberedAreas.collect { it.updatedAt ?: 0L }.max() ?: 0L) as Long
    def rows = rememberedAreas.collect { area ->
        def dynamicSummary = area.dynamic?.enabled ?
                " / dynamic: ${area.dynamic.deviceName ?: area.dynamic.deviceId} ${area.dynamic.attribute}=${area.dynamic.activeValue} (${area.dynamicActive ? 'active' : 'inactive'})" :
                ""
        def areaSummary = isInactiveInterferenceAreaBounds(area.bounds as Map) ?
                "No area configured" :
                "X ${round2(area.bounds.xmin)}..${round2(area.bounds.xmax)}, Y ${round2(area.bounds.ymin)}..${round2(area.bounds.ymax)}, Z ${round2(area.bounds.zmin)}..${round2(area.bounds.zmax)}"
        "Area ${area.areaIndex}: ${areaSummary}${dynamicSummary}"
    }
    def header = updatedAt > 0L ? "Last updated: ${formatTimestamp(updatedAt)}<br>" : ""
    renderNoteCard("Device Interference Areas", header + rows.join("<br>"))
}

private String renderInterferenceAreasControlCard(deviceId, List selectableClusters) {
    def devKey = deviceKey(deviceId)
    def activeGhosts = (getTrackedGhostsForDisplay(deviceId) ?: []) as List
    def rememberedAreas = getRememberedInterferenceAreas(devKey)
    if (!rememberedAreas) {
        return renderActionHintCard("No Interference Area Data", "No interference area data is currently available for this device.")
    }

    def updatedAt = (rememberedAreas.collect { it.updatedAt ?: 0L }.max() ?: 0L) as Long
    def rows = rememberedAreas.collect { area ->
        def accent = "#94a3b8"
        if (isInactiveInterferenceAreaBounds(area.bounds as Map)) {
            return "<div style='border-left:4px solid ${accent}; background:#ffffff; padding:8px 10px; margin:6px 0;'>" +
                    "<div style='font-weight:bold; color:#111827; margin-bottom:2px;'>Area ${area.areaIndex}</div>" +
                    "<div style='color:#475569;'>Not configured</div>" +
                    "</div>"
        }

        def statusText = "Configured, but no current ghost match"
        def targetedCluster = activeGhosts.find { cluster ->
            (cluster?.targetAreaIndex as Integer) == (area.areaIndex as Integer)
        }
        if (targetedCluster?.bounds && sameBounds(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            statusText = "Currently targeting Ghost ${idx}"
            accent = "#2563eb"
        }
        else if (targetedCluster?.bounds && isBoundsWithinBounds(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            statusText = "Currently targeting Ghost ${idx}"
            accent = "#2563eb"
        }
        else if (targetedCluster?.bounds && boundsOverlap(targetedCluster.bounds, area.bounds)) {
            def idx = activeGhosts.indexOf(targetedCluster) + 1
            statusText = "Currently targeting Ghost ${idx}, but the ghost now extends beyond this area"
            accent = "#b91c1c"
        } else {
            def exactMatch = activeGhosts.find { cluster ->
                cluster?.bounds && sameBounds(cluster.bounds, area.bounds)
            }
            if (exactMatch) {
                def idx = activeGhosts.indexOf(exactMatch) + 1
                statusText = "Matches Ghost ${idx}"
                accent = "#2563eb"
            } else {
                def overlappingCluster = activeGhosts.find { cluster ->
                    cluster?.bounds && boundsOverlap(cluster.bounds, area.bounds)
                }
                if (overlappingCluster) {
                    def idx = activeGhosts.indexOf(overlappingCluster) + 1
                    if (isBoundsWithinBounds(overlappingCluster.bounds, area.bounds)) {
                        statusText = "Contains Ghost ${idx}"
                        accent = "#0f766e"
                    } else {
                        statusText = "May be using older bounds than Ghost ${idx}"
                        accent = "#b91c1c"
                    }
                } else {
                    accent = "#d97706"
                }
            }
        }

        "<div style='border-left:4px solid ${accent}; background:#ffffff; padding:8px 10px; margin:6px 0;'>" +
                "<div style='font-weight:bold; color:#111827; margin-bottom:4px;'>Area ${area.areaIndex}</div>" +
                "<div style='color:#475569; margin-bottom:4px;'>X ${round2(area.bounds.xmin)}..${round2(area.bounds.xmax)}, Y ${round2(area.bounds.ymin)}..${round2(area.bounds.ymax)}, Z ${round2(area.bounds.zmin)}..${round2(area.bounds.zmax)}</div>" +
                "<div style='color:#111827;'>${statusText}</div>" +
                "</div>"
    }
    def footer = updatedAt > 0L ? "<div style='text-align:right; color:#64748b; font-size:11px; margin-top:8px;'>Last updated: ${formatTimestamp(updatedAt)}</div>" : ""
    "<div>${rows.join('')}${footer}</div>"
}

private String renderCorrelationEventDefinitions(deviceId) {
    def events = getCorrelatedActivationEvents(deviceId)
    if (!events) {
        return null
    }

    def rows = events.collect { event ->
        "Event ${event.index}: ${event.cardTitle ? "${event.cardTitle} -> " : ""}${event.shortLabel}"
    }
    renderNoteCard("Correlation Event Definitions", rows.join("<br>"))
}

private String renderAreaAssignmentSummaryCard(deviceId) {
    def devKey = deviceKey(deviceId)
    def activeGhosts = (getTrackedGhostsForDisplay(deviceId) ?: []) as List
    def areas = getRememberedInterferenceAreas(devKey)

    if (!areas) {
        return getInterface("deviceSubHeader", "Interference Area Controls") +
                renderActionHintCard("Interference Area Status", "No interference area data is currently available for this device.")
    }

    def rows = areas.collect { area ->
        def accent = "#94a3b8"
        def text
        if (isInactiveInterferenceAreaBounds(area.bounds as Map)) {
            text = "Area ${area.areaIndex}: No area configured"
            accent = "#94a3b8"
        } else {
            def targetedCluster = activeGhosts.find { cluster ->
                (cluster?.targetAreaIndex as Integer) == (area.areaIndex as Integer)
            }
            if (targetedCluster) {
                def idx = activeGhosts.indexOf(targetedCluster) + 1
                def stale = targetedCluster?.bounds && boundsOverlap(targetedCluster.bounds, area.bounds) && !isBoundsWithinBounds(targetedCluster.bounds, area.bounds)
                text = stale ?
                        "Area ${area.areaIndex}: Ghost ${idx} targeted, but the ghost now extends beyond this area" :
                        "Area ${area.areaIndex}: Ghost ${idx} targeted"
                accent = stale ? "#b91c1c" : "#2563eb"
            } else {
                def exactMatch = activeGhosts.find { cluster ->
                    cluster?.bounds && sameBounds(cluster.bounds, area.bounds)
                }
                if (exactMatch) {
                    def idx = activeGhosts.indexOf(exactMatch) + 1
                    text = "Area ${area.areaIndex}: Matches Ghost ${idx}"
                    accent = "#2563eb"
                } else {
                    def overlappingCluster = activeGhosts.find { cluster ->
                        cluster?.bounds && boundsOverlap(cluster.bounds, area.bounds)
                    }
                    if (overlappingCluster) {
                        def idx = activeGhosts.indexOf(overlappingCluster) + 1
                        text = isBoundsWithinBounds(overlappingCluster.bounds, area.bounds) ?
                                "Area ${area.areaIndex}: Contains Ghost ${idx}" :
                                "Area ${area.areaIndex}: Overlaps Ghost ${idx}, but the assignment may be stale"
                        accent = isBoundsWithinBounds(overlappingCluster.bounds, area.bounds) ? "#0f766e" : "#b91c1c"
                    } else {
                        text = "Area ${area.areaIndex}: Configured, no current ghost match"
                        accent = "#d97706"
                    }
                }
            }
        }

        "<div style='border-left:4px solid ${accent}; background:#ffffff; padding:8px 10px; margin:4px 0;'>${text}</div>"
    }

    getInterface("deviceSubHeader", "Interference Area Controls") +
            "<div style='margin:6px 0 2px 0;'>" +
            rows.join("") +
            "</div>"
}

private String renderDeviceStatsCard(dev, String devKey, Map displayCounts, Map displayData, Map lastSummary, List trackedGhosts, Integer archivedGhostCount, Integer escapingPointCount, Map xyScale) {
    def ghostDetailActions = [
            buttonLink("reclusterDevice_${devKey}", "<div style='float:right;vertical-align:middle; margin:4px;'><b><font size=3>Re-evaluate</font></b></div>", "#1A77C9", 14)
    ]
    def hasGhostDetails = (((displayCounts?.detectedGhosts ?: 0) as Integer) +
            ((displayCounts?.persistentGhosts ?: 0) as Integer) +
            ((displayCounts?.targetedGhosts ?: 0) as Integer) +
            ((displayCounts?.escapingGhosts ?: 0) as Integer) +
            ((displayCounts?.bustedGhosts ?: 0) as Integer)) > 0
    def totalGraphPoints = ((displayData.totalPointCount ?: 0) as Integer)
    def showGraphSection = hasGhostDetails || totalGraphPoints > 0
    def html = new StringBuilder()
    html << "<div style='border:1px solid #bfccda; background:#eef4f8; padding:10px; margin:8px 0 14px 0; box-shadow: 1px 2px #d8e2ea;'>"
    html << getInterface(
            "deviceHeaderWithRightLink",
            "Device: ${dev.displayName}",
            buttonLink("clearDeviceStats_${devKey}", "<div style='float:right;vertical-align:middle; margin:4px;'><b><font size=3>Clear Device Stats</font></b></div>", "#b91c1c", 14)
    )
    html << renderMetricDashboard([
            [title: "Ghost Summary", stats: getGhostSummaryStats(dev.id, "Overall Summary")],
            [title: "Tracking", html: renderTrackingPanel(getTrackingStats(dev.id, displayCounts, lastSummary, displayData), devKey)]
    ], 2)
    if (showGraphSection) {
        html << getInterface("deviceSubHeaderWithRightLink", "Ghost Details", ghostDetailActions.join(""))
        html << renderSideBySidePlots(
                "X / Y View",
                renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "y", xyScale, displayData.pointsPendingProcessing as boolean),
                "X / Z View",
                renderClusterPlot(displayData.points, displayData.outOfBoundsPoints, displayData.currentClusters, displayData.historicalClusters, dev, "x", "z", xyScale, displayData.pointsPendingProcessing as boolean)
        )

        if (totalGraphPoints > safeMaxGraphPointsPerDevice()) {
            html << renderNoteCard(
                    "Graph Point Limit",
                    "${totalGraphPoints} total point(s) were available for this device, but the graphs are limited to ${safeMaxGraphPointsPerDevice()} points to keep the page responsive."
            )
        }

        if ((displayCounts.leakingGhosts ?: 0) > 0 || (displayCounts.escapingGhosts ?: 0) > 0 || escapingPointCount > 0) {
            html << buildGhostAlertsHtml(displayCounts.leakingGhosts as Integer, escapingPointCount as Integer)
        }

        if (trackedGhosts) {
            trackedGhosts.eachWithIndex { cluster, idx ->
                html << renderClusterDetails(cluster, idx + 1, devKey)
            }
        }
    }

    def correlationSummary = renderCorrelationSummary(dev.id)
    if (hasGhostDetails && correlationSummary) {
        html << getInterface(
                "deviceSubHeaderWithRightLink",
                "Correlation Tracking",
                buttonLink("clearCorrelationEvents_${devKey}", "<div style='float:right;vertical-align:middle; margin:4px;'><b><font size=3>Clear</font></b></div>", "#b91c1c", 14)
        )
        html << correlationSummary
    }

    if ((lastSummary.unclusteredPointCount ?: 0) > 0) {
        html << renderNoteCard("Detection Note", "Last processed run had ${lastSummary.unclusteredPointCount} point(s) that did not form a ghost cluster.")
    }

    if (hasGhostDetails) {
        html << renderAreaAssignmentSummaryCard(dev.id)
    }

    html << getInterface(
            "deviceSubHeaderWithRightLink",
            "Archived Ghosts",
            archivedGhostCount > 0 ?
                    buttonLink(
                            "${displayData.showArchivedGhosts ? 'hideArchivedGhosts' : 'showArchivedGhosts'}_${devKey}",
                            "<div style='float:right;vertical-align:middle; margin:4px;'><b><font size=3>${displayData.showArchivedGhosts ? 'Hide Archived Ghosts' : 'Show Archived Ghosts'}</font></b></div>",
                            "#1A77C9",
                            14
                    ) :
                    ""
    )
    if ((archivedGhostCount ?: 0) <= 0) {
        html << renderActionHintCard("No Archived Ghosts", "No ghosts have aged out of active tracking for this device yet.")
    } else {
        html << renderMetricDashboard([
                [title: "Summary", stats: getArchivedGhostSectionStats(dev.id, displayData.archivedGhosts, displayData.showArchivedGhosts as boolean)]
        ], 1)
    }
    if (displayData.showArchivedGhosts && displayData.archivedGhosts) {
        displayData.archivedGhosts.eachWithIndex { archivedGhost, idx ->
            html << renderArchivedGhostDetails(archivedGhost as Map, idx + 1)
        }
    }

    html << "</div>"
    html.toString()
}

private String renderAllDevicesSummaryCard(Map networkSummary, def recommendation) {
    def html = new StringBuilder()
    html << "<div style='border:1px solid #bfccda; background:#eef4f8; padding:10px; margin:8px 0 14px 0; box-shadow:1px 2px #d8e2ea;'>"
    html << getInterface("deviceHeaderWithRightLink", "All Devices Summary", "")
    html << renderMetricDashboard([
            [title: "", stats: ((networkSummary ?: [:]) as Map).findAll { key, value -> key != "_html" }]
    ], 1)
    if (networkSummary?._html) {
        html << networkSummary._html as String
    }
    if (recommendation) {
        html << renderRecommendationSummary(recommendation)
    }
    html << "</div>"
    html.toString()
}

private String renderFramedPageCard(String title, String bodyHtml, String headerLink = "") {
    def html = new StringBuilder()
    html << getInterface(headerLink ? "headerWithRightLink" : "header", title, headerLink)
    html << "<div style='border:1px solid #bfccda; border-top:none; background:#eef4f8; padding:10px; margin:0 0 12px 0; box-shadow:1px 2px #d8e2ea;'>${bodyHtml ?: ""}</div>"
    html.toString()
}

private String buildInstalledAppPageUrl(String pageName, Map params = [:]) {
    def queryString = ((params ?: [:]).findAll { key, value -> value != null && value.toString() != "" } as Map).collect { key, value ->
        "${java.net.URLEncoder.encode(key.toString(), 'UTF-8')}=${java.net.URLEncoder.encode(value.toString(), 'UTF-8')}"
    }.join("&")
    def base = "/installedapp/configure/${app?.id}/${pageName}"
    queryString ? "${base}?${queryString}" : base
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

private Integer safeCorrelationEpisodeGapSeconds() {
    Math.max(1, (correlationEpisodeGapSeconds ?: 180) as Integer)
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
    def leaking = isGhostLeaking(devKey, cluster)
    def escaping = isGhostEscaping(devKey, cluster)
    def statusStyle = ghostStatusNoteStyle(status)
    def episodeCount = countGhostEpisodesForCluster(devKey, cluster)
    def episodeAvg = averageGhostEpisodesPerDay(devKey, cluster)

    def hasPoints = cluster.points && cluster.points.size() > 0
    def clusterKey = "${devKey}_cluster_${displayIndex}"

    def stats = [
            "X range": "${round2(bounds.xmin)} to ${round2(bounds.xmax)} cm",
            "Y range": "${round2(bounds.ymin)} to ${round2(bounds.ymax)} cm",
            "Z range": "${round2(bounds.zmin)} to ${round2(bounds.zmax)} cm",
            "Density": cluster.density ?: 0,
            "Days in window": cluster.daysSeen ?: 0,
            "Consecutive days": cluster.consecutiveSeen ?: 0,
            "Missing streak": cluster.absentStreak ?: 0,
            "Episodes": episodeCount,
            "Avg episodes/day": round2(episodeAvg),
            "Stability": "${round2(cluster.stabilityPct ?: calculateStabilityPercent(cluster))}%"
    ]

    def detailNotes = []

    if (rememberedArea?.bounds) {
        detailNotes << "<div style='margin-bottom:6px;'><span style='color:${statusStyle.label}; font-size:11px; text-transform:uppercase; letter-spacing:0.04em;'>Targeting</span><div style='margin-top:2px; color:#111827; line-height:1.35;'>${describeGhostTargeting(devKey, cluster)}</div></div>"
        detailNotes << "<div style='margin-bottom:6px;'><span style='color:#64748b; font-size:11px; text-transform:uppercase; letter-spacing:0.04em;'>Area ${effectiveTargetIndex}</span><div style='margin-top:2px; color:#111827; line-height:1.35;'>X ${round2(rememberedArea.bounds.xmin)}..${round2(rememberedArea.bounds.xmax)}, Y ${round2(rememberedArea.bounds.ymin)}..${round2(rememberedArea.bounds.ymax)}, Z ${round2(rememberedArea.bounds.zmin)}..${round2(rememberedArea.bounds.zmax)} cm</div></div>"
        detailNotes << "<div style='margin-bottom:6px;'><span style='color:#64748b; font-size:11px; text-transform:uppercase; letter-spacing:0.04em;'>Area fit</span><div style='margin-top:2px; color:#111827; line-height:1.35;'>${leaking ? "Ghost exceeds area: ${describeGhostAreaExpansion(devKey, cluster)}" : "Ghost does not currently exceed the targeted area"}</div></div>"
    }
    if (escaping) {
        def escapingMode = getEscapingGhostMode(devKey, cluster)
        def escapingMessage = escapingMode == "inside-area" ?
                "Occupancy-contributing points are still appearing inside Area ${effectiveTargetIndex}" :
                escapingMode == "adjacent" ?
                        "Occupancy-contributing points adjacent to Area ${effectiveTargetIndex} suggest the area should be expanded" :
                        "Occupancy-contributing points are appearing both inside and just outside Area ${effectiveTargetIndex}"
        detailNotes << "<div style='margin-bottom:0;'><span style='color:#b91c1c; font-size:11px; text-transform:uppercase; letter-spacing:0.04em;'>Escaping alert</span><div style='margin-top:2px; color:#111827; line-height:1.35;'>${escapingMessage}</div></div>"
    }

    def html = new StringBuilder()
    html << "<div style='border:1px solid #d7d7d7; background:#fbfbfb; padding:8px 10px; margin:4px 0;'>"
    def headerActions = []
    if (leaking && rememberedArea?.bounds) {
        headerActions << buttonLink("expandGhost_${devKey}_${displayIndex}", "<div style='float:right;vertical-align:middle; margin:2px 4px;'><b><font size=3>Expand</font></b></div>", "#1A77C9", 13)
    }
    headerActions << buttonLink("clearGhost_${devKey}_${displayIndex}", "<div style='float:right;vertical-align:middle; margin:2px 4px;'><b><font size=3>Clear</font></b></div>", "#b91c1c", 13)
    html << "<div style='margin:-8px -10px 8px -10px; padding:8px 10px; background:${statusStyle.background}; border-bottom:1px solid ${statusStyle.border}; font-weight:bold; color:#111827; overflow:auto;'><div style='display:inline-block;'>Ghost ${displayIndex}: ${status}</div><div style='float:right; display:inline-block; text-align:center;'>${headerActions.join('')}</div></div>"
    html << "<table style='width:100%; border-collapse:collapse;'>"
    stats.each { label, value ->
        html << "<tr>"
        html << "<td style='padding:3px 0; color:#666666; width:40%;'>${label}</td>"
        html << "<td style='padding:3px 0; color:#111111; font-weight:bold; text-align:right;'>${value}</td>"
        html << "</tr>"
    }
    html << "</table>"
    if (detailNotes) {
        html << "<div style='border:1px solid ${statusStyle.border}; background:${statusStyle.background}; padding:10px; margin:8px 0 4px 0;'>${detailNotes.join('')}</div>"
    }

    // Add cluster splitting controls if the cluster has points
    if (hasPoints && cluster.density >= safeMinClusterEvents() * 2) {
        html << "<div style='margin-top:8px;'>"
        html << "<input name='splitClusterRadius_${clusterKey}' type='decimal' title='Split radius (cm)' value='${round2(cluster.radius * 0.5)}' style='width:80px;'/> "
        html << "<input name='splitCluster_${clusterKey}' type='button' value='Split Cluster ${displayIndex}' style='margin-left:4px;'/>"
        html << "</div>"
    }

    html << "</div>"
    html.toString()
}

private Integer countGhostEpisodesForCluster(String devKey, Map cluster) {
    def episodeStarts = getGhostEpisodeStartsForCluster(devKey, cluster)
    if (episodeStarts) {
        return episodeStarts.size()
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    if (!dev) {
        return 0
    }

    def stableClusters = (getStableClusters(dev.id) ?: []) as List
    if (stableClusters.size() <= 1) {
        return getDisplayGhostEpisodeCount(dev.id)
    }

    0
}

private BigDecimal averageGhostEpisodesPerDay(String devKey, Map cluster) {
    def days = Math.max(1, getActiveTrackingDaysForCluster(cluster))
    (((countGhostEpisodesForCluster(devKey, cluster) ?: 0) as BigDecimal) / days).setScale(2, BigDecimal.ROUND_HALF_UP)
}

private List getGhostEpisodeStartsForCluster(String devKey, Map cluster) {
    if (!cluster) {
        return []
    }

    def dev = mmwaveDevices?.find { deviceKey(it?.id) == devKey }
    def currentCluster = getCurrentClusterForGhost(devKey, cluster)
    def referenceBounds = currentCluster?.bounds ?: cluster?.bounds
    def pointSource = []
    pointSource.addAll((((cluster.points ?: []) as List)).collect { clonePoint(it) })
    pointSource.addAll((((currentCluster?.points ?: []) as List)).collect { clonePoint(it) })

    if (!pointSource && dev && isValidBounds(referenceBounds)) {
        def displayData = getDisplayData(dev.id)
        pointSource.addAll((((displayData.points ?: []) as List)).findAll { point ->
            point?.ts instanceof Number &&
                    isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, referenceBounds)
        }.collect { clonePoint(it) })
    }

    def timedPoints = pointSource
            .findAll { it?.ts instanceof Number }
            .unique { point -> "${point.x}|${point.y}|${point.z}|${point.ts}" }
            .sort { a, b -> (a.ts as Long) <=> (b.ts as Long) }
    if (!timedPoints) {
        return []
    }

    def gapMs = safeCorrelationEpisodeGapSeconds() * 1000L
    def starts = []
    Long priorTs = null
    timedPoints.each { point ->
        def pointTs = point.ts as Long
        if (priorTs == null || (pointTs - priorTs) > gapMs) {
            starts << pointTs
        }
        priorTs = pointTs
    }
    starts
}

private Map ghostStatusNoteStyle(String status) {
    switch (status) {
        case "Escaping":
            return [border: "#ef9a9a", background: "#fff1f2", label: "#b91c1c"]
        case "Targeted":
            return [border: "#f4a261", background: "#fff4eb", label: "#c2410c"]
        case "Persistent":
            return [border: "#e9c46a", background: "#fffbea", label: "#a16207"]
        case "Busted":
            return [border: "#86efac", background: "#f0fdf4", label: "#166534"]
        default:
            return [border: "#bfdbfe", background: "#eff6ff", label: "#1d4ed8"]
    }
}

private String describeClusterOption(Map cluster, Integer idx) {
    "Ghost ${idx + 1}"
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
            "Reason": recommendation.reason == "leaking" ? "Targeted ghost is leaking beyond its current area" : "Persistent ghost should be targeted",
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
        if (recommendation.reason == "leaking") {
            return "Expand interference area ${recommendation.areaIndex} to cover a leaking ghost"
        }
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

private boolean shouldProcessPointsOnDetectionInactive() {
    processPointsOnDetectionInactive == true
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
    def newEscapingGhosts = countNewGhostState(previousStableClusters, currentStableClusters) { ghost ->
        isGhostEscaping(devKey, ghost) && !isGhostBusted(devKey, ghost)
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
    if (notifyOnEscapingGhost && newEscapingGhosts > 0) {
        sendNotification("${dev.displayName}: ${newEscapingGhosts} targeted ghost${newEscapingGhosts == 1 ? '' : 's'} ${newEscapingGhosts == 1 ? 'is' : 'are'} escaping from inside the configured interference area.")
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
    def escapingMode = getEscapingGhostMode(devKey, cluster)

    if (isGhostBusted(devKey, cluster)) {
        return "Area ${effectiveTargetIndex} is containing this ghost; current points are fully ignored inside the target area"
    }

    if (escapingMode == "both") {
        return "Area ${effectiveTargetIndex} is being escaped from both inside the area and just beyond its boundary; review the area settings and consider expansion"
    }
    if (escapingMode == "inside-area") {
        return "Area ${effectiveTargetIndex} is being escaped from inside the area; review the interference area settings"
    }
    if (escapingMode == "adjacent") {
        return "Area ${effectiveTargetIndex} is targeting this ghost, but the ghost extends beyond the area; expansion is recommended"
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
    if (!hasLiveGhostEvidence(devKey)) {
        if (cluster?.escapingModeSnapshot) {
            return "Escaping"
        }
        if (cluster?.ghostStateSnapshot) {
            return cluster.ghostStateSnapshot as String
        }
    }
    if (isGhostEscaping(devKey, cluster)) {
        return "Escaping"
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
        case "device-sync":
            return "Device-synced"
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
            "Processing": shouldProcessPointsOnDetectionInactive() ? "On detection inactive + daily boundary fallback" : "Daily boundary only",
            "Positional clustering": "${clusteringAlgorithm ?: 'DBSCAN'} / r=${clusterRadius ?: 50}cm / min=${minClusterEvents ?: 5} / bin=${round2(safePointBinSizeCm())}cm",
            "Persistence": "history ${historyDays ?: 14}d / persistent ${persistentGhostDays ?: 2}d / busted ${bustedGhostDays ?: 2}d",
            "Display": "detected grace ${safeDisplayGraceDays()}d / historical overlays ${showHistoricalGhostOverlaysEnabled() ? 'on' : 'off'}",
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
    def detected = 0
    def persistent = 0
    def targeted = 0
    def escaping = 0
    def busted = 0
    def leaking = 0
    def escapingPoints = 0

    mmwaveDevices?.each { dev ->
        def displayCounts = getDisplayCounts(dev.id)
        detected += displayCounts.detectedGhosts ?: 0
        persistent += displayCounts.persistentGhosts ?: 0
        targeted += displayCounts.targetedGhosts ?: 0
        escaping += displayCounts.escapingGhosts ?: 0
        busted += displayCounts.bustedGhosts ?: 0
        leaking += displayCounts.leakingGhosts ?: 0
        escapingPoints += getEscapingPointCount(dev.id) ?: 0
    }

    def stats = [
            "Detected": detected,
            "Persistent": persistent,
            "Targeted": targeted,
            "Escaping": escaping,
            "Busted": busted
    ]
    def alertHtml = buildGhostAlertsHtml(leaking as Integer, escapingPoints as Integer)
    if (alertHtml) {
        stats._html = alertHtml
    }
    stats
}

private String renderHomeHeroCard() {
    """<div style='text-align:center;'>
<div style='display:flex; flex-direction:column; align-items:center; gap:0px;'>
<img src='https://raw.githubusercontent.com/lnjustin/App-Images/master/ghostBuster/Gemini_Generated_Image_1grzz61grzz61grz.png' alt='Ghost Buster logo' style='display:block; width:115px; max-width:100%; height:auto; border-radius:10px;' />
</div>
<div style='font-weight:bold; color:#223043; font-size:16px; line-height:1.0;'>Bust ghosts without the calls</div>
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

private void scheduleStatsPageRefresh(Long durationMs = 3000L, Long delayMs = 0L, Integer intervalSeconds = 1) {
    def nowMs = now()
    state.statsPageRefreshStartAt = nowMs + Math.max(0L, delayMs ?: 0L)
    state.statsPageRefreshUntil = nowMs + Math.max(1000L, durationMs ?: 3000L)
    state.statsPageRefreshIntervalSeconds = Math.max(1, intervalSeconds ?: 1)
}

private void scheduleDeviceAreaPageRefresh(String deviceId, Long durationMs = 12000L, Integer intervalSeconds = 1) {
    if (!deviceId) {
        return
    }

    state.deviceAreaPageDeviceId = deviceId
    state.deviceAreaPageRefreshUntil = now() + Math.max(1000L, durationMs ?: 12000L)
    state.deviceAreaPageRefreshIntervalSeconds = Math.max(1, intervalSeconds ?: 1)
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

private String formatTimeOfDay(Long timestamp) {
    if (!timestamp) {
        return "Unknown"
    }
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    new Date(timestamp).format("h:mm a", tz)
}

private Map cloneCluster(Map cluster) {
    [
            center: clonePoint(cluster.center),
            bounds: cloneBounds(cluster.bounds),
            radius: cluster.radius,
            density: cluster.density,
            points: representativeClusterPoints(cluster.points),
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
            appliedBounds: cloneBounds(cluster.appliedBounds),
            maxStateReached: cluster.maxStateReached
    ] + ghostSnapshotFields(cluster)
}

private Map snapshotCluster(Map cluster) {
    [
            center: clonePoint(cluster.center),
            bounds: cloneBounds(cluster.bounds),
            radius: cluster.radius ?: 0.0d,
            density: cluster.density ?: 0,
            points: representativeClusterPoints(cluster.points),
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
            appliedBounds: cloneBounds(cluster.appliedBounds),
            maxStateReached: cluster.maxStateReached
    ] + ghostSnapshotFields(cluster)
}

private List representativeClusterPoints(List points) {
    def cloned = ((points ?: []) as List).collect { clonePoint(it) }
    evenlySamplePoints(cloned, safeMaxClusterStoredPoints())
}

private Integer safeMaxClusterStoredPoints() {
    Math.max(10, Math.min(200, safeMaxGraphPointsPerDevice()))
}

private Map ghostSnapshotFields(Map cluster) {
    [
            ghostStateSnapshot: cluster?.ghostStateSnapshot,
            escapingModeSnapshot: cluster?.escapingModeSnapshot,
            escapingBounds: cloneBounds(cluster?.escapingBounds),
            escapingPointCount: cluster?.escapingPointCount ?: 0,
            targetedAreaBounds: cloneBounds(cluster?.targetedAreaBounds)
    ]
}

private void copyGhostSnapshotFields(Map target, Map source) {
    if (!target) {
        return
    }
    target.ghostStateSnapshot = source?.ghostStateSnapshot
    target.escapingModeSnapshot = source?.escapingModeSnapshot
    target.escapingBounds = cloneBounds(source?.escapingBounds)
    target.escapingPointCount = source?.escapingPointCount ?: 0
    target.targetedAreaBounds = cloneBounds(source?.targetedAreaBounds)
}

private Map enrichClusterWithGhostSnapshotData(String devKey, Map cluster, dev, Map liveCluster = null, List rawPoints = null, List rawOutOfBoundsPoints = null) {
    def enriched = snapshotCluster(cluster)
    copyGhostSnapshotFields(enriched, buildGhostSnapshotData(devKey, cluster, dev, liveCluster, rawPoints, rawOutOfBoundsPoints))
    enriched
}

private Map buildGhostSnapshotData(String devKey, Map cluster, dev, Map liveCluster = null, List rawPoints = null, List rawOutOfBoundsPoints = null) {
    def effectiveCluster = liveCluster ?: cluster
    def targetArea = getRememberedAreaForGhost(devKey, cluster)
    def allPoints = []
    allPoints.addAll(rawPoints ?: [])
    allPoints.addAll(rawOutOfBoundsPoints ?: [])
    def pointBuckets = classifyPlotPoints(rawPoints ?: [], rawOutOfBoundsPoints ?: [], dev)
    def escapingInsidePoints = ((pointBuckets.occupancyAssociatedInterferencePoints ?: []) as List).findAll { point ->
        targetArea?.bounds &&
                isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, targetArea.bounds) &&
                effectiveCluster?.bounds &&
                isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, effectiveCluster.bounds)
    }
    def escapingAdjacentPoints = ((effectiveCluster?.points ?: []) as List).findAll { point ->
        point?.ghostEligible != false &&
                targetArea?.bounds &&
                !isPointWithinBounds(point.x as Double, point.y as Double, point.z as Double, targetArea.bounds)
    }.collect { clonePoint(it) }
    def escapingAdjacentByBounds = targetArea?.bounds &&
            effectiveCluster?.bounds &&
            boundsOverlap(effectiveCluster.bounds, targetArea.bounds) &&
            !isBoundsWithinBounds(effectiveCluster.bounds, targetArea.bounds)

    def escapingPoints = []
    escapingPoints.addAll(escapingInsidePoints.collect { clonePoint(it) })
    escapingPoints.addAll(escapingAdjacentPoints)

    def escapingMode = escapingInsidePoints && (escapingAdjacentPoints || escapingAdjacentByBounds) ? "both" :
            escapingInsidePoints ? "inside-area" :
                    (escapingAdjacentPoints || escapingAdjacentByBounds) ? "adjacent" :
                            null

    def computedState = targetArea?.bounds ?
            (escapingMode ? "Escaping" : (isGhostTargeted(devKey, cluster) ? "Targeted" : (isClusterPersistent(cluster) ? "Persistent" : "Detected"))) :
            (isClusterPersistent(cluster) ? "Persistent" : "Detected")

    [
            ghostStateSnapshot: computedState,
            escapingModeSnapshot: escapingMode,
            escapingBounds: escapingPoints ? boundsForPoints(escapingPoints) :
                    (escapingAdjacentByBounds ? cloneBounds(effectiveCluster?.bounds) : null),
            escapingPointCount: escapingPoints ? escapingPoints.size() : (escapingAdjacentByBounds ? Math.max(1, cluster?.escapingPointCount ?: 0) : 0),
            targetedAreaBounds: cloneBounds(targetArea?.bounds ?: cluster?.targetedAreaBounds)
    ]
}

private Map boundsForPoints(List points) {
    if (!(points ?: [])) {
        return null
    }
    [
            xmin: points.collect { it.x }.min(),
            xmax: points.collect { it.x }.max(),
            ymin: points.collect { it.y }.min(),
            ymax: points.collect { it.y }.max(),
            zmin: points.collect { it.z }.min(),
            zmax: points.collect { it.z }.max()
    ]
}

private Map clonePoint(Map point) {
    [
            x: point?.x ?: 0.0d,
            y: point?.y ?: 0.0d,
            z: point?.z ?: 0.0d,
            ts: point?.ts,
            binCount: point?.binCount,
            pointCount: point?.pointCount,
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

    def appliedBounds = active ? cloneBounds(bounds) : inactiveInterferenceAreaBounds()
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

private Map inactiveInterferenceAreaBounds() {
    [xmin: 0, xmax: 0, ymin: 0, ymax: 0, zmin: -600, zmax: 600]
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

private Integer safeDisplayGraceDays() {
    Math.max(0, (displayGraceDays ?: 1) as Integer)
}

private boolean showHistoricalGhostOverlaysEnabled() {
    showHistoricalGhostOverlays == true
}

private Double safeMaxLeakExpandX() {
    Math.max(0.0d, (maxLeakExpandX ?: 0.0d) as Double)
}

private Double safeMaxLeakExpandY() {
    Math.max(0.0d, (maxLeakExpandY ?: 0.0d) as Double)
}

private Double safeMaxLeakExpandZ() {
    Math.max(0.0d, (maxLeakExpandZ ?: 0.0d) as Double)
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
    if (title) {
        cards << "<div style='font-weight:bold; color:#333333; margin-bottom:8px;'>${title}</div>"
    }
    cards << renderInlineMetricTiles(stats)
    cards << "</div>"
    cards.toString()
}

private String renderHtmlPanel(String title, String html) {
    "<div class='gt-panel'>${title ? "<div style='font-weight:bold; color:#333333; margin-bottom:8px;'>${title}</div>" : ""}${html}</div>"
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
    def style = metricTileStyle(label, value)
    "<div style='border:1px solid ${style.border}; background:${style.background}; padding:8px 10px; min-height:54px; box-sizing:border-box;'>" +
            "<div style='color:#6b7280; font-size:10px; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:4px;'>${label}</div>" +
            "<div style='color:#111111; font-size:16px; font-weight:bold;'>${value}</div>" +
            "</div>"
}

private Map metricTileStyle(String label, value = null) {
    if (isNeutralGhostCountTile(label, value)) {
        return [border: "#e1e5e8", background: "#ffffff"]
    }
    switch ((label ?: "").toLowerCase()) {
        case "detected":
            return [border: "#90caf9", background: "#e3f2fd"]
        case "persistent":
            return [border: "#d4af37", background: "#fffbea"]
        case "targeted":
            return [border: "#d84315", background: "#ffe9dd"]
        case "escaping":
            return [border: "#e57373", background: "#ffebee"]
        case "busted":
            return [border: "#a5d6a7", background: "#e8f5e9"]
        case "detection":
            return [border: "#cfd8dc", background: "#f8fafc"]
        default:
            return [border: "#e1e5e8", background: "#ffffff"]
    }
}

private boolean isNeutralGhostCountTile(String label, value) {
    def ghostCountLabels = ["detected", "persistent", "targeted", "escaping", "busted"]
    if (!(ghostCountLabels.contains((label ?: "").toLowerCase()))) {
        return false
    }

    if (value instanceof Number) {
        return (value as Number).doubleValue() <= 0.0d
    }

    def text = value?.toString()?.trim()
    if (!text) {
        return true
    }

    try {
        return new BigDecimal(text) <= 0
    } catch (Exception ignored) {
        return false
    }
}

private String renderTrackingPanel(Map stats, String devKey = null) {
    def detectionValue = stats["Detection"]
    def pointsProcessed = stats["Points processed"]
    def pendingProcessing = stats["Pending processing"]
    def ghostAlerts = stats["Ghost alerts"]
    def ignoredOutsideWindow = stats["Ignored outside window"]
    def lastOutsideWindowPoint = stats["Last outside-window point"]
    def pendingCount = 0
    def ignoredCount = 0
    try {
        pendingCount = (pendingProcessing instanceof Number) ? (pendingProcessing as Integer) : ((pendingProcessing?.toString()?.isInteger()) ? (pendingProcessing as Integer) : 0)
    } catch (Exception ignored) {
        pendingCount = 0
    }
    try {
        ignoredCount = (ignoredOutsideWindow instanceof Number) ? (ignoredOutsideWindow as Integer) : ((ignoredOutsideWindow?.toString()?.isInteger()) ? (ignoredOutsideWindow as Integer) : 0)
    } catch (Exception ignored) {
        ignoredCount = 0
    }
    def pendingLabel = devKey ? "<span style='display:block; overflow:auto;'><span style='float:left;'>Pending processing</span><span style='float:right;'>${buttonLink("clearPendingPoints_${devKey}", "<span style='font-size:11px; font-weight:bold;'>Clear</span>", "#b91c1c", 11)}</span></span>" : "Pending processing"
    def processedValue = pointsProcessed
    def pendingValue = devKey && pendingCount > 0 ?
            "<span style='display:block; overflow:auto;'><span style='float:left;'>${pendingProcessing}</span><span style='float:right;'>${buttonLink("processPendingPoints_${devKey}", "<span style='font-size:11px; font-weight:bold;'>Process</span>", "#1A77C9", 11)}</span></span>" :
            pendingProcessing
    def outsideWindowHtml = ignoredCount > 0 ? """
<div class='gt-metric-half'>${renderMetricTile("Ignored outside window", ignoredOutsideWindow)}</div>
<div class='gt-metric-half'>${renderMetricTile("Last outside-window point", lastOutsideWindowPoint)}</div>""" : ""
    """<div class='gt-metric-grid'>
<div class='gt-metric-full'>${renderMetricTile("Detection", detectionValue)}</div>
<div class='gt-metric-half'>${renderMetricTile("Points processed", processedValue)}</div>
<div class='gt-metric-half'>${renderMetricTile(pendingLabel, pendingValue)}</div>
${outsideWindowHtml}
<div class='gt-metric-full'>${renderMetricTile("Ghost alerts", ghostAlerts)}</div>
</div>"""
}

private String renderActionHintCard(String title, String message) {
    "<div style='border:1px dashed #cbd5e1; background:#f8fafc; padding:10px; margin:6px 0; color:#334155;'><div style='font-weight:bold; margin-bottom:4px;'>${title}</div><div>${message}</div></div>"
}

private String renderSideBySidePlots(String leftTitle, String leftPlot, String rightTitle, String rightPlot) {
    """${renderResponsiveUiStyles()}<div class='gt-plot-grid'>
<div class='gt-plot-card'>${leftPlot}</div>
<div class='gt-plot-card'>${rightPlot}</div>
</div>"""
}

private String renderCommonPlotLegend() {
    """<div class='gt-panel'>
<div class='gt-legend-grid'>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-dot' style='background:#ff9800;'></span><span>In-bounds point</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-dot' style='background:#d1d5db;border:0.55px dashed #334155;'></span><span>Pending-processing point</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-dot' style='background:#888888;'></span><span>Ignored point</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-dot' style='background:#d32f2f;'></span><span>Escaping point</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:rgba(197,204,211,0.18);border:1.2px solid #c5ccd3;'></span><span>Interference area</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:transparent;border:1px dashed #888888;'></span><span>Device bounds</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:none;border:1.6px solid #1565c0;'></span><span>Detected ghost</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:none;border:1.6px dashed #f9a825;'></span><span>Persistent ghost</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:rgba(239,108,0,0.14);border:1.6px solid #ef6c00;'></span><span>Targeted ghost</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:rgba(198,40,40,0.14);border:1.6px solid #c62828;'></span><span>Escaping ghost</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:rgba(46,125,50,0.14);border:1.6px solid #2e7d32;'></span><span>Busted ghost</span></div>
<div class='gt-legend-item'><span class='gt-legend-swatch gt-legend-box' style='background:rgba(21,101,192,0.08);border:1.6px dashed #1565c0;'></span><span>Archived ghost</span></div>
</div>
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
.gt-legend-grid{display:flex;flex-wrap:wrap;gap:8px 14px;align-items:center}
.gt-legend-item{display:flex;align-items:center;gap:6px;color:#334155;font-size:12px;line-height:1.25}
.gt-legend-swatch{display:inline-block;box-sizing:border-box;flex:0 0 auto}
.gt-legend-dot{width:10px;height:10px;border-radius:999px}
.gt-legend-box{width:14px;height:10px}
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

private String renderArchivedGhostDetails(Map archivedGhost, Integer displayIndex) {
    def bounds = archivedGhost?.canonicalBounds ?: archivedGhost?.bounds ?: [:]
    def status = archivedGhost?.maxStateReached ?: "Detected"
    def statusStyle = ghostStatusNoteStyle(status)
    def targetedText = archivedGhost?.lastTargetedAreaIndex != null ?
            "Area ${archivedGhost.lastTargetedAreaIndex}${archivedGhost?.targetSource ? " (${archivedGhost.targetSource})" : ""}" :
            "None"

    def stats = [
            "X range": "${round2(bounds.xmin)} to ${round2(bounds.xmax)} cm",
            "Y range": "${round2(bounds.ymin)} to ${round2(bounds.ymax)} cm",
            "Z range": "${round2(bounds.zmin)} to ${round2(bounds.zmax)} cm",
            "First seen day": archivedGhost?.firstSeenDay ?: 0,
            "Last seen day": archivedGhost?.lastSeenDay ?: 0,
            "Days seen": archivedGhost?.daysSeen ?: 0,
            "Episodes": archivedGhost?.episodes ?: 0,
            "Last targeted area": targetedText
    ]

    def notes = ""
    if (isValidBounds(archivedGhost?.lastTargetedAreaBounds)) {
        notes = "<div style='margin-top:8px; color:#111827; line-height:1.35;'><span style='color:#64748b; font-size:11px; text-transform:uppercase; letter-spacing:0.04em;'>Last targeted bounds</span><div style='margin-top:2px;'>X ${round2(archivedGhost.lastTargetedAreaBounds.xmin)}..${round2(archivedGhost.lastTargetedAreaBounds.xmax)}, Y ${round2(archivedGhost.lastTargetedAreaBounds.ymin)}..${round2(archivedGhost.lastTargetedAreaBounds.ymax)}, Z ${round2(archivedGhost.lastTargetedAreaBounds.zmin)}..${round2(archivedGhost.lastTargetedAreaBounds.zmax)} cm</div></div>"
    }

    def html = new StringBuilder()
    html << "<div style='border:1px solid #d7d7d7; background:#fbfbfb; padding:8px 10px; margin:4px 0;'>"
    html << "<div style='margin:-8px -10px 8px -10px; padding:8px 10px; background:${statusStyle.background}; border-bottom:1px solid ${statusStyle.border}; font-weight:bold; color:#111827;'>Ghost ${displayIndex}: ${status}</div>"
    html << "<table style='width:100%; border-collapse:collapse;'>"
    stats.each { label, value ->
        html << "<tr>"
        html << "<td style='padding:3px 0; color:#666666; width:40%;'>${label}</td>"
        html << "<td style='padding:3px 0; color:#111111; font-weight:bold; text-align:right;'>${value}</td>"
        html << "</tr>"
    }
    html << "</table>"
    html << notes
    html << "</div>"
    html.toString()
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

private Long safeTimestamp(value) {
    if (value == null || value == "") {
        return null
    }
    try {
        value as Long
    } catch (Exception ignored) {
        null
    }
}

private String formatTimestamp(Long ts) {
    if (!ts || ts <= 0L) {
        return "Unknown"
    }
    try {
        new Date(ts).format("yyyy-MM-dd h:mm:ss a", location?.timeZone ?: TimeZone.getTimeZone("America/New_York"))
    } catch (Exception ignored) {
        new Date(ts).toString()
    }
}


private void logThrowable(String context, Throwable t) {
    if (!t) {
        log.error("${context} failed")
        return
    }

    log.error("${context} failed: ${t.class?.name}: ${t.message}")

    try {
        t?.stackTrace?.take(20)?.each {
            log.error("  at ${it}")
        }
    } catch (ignored) {
        log.error("  (stack trace unavailable)")
    }
}

private String stackTraceString(Throwable t) {
    try {
        return t?.stackTrace?.collect { it.toString() }?.join("\n")
    } catch (Throwable ignored) {
        return null
    }
}

def getInterface(type, txt = "", link = "") {
    switch (type) {
        case "line":
            return "<hr style='background-color:#555555; height:1px; border:0;' />"
        case "header":
            return "<div style='color:#17324d;font-weight:bold;background:linear-gradient(180deg,#dbeafe 0%,#c7ddf7 100%);border:1px solid #9fb8d1;box-shadow:2px 3px #cbd5e1; border-radius:6px 6px 0 0; padding:2px 4px;'> ${txt}</div>"
        case "headerWithRightLink":
            return "<div style='color:#17324d;font-weight:bold;background:linear-gradient(180deg,#dbeafe 0%,#c7ddf7 100%);border:1px solid #9fb8d1;box-shadow:2px 3px #cbd5e1; overflow:auto; border-radius:6px 6px 0 0; padding:2px 4px;'><div style='display:inline-block; padding-left:2px;'> ${txt}</div><div style='float:right; display:inline-block; text-align:center;'>${link}</div></div>"
        case "deviceHeaderWithRightLink":
            return "<div style='color:#17324d;font-weight:bold;background:linear-gradient(180deg,#dbeafe 0%,#c7ddf7 100%);border:1px solid #9fb8d1;box-shadow:2px 3px #cbd5e1; overflow:auto; border-radius:6px 6px 0 0; padding:2px 4px; margin-top:4px;'><div style='display:inline-block; padding-left:2px;'> ${txt}</div><div style='float:right; display:inline-block; text-align:center;'>${link}</div></div>"
        case "error":
            return "<div style='color:#ff0000;font-weight:bold;'>${txt}</div>"
        case "note":
            return "<div style='color:#333333;font-size:small;'>${txt}</div>"
        case "subField":
            return "<div style='color:#000000;background-color:#ededed;'>${txt}</div>"
        case "subHeader":
            return "<div style='color:#17324d;font-weight:bold;background:#eaf2fb;border:1px solid #c8d7e6;box-shadow:1px 2px #dde6ef; border-radius:4px; padding:2px 4px;'> ${txt}</div>"
        case "subHeaderWithRightLink":
            return "<div style='color:#17324d;font-weight:bold;background:#eaf2fb;border:1px solid #c8d7e6;box-shadow:1px 2px #dde6ef; overflow:auto; border-radius:4px; padding:2px 4px;'><div style='display:inline-block; padding-left:2px;'> ${txt}</div><div style='float:right; display:inline-block; text-align:center;'>${link}</div></div>"
        case "deviceSubHeader":
            return "<div style='color:#17324d;font-weight:bold;background:#eaf2fb;border:1px solid #c8d7e6;box-shadow:1px 2px #dde6ef; border-radius:4px; padding:2px 4px; margin-top:6px;'> ${txt}</div>"
        case "deviceSubHeaderWithRightLink":
            return "<div style='color:#17324d;font-weight:bold;background:#eaf2fb;border:1px solid #c8d7e6;box-shadow:1px 2px #dde6ef; overflow:auto; border-radius:4px; padding:2px 4px; margin-top:6px;'><div style='display:inline-block; padding-left:2px;'> ${txt}</div><div style='float:right; display:inline-block; text-align:center;'>${link}</div></div>"
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

String buttonLink(String btnName, String linkText, color = "#1A77C9", font = 15) {
    "<div style='display:inline-block;vertical-align:middle;' class='form-group'><input type='hidden' name='${btnName}.type' value='button'></div><div style='display:inline-block;vertical-align:middle'><div class='submitOnChange' onclick='buttonClick(this)' style='display:inline-block;vertical-align:middle;color:${color};cursor:pointer;font-size:${font}px'>${linkText}</div></div><div style='display:inline-block;vertical-align:middle;'><input type='hidden' name='settings[${btnName}]' value=''></div>"
}
