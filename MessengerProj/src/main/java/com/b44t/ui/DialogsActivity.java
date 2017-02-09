/*******************************************************************************
 *
 *                          Messenger Android Frontend
 *                        (C) 2013-2016 Nikolai Kudashov
 *                           (C) 2017 Björn Petersen
 *                    Contact: r10s@b44t.com, http://b44t.com
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see http://www.gnu.org/licenses/ .
 *
 ******************************************************************************/


package com.b44t.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.b44t.messenger.AndroidUtilities;
import com.b44t.messenger.ImageLoader;
import com.b44t.messenger.LocaleController;
import com.b44t.messenger.MrChat;
import com.b44t.messenger.MrMailbox;
import com.b44t.messenger.MrMsg;
import com.b44t.messenger.support.widget.LinearLayoutManager;
import com.b44t.messenger.support.widget.RecyclerView;
import com.b44t.messenger.FileLog;
import com.b44t.messenger.MessagesController;
import com.b44t.messenger.NotificationCenter;
import com.b44t.messenger.R;
import com.b44t.messenger.UserConfig;
import com.b44t.ui.Adapters.DialogsAdapter;
import com.b44t.ui.Adapters.DialogsSearchAdapter;
import com.b44t.ui.Cells.UserCell;
import com.b44t.ui.Cells.DialogCell;
import com.b44t.ui.ActionBar.ActionBar;
import com.b44t.ui.ActionBar.ActionBarMenu;
import com.b44t.ui.ActionBar.ActionBarMenuItem;
import com.b44t.ui.ActionBar.BaseFragment;
import com.b44t.ui.ActionBar.MenuDrawable;
import com.b44t.ui.Components.PlayerView;
import com.b44t.ui.Components.EmptyTextProgressView;
import com.b44t.ui.Components.LayoutHelper;
import com.b44t.ui.Components.RecyclerListView;
import com.b44t.ui.ActionBar.Theme;

import java.util.ArrayList;

public class DialogsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private DialogsAdapter dialogsAdapter;
    private DialogsSearchAdapter dialogsSearchAdapter;
    private EmptyTextProgressView searchEmptyView;
    private LinearLayout emptyView;
    private ActionBarMenuItem passcodeItem;
    private ImageView floatingButton;

    // Floating hiding action as in Telegram - I think this is not useful:
    // - it always takes a moment to check if the button is there or not (ot to let it appear)
    // - if there is nothing to scroll the floting button does not move away -
    //   and covers always a part of the last row. This is not better than without moving away.
    // - hidden or not, covered parts oif the last row can be seen by moving the content
        /* private int prevPosition;
        private int prevTop;
        private boolean scrollUpdated; */
    // /Floating hiding action

    private boolean floatingHidden;
    private final AccelerateDecelerateInterpolator floatingInterpolator = new AccelerateDecelerateInterpolator();

    private boolean checkPermission = true;

    private String selectAlertString;

    private static boolean dialogsLoaded;
    private boolean searching;
    private boolean searchWas;
    private boolean onlySelect;
    private String onlySelectTitle = "";
    private long openedDialogId;

    private DialogsActivityDelegate delegate;

    public interface DialogsActivityDelegate {
        void didSelectDialog(DialogsActivity fragment, long dialog_id, boolean param);
    }

    public DialogsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        if (getArguments() != null) {
            onlySelect = arguments.getBoolean("onlySelect", false);
            onlySelectTitle = arguments.getString("onlySelectTitle");
            if( onlySelectTitle==null || onlySelectTitle.isEmpty()) {
                onlySelectTitle = LocaleController.getString("SelectChat", R.string.SelectChat);
            }
            selectAlertString = arguments.getString("selectAlertString");
        }

        NotificationCenter.getInstance().addObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.emojiDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.contactsDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.openedChatChanged);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.didSetPasscode);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.reloadHints);

        if (!dialogsLoaded) {
            NotificationCenter.getInstance().postNotificationName(NotificationCenter.dialogsNeedReload); // this is the rest of the first call to the removed MessagesController.loadDialogs(); not sure, if this is really needed
            dialogsLoaded = true;
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();

        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.emojiDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.contactsDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.openedChatChanged);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.didSetPasscode);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.reloadHints);

        delegate = null;
    }

    @Override
    public View createView(final Context context) {
        searching = false;
        searchWas = false;

        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                Theme.loadRecources(context);
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        if (!onlySelect ) {
            passcodeItem = menu.addItem(1, R.drawable.lock_close);
            updatePasscodeButton();
        }
        final ActionBarMenuItem item = menu.addItem(0, R.drawable.ic_ab_search).setIsSearchField(true, true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (listView != null) {
                    if (!onlySelect) {
                        floatingButton.setVisibility(View.GONE);
                    }
                }
                updatePasscodeButton();
            }

            @Override
            public boolean canCollapseSearch() {
                return true;
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                searchWas = false;
                if (listView != null) {
                    searchEmptyView.setVisibility(View.GONE);
                    listView.setEmptyView(emptyView);
                    if (!onlySelect) {
                        floatingButton.setVisibility(View.VISIBLE);
                        floatingHidden = true;
                        floatingButton.setTranslationY(AndroidUtilities.dp(100));
                        hideFloatingButton(false);
                    }
                    if (listView.getAdapter() != dialogsAdapter) {
                        listView.setAdapter(dialogsAdapter);
                        dialogsAdapter.notifyDataSetChanged();
                    }
                }
                updatePasscodeButton();
            }

            @Override
            public void onTextChanged(EditText editText) {
                String text = editText.getText().toString();
                if (text.length() != 0 ) {
                    // text entered
                    searchWas = true;
                    if (searchEmptyView != null && listView.getEmptyView() != searchEmptyView) {
                        emptyView.setVisibility(View.GONE);
                        searchEmptyView.showTextView();
                        listView.setEmptyView(searchEmptyView);
                    }
                    if (dialogsSearchAdapter != null ) {
                        if( listView.getAdapter() != dialogsSearchAdapter ) {
                            listView.setAdapter(dialogsSearchAdapter);
                        }
                        dialogsSearchAdapter.searchDialogs(text);
                        dialogsSearchAdapter.notifyDataSetChanged();
                    }
                }
                else if( listView.getAdapter()==dialogsSearchAdapter ) {
                    // empty text
                    listView.setAdapter(dialogsAdapter);
                    dialogsAdapter.notifyDataSetChanged();
                }
            }
        });
        item.getSearchField().setHint(LocaleController.getString("Search", R.string.Search));
        if (onlySelect) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setTitle(onlySelectTitle);
        } else {
            actionBar.setBackButtonDrawable(new MenuDrawable());
            actionBar.setTitle(LocaleController.getString("AppName", R.string.AppName));
        }
        actionBar.setAllowOverlayTitle(true);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onlySelect) {
                        finishFragment();
                    } else if (parentLayout != null) {
                        parentLayout.getDrawerLayoutContainer().openDrawer(false);
                    }
                } else if (id == 1) {
                    UserConfig.appLocked = !UserConfig.appLocked;
                    UserConfig.saveConfig(false);
                    updatePasscodeButton();
                }
            }
        });


        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        
        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(true);
        listView.setItemAnimator(null);
        listView.setInstantClick(true);
        listView.setLayoutAnimation(null);
        listView.setTag(4);
        layoutManager = new LinearLayoutManager(context) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        };
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        listView.setLayoutManager(layoutManager);
        listView.setVerticalScrollbarPosition(LocaleController.isRTL ? ListView.SCROLLBAR_POSITION_LEFT : ListView.SCROLLBAR_POSITION_RIGHT);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                if (listView == null || listView.getAdapter() == null) {
                    return;
                }

                // handle single click
                long dialog_id = 0;
                int message_id = 0;
                RecyclerView.Adapter adapter = listView.getAdapter();
                if (adapter == dialogsAdapter) {
                    MrChat mrChat = dialogsAdapter.getItem(position);
                    if (mrChat == null) {
                        return;
                    }
                    dialog_id = mrChat.getId();
                } else if (adapter == dialogsSearchAdapter) {
                    Object obj  = dialogsSearchAdapter.getItem(position);
                    if( obj instanceof MrChat ) {
                        dialog_id = ((MrChat)obj).getId();
                    }
                    else if( obj instanceof MrMsg) {
                        MrMsg  mrMsg = (MrMsg)obj;
                        dialog_id = mrMsg.getChatId();
                        message_id = mrMsg.getId();
                    }
                }

                if (dialog_id == 0) {
                    return;
                }

                if (onlySelect) {
                    didSelectResult(dialog_id, true, false);
                } else {
                    Bundle args = new Bundle();
                    args.putInt("chat_id", (int)dialog_id);
                    if (message_id != 0) {
                        args.putInt("message_id", message_id);
                    }

                    if (AndroidUtilities.isTablet()) {
                        if (openedDialogId == dialog_id && adapter != dialogsSearchAdapter) {
                            return;
                        }
                        if (dialogsAdapter != null) {
                            dialogsAdapter.setOpenedDialogId(openedDialogId = dialog_id);
                            updateVisibleRows(MessagesController.UPDATE_MASK_SELECT_DIALOG);
                        }
                    }

                    presentFragment(new ChatActivity(args));
                }
            }
        });

        searchEmptyView = new EmptyTextProgressView(context);
        searchEmptyView.setVisibility(View.GONE);
        searchEmptyView.setShowAtCenter(true);
        searchEmptyView.setText(LocaleController.getString("NoResult", R.string.NoResult));
        frameLayout.addView(searchEmptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setVisibility(View.GONE);
        emptyView.setGravity(Gravity.CENTER);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });

        TextView textView = new TextView(context);
        textView.setText(LocaleController.getString("NoChats", R.string.NoChats));
        textView.setTextColor(0xff959595);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        emptyView.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        String help = LocaleController.getString("NoChatsHelp", R.string.NoChatsHelp);
        textView.setText(help);
        textView.setTextColor(0xff959595);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(64) /*move the whole stuff a little bit up*/);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1);
        emptyView.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        floatingButton = new ImageView(context);
        floatingButton.setVisibility(onlySelect ? View.GONE : View.VISIBLE);
        floatingButton.setScaleType(ImageView.ScaleType.CENTER);
        floatingButton.setBackgroundResource(R.drawable.floating_states);
        floatingButton.setImageResource(R.drawable.floating_pencil);
        if (Build.VERSION.SDK_INT >= 21) {
            StateListAnimator animator = new StateListAnimator();
            animator.addState(new int[]{android.R.attr.state_pressed}, ObjectAnimator.ofFloat(floatingButton, "translationZ", AndroidUtilities.dp(2), AndroidUtilities.dp(4)).setDuration(200));
            animator.addState(new int[]{}, ObjectAnimator.ofFloat(floatingButton, "translationZ", AndroidUtilities.dp(4), AndroidUtilities.dp(2)).setDuration(200));
            floatingButton.setStateListAnimator(animator);
            floatingButton.setOutlineProvider(new ViewOutlineProvider() {
                @SuppressLint("NewApi")
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                }
            });
        }
        frameLayout.addView(floatingButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, LocaleController.isRTL ? 14 : 0, 0, LocaleController.isRTL ? 0 : 14, 14));
        floatingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bundle args = new Bundle();
                args.putInt("do_what", ContactsActivity.SELECT_CONTACT_FOR_NEW_CHAT);
                presentFragment(new ContactsActivity(args));
            }
        });

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                // if we disable this, the keyboard always lays over the list and the end of the list is never reachable -- was: due to the setIsSearchField()-HACK, we do not want force keyboard disappering (HACK looks smaller so ;-)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searching && searchWas) {
                    AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                //int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                //int visibleItemCount = Math.abs(layoutManager.findLastVisibleItemPosition() - firstVisibleItem) + 1;
                //int totalItemCount = recyclerView.getAdapter().getItemCount();

                //if (searching && searchWas) {
                    //if (visibleItemCount > 0 && layoutManager.findLastVisibleItemPosition() == totalItemCount - 1 && !dialogsSearchAdapter.isMessagesSearchEndReached()) {
                    //    dialogsSearchAdapter.loadMoreSearchMessages();
                    //}
                    //return;
                //}

                // Floating hiding action
                /* if (floatingButton.getVisibility() != View.GONE) {
                    final View topChild = recyclerView.getChildAt(0);
                    int firstViewTop = 0;
                    if (topChild != null) {
                        firstViewTop = topChild.getTop();
                    }
                    boolean goingDown;
                    boolean changed = true;
                    if (prevPosition == firstVisibleItem) {
                        final int topDelta = prevTop - firstViewTop;
                        goingDown = firstViewTop < prevTop;
                        changed = Math.abs(topDelta) > 1;
                    } else {
                        goingDown = firstVisibleItem > prevPosition;
                    }
                    if (changed && scrollUpdated) {
                        hideFloatingButton(goingDown);
                    }
                    prevPosition = firstVisibleItem;
                    prevTop = firstViewTop;
                    scrollUpdated = true;
                } */
                // /Floating hiding action
            }
        });

        dialogsAdapter = new DialogsAdapter(context);
        if (AndroidUtilities.isTablet() && openedDialogId != 0) {
            dialogsAdapter.setOpenedDialogId(openedDialogId);
        }
        listView.setAdapter(dialogsAdapter);
        dialogsSearchAdapter = new DialogsSearchAdapter(context);

        searchEmptyView.setVisibility(View.GONE);
        listView.setEmptyView(emptyView);

        if (!onlySelect ) {
            frameLayout.addView(new PlayerView(context, this), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 39, Gravity.TOP | Gravity.LEFT, 0, -36, 0, 0));
        }

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dialogsAdapter != null) {
            dialogsAdapter.notifyDataSetChanged();
        }
        if (dialogsSearchAdapter != null) {
            dialogsSearchAdapter.notifyDataSetChanged();
        }
        if (checkPermission && !onlySelect && Build.VERSION.SDK_INT >= 23) {
            Activity activity = getParentActivity();
            if (activity != null) {
                checkPermission = false;
                if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    askForPermissons();
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void askForPermissons() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        ArrayList<String> permissons = new ArrayList<>();
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissons.add(Manifest.permission.READ_CONTACTS);
            permissons.add(Manifest.permission.WRITE_CONTACTS);
            permissons.add(Manifest.permission.GET_ACCOUNTS);
        }
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissons.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissons.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        String[] items = permissons.toArray(new String[permissons.size()]);
        activity.requestPermissions(items, LaunchActivity.REQ_CONTACT_N_STORAGE_PERMISON_ID);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!onlySelect && floatingButton != null) {
            floatingButton.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    floatingButton.setTranslationY(floatingHidden ? AndroidUtilities.dp(100) : 0);
                    floatingButton.setClickable(!floatingHidden);
                    if (floatingButton != null) {
                        if (Build.VERSION.SDK_INT < 16) {
                            floatingButton.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                        } else {
                            floatingButton.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == LaunchActivity.REQ_CONTACT_N_STORAGE_PERMISON_ID) {
            for (int a = 0; a < permissions.length; a++) {
                if (grantResults.length <= a || grantResults[a] != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                switch (permissions[a]) {
                    case Manifest.permission.READ_CONTACTS:
                        //ContactsController.getInstance().readContacts();
                        break;
                    case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                        ImageLoader.getInstance().checkMediaPaths();
                        break;
                }
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            if (dialogsAdapter != null) {
                dialogsAdapter.notifyDataSetChanged();
                /* EDIT BY MR
                if (dialogsAdapter.isDataSetChanged()) {
                    dialogsAdapter.notifyDataSetChanged();
                } else {
                    updateVisibleRows(MessagesController.UPDATE_MASK_NEW_MESSAGE);
                }
                */
            }
            if (dialogsSearchAdapter != null) {
                dialogsSearchAdapter.notifyDataSetChanged();
            }
            if (listView != null) {
                try {
                    /* EDIT BY MR
                    if (MessagesController.getInstance().loadingDialogs && MessagesController.getInstance().dialogs.isEmpty()) {
                        searchEmptyView.setVisibility(View.GONE);
                        emptyView.setVisibility(View.GONE);
                        listView.setEmptyView(progressView);
                    } else
                    */
                    {
                        //progressView.setVisibility(View.GONE); -- EDIT BY MR
                        if (searching && searchWas) {
                            emptyView.setVisibility(View.GONE);
                            listView.setEmptyView(searchEmptyView);
                        } else {
                            searchEmptyView.setVisibility(View.GONE);
                            listView.setEmptyView(emptyView);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e("messenger", e); //TODO fix it in other way?
                }
            }
        } else if (id == NotificationCenter.emojiDidLoaded) {
            updateVisibleRows(0);
        } else if (id == NotificationCenter.updateInterfaces) {
            updateVisibleRows((Integer) args[0]);
        } else if (id == NotificationCenter.contactsDidLoaded) {
            updateVisibleRows(0);
        } else if (id == NotificationCenter.openedChatChanged) {
            if ( AndroidUtilities.isTablet()) {
                boolean close = (Boolean) args[1];
                long dialog_id = (Long) args[0];
                if (close) {
                    if (dialog_id == openedDialogId) {
                        openedDialogId = 0;
                    }
                } else {
                    openedDialogId = dialog_id;
                }
                if (dialogsAdapter != null) {
                    dialogsAdapter.setOpenedDialogId(openedDialogId);
                }
                updateVisibleRows(MessagesController.UPDATE_MASK_SELECT_DIALOG);
            }
        } else if (id == NotificationCenter.notificationsSettingsUpdated) {
            updateVisibleRows(0);
        } else if ( id == NotificationCenter.messageSendError) {
            updateVisibleRows(MessagesController.UPDATE_MASK_SEND_STATE);
        } else if (id == NotificationCenter.didSetPasscode) {
            updatePasscodeButton();
        } else if (id == NotificationCenter.reloadHints) {
            if (dialogsSearchAdapter != null) {
                dialogsSearchAdapter.notifyDataSetChanged();
            }
        }
    }

    private void updatePasscodeButton() {
        if (passcodeItem == null) {
            return;
        }
        if (UserConfig.passcodeHash.length() != 0 && !searching) {
            passcodeItem.setVisibility(View.VISIBLE);
            if (UserConfig.appLocked) {
                passcodeItem.setIcon(R.drawable.lock_close);
            } else {
                passcodeItem.setIcon(R.drawable.lock_open);
            }
        } else {
            passcodeItem.setVisibility(View.GONE);
        }
    }

    private void hideFloatingButton(boolean hide) {
        if (floatingHidden == hide) {
            return;
        }
        floatingHidden = hide;
        ObjectAnimator animator = ObjectAnimator.ofFloat(floatingButton, "translationY", floatingHidden ? AndroidUtilities.dp(100) : 0).setDuration(300);
        animator.setInterpolator(floatingInterpolator);
        floatingButton.setClickable(!hide);
        animator.start();
    }

    private void updateVisibleRows(int mask) {
        if (listView == null) {
            return;
        }
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View child = listView.getChildAt(a);
            if (child instanceof DialogCell) {
                if (listView.getAdapter() != dialogsSearchAdapter) {
                    DialogCell cell = (DialogCell) child;
                    if ((mask & MessagesController.UPDATE_MASK_NEW_MESSAGE) != 0) {
                        cell.checkCurrentDialogIndex();
                        if ( AndroidUtilities.isTablet()) {
                            cell.setDialogSelected(cell.getDialogId() == openedDialogId);
                        }
                    } else if ((mask & MessagesController.UPDATE_MASK_SELECT_DIALOG) != 0) {
                        if ( AndroidUtilities.isTablet()) {
                            cell.setDialogSelected(cell.getDialogId() == openedDialogId);
                        }
                    } else {
                        cell.update(mask);
                    }
                }
            } else if (child instanceof UserCell) {
                ((UserCell) child).update(mask);
            }
        }
    }

    public void setDelegate(DialogsActivityDelegate dialogsActivityDelegate) {
        delegate = dialogsActivityDelegate;
    }

    public boolean isMainDialogList() {
        return delegate == null;
    }

    private void didSelectResult(final long dialog_id, boolean useAlert, final boolean param) {
        if (useAlert && (selectAlertString != null )) {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());

            MrChat mrChat = MrMailbox.getChat((int)dialog_id);
            builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatStringSimple(selectAlertString,
                    mrChat.getNameNAddr() /*display addr as there may be contacts with the same name but different addresses*/)));

            builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    didSelectResult(dialog_id, false, false);
                }
            });
            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
            showDialog(builder.create());
        } else {
            if (delegate != null) {
                delegate.didSelectDialog(DialogsActivity.this, dialog_id, param);
                delegate = null;
            } else {
                finishFragment();
            }
        }
    }
}
