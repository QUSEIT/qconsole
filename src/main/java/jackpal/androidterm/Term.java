/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import com.quseit.common.AssetExtract;
import com.quseit.common.ResourceManager;
import com.quseit.util.FileUtils;
import com.quseit.util.NAction;
import com.quseit.util.NStorage;
import com.quseit.util.NUtil;
import com.quseit.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

import jackpal.androidterm.compat.ActionBarCompat;
import jackpal.androidterm.compat.ActivityCompat;
import jackpal.androidterm.compat.AndroidCompat;
import jackpal.androidterm.compat.MenuItemCompat;
import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;
import jackpal.androidterm.util.SessionList;
import jackpal.androidterm.util.TermSettings;

/**
 * A terminal emulator activity.
 */

public class Term extends Activity implements UpdateCallback {
    private ResourceManager resourceManager;

    private String TAG = "Term";

    /**
     * The ViewFlipper which holds the collection of EmulatorView widgets.
     */
    private TermViewFlipper mViewFlipper;

    /**
     * The name of the ViewFlipper in the resources.
     */
    private static final int VIEW_FLIPPER = R.id.view_flipper;

    private SessionList mTermSessions;

    private SharedPreferences mPrefs;
    private TermSettings mSettings;

    private final static int SELECT_TEXT_ID = 0;
    private final static int COPY_ALL_ID = 1;
    private final static int PASTE_ID = 2;
    private final static int SEND_CONTROL_KEY_ID = 3;
    private final static int SEND_FN_KEY_ID = 4;

    private boolean mAlreadyStarted = false;
    private boolean mStopServiceOnFinish = false;

    private Intent TSIntent;
    private Intent mLastNewIntent;

    public static final int REQUEST_CHOOSE_WINDOW = 1;
    public static final String EXTRA_WINDOW_ID = "jackpal.androidterm.window_id";
    private int onResumeSelectWindow = -1;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    // Available on API 12 and later
    private static final int WIFI_MODE_FULL_HIGH_PERF = 3;

    private boolean mBackKeyPressed;

    private static final String ACTION_PATH_BROADCAST = "jackpal.androidterm.broadcast.APPEND_TO_PATH";
    private static final String ACTION_PATH_PREPEND_BROADCAST = "jackpal.androidterm.broadcast.PREPEND_TO_PATH";
    private static final String PERMISSION_PATH_BROADCAST = "jackpal.androidterm.permission.APPEND_TO_PATH";
    private static final String PERMISSION_PATH_PREPEND_BROADCAST = "jackpal.androidterm.permission.PREPEND_TO_PATH";
    private int mPendingPathBroadcasts = 0;
    private BroadcastReceiver mPathReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String path = makePathFromBundle(getResultExtras(false));
            if (intent.getAction().equals(ACTION_PATH_PREPEND_BROADCAST)) {
                mSettings.setPrependPath(path);
            } else {
                mSettings.setAppendPath(path);
            }
            mPendingPathBroadcasts--;

            if (mPendingPathBroadcasts <= 0 && mTermService != null) {
                populateViewFlipper();
                populateWindowList();
            }
        }
    };
    // Available on API 12 and later
    private static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x20;

    private TermService mTermService;
    private ServiceConnection mTSConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(TermDebug.LOG_TAG, "Bound to TermService");
            TermService.TSBinder binder = (TermService.TSBinder) service;
            mTermService = binder.getService();
            if (mPendingPathBroadcasts <= 0) {
                populateViewFlipper();
                populateWindowList();
            }
        }

        public void onServiceDisconnected(ComponentName arg0) {
            mTermService = null;
        }
    };

    private ActionBarCompat mActionBar;
    private int mActionBarMode = TermSettings.ACTION_BAR_MODE_NONE;

    private WindowListAdapter mWinListAdapter;

    private class WindowListActionBarAdapter extends WindowListAdapter implements UpdateCallback {
        // From android.R.style in API 13
        private static final int TextAppearance_Holo_Widget_ActionBar_Title = 0x01030112;

        public WindowListActionBarAdapter(SessionList sessions) {
            super(sessions);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView label = new TextView(Term.this);
            String title = getSessionTitle(position, getString(R.string.window_title, position + 1));
            label.setText(title);
            if (AndroidCompat.SDK >= 13) {
                label.setTextAppearance(Term.this, TextAppearance_Holo_Widget_ActionBar_Title);
            } else {
                label.setTextAppearance(Term.this, android.R.style.TextAppearance_Medium);
            }
            return label;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return super.getView(position, convertView, parent);
        }

        public void onUpdate() {
            notifyDataSetChanged();
            mActionBar.setSelectedNavigationItem(mViewFlipper.getDisplayedChild());
        }
    }

    private ActionBarCompat.OnNavigationListener mWinListItemSelected = new ActionBarCompat.OnNavigationListener() {
        public boolean onNavigationItemSelected(int position, long id) {
            int oldPosition = mViewFlipper.getDisplayedChild();
            if (position != oldPosition) {
                mViewFlipper.setDisplayedChild(position);
                if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                    mActionBar.hide();
                }
            }
            return true;
        }
    };

    private boolean mHaveFullHwKeyboard = false;

    private class EmulatorViewGestureListener extends SimpleOnGestureListener {
        private EmulatorView view;

        public EmulatorViewGestureListener(EmulatorView view) {
            this.view = view;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            doUIToggle((int) e.getX(), (int) e.getY(), view.getVisibleWidth(), view.getVisibleHeight());
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float absVelocityX = Math.abs(velocityX);
            float absVelocityY = Math.abs(velocityY);
            if (absVelocityX > Math.max(1000.0f, 2.0 * absVelocityY)) {
                // Assume user wanted side to side movement
                if (velocityX > 0) {
                    // Left to right swipe -- previous window
                    mViewFlipper.showPrevious();
                } else {
                    // Right to left swipe -- next window
                    mViewFlipper.showNext();
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private View.OnKeyListener mBackKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES && mActionBar.isShowing()) {
                /* We need to intercept the key event before the view sees it,
                   otherwise the view will handle it before we get it */
                onKeyUp(keyCode, event);
                return true;
            } else {
                return false;
            }
        }
    };

    private Handler mHandler = new Handler();

    //private String[] mArgs = null;


    @SuppressLint({"WrongConstant", "InvalidWakeLockTag"})
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        //Log.e(TermDebug.LOG_TAG, "onCreate");
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings = new TermSettings(getResources(), mPrefs);

        resourceManager = new ResourceManager(this);

        // for qlua and texteditor
//        if (code.startsWith("qlua") || code.startsWith("texteditor")) {
//        	unpackDataInPyAct("private4qe", getFilesDir());
//        }
        Intent broadcast = new Intent(ACTION_PATH_BROADCAST);
        if (AndroidCompat.SDK >= 12) {
            broadcast.addFlags(FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        mPendingPathBroadcasts++;
        sendOrderedBroadcast(broadcast, PERMISSION_PATH_BROADCAST, mPathReceiver, null, RESULT_OK, null, null);

        broadcast = new Intent(broadcast);
        broadcast.setAction(ACTION_PATH_PREPEND_BROADCAST);
        mPendingPathBroadcasts++;
        sendOrderedBroadcast(broadcast, PERMISSION_PATH_PREPEND_BROADCAST, mPathReceiver, null, RESULT_OK, null, null);

        TSIntent = new Intent(this, TermService.class);
        startService(TSIntent);

        if (!bindService(TSIntent, mTSConnection, BIND_AUTO_CREATE)) {
            Log.w(TermDebug.LOG_TAG, "bind to service failed!");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            int actionBarMode = mSettings.actionBarMode();
            mActionBarMode = actionBarMode;
            switch (actionBarMode) {
                case TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE:
                    setTheme(R.style.Theme_Holo);
                    getActionBar().setBackgroundDrawable(this.getBaseContext().getResources().getDrawable(R.drawable.action_bar_background));
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
//                        getActionBar().setIcon(R.drawable.ic_back);
//                    }

                    break;
                case TermSettings.ACTION_BAR_MODE_HIDES:
                    setTheme(R.style.Theme_Holo_ActionBarOverlay);
                    break;
            }
        }

        setContentView(R.layout.term_activity);

        mViewFlipper = (TermViewFlipper) findViewById(VIEW_FLIPPER);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermDebug.LOG_TAG);
        WifiManager wm = (WifiManager) getApplication().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int wifiLockMode = WifiManager.WIFI_MODE_FULL;
        if (AndroidCompat.SDK >= 12) {
            wifiLockMode = WIFI_MODE_FULL_HIGH_PERF;
        }
        mWifiLock = wm.createWifiLock(wifiLockMode, TermDebug.LOG_TAG);

        ActionBarCompat actionBar = ActivityCompat.getActionBar(this);
        if (actionBar != null) {
            mActionBar = actionBar;
            actionBar.setNavigationMode(ActionBarCompat.NAVIGATION_MODE_LIST);
            actionBar.setDisplayOptions(0, ActionBarCompat.DISPLAY_SHOW_TITLE);

            if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                actionBar.hide();
            }
        }

        mHaveFullHwKeyboard = checkHaveFullHwKeyboard(getResources().getConfiguration());

        updatePrefs();
        mAlreadyStarted = true;

        //
        //this.bindService(new Intent(Term.this, PyScriptService2.class), connection, BIND_AUTO_CREATE);
    }
    
  
    /*
	protected static final String TAG = "TERM";

	private ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {		
			//if (!CONF.DEBUG) 
			Log.d(TAG, "onServiceConnected");
			//binded = true;
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			//if (!CONF.DEBUG) 
			Log.d(TAG, "onServiceDisconnected");
			//binded = false;
		}
	};*/

    private String makePathFromBundle(Bundle extras) {
        if (extras == null || extras.size() == 0) {
            return "";
        }

        String[] keys = new String[extras.size()];
        keys = extras.keySet().toArray(keys);
        Collator collator = Collator.getInstance(Locale.US);
        Arrays.sort(keys, collator);

        StringBuilder path = new StringBuilder();
        for (String key : keys) {
            String dir = extras.getString(key);
            if (dir != null && !dir.equals("")) {
                path.append(dir);
                path.append(":");
            }
        }

        return path.substring(0, path.length() - 1);
    }

    private void populateViewFlipper() {
        if (mTermService != null) {
            mTermSessions = mTermService.getSessions();
            mTermSessions.addCallback(this);

            String[] mArgs = this.getIntent().getStringArrayExtra("PYTHONARGS");

            if (mArgs != null) {
                mTermSessions.add(createPyTermSession(mArgs));

            } else {
                mArgs = this.getIntent().getStringArrayExtra("ARGS");
                if (mArgs != null) {
                    mTermSessions.add(createPyTermSession(mArgs));

                } else {

                    if (mTermSessions.size() == 0) {
                        mTermSessions.add(createPyTermSession(mArgs));
                    }
                }
            }

            for (TermSession session : mTermSessions) {
                EmulatorView view = createEmulatorView(session);
                mViewFlipper.addView(view);

            }


            updatePrefs();

            Intent intent = getIntent();
            int flags = intent.getFlags();
            String action = intent.getAction();
            if ((flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 &&
                    action != null) {
                if (action.equals(RemoteInterface.PRIVACT_OPEN_NEW_WINDOW)) {
                    mViewFlipper.setDisplayedChild(mTermSessions.size() - 1);
                } else if (action.equals(RemoteInterface.PRIVACT_SWITCH_WINDOW)) {
                    int target = intent.getIntExtra(RemoteInterface.PRIVEXTRA_TARGET_WINDOW, -1);
                    if (target >= 0) {
                        mViewFlipper.setDisplayedChild(target);
                    }
                }
            } else {
                if (mArgs != null) {
                    mViewFlipper.setDisplayedChild(mTermSessions.size() - 1);

                }
            }

            mViewFlipper.resumeCurrentView();
        }
    }

    private void populateWindowList() {
        if (mActionBar == null) {
            // Not needed
            return;
        }

        if (mTermSessions != null) {
            int position = mViewFlipper.getDisplayedChild();
            WindowListAdapter adapter = mWinListAdapter;
            if (adapter == null) {
                adapter = new WindowListActionBarAdapter(mTermSessions);
                mWinListAdapter = adapter;

                SessionList sessions = mTermSessions;
                sessions.addCallback(adapter);
                sessions.addTitleChangedListener(adapter);
                mViewFlipper.addCallback(adapter);
                mActionBar.setListNavigationCallbacks(adapter, mWinListItemSelected);
            } else {
                adapter.setSessions(mTermSessions);
            }
            mActionBar.setSelectedNavigationItem(position);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //this.unbindService(connection);

        mViewFlipper.removeAllViews();
        unbindService(mTSConnection);
        if (mStopServiceOnFinish) {
            stopService(TSIntent);
        }
        mTermService = null;
        mTSConnection = null;
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        }
    }

    private void restart() {
        startActivity(getIntent());
        finish();
    }

    protected static TermSession createTermSession(Context context, TermSettings settings, String initialCommand, String path) {
        Log.d("Term", "createTermSession:("+initialCommand+")(path):"+path);
        ShellTermSession session = new ShellTermSession(context, settings, initialCommand, path);
        // XXX We should really be able to fetch this from within TermSession
        session.setProcessExitMessage(context.getString(R.string.process_exit_message));

        return session;
    }

    @SuppressLint("NewApi")
    private TermSession createTermSession(String[] mArgs) {
        TermSettings settings = mSettings;
        TermSession session;
        if (mArgs != null && mArgs.length > 1) {
            session = createTermSession(this, settings, mArgs[0], mArgs[1]);
        } else {
            session = createTermSession(this, settings, mArgs[0], "");
        }
        session.setFinishCallback(mTermService);
        return session;
    }

    @SuppressLint("NewApi")
    private TermSession createPyTermSession(String[] mArgs) {
        TermSettings settings = mSettings;
        TermSession session;
        String code = NAction.getCode(getApplicationContext());
        String scmd = "";

        Log.d(TAG, "createPyTermSession:(code)"+code);

        if (code.startsWith("qlua")) {
            scmd = NUtil.is64Bit() ? getApplicationContext().getFilesDir() + "/bin/lua5-64" : getApplicationContext().getFilesDir() + "/bin/lua5";

            if (mArgs == null) {
                //settings.setShell(scmd);
                //scmd = "";
                session = createTermSession(this, settings, scmd, "");
            } else {
                Log.d(TAG, "createPyTermSession（scmd）:"+scmd+":margs0:"+mArgs[0]);
                //String content = FileHelper.getFileContents(mArgs[0]);
                String cmd = scmd + " " + mArgs[0] + "";
                //settings.setShell(cmd);

                session = createTermSession(this, settings, cmd, mArgs[1]);
                mArgs = null;
            }

        } else {
            boolean isRootEnable = NAction.isRootEnable(this);
            if (Build.VERSION.SDK_INT >= 20) {
                scmd = isRootEnable ? getApplicationContext().getFilesDir() + "/bin/qpython3-android5-root.sh" : getApplicationContext().getFilesDir() + "/bin/qpython3-android5.sh" ;

            } else {
                scmd = isRootEnable ? getApplicationContext().getFilesDir() + "/bin/qpython3-root.sh" : getApplicationContext().getFilesDir() + "/bin/qpython3.sh";

            }

            if (mArgs == null) {

                if (Build.VERSION.SDK_INT >= 20) {
                    scmd = isRootEnable ? getApplicationContext().getFilesDir() + "/bin/qpython3-android5-root.sh && exit" : getApplicationContext().getFilesDir() + "/bin/qpython3-android5.sh && exit";

                } else {
                    scmd = isRootEnable ? getApplicationContext().getFilesDir() + "/bin/qpython3-root.sh && exit" : getApplicationContext().getFilesDir() + "/bin/qpython3.sh && exit";

                }
                session = createTermSession(this, settings, scmd, "");

            } else {

                String cm = "";
                for (int i = 0; i < mArgs.length - 1; i++) {
                    cm += " " + StringUtils.addSlashes(mArgs[i]) + " ";
                }
                session = createTermSession(this, settings, scmd + " " + cm + " && exit", mArgs[1]);

            }
        }
        session.setFinishCallback(mTermService);
        return session;
    }

    private TermView createEmulatorView(TermSession session) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        TermView emulatorView = new TermView(this, session, metrics);

        emulatorView.setExtGestureListener(new EmulatorViewGestureListener(emulatorView));
        emulatorView.setOnKeyListener(mBackKeyListener);
        registerForContextMenu(emulatorView);

        return emulatorView;
    }

    private TermSession getCurrentTermSession() {
        SessionList sessions = mTermSessions;
        if (sessions == null) {
            return null;
        } else {
            return sessions.get(mViewFlipper.getDisplayedChild());
        }
    }

    private EmulatorView getCurrentEmulatorView() {
        return (EmulatorView) mViewFlipper.getCurrentView();
    }

    private void updatePrefs() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        ColorScheme colorScheme = new ColorScheme(mSettings.getColorScheme());

        mViewFlipper.updatePrefs(mSettings);

        for (View v : mViewFlipper) {
            ((EmulatorView) v).setDensity(metrics);
            ((TermView) v).updatePrefs(mSettings);
        }

        if (mTermSessions != null) {
            for (TermSession session : mTermSessions) {
                ((ShellTermSession) session).updatePrefs(mSettings);
            }
        }

        {
            Window win = getWindow();
            WindowManager.LayoutParams params = win.getAttributes();
            final int FULLSCREEN = WindowManager.LayoutParams.FLAG_FULLSCREEN;
            int desiredFlag = mSettings.showStatusBar() ? 0 : FULLSCREEN;
            if (desiredFlag != (params.flags & FULLSCREEN) || (AndroidCompat.SDK >= 11 && mActionBarMode != mSettings.actionBarMode())) {
                if (mAlreadyStarted) {
                    // Can't switch to/from fullscreen after
                    // starting the activity.
                    restart();
                } else {
                    win.setFlags(desiredFlag, FULLSCREEN);
                    if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                        mActionBar.hide();
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        SessionList sessions = mTermSessions;
        TermViewFlipper viewFlipper = mViewFlipper;
        if (sessions != null) {
            sessions.addCallback(this);
            WindowListAdapter adapter = mWinListAdapter;
            if (adapter != null) {
                sessions.addCallback(adapter);
                sessions.addTitleChangedListener(adapter);
                viewFlipper.addCallback(adapter);
            }
        }
        if (sessions != null && sessions.size() < viewFlipper.getChildCount()) {
            for (int i = 0; i < viewFlipper.getChildCount(); ++i) {
                EmulatorView v = (EmulatorView) viewFlipper.getChildAt(i);
                if (!sessions.contains(v.getTermSession())) {
                    v.onPause();
                    viewFlipper.removeView(v);
                    --i;
                }
            }
        }

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings.readPrefs(mPrefs);
        updatePrefs();

        if (onResumeSelectWindow >= 0) {
            viewFlipper.setDisplayedChild(onResumeSelectWindow);
            onResumeSelectWindow = -1;
        }
        viewFlipper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();

        SessionList sessions = mTermSessions;
        TermViewFlipper viewFlipper = mViewFlipper;

        viewFlipper.onPause();
        if (sessions != null) {
            sessions.removeCallback(this);
            WindowListAdapter adapter = mWinListAdapter;
            if (adapter != null) {
                sessions.removeCallback(adapter);
                sessions.removeTitleChangedListener(adapter);
                viewFlipper.removeCallback(adapter);
            }
        }

        if (AndroidCompat.SDK < 5) {
            /* If we lose focus between a back key down and a back key up,
               we shouldn't respond to the next back key up event unless
               we get another key down first */
            mBackKeyPressed = false;
        }

        /* Explicitly close the input method
           Otherwise, the soft keyboard could cover up whatever activity takes
           our place */
        final IBinder token = viewFlipper.getWindowToken();
        new Thread() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(token, 0);
            }
        }.start();
    }

    private boolean checkHaveFullHwKeyboard(Configuration c) {
        return (c.keyboard == Configuration.KEYBOARD_QWERTY) &&
                (c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mHaveFullHwKeyboard = checkHaveFullHwKeyboard(newConfig);

        EmulatorView v = (EmulatorView) mViewFlipper.getCurrentView();
        if (v != null) {
            v.updateSize(false);
        }

        if (mWinListAdapter != null) {
            // Force Android to redraw the label in the navigation dropdown
            mWinListAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
//         MenuItem back_btn = menu.findItem(R.id.);
//        back_btn.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
//            @Override
//            public boolean onMenuItemClick(MenuItem item) {
//                Toast.makeText(getApplicationContext(),"HERE", Toast.LENGTH_LONG).show();
//                return true;
//            }
//        });
        //MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_special_keys), MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_new_window), MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        //MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_close_window), MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_send_control_key), MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_toggle_wakelock), MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            Log.d(TAG, "onOptionsItemSelected");
        } else if (id == R.id.menu_preferences) {
            doPreferences();
        } else if (id == R.id.menu_new_window) {
            doCreateNewWindow();
        /*} else if (id == R.id.menu_close_window) {
            confirmCloseWindow();
        } else if (id == R.id.menu_window_list) {
            startActivityForResult(new Intent(this, WindowList.class), REQUEST_CHOOSE_WINDOW);
        } else if (id == R.id.menu_reset) {
            doResetTerminal();
            Toast toast = Toast.makeText(this,R.string.reset_toast_notification,Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else if (id == R.id.menu_send_email) {
            doEmailTranscript();*/

        /*
                  case SELECT_TEXT_ID:
            getCurrentEmulatorView().toggleSelectingText();
            return true;
          case COPY_ALL_ID:
            doCopyAll();
            return true;
          case PASTE_ID:
            doPaste();
            return true;
          case SEND_CONTROL_KEY_ID:
            doSendControlKey();
            return true;
          case SEND_FN_KEY_ID:
            doSendFnKey();
         */
            //} else if (id == R.id.menu_special_keys) {
            //  doDocumentKeys();
            //} else if (id == R.id.menu_toggle_soft_keyboard) {
            //  doToggleSoftKeyboard();
        } else if (id == R.id.menu_toggle_wakelock) {
            doToggleWakeLock();
        } else if (id == R.id.menu_toggle_wifilock) {
            doToggleWifiLock();
        } else if (id == ActionBarCompat.ID_HOME) {
            closeWindow();
        } else if (id == R.id.menu_select_text) {
            getCurrentEmulatorView().toggleSelectingText();

        } else if (id == R.id.menu_copy_all) {
            doCopyAll();

        } else if (id == R.id.menu_paste) {
            doPaste();

        } else if (id == R.id.menu_send_control_key) {
            doSendControlKey();

            //} else if (id == R.id.menu_send_fn_key) {
            //  doSendFnKey();


        } else {
            //Log.d(TAG, "onOptionsItemSelected:");
        }
        // Hide the action bar if appropriate
        if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
            mActionBar.hide();
        }
        return super.onOptionsItemSelected(item);
    }

    private void doCreateNewWindow() {
        if (mTermSessions == null) {
            Log.w(TermDebug.LOG_TAG, "Couldn't create new window because mTermSessions == null");
            return;
        }

        TermSession session = createPyTermSession(null);
        mTermSessions.add(session);

        TermView view = createEmulatorView(session);
        view.updatePrefs(mSettings);

        mViewFlipper.addView(view);
        mViewFlipper.setDisplayedChild(mViewFlipper.getChildCount() - 1);
    }

    private void confirmCloseWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            final AlertDialog.Builder b = new AlertDialog.Builder(this, AlertDialog.THEME_TRADITIONAL);
            b.setIcon(android.R.drawable.ic_dialog_alert);
            b.setMessage(R.string.confirm_window_close_message);
            final Runnable closeWindow = new Runnable() {
                public void run() {
                    doCloseWindow();
                }
            };
            b.setPositiveButton(android.R.string.no, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                    mHandler.post(closeWindow);
                }
            });
            b.setNegativeButton(android.R.string.yes, null);

            b.show();
        } else {
            final AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setIcon(android.R.drawable.ic_dialog_alert);
            b.setMessage(R.string.confirm_window_close_message);
            final Runnable closeWindow = new Runnable() {
                public void run() {
                    doCloseWindow();
                }
            };
            b.setPositiveButton(android.R.string.no, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                    mHandler.post(closeWindow);
                }
            });
            b.setNegativeButton(android.R.string.yes, null);

            b.show();
        }
    }

    private void doCloseWindow() {
        if (mTermSessions == null) {
            return;
        }

        EmulatorView view = getCurrentEmulatorView();
        if (view == null) {
            return;
        }
        TermSession session = mTermSessions.remove(mViewFlipper.getDisplayedChild());
        view.onPause();
        session.finish();
        mViewFlipper.removeView(view);
        if (mTermSessions.size() == 0) {
            mStopServiceOnFinish = true;
            finish();
        } else {
            mViewFlipper.showNext();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        switch (request) {
            case REQUEST_CHOOSE_WINDOW:
                if (result == RESULT_OK && data != null) {
                    int position = data.getIntExtra(EXTRA_WINDOW_ID, -2);
                    if (position >= 0) {
                        // Switch windows after session list is in sync, not here
                        onResumeSelectWindow = position;
                    } else if (position == -1) {
                        doCreateNewWindow();
                        onResumeSelectWindow = mTermSessions.size() - 1;
                    }
                } else {
                    // Close the activity if user closed all sessions
                    if (mTermSessions == null || mTermSessions.size() == 0) {
                        mStopServiceOnFinish = true;
                        finish();
                    }
                }
                break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            // Don't repeat action if intent comes from history
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (action.equals(RemoteInterface.PRIVACT_OPEN_NEW_WINDOW)) {
            // New session was created, add an EmulatorView to match
            SessionList sessions = mTermSessions;
            if (sessions == null) {
                // Presumably populateViewFlipper() will do this later ...
                return;
            }
            int position = sessions.size() - 1;

            TermSession session = sessions.get(position);
            EmulatorView view = createEmulatorView(session);

            mViewFlipper.addView(view);
            onResumeSelectWindow = position;
        } else if (action.equals(RemoteInterface.PRIVACT_SWITCH_WINDOW)) {
            int target = intent.getIntExtra(RemoteInterface.PRIVEXTRA_TARGET_WINDOW, -1);
            if (target >= 0) {
                onResumeSelectWindow = target;
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem wakeLockItem = menu.findItem(R.id.menu_toggle_wakelock);
        MenuItem wifiLockItem = menu.findItem(R.id.menu_toggle_wifilock);
        if (mWakeLock.isHeld()) {
            wakeLockItem.setTitle(R.string.disable_wakelock);
        } else {
            wakeLockItem.setTitle(R.string.enable_wakelock);
        }
        if (mWifiLock.isHeld()) {
            wifiLockItem.setTitle(R.string.disable_wifilock);
        } else {
            wifiLockItem.setTitle(R.string.enable_wifilock);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case SELECT_TEXT_ID:
                getCurrentEmulatorView().toggleSelectingText();
                return true;
            case COPY_ALL_ID:
                doCopyAll();
                return true;
            case PASTE_ID:
                doPaste();
                return true;
            case SEND_CONTROL_KEY_ID:
                doSendControlKey();
                return true;
            case SEND_FN_KEY_ID:
                doSendFnKey();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    public void closeWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {

            AlertDialog.Builder alert = new AlertDialog.Builder(this, AlertDialog.THEME_TRADITIONAL);
            alert.setTitle(R.string.close_window).setMessage(R.string.confirm_window_close_message)
                    .setPositiveButton(R.string.promote_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (AndroidCompat.SDK < 5) {
                                mBackKeyPressed = true;

                            } else {
                                mStopServiceOnFinish = true;

                                finish();
                            }

                        }
                    }).setNegativeButton(R.string.promote_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    finish();
                }
            });
            alert.create().show();
        } else {
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(R.string.close_window).setMessage(R.string.confirm_window_close_message)
                    .setPositiveButton(R.string.promote_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (AndroidCompat.SDK < 5) {
                                mBackKeyPressed = true;

                            } else {
                                mStopServiceOnFinish = true;

                                finish();
                            }

                        }
                    }).setNegativeButton(R.string.promote_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    finish();
                }
            });
            alert.create().show();
        }

    }

    @Override
    public boolean onKeyDown(final int keyCode, KeyEvent event) {
        /* The pre-Eclair default implementation of onKeyDown() would prevent
           our handling of the Back key in onKeyUp() from taking effect, so
           ignore it here */

    	/*String from = this.getIntent().getStringExtra("com.quseit.common.extra.CONTENT_URL0");
    	if (from!=null && from.equals("main")) {
    		if (AndroidCompat.SDK < 5 && keyCode == KeyEvent.KEYCODE_BACK) {
                // Android pre-Eclair has no key event tracking, and a back key
                 //  down event delivered to an activity above us in the back stack
                  // could be succeeded by a back key up event to us, so we need to
                   //keep track of our own back key presses 
                mBackKeyPressed = true;


                return true;
            } else {
                return super.onKeyDown(keyCode, event);

            }
    	} else {*/

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {

            AlertDialog.Builder alert = new AlertDialog.Builder(this, AlertDialog.THEME_TRADITIONAL);
            alert.setTitle(R.string.close_window).setMessage(R.string.confirm_window_close_message)
                    .setPositiveButton(R.string.promote_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (AndroidCompat.SDK < 5 && keyCode == KeyEvent.KEYCODE_BACK) {
                                mBackKeyPressed = true;

                            } else {
                                mStopServiceOnFinish = true;

                                finish();
                            }

                        }
                    }).setNegativeButton(R.string.promote_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    finish();
                }
            });
            alert.create().show();

            return super.onKeyDown(keyCode, event);
        } else {
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(R.string.close_window).setMessage(R.string.confirm_window_close_message)
                    .setPositiveButton(R.string.promote_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (AndroidCompat.SDK < 5 && keyCode == KeyEvent.KEYCODE_BACK) {
                                mBackKeyPressed = true;

                            } else {
                                mStopServiceOnFinish = true;

                                finish();
                            }

                        }
                    }).setNegativeButton(R.string.promote_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    finish();
                }
            });
            alert.create().show();

            return super.onKeyDown(keyCode, event);
        }

        //}
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        /*case KeyEvent.KEYCODE_BACK:
            if (AndroidCompat.SDK < 5) {
                if (!mBackKeyPressed) {
                    return false;
                }
                mBackKeyPressed = false;
            }
            if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES && mActionBar.isShowing()) {
                mActionBar.hide();
                return true;
            }
            switch (mSettings.getBackKeyAction()) {
            case TermSettings.BACK_KEY_STOPS_SERVICE:
                mStopServiceOnFinish = true;
            case TermSettings.BACK_KEY_CLOSES_ACTIVITY:
                finish();
                return true;
            case TermSettings.BACK_KEY_CLOSES_WINDOW:
                doCloseWindow();
                return true;
            default:
                return false;
            }*/
            case KeyEvent.KEYCODE_MENU:
                if (mActionBar != null && !mActionBar.isShowing()) {
                    mActionBar.show();
                    return true;
                } else {
                    return super.onKeyUp(keyCode, event);
                }
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // Called when the list of sessions changes
    public void onUpdate() {
        SessionList sessions = mTermSessions;
        if (sessions == null) {
            return;
        }

        if (sessions.size() == 0) {
            mStopServiceOnFinish = true;
            finish();
        } else if (sessions.size() < mViewFlipper.getChildCount()) {
            for (int i = 0; i < mViewFlipper.getChildCount(); ++i) {
                EmulatorView v = (EmulatorView) mViewFlipper.getChildAt(i);
                if (!sessions.contains(v.getTermSession())) {
                    v.onPause();
                    mViewFlipper.removeView(v);
                    --i;
                }
            }
        }
    }

    private boolean canPaste() {
        ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip.hasText()) {
            return true;
        }
        return false;
    }

    private void doPreferences() {
        startActivity(new Intent(this, TermPreferences.class));
    }

    private void doResetTerminal() {
        TermSession session = getCurrentTermSession();
        if (session != null) {
            session.reset();
        }
    }

    private void doEmailTranscript() {
        TermSession session = getCurrentTermSession();
        if (session != null) {
            // Don't really want to supply an address, but
            // currently it's required, otherwise nobody
            // wants to handle the intent.
            String addr = "support@qpython.org";
            Intent intent =
                    new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"
                            + addr));

            String subject = getString(R.string.email_transcript_subject);
            String title = session.getTitle();
            if (title != null) {
                subject = subject + " - " + title;
            }
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.putExtra(Intent.EXTRA_TEXT,
                    session.getTranscriptText().trim());
            try {
                startActivity(Intent.createChooser(intent,
                        getString(R.string.email_transcript_chooser_title)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this,
                        R.string.email_transcript_no_email_activity_found,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doCopyAll() {
        ClipboardManager clip = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        clip.setText(getCurrentTermSession().getTranscriptText().trim());
    }

    private void doPaste() {
        ClipboardManager clip = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence paste = clip.getText();
        byte[] utf8;
        if (paste != null) {
            try {
                utf8 = paste.toString().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                Log.e(TermDebug.LOG_TAG, "UTF-8 encoding not found.");
                return;
            }
            getCurrentTermSession().write(paste.toString());
        }
    }

    private void doSendControlKey() {
        getCurrentEmulatorView().sendControlKey();
    }

    private void doSendFnKey() {
        getCurrentEmulatorView().sendFnKey();
    }

    private void doDocumentKeys() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {

            AlertDialog.Builder dialog = new AlertDialog.Builder(this, AlertDialog.THEME_TRADITIONAL);
            Resources r = getResources();
            dialog.setTitle(r.getString(R.string.control_key_dialog_title));
            dialog.setMessage(
                    formatMessage(mSettings.getControlKeyId(), TermSettings.CONTROL_KEY_ID_NONE,
                            r, R.array.control_keys_short_names,
                            R.string.control_key_dialog_control_text,
                            R.string.control_key_dialog_control_disabled_text, "CTRLKEY")
                            + "\n\n" +
                            formatMessage(mSettings.getFnKeyId(), TermSettings.FN_KEY_ID_NONE,
                                    r, R.array.fn_keys_short_names,
                                    R.string.control_key_dialog_fn_text,
                                    R.string.control_key_dialog_fn_disabled_text, "FNKEY"));
            dialog.show();

        } else {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this);
            Resources r = getResources();
            dialog.setTitle(r.getString(R.string.control_key_dialog_title));
            dialog.setMessage(
                    formatMessage(mSettings.getControlKeyId(), TermSettings.CONTROL_KEY_ID_NONE,
                            r, R.array.control_keys_short_names,
                            R.string.control_key_dialog_control_text,
                            R.string.control_key_dialog_control_disabled_text, "CTRLKEY")
                            + "\n\n" +
                            formatMessage(mSettings.getFnKeyId(), TermSettings.FN_KEY_ID_NONE,
                                    r, R.array.fn_keys_short_names,
                                    R.string.control_key_dialog_fn_text,
                                    R.string.control_key_dialog_fn_disabled_text, "FNKEY"));
            dialog.show();
        }
    }

    private String formatMessage(int keyId, int disabledKeyId,
                                 Resources r, int arrayId,
                                 int enabledId,
                                 int disabledId, String regex) {
        if (keyId == disabledKeyId) {
            return r.getString(disabledId);
        }
        String[] keyNames = r.getStringArray(arrayId);
        String keyName = keyNames[keyId];
        String template = r.getString(enabledId);
        String result = template.replaceAll(regex, keyName);
        return result;
    }

    private void doToggleSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);

    }

    private void doToggleWakeLock() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        } else {
            mWakeLock.acquire();
        }
        ActivityCompat.invalidateOptionsMenu(this);
    }

    private void doToggleWifiLock() {
        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        } else {
            mWifiLock.acquire();
        }
        ActivityCompat.invalidateOptionsMenu(this);
    }

    private void doToggleActionBar() {
        ActionBarCompat bar = mActionBar;
        if (bar == null) {
            return;
        }
        if (bar.isShowing()) {
            bar.hide();
        } else {
            bar.show();
        }
    }

    private void doUIToggle(int x, int y, int width, int height) {
        switch (mActionBarMode) {
            case TermSettings.ACTION_BAR_MODE_NONE:
                if (AndroidCompat.SDK >= 11 && (mHaveFullHwKeyboard || y < height / 2)) {
                    openOptionsMenu();
                    return;
                } else {
                    doToggleSoftKeyboard();
                }
                break;
            case TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE:
                if (!mHaveFullHwKeyboard) {
                    doToggleSoftKeyboard();
                }
                break;
            case TermSettings.ACTION_BAR_MODE_HIDES:
                if (mHaveFullHwKeyboard || y < height / 2) {
                    doToggleActionBar();
                    return;
                } else {
                    doToggleSoftKeyboard();
                }
                break;
        }
        getCurrentEmulatorView().requestFocus();
    }

//    public void unpackDataInPyAct(final String resource, File target) {
//        // The version of data in memory and on disk.
//        String data_version = resourceManager.getString(resource + "_version");
//        String disk_version = "0";
//
//        //Log.d(TAG, "data_version:"+data_version+"-"+resource + "_version"+"-"+resourceManager);
//        // If no version, no unpacking is necessary.
//        if (data_version == null) {
//            return;
//        }
//
//        // Check the current disk version, if any.
//        String filesDir = target.getAbsolutePath();
//        String disk_version_fn = filesDir + "/" + resource + ".version";
//
//        try {
//            byte buf[] = new byte[64];
//            InputStream is = new FileInputStream(disk_version_fn);
//            int len = is.read(buf);
//            disk_version = new String(buf, 0, len);
//            is.close();
//        } catch (Exception e) {
//            disk_version = "0";
//        }
//
//
//        //Log.d(TAG, "data_version:"+Math.round(Double.parseDouble(data_version))+"-disk_version:"+Math.round(Double.parseDouble(disk_version))+"-RET:"+(int)(Double.parseDouble(data_version)-Double.parseDouble(disk_version)));
//        if ((int)(Double.parseDouble(data_version)-Double.parseDouble(disk_version))>0 || disk_version.equals("0")) {
//            Log.v(TAG, "Extracting " + resource + " assets.");
//
//            //recursiveDelete(target);
//            target.mkdirs();
//
//            AssetExtract ae = new AssetExtract(this);
//            if (!ae.extractTar(resource + ".mp3", target.getAbsolutePath())) {
//                Toast.makeText(this,"Could not extract " + resource + " data.", Toast.LENGTH_SHORT).show();
//            }
//
//            try {
//            	/*if (resource.equals("private")) {
//            		Toast.makeText(getApplicationContext(), R.string.first_load, Toast.LENGTH_SHORT).show();
//            	}*/
//                // Write .nomedia.
//                new File(target, ".nomedia").createNewFile();
//
//                // Write version file.
//                FileOutputStream os = new FileOutputStream(disk_version_fn);
//                os.write(data_version.getBytes());
//                os.close();
//            } catch (Exception e) {
//                Log.w("python", e);
//                Toast.makeText(this, "Could not extract " + resource + " data, make sure your device have enough space.", Toast.LENGTH_LONG);
//            }
//        } else {
//        	Log.d(TAG, "NO EXTRACT");
//
//        }
//        if (resource.startsWith("private")) {
//
//			File bind = new File(getFilesDir()+"/bin");
//			if (bind.listFiles()!=null) {
//				for (File bin : bind.listFiles()) {
//					try {
//			  			//Log.d(TAG, "chmod:"+bin.getAbsolutePath());
//
//						FileUtils.chmod(bin, 0755);
//					} catch (Exception e1) {
//						e1.printStackTrace();
//					}
//				}
//			}
//        }
//
//    }
//}
}