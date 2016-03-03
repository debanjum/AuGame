package edu.dartmouth.cs.armudwear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Vibrator;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.WatchViewStub;
import android.support.wearable.view.WearableListView;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.androidplot.ui.AnchorPosition;
import com.androidplot.ui.LayoutManager;
import com.androidplot.ui.SizeLayoutType;
import com.androidplot.ui.SizeMetrics;
import com.androidplot.ui.XLayoutStyle;
import com.androidplot.ui.YLayoutStyle;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;

import edu.dartmouth.cs.armudwear.UI.ObjectAdapter;
import edu.dartmouth.cs.armudwear.UI.StatsSeries;
import edu.dartmouth.cs.armudwear.data.WatchDataLayerListenerService;
import edu.dartmouth.cs.armudwear.gesture.SensorsService;



import android.graphics.Color;
import android.graphics.Paint;

import com.androidplot.xy.*;

public class ARMudWearActivity extends WearableActivity
implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
WearableListView.OnCentralPositionChangedListener {


    private WatchViewStub mContainerView;
    private TextView mTitleView;
    private WearableListView mFocusListView;
    private Button mLeftListButton;
    private Button mRightListButton;

    private ArrayList<String> mCharArray;
    private ArrayList<String> mMobArray;
    private ArrayList<String> mObjArray;
    private ArrayList<String> mInvArray;
    private ArrayList<String> mFocusArray;

    private String[] mButtonLabels = { "Chr", "Obj", "Inv"};
    private String[] mTitles = {"Characters: ", "Objects: ", "Your Inventory"};
    private String mCurrentLocation;
    private int mHealthPoints;
    private int mMaxHP;
    private int mExperiencePoints;
    private int mMagicPoints;
    private int mGoldPieces;
    private int mLevel;

    private XYPlot statsPlot;
    private StatsSeries mHealthSeries;
    private StatsSeries mExperienceSeries;

    private int mCurrentFocusContext;
    private String mCurrentFocusObject;
    private boolean mFocusIsIdle;
    Intent mClassifyIntent;

    protected GoogleApiClient mGoogleApiClient;

    private Vibrator vibrator;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("onCreate", "start");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_armud_wear);
        setAmbientEnabled();

        final WearableListView.OnCentralPositionChangedListener listener = this;
        final Context context = this;
        mContainerView = (WatchViewStub) findViewById(R.id.watch_view_stub);
        mContainerView.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mFocusListView = (WearableListView) stub.findViewById(R.id.focusListView);
                mTitleView = (TextView) stub.findViewById(R.id.titleText);
                statsPlot = (XYPlot) stub.findViewById(R.id.statsPlot);
                updateFocusArray(Globals.FOCUS_CONTEXT_CHARACTER);
                mFocusListView.addOnCentralPositionChangedListener(listener);
                createStatsDisplay();
                mLeftListButton = (Button) stub.findViewById(R.id.leftListButton);
                mRightListButton = (Button) stub.findViewById(R.id.rightListButton);
                mLeftListButton.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                onClickContextSwitch((mCurrentFocusContext + 1) % 3);
                            }
                        });
                mRightListButton.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                //(a % b + b) % b : modulus
                                onClickContextSwitch(((mCurrentFocusContext - 1) % 3 + 3) % 3);
                            }
                        });
            }
        });

        mCharArray = new ArrayList<String>();
        mObjArray = new ArrayList<String>();
        mInvArray = new ArrayList<String>();
        mMobArray = new ArrayList<String>();
        mFocusArray = new ArrayList<String>();
        mCurrentFocusObject = "";
        mCurrentFocusContext = Globals.FOCUS_CONTEXT_CHARACTER;
        mFocusIsIdle = true;
        mCurrentLocation = "";

        buildGoogleApiClient();

        LocalBroadcastManager.getInstance(this).registerReceiver(mMsgToWearReceiver,
                new IntentFilter(Globals.ARMUD_DATA_PATH));

        LocalBroadcastManager.getInstance(this).registerReceiver(mCommandReceiver,
                new IntentFilter(Globals.COMMAND_UPDATED));

        startService(new Intent(this, WatchDataLayerListenerService.class));

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    public void onClickContextSwitch(int newFocusContext) {
        switchFocusContext(newFocusContext);
        mCurrentFocusContext = newFocusContext;
        updateFocusArray(mCurrentFocusContext);
        //(a % b + b) % b: modulus
        mLeftListButton.setText(mButtonLabels[(mCurrentFocusContext + 1) % 3]);
        mRightListButton.setText(mButtonLabels[((mCurrentFocusContext - 1) % 3 + 3) % 3]);
        String titleSuffix;
        if (mCurrentFocusContext == Globals.FOCUS_CONTEXT_INVENTORY) {
            titleSuffix = "";
        } else {
            titleSuffix = mCurrentLocation;
        }
        mTitleView.setText(mTitles[mCurrentFocusContext] + titleSuffix);
    }

    // mMsgToWearReceiver will be called whenever an Intent
    // with an action named "data_changed" is broadcast.
    // This receiver deals with object changes from server
    private BroadcastReceiver mMsgToWearReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String command = intent.getStringExtra("command");
            String obj = intent.getStringExtra("obj");
            Log.d("command receiver", "Got command: " + command + " " + obj);
            switch (command) {
                case "char_add":
                    Log.d("command receiver", "adding character");
                    if (!mCharArray.contains(obj)) {
                        mCharArray.add(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_CHARACTER);
                    }
                    break;
                case "char_remove":
                    if (mCharArray.contains(obj)) {
                        mCharArray.remove(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_CHARACTER);
                    }
                    break;
                case "obj_add":
                    if (!mObjArray.contains(obj)) {
                        mObjArray.add(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_OBJECT);
                    }
                    break;
                case "obj_remove":
                    if (mObjArray.contains(obj)) {
                        mObjArray.remove(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_OBJECT);
                    }
                    break;
                case "inv_add":
                    if (!mInvArray.contains(obj)) {
                        mInvArray.add(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_INVENTORY);
                    }
                    break;
                case "inv_remove":
                    if (mInvArray.contains(obj)) {
                        mInvArray.remove(obj);
                        updateFocusArray(Globals.FOCUS_CONTEXT_INVENTORY);
                    }
                    break;
                case "health":
                    mHealthPoints = Integer.parseInt(obj);
                    mHealthSeries.updateValue(mHealthPoints);
                    if (mHealthPoints > mMaxHP) {
                        mMaxHP = mHealthPoints;
                        mHealthSeries.updateMaxValue(mMaxHP);
                    }
                    statsPlot.redraw();
                    break;
                case "xp":
                    mExperiencePoints = Integer.parseInt(obj);
                    mExperienceSeries.updateValue(mExperiencePoints);
                    statsPlot.redraw();
                    break;
                case "level:":
                    mLevel = Integer.parseInt(obj);
                    break;
                case "LOC":
                    if (!mCurrentLocation.equals(obj)) {
                        mCurrentLocation = obj;
                        onClickContextSwitch(mCurrentFocusContext);
                    }
            }
        }
    };


    /*
     * This function starts and stops different classifier services
     * Depending on which kind of listview is shown to the user
     */
    private void switchFocusContext(int focusContext) {
        Log.d("Focus Context-start", Integer.toString(mCurrentFocusContext));
        if (mCurrentFocusContext == focusContext && !mFocusIsIdle){
            return;
        }
        if (!mFocusIsIdle) {
            stopService(mClassifyIntent);
            mFocusIsIdle = true;
        }
        if (focusContext != Globals.FOCUS_CONTEXT_IDLE) {
            mClassifyIntent = new Intent(this, SensorsService.class);
            mClassifyIntent.putExtra(Globals.CONTEXT_KEY, focusContext);
            startService(mClassifyIntent);
            mFocusIsIdle = false;
        }
        Log.d("Focus Context-end", Integer.toString(mCurrentFocusContext));
    }


    /*
     * This function updates the listview shown to the user
     * it is called when the server updates the objects in the
     * user's current environment
     */
    private void updateFocusArray(int focusContext) {
        if (mCurrentFocusContext == focusContext) {
            mFocusArray.clear();
            switch (focusContext)
            {
                case Globals.FOCUS_CONTEXT_CHARACTER:
                    mFocusArray.addAll(mCharArray);
                    break;
                case Globals.FOCUS_CONTEXT_OBJECT:
                    mFocusArray.addAll(mObjArray);
                    break;
                case Globals.FOCUS_CONTEXT_INVENTORY:
                    mFocusArray.addAll(mInvArray);
                    break;
                case Globals.FOCUS_CONTEXT_IDLE:
                    break;
            }
            if (mFocusArray.isEmpty()){
                mFocusArray.add("Nothing Here");
                mCurrentFocusObject = "";
            } else {
                // the next line is bad, because it updates the focus object whenever a new object is added to the current list
                mCurrentFocusObject = mFocusArray.get(0);
            }
            Log.d("updating listview", mFocusArray.get(0));
            ObjectAdapter mAdapter = new ObjectAdapter(this, mFocusArray);
            mFocusListView.setAdapter(mAdapter);
        }
    }

    // mCommandReceiver will be called whenever an Intent
    // with an action named Globals.COMMAND_UPDATED is broadcast.
    // This receiver deals with object changes from server
    private BroadcastReceiver mCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //vibrate watch user feedback
            vibrator.vibrate(100);

            // Get extra data included in the Intent
            int commandNumber = intent.getIntExtra("commandNumber", Globals.NO_COMMAND_DETECTED);
            String command = "";
            switch (commandNumber) {
                case Globals.COMMAND_ID_ATTACK:
                    command = "attack";
                    break;
                case Globals.COMMAND_ID_CLAP:
                    command = "default";
                    break;
                case Globals.COMMAND_ID_DROP:
                    command = "drop";
                    break;
                case Globals.COMMAND_ID_THROW:
                    break;
                case Globals.COMMAND_ID_GET:
                    command = "get";
                    break;
            }
            String obj = mCurrentFocusObject;
            if (!mCurrentFocusObject.equals("")) {
                if (mGoogleApiClient.isConnected()) {
                    Log.d("Send command to phone", command + " " + obj);
                    new SendMessageToPhoneThread(Globals.COMMAND_PATH, command + " " + obj).start();
                } else {
                    Log.d("Send command failure", "not connected to phone");
                    mGoogleApiClient.connect();
                }
            } else {
                Log.d("Command Failure", "No Focus Object");
            }
        }
    };


    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
        startService(new Intent(this, WatchDataLayerListenerService.class));
        /*
        if (!mFocusIsIdle) {
            startService(mClassifyIntent);
        } */
    }

    @Override
    protected void onStop() {
        super.onStop();
        mGoogleApiClient.disconnect();
        stopService(new Intent(this, WatchDataLayerListenerService.class));
        /*
        if (!mFocusIsIdle) {
            stopService(mClassifyIntent);
        } */
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
        updateDisplay();
    }

    @Override
    public void onUpdateAmbient() {
        super.onUpdateAmbient();
        updateDisplay();
    }

    @Override
    public void onExitAmbient() {
        updateDisplay();
        super.onExitAmbient();
    }

    private void updateDisplay() {
        if (isAmbient()) {
            mContainerView.setBackgroundColor(getResources().getColor(android.R.color.black));
            mTitleView.setTextColor(getResources().getColor(android.R.color.white));
        } else {
            mContainerView.setBackground(null);
            mTitleView.setTextColor(getResources().getColor(android.R.color.black));
        }
    }
    protected synchronized void buildGoogleApiClient() {
        Log.i("Startup", "Building GoogleApiClient");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
    }


    @Override
    public void onConnected(Bundle bundle) {
        Log.i("GoogleApiClient", "Connected!");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        // The connection to Google Play services was lost for some reason. We call connect() to
        // attempt to re-establish the connection.
        Log.i("GoogleApiClient", "Connection suspended");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // Refer to the javadoc for ConnectionResult to see what error codes might be returned in
        // onConnectionFailed.
        Log.i("GoogleApiClient", "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }

    @Override
    public void onCentralPositionChanged(int i) {
        mCurrentFocusObject = mFocusArray.get(i);
        Log.d("CentralPositionChanged", mCurrentFocusObject);
    }


    class SendMessageToPhoneThread extends Thread {
        String path;
        String message;

        // Constructor for sending data objects to the data layer
        SendMessageToPhoneThread(String p, String m) {
            path = p;
            message = m;
        }

        public void run() {
            NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
            for(Node node : nodes.getNodes()) {
                MessageApi.SendMessageResult result = Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(), path, message.getBytes()).await();
                if(!result.getStatus().isSuccess()){
                    Log.e("test", "error");
                } else {
                    Log.i("test", "success!! sent to: " + node.getDisplayName());
                }
            }
        }
    }


    public void createStatsDisplay() {
        // get handles to our View defined in layout.xml:

        XYGraphWidget graphWidget = statsPlot.getGraphWidget();
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        graphWidget.setBackgroundPaint(white);
        graphWidget.setDomainGridLinePaint(null);
        graphWidget.setRangeGridLinePaint(null);
        graphWidget.setGridBackgroundPaint(null);
        graphWidget.setMargins(0, 0, 0, 0);
        graphWidget.setPadding(0, 0, 0, 0);
        graphWidget.setGridPadding(0, 0, 0, 0);
        graphWidget.setRangeLabelWidth(0);
        graphWidget.setDomainLabelWidth(0);
        graphWidget.position(-0.5f, XLayoutStyle.RELATIVE_TO_RIGHT,
                -0.5f, YLayoutStyle.RELATIVE_TO_BOTTOM,
                AnchorPosition.CENTER);
        graphWidget.setSize(new SizeMetrics(
                0, SizeLayoutType.FILL,
                0, SizeLayoutType.FILL));
        LayoutManager layoutManager = statsPlot.getLayoutManager();
        layoutManager.remove(statsPlot.getRangeLabelWidget());
        layoutManager.remove(statsPlot.getDomainLabelWidget());
        layoutManager.remove(statsPlot.getLegendWidget());
        statsPlot.setPlotMargins(0, 0, 0, 0);
        statsPlot.setPlotPadding(0, 0, 0, 0);


        StatsSeries baseline = new StatsSeries(30, 30, 30, true);
        mHealthSeries = new StatsSeries(100, 100, 29);
        mExperienceSeries = new StatsSeries(0, 100, 27);
        mMaxHP = 100;

        LineAndPointFormatter formatBase = new LineAndPointFormatter(Color.WHITE,null,null,null);
        formatBase.getLinePaint().setStrokeJoin(Paint.Join.ROUND);
        formatBase.getLinePaint().setStrokeWidth(20);
        statsPlot.addSeries(baseline,
                formatBase);

        LineAndPointFormatter formatHealth = new LineAndPointFormatter(Color.RED,null,null,null);
        formatHealth.getLinePaint().setStrokeJoin(Paint.Join.ROUND);
        formatHealth.getLinePaint().setStrokeWidth(10);
        statsPlot.addSeries(mHealthSeries,
                formatHealth);


        LineAndPointFormatter formatXP = new LineAndPointFormatter(Color.BLUE,null,null,null);
        formatXP.getLinePaint().setStrokeWidth(10);
        formatXP.getLinePaint().setStrokeJoin(Paint.Join.ROUND);

        //formatter2.getFillPaint().setAlpha(220);
        statsPlot.addSeries(mExperienceSeries, formatXP);
    }
}
