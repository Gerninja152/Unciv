package com.unciv.ui.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.RepeatAction
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.MainMenuScreen
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.ReligionState
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.event.EventBus
import com.unciv.logic.map.MapVisualization
import com.unciv.logic.multiplayer.MultiplayerGameUpdated
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.Tutorial
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.cityscreen.CityScreen
import com.unciv.ui.civilopedia.CivilopediaScreen
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.multiplayer.MultiplayerHelpers
import com.unciv.ui.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.pickerscreens.DiplomaticVotePickerScreen
import com.unciv.ui.pickerscreens.DiplomaticVoteResultScreen
import com.unciv.ui.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.pickerscreens.PantheonPickerScreen
import com.unciv.ui.pickerscreens.PolicyPickerScreen
import com.unciv.ui.pickerscreens.ReligiousBeliefsPickerScreen
import com.unciv.ui.pickerscreens.TechButton
import com.unciv.ui.pickerscreens.TechPickerScreen
import com.unciv.ui.popup.ConfirmPopup
import com.unciv.ui.popup.Popup
import com.unciv.ui.popup.ToastPopup
import com.unciv.ui.popup.hasOpenPopups
import com.unciv.ui.saves.LoadGameScreen
import com.unciv.ui.saves.QuickSave
import com.unciv.ui.saves.SaveGameScreen
import com.unciv.ui.trade.DiplomacyScreen
import com.unciv.ui.utils.BaseScreen
import com.unciv.ui.utils.Fonts
import com.unciv.ui.utils.KeyCharAndCode
import com.unciv.ui.utils.extensions.centerX
import com.unciv.ui.utils.extensions.colorFromRGB
import com.unciv.ui.utils.extensions.darken
import com.unciv.ui.utils.extensions.disable
import com.unciv.ui.utils.extensions.enable
import com.unciv.ui.utils.extensions.isEnabled
import com.unciv.ui.utils.extensions.onClick
import com.unciv.ui.utils.extensions.setFontSize
import com.unciv.ui.utils.extensions.toLabel
import com.unciv.ui.utils.extensions.toTextButton
import com.unciv.ui.victoryscreen.VictoryScreen
import com.unciv.ui.worldscreen.bottombar.BattleTable
import com.unciv.ui.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.worldscreen.minimap.MinimapHolder
import com.unciv.ui.worldscreen.status.MultiplayerStatusButton
import com.unciv.ui.worldscreen.status.NextTurnAction
import com.unciv.ui.worldscreen.status.NextTurnButton
import com.unciv.ui.worldscreen.status.StatusButtons
import com.unciv.ui.worldscreen.unit.UnitActionsTable
import com.unciv.ui.worldscreen.unit.UnitTable
import com.unciv.utils.concurrency.Concurrency
import com.unciv.utils.concurrency.launchOnGLThread
import com.unciv.utils.concurrency.launchOnThreadPool
import com.unciv.utils.concurrency.withGLContext
import com.unciv.utils.debug
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

/**
 * Do not create this screen without seriously thinking about the implications: this is the single most memory-intensive class in the application.
 * There really should ever be only one in memory at the same time, likely managed by [UncivGame].
 *
 * @param gameInfo The game state the screen should represent
 * @param viewingCiv The currently active [civilization][CivilizationInfo]
 * @param restoreState
 */
class WorldScreen(
    val gameInfo: GameInfo,
    val viewingCiv: CivilizationInfo,
    restoreState: RestoreState? = null
) : BaseScreen() {
    /** When set, causes the screen to update in the next [render][BaseScreen.render] event */
    var shouldUpdate = false

    /** Indicates it's the player's ([viewingCiv]) turn */
    var isPlayersTurn = viewingCiv == gameInfo.currentPlayerCiv
        private set     // only this class is allowed to make changes

    /** Selected civilization, used in spectator and replay mode, equals viewingCiv in ordinary games */
    var selectedCiv = viewingCiv

    var fogOfWar = true
        private set

    /** `true` when it's the player's turn unless he is a spectator*/
    val canChangeState
        get() = isPlayersTurn && !viewingCiv.isSpectator()

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)

    /** Bottom left widget holding information about a selected unit or city */
    val bottomUnitTable = UnitTable(this)

    private var waitingForAutosave = false
    private val mapVisualization = MapVisualization(gameInfo, viewingCiv)

    private val minimapWrapper = MinimapHolder(mapHolder)

    private val topBar = WorldScreenTopBar(this)


    private val bottomTileInfoTable = TileInfoTable(viewingCiv)
    private val battleTable = BattleTable(this)
    private val unitActionsTable = UnitActionsTable(this)

    private val techPolicyAndVictoryHolder = Table()
    private val techButtonHolder = Table()
    private val diplomacyButtonHolder = Table()
    private val fogOfWarButton = createFogOfWarButton()
    private val nextTurnButton = NextTurnButton()
    private val statusButtons = StatusButtons(nextTurnButton)
    private val tutorialTaskTable = Table().apply { background = ImageGetter.getBackground(
        ImageGetter.getBlue().darken(0.5f)) }
    private val notificationsScroll = NotificationsScroll(this)

    private val zoomController = ZoomButtonPair(mapHolder)

    private var nextTurnUpdateJob: Job? = null

    private val events = EventBus.EventReceiver()


    init {
        // notifications are right-aligned, they take up only as much space as necessary.
        notificationsScroll.width = stage.width / 2

        minimapWrapper.x = stage.width - minimapWrapper.width

        // This is the most memory-intensive operation we have currently, most OutOfMemory errors will occur here
        mapHolder.addTiles()

        // resume music (in case choices from the menu lead to instantiation of a new WorldScreen)
        UncivGame.Current.musicController.resume()

        techButtonHolder.touchable = Touchable.enabled
        techButtonHolder.onClick(UncivSound.Paper) {
            game.pushScreen(TechPickerScreen(viewingCiv))
        }
        techPolicyAndVictoryHolder.add(techButtonHolder)

        fogOfWarButton.isVisible = viewingCiv.isSpectator()

        // Don't show policies until they become relevant
        if (viewingCiv.policies.adoptedPolicies.isNotEmpty() || viewingCiv.policies.canAdoptPolicy()) {
            val policyScreenButton = Button(skin)
            policyScreenButton.add(ImageGetter.getImage("PolicyIcons/Constitution")).size(30f).pad(15f)
            policyScreenButton.onClick { game.pushScreen(PolicyPickerScreen(this)) }
            techPolicyAndVictoryHolder.add(policyScreenButton).pad(10f)
        }

        stage.addActor(mapHolder)
        stage.scrollFocus = mapHolder
        stage.addActor(notificationsScroll)  // very low in z-order, so we're free to let it extend _below_ tile info and minimap if we want
        stage.addActor(minimapWrapper)
        stage.addActor(topBar)
        stage.addActor(statusButtons)
        stage.addActor(techPolicyAndVictoryHolder)
        stage.addActor(tutorialTaskTable)

        if (UncivGame.Current.settings.showZoomButtons) {
            stage.addActor(zoomController)
        }

        diplomacyButtonHolder.defaults().pad(5f)
        stage.addActor(fogOfWarButton)
        stage.addActor(diplomacyButtonHolder)
        stage.addActor(bottomUnitTable)
        stage.addActor(bottomTileInfoTable)
        battleTable.width = stage.width / 3
        battleTable.x = stage.width / 3
        stage.addActor(battleTable)

        stage.addActor(unitActionsTable)

        topBar.update(viewingCiv)
        fogOfWarButton.setPosition(10f, topBar.y - fogOfWarButton.height - 10f)

        val tileToCenterOn: Vector2 =
                when {
                    viewingCiv.cities.isNotEmpty() && viewingCiv.getCapital() != null -> viewingCiv.getCapital()!!.location
                    viewingCiv.getCivUnits().any() -> viewingCiv.getCivUnits().first().getTile().position
                    else -> Vector2.Zero
                }

        // Don't select unit and change selectedCiv when centering as spectator
        if (viewingCiv.isSpectator())
            mapHolder.setCenterPosition(tileToCenterOn, immediately = true, selectUnit = false)
        else
            mapHolder.setCenterPosition(tileToCenterOn, immediately = true, selectUnit = true)

        tutorialController.allTutorialsShowedCallback = { shouldUpdate = true }

        globalShortcuts.add(KeyCharAndCode.BACK) { backButtonAndESCHandler() }

        addKeyboardListener() // for map panning by W,S,A,D
        addKeyboardPresses()  // shortcut keys like F1


        if (gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything

        if (gameInfo.gameParameters.isOnlineMultiplayer) {
            val gameId = gameInfo.gameId
            events.receive(MultiplayerGameUpdated::class, { it.preview.gameId == gameId }) {
                if (isNextTurnUpdateRunning() || game.onlineMultiplayer.hasLatestGameState(gameInfo, it.preview)) {
                    return@receive
                }
                Concurrency.run("Load latest multiplayer state") {
                    loadLatestMultiplayerState()
                }
            }
        }

        if (restoreState != null) restore(restoreState)

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    override fun dispose() {
        super.dispose()
        events.stopReceiving()
    }

    private fun addKeyboardPresses() {
        // Space and N are assigned in createNextTurnButton
        globalShortcuts.add(Input.Keys.F1) { game.pushScreen(CivilopediaScreen(gameInfo.ruleSet)) }
        globalShortcuts.add('E') { game.pushScreen(EmpireOverviewScreen(selectedCiv)) }     // Empire overview last used page
        /*
         * These try to be faithful to default Civ5 key bindings as found in several places online
         * Some are a little arbitrary, e.g. Economic info, Military info
         * Some are very much so as Unciv *is* Strategic View and the Notification log is always visible
         */
        globalShortcuts.add(Input.Keys.F2) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Trades")) }    // Economic info
        globalShortcuts.add(Input.Keys.F3) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Units")) }    // Military info
        globalShortcuts.add(Input.Keys.F4) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Diplomacy")) }    // Diplomacy info
        globalShortcuts.add(Input.Keys.F5) { game.pushScreen(PolicyPickerScreen(this, selectedCiv)) }    // Social Policies Screen
        globalShortcuts.add(Input.Keys.F6) { game.pushScreen(TechPickerScreen(viewingCiv)) }    // Tech Screen
        globalShortcuts.add(Input.Keys.F7) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Cities")) }    // originally Notification Log
        globalShortcuts.add(Input.Keys.F8) { game.pushScreen(VictoryScreen(this)) }    // Victory Progress
        globalShortcuts.add(Input.Keys.F9) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Stats")) }    // Demographics
        globalShortcuts.add(Input.Keys.F10) { game.pushScreen(EmpireOverviewScreen(selectedCiv, "Resources")) }    // originally Strategic View
        globalShortcuts.add(Input.Keys.F11) { QuickSave.save(gameInfo, this) }    // Quick Save
        globalShortcuts.add(Input.Keys.F12) { QuickSave.load(this) }    // Quick Load
        globalShortcuts.add(Input.Keys.HOME) {    // Capital City View
            val capital = gameInfo.currentPlayerCiv.getCapital()
            if (capital != null && !mapHolder.setCenterPosition(capital.location))
                game.pushScreen(CityScreen(capital))
        }
        globalShortcuts.add(KeyCharAndCode.ctrl('O')) { // Game Options
            this.openOptionsPopup(onClose = {
                mapHolder.reloadMaxZoom()
                nextTurnButton.update(hasOpenPopups(), isPlayersTurn, waitingForAutosave, isNextTurnUpdateRunning())
            })
        }
        globalShortcuts.add(KeyCharAndCode.ctrl('S')) { game.pushScreen(SaveGameScreen(gameInfo)) }    //   Save
        globalShortcuts.add(KeyCharAndCode.ctrl('L')) { game.pushScreen(LoadGameScreen(this)) }    //   Load
        globalShortcuts.add(KeyCharAndCode.ctrl('Q')) { game.popScreen() }    //   WorldScreen is the last screen, so this quits
        globalShortcuts.add(Input.Keys.NUMPAD_ADD) { this.mapHolder.zoomIn() }    //   '+' Zoom
        globalShortcuts.add(Input.Keys.NUMPAD_SUBTRACT) { this.mapHolder.zoomOut() }    //   '-' Zoom
    }

    private fun addKeyboardListener() {
        stage.addListener(
                object : InputListener() {
                    private val pressedKeys = mutableSetOf<Int>()
                    private var infiniteAction: RepeatAction? = null
                    private val amountToMove = 6 / mapHolder.scaleX
                    private val ALLOWED_KEYS = setOf(Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D,
                            Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT)


                    override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                        if (keycode !in ALLOWED_KEYS) return false
                        // Without the following Ctrl-S would leave WASD map scrolling stuck
                        // Might be obsolete with keyboard shortcut refactoring
                        if (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) return false

                        pressedKeys.add(keycode)
                        if (infiniteAction == null) {
                            // create a copy of the action, because removeAction() will destroy this instance
                            infiniteAction = Actions.forever(Actions.delay(0.01f, Actions.run { whileKeyPressedLoop() }))
                            mapHolder.addAction(infiniteAction)
                        }
                        return true
                    }

                    fun whileKeyPressedLoop() {
                        for (keycode in pressedKeys) {
                            when (keycode) {
                                Input.Keys.W, Input.Keys.UP -> mapHolder.scrollY -= amountToMove
                                Input.Keys.S, Input.Keys.DOWN -> mapHolder.scrollY += amountToMove
                                Input.Keys.A, Input.Keys.LEFT -> mapHolder.scrollX -= amountToMove
                                Input.Keys.D, Input.Keys.RIGHT -> mapHolder.scrollX += amountToMove
                            }
                        }
                        mapHolder.updateVisualScroll()
                    }

                    override fun keyUp(event: InputEvent?, keycode: Int): Boolean {
                        if (keycode !in ALLOWED_KEYS) return false

                        pressedKeys.remove(keycode)
                        if (infiniteAction != null && pressedKeys.isEmpty()) {
                            // stop the loop otherwise it keeps going even after removal
                            infiniteAction?.finish()
                            // remove and nil the action
                            mapHolder.removeAction(infiniteAction)
                            infiniteAction = null
                        }
                        return true
                    }
                }
        )

    }

    private suspend fun loadLatestMultiplayerState(): Unit = coroutineScope {
        val loadingGamePopup = Popup(this@WorldScreen)
        launchOnGLThread {
            loadingGamePopup.addGoodSizedLabel("Loading latest game state...")
            loadingGamePopup.open()
        }

        try {
            debug("loadLatestMultiplayerState current game: gameId: %s, turn: %s, curCiv: %s",
                gameInfo.gameId, gameInfo.turns, gameInfo.currentPlayer)
            val latestGame = game.onlineMultiplayer.downloadGame(gameInfo.gameId)
            debug("loadLatestMultiplayerState downloaded game: gameId: %s, turn: %s, curCiv: %s",
                latestGame.gameId, latestGame.turns, latestGame.currentPlayer)
            if (viewingCiv.civName == latestGame.currentPlayer || viewingCiv.civName == Constants.spectator) {
                game.platformSpecificHelper?.notifyTurnStarted()
            }
            launchOnGLThread {
                loadingGamePopup.close()
            }
            startNewScreenJob(latestGame)
        } catch (ex: Throwable) {
            launchOnGLThread {
                val message = MultiplayerHelpers.getLoadExceptionMessage(ex)
                loadingGamePopup.innerTable.clear()
                loadingGamePopup.addGoodSizedLabel("Couldn't download the latest game state!").colspan(2).row()
                loadingGamePopup.addGoodSizedLabel(message).colspan(2).row()
                loadingGamePopup.addButton("Retry") {
                    launchOnThreadPool("Load latest multiplayer state after error") {
                        loadLatestMultiplayerState()
                    }
                }.right()
                loadingGamePopup.addButton("Main menu") {
                    game.pushScreen(MainMenuScreen())
                }.left()
            }
        }
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unnecessary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {

        displayTutorialsOnUpdate()

        bottomUnitTable.update()
        bottomTileInfoTable.updateTileTable(mapHolder.selectedTile)
        bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
        bottomTileInfoTable.y = if (game.settings.showMinimap) minimapWrapper.height else 0f
        battleTable.update()

        updateSelectedCiv()

        fogOfWarButton.isEnabled = !selectedCiv.isSpectator()

        tutorialTaskTable.clear()
        val tutorialTask = getCurrentTutorialTask()
        if (tutorialTask == "" || !game.settings.showTutorials || viewingCiv.isDefeated()) {
            tutorialTaskTable.isVisible = false
        } else {
            tutorialTaskTable.isVisible = true
            tutorialTaskTable.add(tutorialTask.toLabel()
                    .apply { setAlignment(Align.center) }).pad(10f)
            tutorialTaskTable.pack()
            tutorialTaskTable.centerX(stage)
            tutorialTaskTable.y = topBar.y - tutorialTaskTable.height
        }

        if (fogOfWar) minimapWrapper.update(selectedCiv)
        else minimapWrapper.update(viewingCiv)

        unitActionsTable.update(bottomUnitTable.selectedUnit)
        unitActionsTable.y = bottomUnitTable.height

        mapHolder.resetArrows()
        if (UncivGame.Current.settings.showUnitMovements) {
            val allUnits = gameInfo.civilizations.asSequence().flatMap { it.getCivUnits() }
            val allAttacks = allUnits.map { unit -> unit.attacksSinceTurnStart.asSequence().map { attacked -> Triple(unit.civInfo, unit.getTile().position, attacked) } }.flatten() +
                gameInfo.civilizations.asSequence().flatMap { civInfo -> civInfo.attacksSinceTurnStart.asSequence().map { Triple(civInfo, it.source, it.target) } }
            mapHolder.updateMovementOverlay(
                allUnits.filter(mapVisualization::isUnitPastVisible),
                allUnits.filter(mapVisualization::isUnitFutureVisible),
                allAttacks.filter { (attacker, source, target) -> mapVisualization.isAttackVisible(attacker, source, target) }
                        .map { (_, source, target) -> source to target }
            )
        }

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)

        if (fogOfWar) mapHolder.updateTiles(selectedCiv)
        else mapHolder.updateTiles(viewingCiv)

        topBar.update(selectedCiv)

        updateTechButton()
        techPolicyAndVictoryHolder.pack()
        techPolicyAndVictoryHolder.setPosition(10f, topBar.y - techPolicyAndVictoryHolder.height - 5f)
        updateDiplomacyButton(viewingCiv)

        if (!hasOpenPopups() && isPlayersTurn) {
            when {
                viewingCiv.shouldShowDiplomaticVotingResults() ->
                    UncivGame.Current.pushScreen(DiplomaticVoteResultScreen(gameInfo.diplomaticVictoryVotesCast, viewingCiv))
                !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.civilizations.any { it.victoryManager.hasWon() }) ->
                    game.pushScreen(VictoryScreen(this))
                viewingCiv.greatPeople.freeGreatPeople > 0 -> game.pushScreen(GreatPersonPickerScreen(viewingCiv))
                viewingCiv.popupAlerts.any() -> AlertPopup(this, viewingCiv.popupAlerts.first()).open()
                viewingCiv.tradeRequests.isNotEmpty() -> {
                    // In the meantime this became invalid, perhaps because we accepted previous trades
                    for (tradeRequest in viewingCiv.tradeRequests.toList())
                        if (!TradeEvaluation().isTradeValid(tradeRequest.trade, viewingCiv,
                                gameInfo.getCivilization(tradeRequest.requestingCiv)))
                            viewingCiv.tradeRequests.remove(tradeRequest)

                    if (viewingCiv.tradeRequests.isNotEmpty()) // if a valid one still exists
                        TradePopup(this).open()
                }
            }
        }

        updateGameplayButtons()

        val maxNotificationsHeight = statusButtons.y -
                (if (game.settings.showMinimap) minimapWrapper.height else 0f) - 5f
        notificationsScroll.update(viewingCiv.notifications, maxNotificationsHeight, bottomTileInfoTable.height)
        notificationsScroll.setTopRight(stage.width - 10f, statusButtons.y - 5f)

        val posZoomFromRight = if (game.settings.showMinimap) minimapWrapper.width
        else bottomTileInfoTable.width
        zoomController.setPosition(stage.width - posZoomFromRight - 10f, 10f, Align.bottomRight)
    }

    private fun getCurrentTutorialTask(): String {
        val completedTasks = game.settings.tutorialTasksCompleted
        if (!completedTasks.contains("Move unit"))
            return "Move a unit!\nClick on a unit > Click on a destination > Click the arrow popup"
        if (!completedTasks.contains("Found city"))
            return "Found a city!\nSelect the Settler (flag unit) > Click on 'Found city' (bottom-left corner)"
        if (!completedTasks.contains("Enter city screen"))
            return "Enter the city screen!\nClick the city button twice"
        if (!completedTasks.contains("Pick technology"))
            return "Pick a technology to research!\nClick on the tech button (greenish, top left) > " +
                    "\n select technology > click 'Research' (bottom right)"
        if (!completedTasks.contains("Pick construction"))
            return "Pick a construction!\nEnter city screen > Click on a unit or building (bottom left side) >" +
                    " \n click 'add to queue'"
        if (!completedTasks.contains("Pass a turn"))
            return "Pass a turn!\nCycle through units with 'Next unit' > Click 'Next turn'"
        if (!completedTasks.contains("Reassign worked tiles"))
            return "Reassign worked tiles!\nEnter city screen > click the assigned (green) tile to unassign > " +
                    "\n click an unassigned tile to assign population"
        if (!completedTasks.contains("Meet another civilization"))
            return "Meet another civilization!\nExplore the map until you encounter another civilization!"
        if (!completedTasks.contains("Open the options table"))
            return "Open the options table!\nClick the menu button (top left) > click 'Options'"
        if (!completedTasks.contains("Construct an improvement"))
            return "Construct an improvement!\nConstruct a Worker unit > Move to a Plains or Grassland tile > " +
                    "\n Click 'Create improvement' (above the unit table, bottom left)" +
                    "\n > Choose the farm > \n Leave the worker there until it's finished"
        if (!completedTasks.contains("Create a trade route")
                && viewingCiv.citiesConnectedToCapitalToMediums.any { it.key.civInfo == viewingCiv })
            game.settings.addCompletedTutorialTask("Create a trade route")
        if (viewingCiv.cities.size > 1 && !completedTasks.contains("Create a trade route"))
            return "Create a trade route!\nConstruct roads between your capital and another city" +
                    "\nOr, automate your worker and let him get to that eventually"
        if (viewingCiv.isAtWar() && !completedTasks.contains("Conquer a city"))
            return "Conquer a city!\nBring an enemy city down to low health > " +
                    "\nEnter the city with a melee unit"
        if (viewingCiv.getCivUnits().any { it.baseUnit.movesLikeAirUnits() } && !completedTasks.contains("Move an air unit"))
            return "Move an air unit!\nSelect an air unit > select another city within range > " +
                    "\nMove the unit to the other city"
        if (!completedTasks.contains("See your stats breakdown"))
            return "See your stats breakdown!\nEnter the Overview screen (top right corner) >" +
                    "\nClick on 'Stats'"

        return ""
    }

    private fun displayTutorialsOnUpdate() {

        displayTutorial(Tutorial.Introduction)

        displayTutorial(Tutorial.EnemyCityNeedsConqueringWithMeleeUnit) {
            viewingCiv.diplomacy.values.asSequence()
                    .filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv() } // we're now lazily enumerating over CivilizationInfo's we're at war with
                    .flatMap { it.cities.asSequence() } // ... all *their* cities
                    .filter { it.health == 1 } // ... those ripe for conquering
                    .flatMap { it.getCenterTile().getTilesInDistance(2).asSequence() }
                    // ... all tiles around those in range of an average melee unit
                    // -> and now we look for a unit that could do the conquering because it's ours
                    //    no matter whether civilian, air or ranged, tell user he needs melee
                    .any { it.getUnits().any { unit -> unit.civInfo == viewingCiv } }
        }
        displayTutorial(Tutorial.AfterConquering) { viewingCiv.cities.any { it.hasJustBeenConquered } }

        displayTutorial(Tutorial.InjuredUnits) { gameInfo.getCurrentPlayerCivilization().getCivUnits().any { it.health < 100 } }

        displayTutorial(Tutorial.Workers) {
            gameInfo.getCurrentPlayerCivilization().getCivUnits().any {
                it.hasUniqueToBuildImprovements && it.isCivilian() && !it.isGreatPerson()
            }
        }
    }

    private fun updateDiplomacyButton(civInfo: CivilizationInfo) {
        diplomacyButtonHolder.clear()
        if (!civInfo.isDefeated() && !civInfo.isSpectator() && civInfo.getKnownCivs()
                        .filterNot { it == viewingCiv || it.isBarbarian() }
                        .any()) {
            displayTutorial(Tutorial.OtherCivEncountered)
            val btn = "Diplomacy".toTextButton()
            btn.onClick { game.pushScreen(DiplomacyScreen(viewingCiv)) }
            btn.label.setFontSize(30)
            btn.labelCell.pad(10f)
            diplomacyButtonHolder.add(btn)
        }
        diplomacyButtonHolder.pack()
        diplomacyButtonHolder.y = techPolicyAndVictoryHolder.y - 20 - diplomacyButtonHolder.height
    }

    private fun updateTechButton() {
        if (gameInfo.ruleSet.technologies.isEmpty()) return
        techButtonHolder.isVisible = viewingCiv.cities.isNotEmpty()
        techButtonHolder.clearChildren()

        if (viewingCiv.tech.currentTechnology() != null) {
            val currentTech = viewingCiv.tech.currentTechnologyName()!!
            val innerButton = TechButton(currentTech, viewingCiv.tech)
            innerButton.color = colorFromRGB(7, 46, 43)
            techButtonHolder.add(innerButton)
            val turnsToTech = viewingCiv.tech.turnsToTech(currentTech)
            innerButton.text.setText(currentTech.tr() + "\r\n" + turnsToTech + Fonts.turn)
        } else if (viewingCiv.tech.canResearchTech() || viewingCiv.tech.researchedTechnologies.any()) {
            val buttonPic = Table()
            buttonPic.background = ImageGetter.getRoundedEdgeRectangle(colorFromRGB(7, 46, 43))
            buttonPic.defaults().pad(20f)
            val text = if (viewingCiv.tech.canResearchTech()) "{Pick a tech}!" else "Technologies"
            buttonPic.add(text.toLabel(Color.WHITE, 30))
            techButtonHolder.add(buttonPic)
        }

        techButtonHolder.pack()
    }

    private fun updateSelectedCiv() {
        when {
            bottomUnitTable.selectedUnit != null -> selectedCiv = bottomUnitTable.selectedUnit!!.civInfo
            bottomUnitTable.selectedCity != null -> selectedCiv = bottomUnitTable.selectedCity!!.civInfo
            else -> viewingCiv
        }
    }

    private fun createFogOfWarButton(): TextButton {
        val fogOfWarButton = "Fog of War".toTextButton()
        fogOfWarButton.label.setFontSize(30)
        fogOfWarButton.labelCell.pad(10f)
        fogOfWarButton.pack()
        fogOfWarButton.onClick {
            fogOfWar = !fogOfWar
            shouldUpdate = true
        }
        return fogOfWarButton

    }

    class RestoreState(
        mapHolder: WorldMapHolder,
        val selectedCivName: String,
        val viewingCivName: String,
        val fogOfWar: Boolean
    ) {
        val zoom = mapHolder.scaleX
        val scrollX = mapHolder.scrollX
        val scrollY = mapHolder.scrollY
    }
    fun getRestoreState(): RestoreState {
        return RestoreState(mapHolder, selectedCiv.civName, viewingCiv.civName, fogOfWar)
    }

    private fun restore(restoreState: RestoreState) {

        // This is not the case if you have a multiplayer game where you play as 2 civs
        if (viewingCiv.civName == restoreState.viewingCivName) {
            mapHolder.zoom(restoreState.zoom)
            mapHolder.scrollX = restoreState.scrollX
            mapHolder.scrollY = restoreState.scrollY
            mapHolder.updateVisualScroll()
        }

        selectedCiv = gameInfo.getCivilization(restoreState.selectedCivName)
        fogOfWar = restoreState.fogOfWar
    }

    fun nextTurn() {
        isPlayersTurn = false
        shouldUpdate = true

        // on a separate thread so the user can explore their world while we're passing the turn
        nextTurnUpdateJob = Concurrency.runOnNonDaemonThreadPool("NextTurn") {
            debug("Next turn starting")
            val startTime = System.currentTimeMillis()
            val originalGameInfo = gameInfo
            val gameInfoClone = originalGameInfo.clone()
            gameInfoClone.setTransients()  // this can get expensive on large games, not the clone itself

            gameInfoClone.nextTurn()

            if (originalGameInfo.gameParameters.isOnlineMultiplayer) {
                try {
                    game.onlineMultiplayer.updateGame(gameInfoClone)
                } catch (ex: Exception) {
                    val message = when (ex) {
                        is FileStorageRateLimitReached -> "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds"
                        else -> "Could not upload game!"
                    }
                    launchOnGLThread { // Since we're changing the UI, that should be done on the main thread
                        val cantUploadNewGamePopup = Popup(this@WorldScreen)
                        cantUploadNewGamePopup.addGoodSizedLabel(message).row()
                        cantUploadNewGamePopup.addCloseButton()
                        cantUploadNewGamePopup.open()
                    }
                    this@WorldScreen.isPlayersTurn = true // Since we couldn't push the new game clone, then it's like we never clicked the "next turn" button
                    this@WorldScreen.shouldUpdate = true
                    return@runOnNonDaemonThreadPool
                }
            }

            if (game.gameInfo != originalGameInfo) // while this was turning we loaded another game
                return@runOnNonDaemonThreadPool

            debug("Next turn took %sms", System.currentTimeMillis() - startTime)

            startNewScreenJob(gameInfoClone)
        }
    }

    fun switchToNextUnit() {
        // Try to select something new if we already have the next pending unit selected.
        val nextDueUnit = viewingCiv.cycleThroughDueUnits(bottomUnitTable.selectedUnit)
        if (nextDueUnit != null) {
            mapHolder.setCenterPosition(
                nextDueUnit.currentTile.position,
                immediately = false,
                selectUnit = false
            )
            bottomUnitTable.selectUnit(nextDueUnit)
            shouldUpdate = true
            // Unless 'wait' action is chosen, the unit will not be considered due anymore.
            nextDueUnit.due = false
        }
    }

    private fun isNextTurnUpdateRunning(): Boolean {
        val job = nextTurnUpdateJob
        return job != null && job.isActive
    }

    private fun updateGameplayButtons() {
        nextTurnButton.update(hasOpenPopups(), isPlayersTurn, waitingForAutosave, isNextTurnUpdateRunning(), getNextTurnAction())

        updateMultiplayerStatusButton()

        statusButtons.pack()
        statusButtons.setPosition(stage.width - statusButtons.width - 10f, topBar.y - statusButtons.height - 10f)
    }

    private fun updateMultiplayerStatusButton() {
        if (gameInfo.gameParameters.isOnlineMultiplayer || game.settings.multiplayer.statusButtonInSinglePlayer) {
            if (statusButtons.multiplayerStatusButton != null) return
            statusButtons.multiplayerStatusButton = MultiplayerStatusButton(this, game.onlineMultiplayer.getGameByGameId(gameInfo.gameId))
        } else {
            if (statusButtons.multiplayerStatusButton == null) return
            statusButtons.multiplayerStatusButton = null
        }
    }

    private fun getNextTurnAction(): NextTurnAction {
        return when {
            isNextTurnUpdateRunning() ->
                NextTurnAction("Working...", Color.GRAY) {}
            !isPlayersTurn && gameInfo.gameParameters.isOnlineMultiplayer ->
                NextTurnAction("Waiting for [${gameInfo.currentPlayerCiv}]...", Color.GRAY) {}
            !isPlayersTurn && !gameInfo.gameParameters.isOnlineMultiplayer ->
                NextTurnAction("Waiting for other players...",Color.GRAY) {}

            viewingCiv.shouldGoToDueUnit() ->
                NextTurnAction("Next unit", Color.LIGHT_GRAY) { switchToNextUnit() }

            viewingCiv.cities.any { it.cityConstructions.currentConstructionFromQueue == "" } ->
                NextTurnAction("Pick construction", Color.CORAL) {
                    val cityWithNoProductionSet = viewingCiv.cities
                        .firstOrNull { it.cityConstructions.currentConstructionFromQueue == "" }
                    if (cityWithNoProductionSet != null) game.pushScreen(
                        CityScreen(cityWithNoProductionSet)
                    )
                }

            viewingCiv.shouldOpenTechPicker() ->
                NextTurnAction("Pick a tech", Color.SKY) {
                    game.pushScreen(
                        TechPickerScreen(viewingCiv, null, viewingCiv.tech.freeTechs != 0)
                    )
                }

            viewingCiv.policies.shouldOpenPolicyPicker || (viewingCiv.policies.freePolicies > 0 && viewingCiv.policies.canAdoptPolicy()) ->
                NextTurnAction("Pick a policy", Color.VIOLET) {
                    game.pushScreen(PolicyPickerScreen(this))
                    viewingCiv.policies.shouldOpenPolicyPicker = false
                }

            viewingCiv.religionManager.canFoundPantheon() ->
                NextTurnAction("Found Pantheon", Color.WHITE) {
                    game.pushScreen(PantheonPickerScreen(viewingCiv))
                }

            viewingCiv.religionManager.religionState == ReligionState.FoundingReligion ->
                NextTurnAction("Found Religion", Color.WHITE) {
                    game.pushScreen(
                        ReligiousBeliefsPickerScreen(
                            viewingCiv,
                            viewingCiv.religionManager.getBeliefsToChooseAtFounding(),
                            pickIconAndName = true
                        )
                    )
                }

            viewingCiv.religionManager.religionState == ReligionState.EnhancingReligion ->
                NextTurnAction("Enhance a Religion", Color.ORANGE) {
                    game.pushScreen(
                        ReligiousBeliefsPickerScreen(
                            viewingCiv,
                            viewingCiv.religionManager.getBeliefsToChooseAtEnhancing(),
                            pickIconAndName = false
                        )
                    )
                }

            viewingCiv.mayVoteForDiplomaticVictory() ->
                NextTurnAction("Vote for World Leader", Color.MAROON) {
                    game.pushScreen(DiplomaticVotePickerScreen(viewingCiv))
                }

            !viewingCiv.hasMovedAutomatedUnits && viewingCiv.getCivUnits()
                .any { it.currentMovement > Constants.minimumMovementEpsilon && (it.isMoving() || it.isAutomated() || it.isExploring()) } ->
                NextTurnAction("Move automated units", Color.LIGHT_GRAY) {
                    viewingCiv.hasMovedAutomatedUnits = true
                    isPlayersTurn = false // Disable state changes
                    nextTurnButton.disable()
                    Concurrency.run("Move automated units") {
                        for (unit in viewingCiv.getCivUnits())
                            unit.doAction()
                        launchOnGLThread {
                            shouldUpdate = true
                            isPlayersTurn = true //Re-enable state changes
                            nextTurnButton.enable()
                        }
                    }
                }

            else ->
                NextTurnAction("${Fonts.turn}{Next turn}", Color.WHITE) {
                    val action = {
                        game.settings.addCompletedTutorialTask("Pass a turn")
                        nextTurn()
                    }
                    if (game.settings.confirmNextTurn) {
                        ConfirmPopup(this, "Confirm next turn", "Next turn", true, action = action).open()
                    } else {
                        action()
                    }
                }
        }
    }

    override fun resize(width: Int, height: Int) {
        if (stage.viewport.screenWidth != width || stage.viewport.screenHeight != height) {
            startNewScreenJob(gameInfo) // start over
        }
    }


    override fun render(delta: Float) {
        //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
        //    otherwise images will not load properly!
        if (shouldUpdate) {
            shouldUpdate = false

            update()
            showTutorialsOnNextTurn()
        }
//        topBar.selectedCivLabel.setText(Gdx.graphics.framesPerSecond) // for framerate testing


        super.render(delta)
    }

    private fun showTutorialsOnNextTurn() {
        if (!game.settings.showTutorials) return
        displayTutorial(Tutorial.SlowStart)
        displayTutorial(Tutorial.CityExpansion) { viewingCiv.cities.any { it.expansion.tilesClaimed() > 0 } }
        displayTutorial(Tutorial.BarbarianEncountered) { viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civInfo.isBarbarian() } } }
        displayTutorial(Tutorial.RoadsAndRailroads) { viewingCiv.cities.size > 2 }
        displayTutorial(Tutorial.Happiness) { viewingCiv.getHappiness() < 5 }
        displayTutorial(Tutorial.Unhappiness) { viewingCiv.getHappiness() < 0 }
        displayTutorial(Tutorial.GoldenAge) { viewingCiv.goldenAges.isGoldenAge() }
        displayTutorial(Tutorial.IdleUnits) { gameInfo.turns >= 50 && game.settings.checkForDueUnits }
        displayTutorial(Tutorial.ContactMe) { gameInfo.turns >= 100 }
        val resources = viewingCiv.detailedCivResources.asSequence().filter { it.origin == "All" }  // Avoid full list copy
        displayTutorial(Tutorial.LuxuryResource) { resources.any { it.resource.resourceType == ResourceType.Luxury } }
        displayTutorial(Tutorial.StrategicResource) { resources.any { it.resource.resourceType == ResourceType.Strategic } }
        displayTutorial(Tutorial.EnemyCity) {
            viewingCiv.getKnownCivs().asSequence().filter { viewingCiv.isAtWarWith(it) }
                    .flatMap { it.cities.asSequence() }.any { viewingCiv.exploredTiles.contains(it.location) }
        }
        displayTutorial(Tutorial.ApolloProgram) { viewingCiv.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts) }
        displayTutorial(Tutorial.SiegeUnits) { viewingCiv.getCivUnits().any { it.baseUnit.isProbablySiegeUnit() } }
        displayTutorial(Tutorial.Embarking) { viewingCiv.hasUnique(UniqueType.LandUnitEmbarkation) }
        displayTutorial(Tutorial.NaturalWonders) { viewingCiv.naturalWonders.size > 0 }
        displayTutorial(Tutorial.WeLoveTheKingDay) { viewingCiv.cities.any { it.demandedResource != "" } }
    }

    private fun backButtonAndESCHandler() {

        // Deselect Unit
        if (bottomUnitTable.selectedUnit != null) {
            bottomUnitTable.selectUnit()
            bottomUnitTable.isVisible = false
            shouldUpdate = true
            return
        }

        // Deselect city
        if (bottomUnitTable.selectedCity != null) {
            bottomUnitTable.selectedCity = null
            bottomUnitTable.isVisible = false
            shouldUpdate = true
            return
        }

        game.popScreen()
    }

    fun autoSave() {
        waitingForAutosave = true
        shouldUpdate = true
        UncivGame.Current.gameSaver.requestAutoSave(gameInfo).invokeOnCompletion {
            // only enable the user to next turn once we've saved the current one
            waitingForAutosave = false
            shouldUpdate = true
        }
    }
}

/** This exists so that no reference to the current world screen remains, so the old world screen can get garbage collected during [UncivGame.loadGame]. */
private fun startNewScreenJob(gameInfo: GameInfo) {
    Concurrency.run {
        val newWorldScreen = try {
            UncivGame.Current.loadGame(gameInfo)
        } catch (oom: OutOfMemoryError) {
            withGLContext {
                val mainMenu = UncivGame.Current.goToMainMenu()
                ToastPopup("Not enough memory on phone to load game!", mainMenu)
            }
            return@run
        }

        val shouldAutoSave = gameInfo.turns % UncivGame.Current.settings.turnsBetweenAutosaves == 0
        if (shouldAutoSave) {
            newWorldScreen.autoSave()
        }
    }
}
