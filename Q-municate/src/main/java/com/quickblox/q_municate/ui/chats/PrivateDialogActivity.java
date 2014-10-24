package com.quickblox.q_municate.ui.chats;

import android.app.ActionBar;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.quickblox.internal.core.exception.QBResponseException;
import com.quickblox.module.chat.model.QBDialog;
import com.quickblox.module.chat.model.QBDialogType;
import com.quickblox.module.content.model.QBFile;
import com.quickblox.module.videochat_webrtc.WebRTC;
import com.quickblox.q_municate.R;
import com.quickblox.q_municate.db.DatabaseManager;
import com.quickblox.q_municate.db.tables.MessageTable;
import com.quickblox.q_municate.model.AppSession;
import com.quickblox.q_municate.model.User;
import com.quickblox.q_municate.qb.commands.QBAcceptFriendCommand;
import com.quickblox.q_municate.qb.commands.QBRejectFriendCommand;
import com.quickblox.q_municate.qb.commands.QBUpdateDialogCommand;
import com.quickblox.q_municate.qb.helpers.QBPrivateChatHelper;
import com.quickblox.q_municate.service.QBService;
import com.quickblox.q_municate.service.QBServiceConsts;
import com.quickblox.q_municate.ui.dialogs.AlertDialog;
import com.quickblox.q_municate.ui.mediacall.CallActivity;
import com.quickblox.q_municate.utils.Consts;
import com.quickblox.q_municate.utils.DateUtils;
import com.quickblox.q_municate.utils.ErrorUtils;
import com.quickblox.q_municate.utils.ReceiveFileFromBitmapTask;

import java.io.File;

import de.keyboardsurfer.android.widget.crouton.Crouton;
import se.emilsjolander.stickylistheaders.StickyListHeadersAdapter;

public class PrivateDialogActivity extends BaseDialogActivity implements ReceiveFileFromBitmapTask.ReceiveFileListener {

    private User opponentFriend;
    private ContentObserver statusContentObserver;
    private Cursor friendCursor;
    private FriendOperationAction friendOperationAction;

    public PrivateDialogActivity() {
        super(R.layout.activity_dialog, QBService.PRIVATE_CHAT_HELPER);
    }

    public static void start(Context context, User opponent, QBDialog dialog) {
        Intent intent = new Intent(context, PrivateDialogActivity.class);
        intent.putExtra(QBServiceConsts.EXTRA_OPPONENT, opponent);
        intent.putExtra(QBServiceConsts.EXTRA_DIALOG, dialog);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        friendOperationAction = new FriendOperationAction();
        opponentFriend = (User) getIntent().getExtras().getSerializable(QBServiceConsts.EXTRA_OPPONENT);
        dialog = (QBDialog) getIntent().getExtras().getSerializable(QBServiceConsts.EXTRA_DIALOG);
        dialogId = dialog.getDialogId();
        friendCursor = DatabaseManager.getFriendCursorById(this, opponentFriend.getUserId());
        initListView();
        initActionBar();
        registerStatusChangingObserver();
        setCurrentDialog(dialog);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Crouton.cancelAllCroutons();
    }

    @Override
    protected void onUpdateChatDialog() {
        if (!messagesAdapter.isEmpty()) {
            startUpdateChatDialog();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        currentOpponent = null;
        unregisterStatusChangingObserver();
    }

    @Override
    protected void onFileSelected(Uri originalUri) {
        Bitmap bitmap = imageUtils.getBitmap(originalUri);
        new ReceiveFileFromBitmapTask(PrivateDialogActivity.this).execute(imageUtils, bitmap, true);
    }

    @Override
    protected void onFileLoaded(QBFile file) {
        try {
            ((QBPrivateChatHelper) chatHelper).sendPrivateMessageWithAttachImage(file,
                    opponentFriend.getUserId());
        } catch (QBResponseException exc) {
            ErrorUtils.showError(this, exc);
        }
        scrollListView();
    }

    @Override
    protected Bundle generateBundleToInitDialog() {
        Bundle bundle = new Bundle();
        bundle.putInt(QBServiceConsts.EXTRA_OPPONENT_ID, opponentFriend.getUserId());
        return bundle;
    }

    private void registerStatusChangingObserver() {
        statusContentObserver = new ContentObserver(new Handler()) {

            @Override
            public boolean deliverSelfNotifications() {
                return true;
            }

            @Override
            public void onChange(boolean selfChange) {
                opponentFriend = DatabaseManager.getUserById(PrivateDialogActivity.this,
                        PrivateDialogActivity.this.opponentFriend.getUserId());
                setOnlineStatus(opponentFriend);
            }
        };
        friendCursor.registerContentObserver(statusContentObserver);
    }

    private void unregisterStatusChangingObserver() {
        if (friendCursor != null && statusContentObserver != null) {
            friendCursor.unregisterContentObserver(statusContentObserver);
        }
    }

    private void setOnlineStatus(User friend) {
        ActionBar actionBar = getActionBar();
        actionBar.setSubtitle(friend.getOnlineStatus());
    }

    private void startUpdateChatDialog() {
        if (dialog != null) {
            QBUpdateDialogCommand.start(this, getDialog());
        }
    }

    private QBDialog getDialog() {
        Cursor cursor = (Cursor) messagesAdapter.getItem(messagesAdapter.getCount() - 1);
        String lastMessage = cursor.getString(cursor.getColumnIndex(MessageTable.Cols.BODY));
        Integer senderId = cursor.getInt(cursor.getColumnIndex(MessageTable.Cols.SENDER_ID));
        dialog.setLastMessage(lastMessage);
        dialog.setLastMessageDateSent(DateUtils.getCurrentTime());
        dialog.setUnreadMessageCount(Consts.ZERO_INT_VALUE);
        dialog.setLastMessageUserId(senderId);
        dialog.setType(QBDialogType.PRIVATE);
        return dialog;
    }

    private void initListView() {
        messagesAdapter = new PrivateDialogMessagesAdapter(this, friendOperationAction,
                getAllDialogMessagesByDialogId(), this, dialog);
        messagesListView.setAdapter((StickyListHeadersAdapter) messagesAdapter);
        ((PrivateDialogMessagesAdapter) messagesAdapter).findLastFriendsRequestMessagesPosition();
    }

    private void initActionBar() {
        actionBar.setTitle(opponentFriend.getFullName());
        actionBar.setSubtitle(opponentFriend.getOnlineStatus());
        actionBar.setLogo(R.drawable.placeholder_user);
        if (!TextUtils.isEmpty(opponentFriend.getAvatarUrl())) {
            loadLogoActionBar(opponentFriend.getAvatarUrl());
        }
    }

    @Override
    public void onCachedImageFileReceived(File file) {
        startLoadAttachFile(file);
    }

    @Override
    public void onAbsolutePathExtFileReceived(String absolutePath) {
    }

    public void sendMessageOnClick(View view) {
        try {
            ((QBPrivateChatHelper) chatHelper).sendPrivateMessage(messageEditText.getText().toString(),
                    opponentFriend.getUserId());
        } catch (QBResponseException exc) {
            ErrorUtils.showError(this, exc);
        }
        messageEditText.setText(Consts.EMPTY_STRING);
        isNeedToScrollMessages = true;
        scrollListView();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.private_dialog_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                navigateToParent();
                return true;
            case R.id.action_attach:
                attachButtonOnClick();
                return true;
            case R.id.action_audio_call:
                callToUser(opponentFriend, WebRTC.MEDIA_STREAM.AUDIO);
                return true;
            case R.id.action_video_call:
                callToUser(opponentFriend, WebRTC.MEDIA_STREAM.VIDEO);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void callToUser(User friend, WebRTC.MEDIA_STREAM callType) {
        if (friend.getUserId() != AppSession.getSession().getUser().getId()) {
            CallActivity.start(PrivateDialogActivity.this, friend, callType);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        scrollListView();
        startLoadDialogMessages();
        currentOpponent = opponentFriend.getFullName();
    }

    private void acceptUser(final int userId) {
        showProgress();
        QBAcceptFriendCommand.start(this, userId);
    }

    private void rejectUser(final int userId) {
        showRejectUserDialog(userId);
    }

    private void showRejectUserDialog(final int userId) {
        User user = DatabaseManager.getUserById(this, userId);
        AlertDialog alertDialog = AlertDialog.newInstance(getResources().getString(
                R.string.frl_dlg_reject_friend, user.getFullName()));
        alertDialog.setPositiveButton(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showProgress();
                QBRejectFriendCommand.start(PrivateDialogActivity.this, userId);
            }
        });
        alertDialog.setNegativeButton(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        alertDialog.show(getFragmentManager(), null);
    }

    public interface FriendOperationListener {

        void onAcceptUserClicked(int userId);

        void onRejectUserClicked(int userId);
    }

    private class FriendOperationAction implements FriendOperationListener {

        @Override
        public void onAcceptUserClicked(int userId) {
            acceptUser(userId);
        }

        @Override
        public void onRejectUserClicked(int userId) {
            rejectUser(userId);
        }
    }
}