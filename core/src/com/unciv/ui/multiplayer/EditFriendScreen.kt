package com.unciv.ui.multiplayer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.PickerScreen
import com.unciv.ui.popup.ConfirmPopup
import com.unciv.ui.popup.ToastPopup
import com.unciv.ui.utils.extensions.enable
import com.unciv.ui.utils.extensions.onClick
import com.unciv.ui.utils.extensions.toLabel
import com.unciv.ui.utils.extensions.toTextButton
import java.util.*

class EditFriendScreen(selectedFriend: FriendList.Friend) : PickerScreen() {
    init {
        val friendNameTextField = TextField(selectedFriend.name, skin)
        val pastePlayerIDButton = "Player ID from clipboard".toTextButton()
        val playerIDTextField = TextField(selectedFriend.playerID, skin)
        val deleteFriendButton = "Delete".toTextButton()
        val friendlist = FriendList()

        topTable.add("Friend name".toLabel()).row()
        friendNameTextField.messageText = "Please input a name for your friend!".tr()
        topTable.add(friendNameTextField).pad(10f).padBottom(30f).width(stage.width/2).row()

        pastePlayerIDButton.onClick {
            playerIDTextField.text = Gdx.app.clipboard.contents
        }

        topTable.add("Player ID".toLabel()).row()
        val gameIDTable = Table()
        playerIDTextField.messageText = "Please input a player ID for your friend!".tr()
        gameIDTable.add(playerIDTextField).pad(10f).width(2*stage.width/3 - pastePlayerIDButton.width)
        gameIDTable.add(pastePlayerIDButton)
        topTable.add(gameIDTable).padBottom(30f).row()

        deleteFriendButton.onClick {
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to delete this friend?",
                "Delete"
            ) {
                friendlist.delete(selectedFriend)
                goBack()
            }
            askPopup.open()
        }.apply { color = Color.RED }
        topTable.add(deleteFriendButton)

        //CloseButton Setup
        closeButton.setText("Back".tr())
        closeButton.onClick {
            goBack()
        }

        //RightSideButton Setup
        rightSideButton.setText("Save".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            // if no edits have been made, go back to friends list
            if (selectedFriend.name == friendNameTextField.text && selectedFriend.playerID == playerIDTextField.text) {
                goBack()
            }
            if (friendlist.isFriendNameInFriendList(friendNameTextField.text) == FriendList.ErrorType.ALREADYINLIST
                    && friendlist.isFriendIDInFriendList(playerIDTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                ToastPopup("Player name already used!", this)
                return@onClick
            }
            if (friendlist.isFriendIDInFriendList(playerIDTextField.text) == FriendList.ErrorType.ALREADYINLIST
                    && friendlist.isFriendNameInFriendList(friendNameTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                ToastPopup("Player ID already used!", this)
                return@onClick
            }
            try {
                UUID.fromString(IdChecker.checkAndReturnPlayerUuid(playerIDTextField.text))
            } catch (ex: Exception) {
                ToastPopup("Player ID is incorrect", this)
                return@onClick
            }
            friendlist.edit(selectedFriend, friendNameTextField.text, playerIDTextField.text)
            goBack()
        }
    }
}

fun goBack() {
    val newScreen = UncivGame.Current.popScreen()
    if (newScreen is ViewFriendsListScreen) {
        newScreen.refreshFriendsList()
    }
}
