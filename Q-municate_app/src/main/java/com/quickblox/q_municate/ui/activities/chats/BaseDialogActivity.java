package com.quickblox.q_municate.ui.activities.chats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.Loader;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.SimpleImageLoadingListener;
import com.quickblox.content.model.QBFile;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.q_municate.R;
import com.quickblox.q_municate.core.listeners.ChatUIHelperListener;
import com.quickblox.q_municate.core.listeners.OnImageSourcePickedListener;
import com.quickblox.q_municate.ui.adapters.base.BaseRecyclerViewAdapter;
import com.quickblox.q_municate_core.core.loader.BaseLoader;
import com.quickblox.q_municate.ui.activities.base.BaseLoaderActivity;
import com.quickblox.q_municate.ui.fragments.chats.EmojiFragment;
import com.quickblox.q_municate.ui.fragments.chats.EmojiGridFragment;
import com.quickblox.q_municate.ui.views.emoji.emojiTypes.EmojiObject;
import com.quickblox.q_municate.ui.fragments.dialogs.ImageSourcePickDialogFragment;
import com.quickblox.q_municate.ui.fragments.dialogs.base.TwoButtonsDialogFragment;
import com.quickblox.q_municate.utils.image.ImageLoaderUtils;
import com.quickblox.q_municate.utils.image.ImageSource;
import com.quickblox.q_municate.utils.image.ImageUtils;
import com.quickblox.q_municate.utils.KeyboardUtils;
import com.quickblox.q_municate_core.core.command.Command;
import com.quickblox.q_municate_core.models.AppSession;
import com.quickblox.q_municate_core.models.CombinationMessage;
import com.quickblox.q_municate_core.qb.commands.QBLoadAttachFileCommand;
import com.quickblox.q_municate_core.qb.commands.QBLoadDialogMessagesCommand;
import com.quickblox.q_municate_core.qb.helpers.QBBaseChatHelper;
import com.quickblox.q_municate_core.qb.helpers.QBGroupChatHelper;
import com.quickblox.q_municate_core.qb.helpers.QBPrivateChatHelper;
import com.quickblox.q_municate_core.service.QBService;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ChatUtils;
import com.quickblox.q_municate_core.utils.ConstsCore;
import com.quickblox.q_municate_core.utils.ErrorUtils;
import com.quickblox.q_municate_db.managers.DataManager;
import com.quickblox.q_municate_db.managers.DialogNotificationDataManager;
import com.quickblox.q_municate_db.managers.MessageDataManager;
import com.quickblox.q_municate_db.models.Dialog;
import com.quickblox.q_municate_db.models.DialogNotification;
import com.quickblox.q_municate_db.models.DialogOccupant;
import com.quickblox.q_municate_db.models.Message;
import com.quickblox.q_municate_db.models.User;

import java.io.File;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.Bind;
import butterknife.OnClick;
import butterknife.OnTextChanged;

public abstract class BaseDialogActivity extends BaseLoaderActivity<List<CombinationMessage>> implements
        ChatUIHelperListener, EmojiGridFragment.OnEmojiconClickedListener,
        EmojiFragment.OnEmojiBackspaceClickedListener, OnImageSourcePickedListener {

    private static final int TYPING_DELAY = 1000;
    private static final int DELAY_SCROLLING_LIST = 300;

    private static Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    @Bind(R.id.messages_recycleview)
    protected RecyclerView messagesRecyclerView;

    @Bind(R.id.message_edittext)
    protected EditText messageEditText;

    @Bind(R.id.send_button)
    protected ImageButton sendButton;

    @Bind(R.id.message_typing_view)
    protected View messageTypingView;

    @Bind(R.id.smile_panel_imagebutton)
    protected ImageButton smilePanelImageButton;

    @Bind(R.id.message_typing_box_imageview)
    protected ImageView messageTypingBoxImageView;

    protected View emojisFragment;

    protected Dialog dialog;
    protected Resources resources;
    protected DataManager dataManager;
    protected ImageUtils imageUtils;
    protected BaseRecyclerViewAdapter messagesAdapter;
    protected User opponentUser;
    protected QBBaseChatHelper baseChatHelper;
    protected List<CombinationMessage> combinationMessagesList;
    protected int chatHelperIdentifier;

    private View rootView;
    private int keyboardHeight;
    private boolean needToShowSmileLayout;
    private LoadAttachFileSuccessAction loadAttachFileSuccessAction;
    private LoadDialogMessagesSuccessAction loadDialogMessagesSuccessAction;
    private LoadDialogMessagesFailAction loadDialogMessagesFailAction;
    private AnimationDrawable messageTypingAnimationDrawable;
    private Timer typingTimer;
    private boolean isTypingNow;
    private int skipMessages;
    private Observer messageObserver;
    private Observer dialogNotificationObserver;
    private BroadcastReceiver typingMessageBroadcastReceiver;
    private BroadcastReceiver updatingDialogBroadcastReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        rootView = getLayoutInflater().inflate(R.layout.activity_dialog, null);
        setContentView(rootView);

        activateButterKnife();

        initActionBar();

        initFields();
        initUI();
        initListeners();

        addActions();
        registerBroadcastReceivers();
        addObservers();
        hideSmileLayout();
    }

    @Override
    public void initActionBar() {
        super.initActionBar();
        setActionBarUpButtonEnabled(true);
    }

    private void initFields() {
        resources = getResources();
        dataManager = DataManager.getInstance();
        imageUtils = new ImageUtils(this);
        loadAttachFileSuccessAction = new LoadAttachFileSuccessAction();
        loadDialogMessagesSuccessAction = new LoadDialogMessagesSuccessAction();
        loadDialogMessagesFailAction = new LoadDialogMessagesFailAction();
        typingTimer = new Timer();
        messageObserver = new MessageObserver();
        dialogNotificationObserver = new DialogNotificationObserver();
        typingMessageBroadcastReceiver = new TypingStatusBroadcastReceiver();
        updatingDialogBroadcastReceiver = new UpdatingDialogBroadcastReceiver();
    }

    private void initUI() {
        emojisFragment = _findViewById(R.id.emojicons_fragment);
        sendButton.setEnabled(false);
        messageTypingAnimationDrawable = (AnimationDrawable) messageTypingBoxImageView.getDrawable();
    }

    protected void initMessagesRecyclerView() {
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setItemAnimator(new DefaultItemAnimator());
//        mainThreadHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
//            }
//        });
    }

    private void initListeners() {
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    @Override
                    public void onGlobalLayout() {
                        initKeyboardHeight();
                        if (needToShowSmileLayout) {
                            showSmileLayout(keyboardHeight);
                        }
                    }
                });
    }

    @OnTextChanged(R.id.message_edittext)
    void messageEditTextChanged(CharSequence charSequence) {
        setSendButtonVisibility(charSequence);

        // TODO: now it is possible only for Private chats
        if (Dialog.Type.PRIVATE.equals(dialog.getType())) {
            if (!isTypingNow) {
                isTypingNow = true;
                sendTypingStatus();
            }
            checkStopTyping();
        }
    }

    @OnClick(R.id.smile_panel_imagebutton)
    void smilePanelImageButtonClicked() {
        if (isSmilesLayoutShowing()) {
            hideSmileLayout();
            KeyboardUtils.showKeyboard(BaseDialogActivity.this);
        } else {
            KeyboardUtils.hideKeyboard(BaseDialogActivity.this);
            needToShowSmileLayout = true;
            if (keyboardHeight == ConstsCore.ZERO_INT_VALUE) {
                showSmileLayout(ConstsCore.ZERO_INT_VALUE);
            }
        }
    }

    protected void addActions() {
        addAction(QBServiceConsts.LOAD_ATTACH_FILE_SUCCESS_ACTION, loadAttachFileSuccessAction);
        addAction(QBServiceConsts.LOAD_ATTACH_FILE_FAIL_ACTION, failAction);

        addAction(QBServiceConsts.LOAD_DIALOG_MESSAGES_SUCCESS_ACTION, loadDialogMessagesSuccessAction);
        addAction(QBServiceConsts.LOAD_DIALOG_MESSAGES_FAIL_ACTION, loadDialogMessagesFailAction);

        updateBroadcastActionList();
    }

    protected void removeActions() {
        removeAction(QBServiceConsts.LOAD_ATTACH_FILE_SUCCESS_ACTION);
        removeAction(QBServiceConsts.LOAD_ATTACH_FILE_FAIL_ACTION);

        removeAction(QBServiceConsts.LOAD_DIALOG_MESSAGES_SUCCESS_ACTION);
        removeAction(QBServiceConsts.LOAD_DIALOG_MESSAGES_FAIL_ACTION);

        removeAction(QBServiceConsts.ACCEPT_FRIEND_SUCCESS_ACTION);
        removeAction(QBServiceConsts.ACCEPT_FRIEND_FAIL_ACTION);

        removeAction(QBServiceConsts.REJECT_FRIEND_SUCCESS_ACTION);
        removeAction(QBServiceConsts.REJECT_FRIEND_FAIL_ACTION);

        updateBroadcastActionList();
    }

    private void registerBroadcastReceivers() {
        localBroadcastManager.registerReceiver(typingMessageBroadcastReceiver,
                new IntentFilter(QBServiceConsts.TYPING_MESSAGE));
        localBroadcastManager.registerReceiver(updatingDialogBroadcastReceiver,
                new IntentFilter(QBServiceConsts.UPDATE_DIALOG));
    }

    private void unregisterBroadcastReceivers() {
        localBroadcastManager.unregisterReceiver(updatingDialogBroadcastReceiver);
        localBroadcastManager.unregisterReceiver(typingMessageBroadcastReceiver);
    }

    private void addObservers() {
        dataManager.getMessageDataManager().addObserver(messageObserver);
        dataManager.getDialogNotificationDataManager().addObserver(dialogNotificationObserver);
    }

    private void deleteObservers() {
        dataManager.getMessageDataManager().deleteObserver(messageObserver);
        dataManager.getDialogNotificationDataManager().deleteObserver(dialogNotificationObserver);
    }

    @Override
    public void onBackPressed() {
        if (isSmilesLayoutShowing()) {
            hideSmileLayout();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        createChatLocally();
    }

    @Override
    protected void onPause() {
        super.onPause();

        hideSmileLayout();

        // TODO: now it is possible only for Private chats
        if (Dialog.Type.PRIVATE.equals(dialog.getType())) {
            if (isTypingNow) {
                isTypingNow = false;
                sendTypingStatus();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        readAllMessages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeChatLocally();
        removeActions();
        deleteObservers();
        unregisterBroadcastReceivers();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        canPerformLogout.set(true);
        if ((isGalleryCalled(requestCode) || isCaptureCalled(requestCode)) && resultCode == RESULT_OK) {
            if (data.getData() == null) {
                onFileSelected((Bitmap) data.getExtras().get("data"));
            } else {
                onFileSelected(data.getData());
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onConnectedToService(QBService service) {
        super.onConnectedToService(service);
        onConnectServiceLocally(service);
    }

    @Override
    public void onEmojiconClicked(EmojiObject emojiObject) {
        EmojiFragment.input(messageEditText, emojiObject);
    }

    @Override
    public void onEmojiBackspaceClicked(View v) {
        EmojiFragment.backspace(messageEditText);
    }

    protected void updateData() {
        dialog = dataManager.getDialogDataManager().getByDialogId(dialog.getDialogId());
        if (dialog != null) {
            updateActionBar();
        }
        updateMessagesList();
    }

    protected void onConnectServiceLocally() {
        createChatLocally();
    }

    protected void startMessageTypingAnimation() {
        messageTypingView.setVisibility(View.VISIBLE);
        messageTypingAnimationDrawable.start();
    }

    protected void stopMessageTypingAnimation() {
        messageTypingView.setVisibility(View.GONE);
        messageTypingAnimationDrawable.stop();
    }

    protected void attachButtonOnClick() {
        canPerformLogout.set(false);
        ImageSourcePickDialogFragment.show(getSupportFragmentManager(), this);
    }

    protected void deleteTempMessages() {
        List<DialogOccupant> dialogOccupantsList = dataManager.getDialogOccupantDataManager()
                .getDialogOccupantsListByDialogId(dialog.getDialogId());
        List<Integer> dialogOccupantsIdsList = ChatUtils.getIdsFromDialogOccupantsList(dialogOccupantsList);
        dataManager.getMessageDataManager().deleteTempMessages(dialogOccupantsIdsList);
    }

    @Override
    public void onImageSourcePicked(ImageSource source) {
        switch (source) {
            case GALLERY:
                imageUtils.getImage();
                break;
            case CAMERA:
                imageUtils.getCaptureImage();
                break;
        }
    }

    @Override
    public void onImageSourceClosed() {
        canPerformLogout.set(true);
    }

    private void hideSmileLayout() {
        emojisFragment.setVisibility(View.GONE);
        setSmilePanelIcon(R.drawable.ic_smile_dark);
    }

    private void showSmileLayout(int keyboardHeight) {
        needToShowSmileLayout = false;
        emojisFragment.setVisibility(View.VISIBLE);
        if (keyboardHeight != ConstsCore.ZERO_INT_VALUE) {
            ViewGroup.LayoutParams params = emojisFragment.getLayoutParams();
            params.height = keyboardHeight;
            emojisFragment.setLayoutParams(params);
        }
        setSmilePanelIcon(R.drawable.ic_keyboard_dark);
    }

    private boolean isGalleryCalled(int requestCode) {
        return ImageUtils.GALLERY_INTENT_CALLED == requestCode;
    }

    private boolean isCaptureCalled(int requestCode) {
        return ImageUtils.CAPTURE_CALLED == requestCode;
    }

    protected void loadLogoActionBar(String logoUrl) {
        ImageLoader.getInstance().loadImage(logoUrl, ImageLoaderUtils.UIL_USER_AVATAR_DISPLAY_OPTIONS,
                new SimpleImageLoadingListener() {

                    @Override
                    public void onLoadingComplete(String imageUri, View view, Bitmap loadedBitmap) {
                        setActionBarIcon(
                                ImageUtils.getRoundIconDrawable(BaseDialogActivity.this, loadedBitmap));
                    }
                });
    }

    protected void startLoadAttachFile(final File file) {
        TwoButtonsDialogFragment.show(getSupportFragmentManager(), R.string.dlg_confirm_sending_attach,
                new MaterialDialog.ButtonCallback() {
                    @Override
                    public void onPositive(MaterialDialog dialog) {
                        super.onPositive(dialog);
                        showProgress();
                        QBLoadAttachFileCommand.start(BaseDialogActivity.this, file);
                    }
                });
    }

    protected void startLoadDialogMessages(Dialog dialog, long lastDateLoad) {
        QBLoadDialogMessagesCommand.start(this, ChatUtils.createQBDialogFromLocalDialog(dialog), lastDateLoad,
                skipMessages);
        skipMessages += ConstsCore.DIALOG_MESSAGES_PER_PAGE;
    }

    private void setSendButtonVisibility(CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence) || TextUtils.isEmpty(charSequence.toString().trim())) {
            sendButton.setEnabled(false);
        } else {
            sendButton.setEnabled(true);
        }
    }

    @Override
    public void onScrollMessagesToBottom() {
        scrollMessagesToBottom();
    }

    @Override
    public void onScreenResetPossibilityPerformLogout(boolean canPerformLogout) {
        this.canPerformLogout.set(canPerformLogout);
    }

    private void checkStopTyping() {
        typingTimer.cancel();
        typingTimer = new Timer();
        typingTimer.schedule(new TypingTimerTask(), TYPING_DELAY);
    }

    private void sendTypingStatus() {
        baseChatHelper.sendTypingStatusToServer(opponentUser.getUserId(), isTypingNow);
    }

    private void initKeyboardHeight() {
        final int EXPECTED_HEIGHT = 100;
        Rect r = new Rect();
        rootView.getWindowVisibleDisplayFrame(r);
        int screenHeight = rootView.getRootView().getHeight();
        int heightDifference = screenHeight - (r.bottom - r.top);
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > ConstsCore.ZERO_INT_VALUE) {
            heightDifference -= getResources().getDimensionPixelSize(resourceId);
        }
        if (heightDifference > EXPECTED_HEIGHT) {
            keyboardHeight = heightDifference;
        }
    }

    private void setSmilePanelIcon(int resourceId) {
        smilePanelImageButton.setImageResource(resourceId);
    }

    protected boolean isSmilesLayoutShowing() {
        return emojisFragment.getVisibility() == View.VISIBLE;
    }

    protected void scrollMessagesToBottom() {
        scrollMessagesWithDelay();
    }

    private void scrollMessagesWithDelay() {
        mainThreadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
            }
        }, DELAY_SCROLLING_LIST);
    }

    protected void sendMessage(boolean privateMessage) {
        boolean error = false;
        try {
            if (privateMessage) {
                ((QBPrivateChatHelper) baseChatHelper).sendPrivateMessage(
                        messageEditText.getText().toString(), opponentUser.getUserId());
            } else {
                ((QBGroupChatHelper) baseChatHelper).sendGroupMessage(dialog.getRoomJid(),
                        messageEditText.getText().toString());
            }
        } catch (QBResponseException e) {
            ErrorUtils.showError(this, e);
            error = true;
        } catch (IllegalStateException e) {
            ErrorUtils.showError(this, this.getString(
                    com.quickblox.q_municate_core.R.string.dlg_not_joined_room));
            error = true;
        } catch (Exception e) {
            ErrorUtils.showError(this, e);
            error = true;
        }

        if (!error) {
            messageEditText.setText(ConstsCore.EMPTY_STRING);
//            Message tempLocalMessage = ChatUtils.createTempLocalMessage(
//                    dialog.hashCode() - System.currentTimeMillis(),
//                    null,
//                    messageEditText.getText().toString(),
//                    null);
//            DialogOccupant dialogOccupant = new DialogOccupant();
//            dialogOccupant.setDialog(dialog);
//            dialogOccupant.setUser(UserFriendUtils.createLocalUser(AppSession.getSession().getUser()));
//            tempLocalMessage.setDialogOccupant(dialogOccupant);
//            tempLocalMessage.setCreatedDate(DateUtilsCore.getCurrentTime()-20);
//            messagesAdapter.addItem(new CombinationMessage(tempLocalMessage));
            scrollMessagesToBottom();
        }
    }

    protected void startLoadDialogMessages() {
        if (dialog == null) {
            return;
        }

        showActionBarProgress();

        List<DialogOccupant> dialogOccupantsList = dataManager.getDialogOccupantDataManager()
                .getDialogOccupantsListByDialogId(dialog.getDialogId());
        List<Integer> dialogOccupantsIdsList = ChatUtils.getIdsFromDialogOccupantsList(dialogOccupantsList);
        Message lastReadMessage = dataManager.getMessageDataManager().getLastMessageByDialogId(
                dialogOccupantsIdsList);
        if (lastReadMessage == null) {
            startLoadDialogMessages(dialog, ConstsCore.ZERO_LONG_VALUE);
        } else {
            long lastMessageDateSent = lastReadMessage.getCreatedDate();
            startLoadDialogMessages(dialog, lastMessageDateSent);
        }
    }

    private void readAllMessages() {
        List<Message> messagesList = dataManager.getMessageDataManager().getMessagesByDialogId(
                dialog.getDialogId());
        dataManager.getMessageDataManager().createOrUpdate(ChatUtils.readAllMessages(messagesList,
                AppSession.getSession().getUser()));

        List<DialogNotification> dialogNotificationsList = dataManager.getDialogNotificationDataManager()
                .getDialogNotificationsByDialogId(dialog.getDialogId());
        dataManager.getDialogNotificationDataManager().createOrUpdate(ChatUtils.readAllDialogNotification(
                dialogNotificationsList, AppSession.getSession().getUser()));
    }

    @Override
    public Loader<List<CombinationMessage>> createDataLoader() {
        return new CombinationMessageLoader(this, dataManager, dialog.getDialogId());
    }

    private void createChatLocally() {
        if (service != null) {
            baseChatHelper = (QBBaseChatHelper) service.getHelper(chatHelperIdentifier);
            if (baseChatHelper != null && dialog != null) {
                try {
                    baseChatHelper.createChatLocally(ChatUtils.createQBDialogFromLocalDialog(dialog),
                            generateBundleToInitDialog());
                } catch (QBResponseException e) {
                    ErrorUtils.showError(this, e.getMessage());
                    finish();
                }
            }
        }
    }

    private void closeChatLocally() {
        if (baseChatHelper != null) {
            baseChatHelper.closeChat(ChatUtils.createQBDialogFromLocalDialog(dialog),
                    generateBundleToInitDialog());
        }
        dialog = null;
    }

    protected abstract void updateActionBar();

    protected abstract void onConnectServiceLocally(QBService service);

    protected abstract void onFileSelected(Uri originalUri);

    protected abstract void onFileSelected(Bitmap bitmap);

    protected abstract Bundle generateBundleToInitDialog();

    protected abstract void updateMessagesList();

    protected abstract void onFileLoaded(QBFile file);

    public static class CombinationMessageLoader extends BaseLoader<List<CombinationMessage>> {

        private String dialogId;

        public CombinationMessageLoader(Context context, DataManager dataManager, String dialogId) {
            super(context, dataManager);
            this.dialogId = dialogId;
        }

        @Override
        protected List<CombinationMessage> getItems() {
            return createCombinationMessagesList();
        }

        private List<CombinationMessage> createCombinationMessagesList() {
            List<Message> messagesList = dataManager.getMessageDataManager().getMessagesByDialogId(dialogId);
            List<DialogNotification> dialogNotificationsList = dataManager.getDialogNotificationDataManager()
                    .getDialogNotificationsByDialogId(dialogId);
            return ChatUtils.createCombinationMessagesList(messagesList, dialogNotificationsList);
        }
    }

    private class MessageObserver implements Observer {

        @Override
        public void update(Observable observable, Object data) {
            if (data != null && data.equals(MessageDataManager.OBSERVE_KEY)) {
                updateMessagesList();
            }
        }
    }

    private class DialogNotificationObserver implements Observer {

        @Override
        public void update(Observable observable, Object data) {
            if (data != null && data.equals(DialogNotificationDataManager.OBSERVE_KEY)) {
                updateMessagesList();
            }
        }
    }

    private class TypingTimerTask extends TimerTask {

        @Override
        public void run() {
            isTypingNow = false;
            sendTypingStatus();
        }
    }

    public class LoadAttachFileSuccessAction implements Command {

        @Override
        public void execute(Bundle bundle) {
            QBFile file = (QBFile) bundle.getSerializable(QBServiceConsts.EXTRA_ATTACH_FILE);
            onFileLoaded(file);
            hideProgress();
        }
    }

    public class LoadDialogMessagesSuccessAction implements Command {

        @Override
        public void execute(Bundle bundle) {
            if (messagesAdapter != null && !messagesAdapter.isEmpty()) {
                scrollMessagesToBottom();
            }
        }
    }

    public class LoadDialogMessagesFailAction implements Command {

        @Override
        public void execute(Bundle bundle) {
            hideActionBarProgress();
        }
    }

    private class TypingStatusBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            int userId = extras.getInt(QBServiceConsts.EXTRA_USER_ID);
            // TODO: now it is possible only for Private chats
            if (dialog != null && opponentUser != null && userId == opponentUser.getUserId()) {
                if (Dialog.Type.PRIVATE.equals(dialog.getType())) {
                    boolean isTyping = extras.getBoolean(QBServiceConsts.EXTRA_IS_TYPING);
                    if (isTyping) {
                        startMessageTypingAnimation();
                    } else {
                        stopMessageTypingAnimation();
                    }
                }
            }
        }
    }

    private class UpdatingDialogBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(QBServiceConsts.UPDATE_DIALOG)) {
                updateData();
            }
        }
    }
}