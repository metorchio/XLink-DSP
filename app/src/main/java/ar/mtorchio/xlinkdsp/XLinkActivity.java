/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;

import ar.mtorchio.xlinkdsp.dsp.BleTransport;
import ar.mtorchio.xlinkdsp.dsp.Command;
import ar.mtorchio.xlinkdsp.dsp.Commands;
import ar.mtorchio.xlinkdsp.dsp.Config;
import ar.mtorchio.xlinkdsp.dsp.MockTransport;
import ar.mtorchio.xlinkdsp.dsp.Session;
import ar.mtorchio.xlinkdsp.dsp.Slope;
import ar.mtorchio.xlinkdsp.dsp.Transport;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * XLink DSP: pantallas para manejar un DSP Expert Electronics. Busca el equipo
 * por BLE (mismo filtro de nombres que xair BLEMainActivity) y le habla a través
 * de una Session del package dsp, que tiene todo el protocolo.
 */
public class XLinkActivity extends Activity {

    static final String TAG = "XLinkDsp";

    // Mismo filtro que xair BLEMainActivity (contains / prefijo de 2 letras / igual).
    private static final String[] DEVICE_NAME_CONTAINS = {
            "AMP", "X4", "X6", "X8", "A4", "A6", "A8", "B4", "B6", "B8", "D4", "D6", "D8", "S6", "S8",
            "PX 201", "PX 202", "PX 208", "PX 101", "PX 102", "PX 108", "PX 103", "PX 203",
            "PRO6P", "PRO8", "MXAiR"
    };
    private static final String[] DEVICE_NAME_PREFIXES = {"M8", "mn", "W8"};

    private static final int REQUEST_PERMISSIONS = 1;
    private static final long SCAN_TIMEOUT_MS = 8000;

    static final String PREFS = "xlink";
    static final String PREF_PASSWORD = "password";
    static final String PREF_LAST_ADDRESS = "last_address";
    private static final String PREF_EQ_LIVE = "eq_live";
    private static final String PREF_EQ_SHOW_INPUT = "eq_show_input";

    // Paleta del ícono
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF171B24;
    private static final int CARD_STROKE = 0xFF262C3A;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int MUTED = 0xFF8A93A6;
    private static final int CYAN = 0xFF00E5FF;
    private static final int VIOLET = 0xFF7C4DFF;
    private static final int ORANGE = 0xFFFF8A1F;
    private static final int PINK = 0xFFFF3D7F;
    private static final int GREEN = 0xFF39FF88;
    private static final int RED = 0xFFFF5370;

    private enum State { IDLE, SCANNING, CONNECTING, CONNECTED }

    // Pestañas (solo con un equipo conectado; sin equipo se ve la pantalla de conexión).
    private static final int PAGE_CHANNELS = 0;
    private static final int PAGE_CROSSOVER = 1;
    private static final int PAGE_EQ = 2;
    private static final int PAGE_GAIN = 3;
    private static final String PREF_GROUPS_ON = "groups_on";
    private static final String PREF_GROUPS_HELP_HIDDEN = "groups_help_hidden";
    // + dirección Bluetooth del equipo ("demo" en el modo demo): grupos de cada DSP.
    private static final String PREF_GROUPS_PREFIX = "channel_groups_";
    private static final String PREF_LIMITER_AUTO = "limiter_auto";
    // xair Delay: distancia en mm (rango en Commands); 343 mm = 1 ms de sonido.
    private static final int DELAY_STEP_MM = 10;
    private static final float MM_PER_MS = 343f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    // Conexión abierta con el DSP (real o demo); null sin equipo.
    private Session session;
    private final Session.Scheduler scheduler = new Session.Scheduler() {
        @Override
        public void schedule(Runnable task, long delayMs) {
            handler.postDelayed(task, delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            handler.removeCallbacks(task);
        }
    };
    private SharedPreferences prefs;

    private State state = State.IDLE;
    private final Set<String> seenDevices = new HashSet<>();
    private final Map<String, BluetoothDevice> candidates = new LinkedHashMap<>();
    private final Map<String, View> candidateRows = new LinkedHashMap<>();
    private BluetoothDevice selectedDevice;
    private BluetoothDevice connectedDevice;

    private View statusDot;
    private TextView statusText;
    private TextView statusDetail;
    private TextView scanButton;
    private LinearLayout deviceList;
    private TextView devicesEmpty;
    private EditText passwordField;
    private TextView connectButton;
    private LinearLayout channelsCard;
    private TextView channelsEmpty;
    private LinearLayout channelRows;
    private TextView logToggle;
    private final LinearLayout[] pages = new LinearLayout[4];
    private final TextView[] navItems = new TextView[4];
    private int currentPage = PAGE_CHANNELS;
    private LinearLayout eqCard;
    private TextView eqEmpty;
    private TextView eqTitle;
    private LinearLayout eqChannelChips;
    private EqCurveView eqCurve;
    // En el EQ de un canal: mostrar el EQ de entrada de fondo y la suma de los dos.
    private TextView eqInputToggle;
    private boolean eqShowInput;
    // EQ de entrada agrandado: pantalla completa en horizontal para armar la curva.
    private ExpandView eqExpandButton;
    private View eqExpanded;
    private EqCurveView eqCurveBig;
    private TextView eqBigInfo;
    private LinearLayout eqBigPresetChips;
    private final android.window.OnBackInvokedCallback collapseEqOnBack = this::collapseEq;
    // El gráfico chico y el agrandado editan lo mismo.
    private final EqCurveView.Listener eqCurveListener = new EqCurveView.Listener() {
        @Override
        public void onBandSelected(int band) {
            selectBand(band);
        }

        @Override
        public void onBandChanged(int band, boolean done) {
            onBandEdited(band, done, !isInputEq());
        }

        @Override
        public void onBandQChanged(int band, boolean done) {
            refreshAfterEdit(band);
            if (done) {
                markCustom();
                queueEqWrite("Q", band);
            }
        }
    };
    private LinearLayout eqBandsList;
    private ScrollView eqBandsScroll;
    private LinearLayout eqPresetChips;
    private TextView eqBandLabel;
    private TextView eqSyncText;
    private TextView eqGainValue;
    private TextView eqFreqValue;
    private TextView eqQValue;
    private View eqFreqRow;
    private View eqQRow;
    private final List<TextView[]> eqBandRows = new ArrayList<>();
    private Config config;
    private int eqSelection;
    private int eqSelectedBand;
    // "Custom" por EQ (clave = eqSelection): lo grabado en el DSP más las ediciones a mano.
    private final Map<Integer, float[][]> eqCustom = new HashMap<>();
    // Preset activo por EQ: -1 = Custom.
    private final Map<Integer, Integer> eqActivePreset = new HashMap<>();
    // Live = cada cambio sale al DSP al instante; si no, se acumulan hasta Grabar.
    private boolean eqLive;
    // Live / Grabar está en el encabezado de cada página editable (EQ y Ganancias).
    private final List<TextView> liveButtons = new ArrayList<>();
    private final List<TextView> saveButtons = new ArrayList<>();
    private final List<TextView> syncTexts = new ArrayList<>();
    private LinearLayout gainCard;
    private TextView gainEmpty;
    private LinearLayout gainRows;
    // Actividad con una conexión (real o demo) abierta, para que el widget la use.
    private static WeakReference<XLinkActivity> connectedActivity = new WeakReference<>(null);
    private boolean permissionsAsked = false;
    // Modo demo: conectado a un DSP simulado (MockTransport), para el emulador.
    private boolean demoMode = false;
    private TextView demoButton;
    private TextView logView;
    private ScrollView logScroll;
    // Esqueleto: pantalla de conexión o pantalla principal (pestañas + panel lateral).
    private View connectScreen;
    private View mainScreen;
    private LinearLayout logBlock;
    private LinearLayout drawerLogHolder;
    private View drawerScrim;
    private View drawerPanel;
    private boolean drawerOpen = false;
    private final android.window.OnBackInvokedCallback closeDrawerOnBack = this::closeDrawer;
    private TextView topTitle;
    private TextView topSubtitle;
    private View topDot;
    private TextView drawerName;
    private TextView drawerDetail;
    private View drawerDot;
    // Crossover
    private LinearLayout xoCard;
    private TextView xoEmpty;
    private LinearLayout xoChips;
    private CrossoverView xoView;
    private TextView xoHpfValue;
    private TextView xoLpfValue;
    private LinearLayout xoHpfSlopes;
    private LinearLayout xoLpfSlopes;
    private TextView xoHpfHint;
    private TextView xoLpfHint;
    private int xoSelected;
    private boolean limiterAuto;
    // Mute del master: baja la ganancia al mínimo con un fade y la devuelve al sacarlo.
    private boolean masterMuted;
    private int masterBeforeMute;
    private android.animation.ValueAnimator masterFade;
    // Canales vinculados del equipo conectado; solo se aplican con los grupos prendidos.
    private ChannelGroups groups = new ChannelGroups();
    private String groupsKey;
    private boolean groupsOn;
    private final List<ChainView> groupButtons = new ArrayList<>();
    private final List<TextView> discardButtons = new ArrayList<>();
    // Pantalla de edición de grupos abierta (se redibuja al cambiar un grupo), o null.
    private android.app.Dialog groupsEditor;
    private LinearLayout groupsEditorBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        log("App iniciada");

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (bluetoothAdapter == null) {
            setStatus(RED, "Sin Bluetooth", "Este celular no tiene adaptador Bluetooth");
        } else {
            log("Bluetooth " + (bluetoothAdapter.isEnabled() ? "activado" : "DESACTIVADO"));
            setStatus(MUTED, "Desconectado", "Tocá Buscar para encontrar tu DSP");
        }
        renderState();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        groupsOn = prefs.getBoolean(PREF_GROUPS_ON, false);
        limiterAuto = prefs.getBoolean(PREF_LIMITER_AUTO, true);

        // ===== Pantalla de conexión: solo cuando no hay equipo conectado
        LinearLayout connectionPage = new LinearLayout(this);
        connectionPage.setOrientation(LinearLayout.VERTICAL);
        connectScreen = connectionPage;
        root.addView(connectionPage, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Header: logo + nombre
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        TextView title = text("XLink DSP", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        titles.addView(text("Control BLE para DSP Expert", 13, MUTED));
        header.addView(titles);
        connectionPage.addView(header);

        // Estado
        LinearLayout statusCard = card();
        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusLine.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusText = text("", 18, TEXT);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(10), 0, 0, 0);
        statusLine.addView(statusText);
        statusCard.addView(statusLine);
        statusDetail = text("", 14, MUTED);
        statusDetail.setPadding(0, dp(4), 0, 0);
        statusCard.addView(statusDetail);
        connectionPage.addView(statusCard, cardParams());

        // Equipos
        LinearLayout devicesCard = card();
        LinearLayout devicesHeader = new LinearLayout(this);
        devicesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView devicesTitle = label("EQUIPOS");
        devicesHeader.addView(devicesTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        scanButton = button("Buscar", CYAN, false);
        scanButton.setOnClickListener(v -> onSearchClicked());
        demoButton = button("Demo", VIOLET, false);
        demoButton.setOnClickListener(v -> startDemo());
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(dp(84), dp(40));
        demoParams.rightMargin = dp(8);
        devicesHeader.addView(demoButton, demoParams);
        devicesHeader.addView(scanButton, new LinearLayout.LayoutParams(dp(110), dp(40)));
        devicesCard.addView(devicesHeader);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, dp(8), 0, 0);
        devicesEmpty = text("Ningún equipo todavía", 14, MUTED);
        deviceList.addView(devicesEmpty);
        devicesCard.addView(deviceList);
        connectionPage.addView(devicesCard, cardParams());

        // Password + conectar
        LinearLayout connectRow = new LinearLayout(this);
        connectRow.setGravity(Gravity.CENTER_VERTICAL);
        passwordField = new EditText(this);
        passwordField.setHint("Password");
        passwordField.setHintTextColor(MUTED);
        passwordField.setTextColor(TEXT);
        passwordField.setTextSize(16f);
        passwordField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passwordField.setBackground(rounded(CARD, CARD_STROKE, 12));
        passwordField.setPadding(dp(14), 0, dp(14), 0);
        passwordField.setText(prefs.getString(PREF_PASSWORD, ""));
        connectRow.addView(passwordField, new LinearLayout.LayoutParams(0, dp(52), 1f));
        connectButton = button("Conectar", ORANGE, true);
        connectButton.setOnClickListener(v -> onConnectClicked());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(dp(140), dp(52));
        connectParams.leftMargin = dp(10);
        connectRow.addView(connectButton, connectParams);
        connectionPage.addView(connectRow, cardParams());

        // Log plegable: va debajo de conectar o, ya conectado, dentro del panel lateral
        logBlock = new LinearLayout(this);
        logBlock.setOrientation(LinearLayout.VERTICAL);
        logToggle = text("", 13, MUTED);
        logToggle.setPadding(0, dp(10), 0, dp(4));
        logToggle.setOnClickListener(v -> setLogVisible(logScroll.getVisibility() != View.VISIBLE));
        logBlock.addView(logToggle);
        logView = text("", 11, MUTED);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.addView(logView);
        logBlock.addView(logScroll);
        setLogVisible(false);

        // ===== Pantalla principal: barra superior + pestañas
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        mainScreen = main;
        root.addView(main, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView menu = text("☰", 24, TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
        menu.setClickable(true);
        menu.setContentDescription("Menú");
        menu.setOnClickListener(v -> openDrawer());
        topBar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout topTitles = new LinearLayout(this);
        topTitles.setOrientation(LinearLayout.VERTICAL);
        topTitles.setPadding(dp(8), 0, 0, 0);
        topTitle = text("", 17, TEXT);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topTitles.addView(topTitle);
        topSubtitle = text("", 12, MUTED);
        topTitles.addView(topSubtitle);
        topBar.addView(topTitles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topDot = new View(this);
        topBar.addView(topDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        main.addView(topBar);

        // Contenido: una página visible a la vez, elegida con la barra de abajo
        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            content.addView(pages[i], new android.widget.FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
        LinearLayout channelsPage = pages[PAGE_CHANNELS];
        LinearLayout crossoverPage = pages[PAGE_CROSSOVER];
        LinearLayout eqPage = pages[PAGE_EQ];
        LinearLayout gainPage = pages[PAGE_GAIN];

        // ===== Página 2: Canales (routing + fase + delay de cada salida)
        channelsPage.addView(editableHeader("Canales"));
        channelsEmpty = text("Conectate al DSP: los canales se cargan solos al entrar", 14, MUTED);
        channelsEmpty.setPadding(0, dp(12), 0, 0);
        channelsPage.addView(channelsEmpty);
        // Cada canal es su propia tarjeta: el contenedor solo agrupa y desliza.
        channelsCard = new LinearLayout(this);
        channelsCard.setOrientation(LinearLayout.VERTICAL);
        ScrollView channelsScroll = new ScrollView(this);
        channelsScroll.setVerticalScrollBarEnabled(false);
        channelRows = new LinearLayout(this);
        channelRows.setOrientation(LinearLayout.VERTICAL);
        channelsScroll.addView(channelRows);
        channelsCard.addView(channelsScroll);
        LinearLayout.LayoutParams channelsParams = cardParams();
        channelsParams.height = 0;
        channelsParams.weight = 1f;
        channelsPage.addView(channelsCard, channelsParams);

        // ===== Página 2: Crossover (HPF + LPF de cada salida)
        crossoverPage.addView(editableHeader("Crossover"));
        xoEmpty = text("El DSP no mandó el crossover", 14, MUTED);
        xoEmpty.setPadding(0, dp(12), 0, 0);
        crossoverPage.addView(xoEmpty);
        // Sin una tarjeta grande: pills sueltas arriba y el gráfico y cada filtro en su tarjeta.
        xoCard = new LinearLayout(this);
        xoCard.setOrientation(LinearLayout.VERTICAL);
        ScrollView xoScroll = new ScrollView(this);
        xoScroll.setVerticalScrollBarEnabled(false);
        LinearLayout xoBody = new LinearLayout(this);
        xoBody.setOrientation(LinearLayout.VERTICAL);
        xoScroll.addView(xoBody);
        xoCard.addView(xoScroll);
        LinearLayout xoChipsRow = new LinearLayout(this);
        xoChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView xoChipsScroll = new HorizontalScrollView(this);
        xoChipsScroll.setHorizontalScrollBarEnabled(false);
        xoChips = new LinearLayout(this);
        xoChipsScroll.addView(xoChips);
        xoChipsRow.addView(xoChipsScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        xoBody.addView(xoChipsRow);
        xoView = new CrossoverView(this);
        xoView.setListener((channel, hpf, frequency, done) -> setXoFrequency(hpf, Math.round(frequency), done));
        LinearLayout xoGraphCard = card();
        xoGraphCard.addView(xoView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));
        xoBody.addView(xoGraphCard, cardParams());
        xoHpfValue = text("", 15, TEXT);
        xoHpfSlopes = new LinearLayout(this);
        xoHpfHint = text("", 12, MUTED);
        xoBody.addView(xoSection("PASA ALTOS · HPF", "corta los graves", xoHpfValue, xoHpfSlopes, xoHpfHint, true),
                cardParams());
        xoLpfValue = text("", 15, TEXT);
        xoLpfSlopes = new LinearLayout(this);
        xoLpfHint = text("", 12, MUTED);
        xoBody.addView(xoSection("PASA BAJOS · LPF", "corta los agudos", xoLpfValue, xoLpfSlopes, xoLpfHint, false),
                cardParams());
        LinearLayout.LayoutParams xoParams = cardParams();
        xoParams.height = 0;
        xoParams.weight = 1f;
        crossoverPage.addView(xoCard, xoParams);

        // ===== Página 3: Ecualizador
        eqLive = prefs.getBoolean(PREF_EQ_LIVE, false);
        eqPage.addView(editableHeader("Ecualizador"));
        eqEmpty = text("Conectate al DSP: el ecualizador se carga solo al entrar", 14, MUTED);
        eqEmpty.setPadding(0, dp(12), 0, 0);
        eqPage.addView(eqEmpty);
        // Sin una tarjeta grande: título y pills sueltos, el gráfico y el editor en su tarjeta.
        eqCard = new LinearLayout(this);
        eqCard.setOrientation(LinearLayout.VERTICAL);
        eqTitle = label("ECUALIZADOR");
        eqTitle.setPadding(dp(4), 0, 0, 0);
        eqCard.addView(eqTitle);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        eqChannelChips = new LinearLayout(this);
        chipsScroll.addView(eqChannelChips);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dp(8);
        eqCard.addView(chipsScroll, chipsParams);
        eqCurve = new EqCurveView(this);
        eqCurve.setListener(eqCurveListener);
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210));
        // El gráfico lleva encima, abajo a la derecha, el botón de "IN de fondo".
        android.widget.FrameLayout curveFrame = new android.widget.FrameLayout(this);
        curveFrame.addView(eqCurve, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        eqShowInput = prefs.getBoolean(PREF_EQ_SHOW_INPUT, false);
        eqInputToggle = text("+ IN", 11, MUTED);
        eqInputToggle.setTypeface(Typeface.DEFAULT_BOLD);
        eqInputToggle.setGravity(Gravity.CENTER);
        eqInputToggle.setPadding(dp(10), 0, dp(10), 0);
        eqInputToggle.setOnClickListener(v -> {
            eqShowInput = !eqShowInput;
            prefs.edit().putBoolean(PREF_EQ_SHOW_INPUT, eqShowInput).apply();
            refreshEqUnderlay();
        });
        android.widget.FrameLayout.LayoutParams toggleParams = new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM | Gravity.END);
        toggleParams.bottomMargin = dp(22);
        toggleParams.rightMargin = dp(6);
        curveFrame.addView(eqInputToggle, toggleParams);
        // En el EQ de entrada, en el mismo lugar: agrandarlo a pantalla completa.
        eqExpandButton = new ExpandView(this, false);
        eqExpandButton.setOnClickListener(v -> expandEq());
        android.widget.FrameLayout.LayoutParams expandParams = new android.widget.FrameLayout.LayoutParams(
                dp(36), dp(26), Gravity.BOTTOM | Gravity.END);
        expandParams.bottomMargin = dp(22);
        expandParams.rightMargin = dp(6);
        curveFrame.addView(eqExpandButton, expandParams);
        LinearLayout eqGraphCard = card();
        eqGraphCard.addView(curveFrame, curveParams);
        eqCard.addView(eqGraphCard, cardParams());

        // Presets: Custom (lo grabado) + EQ predefinidos
        HorizontalScrollView presetsScroll = new HorizontalScrollView(this);
        presetsScroll.setHorizontalScrollBarEnabled(false);
        eqPresetChips = new LinearLayout(this);
        presetsScroll.addView(eqPresetChips);
        eqPresetChips.addView(chip("Custom", -1, v -> applyPreset(-1)));
        for (int i = 0; i < EqPresets.NAMES.length; i++) {
            int preset = i;
            eqPresetChips.addView(chip(EqPresets.NAMES[i], i, v -> applyPreset(preset)));
        }
        LinearLayout.LayoutParams presetsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        presetsParams.topMargin = dp(12);
        eqCard.addView(presetsScroll, presetsParams);

        // Editor de la banda seleccionada
        LinearLayout editorHeader = new LinearLayout(this);
        editorHeader.setGravity(Gravity.CENTER_VERTICAL);
        eqBandLabel = text("", 14, TEXT);
        eqBandLabel.setTypeface(Typeface.DEFAULT_BOLD);
        editorHeader.addView(eqBandLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        eqSyncText = text("Arrastrá un punto; sus bordes cambian la Q", 12, MUTED);
        editorHeader.addView(eqSyncText);
        LinearLayout editorCard = card();
        editorCard.addView(editorHeader);
        eqGainValue = text("", 15, TEXT);
        editorCard.addView(stepperRow("Ganancia", eqGainValue, v -> stepGain(-1), v -> stepGain(1)));
        eqFreqValue = text("", 15, TEXT);
        eqFreqRow = stepperRow("Frecuencia", eqFreqValue, v -> stepFrequency(-1), v -> stepFrequency(1));
        editorCard.addView(eqFreqRow);
        eqQValue = text("", 15, TEXT);
        eqQRow = stepperRow("Q", eqQValue, v -> stepQ(-1), v -> stepQ(1));
        editorCard.addView(eqQRow);
        ScrollView bandsScroll = new ScrollView(this);
        bandsScroll.setVerticalScrollBarEnabled(false);
        eqBandsScroll = bandsScroll;
        eqBandsList = new LinearLayout(this);
        eqBandsList.setOrientation(LinearLayout.VERTICAL);
        bandsScroll.addView(eqBandsList);
        LinearLayout.LayoutParams bandsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bandsParams.topMargin = dp(8);
        editorCard.addView(bandsScroll, bandsParams);
        LinearLayout.LayoutParams editorParams = cardParams();
        editorParams.height = 0;
        editorParams.weight = 1f;
        eqCard.addView(editorCard, editorParams);
        LinearLayout.LayoutParams eqParams = cardParams();
        eqParams.height = 0;
        eqParams.weight = 1f;
        eqPage.addView(eqCard, eqParams);

        // ===== Página 4: Niveles (master + cada canal: ganancia, mute y limiter)
        gainPage.addView(editableHeader("Niveles"));
        gainEmpty = text("El DSP no mandó ganancias", 14, MUTED);
        gainEmpty.setPadding(0, dp(12), 0, 0);
        gainPage.addView(gainEmpty);
        // Master y cada canal van en su propia tarjeta: el contenedor solo agrupa y desliza.
        gainCard = new LinearLayout(this);
        gainCard.setOrientation(LinearLayout.VERTICAL);
        ScrollView gainScroll = new ScrollView(this);
        gainScroll.setVerticalScrollBarEnabled(false);
        gainRows = new LinearLayout(this);
        gainRows.setOrientation(LinearLayout.VERTICAL);
        gainScroll.addView(gainRows);
        gainCard.addView(gainScroll);
        LinearLayout.LayoutParams gainParams = cardParams();
        gainParams.height = 0;
        gainParams.weight = 1f;
        gainPage.addView(gainCard, gainParams);

        // ===== Barra de pestañas abajo
        LinearLayout navBar = new LinearLayout(this);
        GradientDrawable navBackground = new GradientDrawable();
        navBackground.setColor(CARD);
        navBar.setBackground(navBackground);
        View navDivider = new View(this);
        navDivider.setBackgroundColor(CARD_STROKE);
        main.addView(navDivider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        String[] navLabels = {"Canales", "Crossover", "EQ", "Niveles"};
        String[] navIcons = {"⇄", "⋈", "∿", "◢"};
        for (int i = 0; i < navItems.length; i++) {
            int page = i;
            // Ícono bastante más grande que el nombre, que queda en 12 sp.
            android.text.SpannableString label = new android.text.SpannableString(navIcons[i] + "\n" + navLabels[i]);
            label.setSpan(new android.text.style.RelativeSizeSpan(1.9f), 0, navIcons[i].length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView item = text("", 12, MUTED);
            item.setText(label);
            item.setGravity(Gravity.CENTER);
            item.setTypeface(Typeface.DEFAULT_BOLD);
            item.setLineSpacing(dp(2), 1f);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
            item.setClickable(true);
            item.setOnClickListener(v -> showPage(page));
            navItems[i] = item;
            navBar.addView(item, new LinearLayout.LayoutParams(0, dp(70), 1f));
        }
        main.addView(navBar);

        // ===== Panel lateral (☰): equipo conectado, cambiar de equipo y log técnico
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x99000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1A1F33, CARD}));
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);
        drawerPanel = drawer;
        root.addView(drawer, new android.widget.FrameLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout drawerHeader = new LinearLayout(this);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        ImageView drawerLogo = new ImageView(this);
        drawerLogo.setImageResource(R.mipmap.ic_launcher);
        drawerHeader.addView(drawerLogo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout drawerTitles = new LinearLayout(this);
        drawerTitles.setOrientation(LinearLayout.VERTICAL);
        drawerTitles.setPadding(dp(12), 0, 0, 0);
        TextView drawerTitle = text("XLink DSP", 20, TEXT);
        drawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        drawerTitles.addView(drawerTitle);
        drawerTitles.addView(text("Control para DSP Expert", 12, MUTED));
        drawerHeader.addView(drawerTitles);
        drawer.addView(drawerHeader);
        TextView deviceLabel = label("EQUIPO CONECTADO");
        deviceLabel.setPadding(0, dp(24), 0, dp(8));
        drawer.addView(deviceLabel);
        LinearLayout deviceCard = new LinearLayout(this);
        deviceCard.setOrientation(LinearLayout.VERTICAL);
        deviceCard.setBackground(rounded(0x33000000, CARD_STROKE, 14));
        deviceCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout deviceLine = new LinearLayout(this);
        deviceLine.setGravity(Gravity.CENTER_VERTICAL);
        drawerDot = new View(this);
        deviceLine.addView(drawerDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        drawerName = text("", 16, TEXT);
        drawerName.setTypeface(Typeface.DEFAULT_BOLD);
        drawerName.setPadding(dp(10), 0, 0, 0);
        deviceLine.addView(drawerName);
        deviceCard.addView(deviceLine);
        drawerDetail = text("", 13, MUTED);
        drawerDetail.setPadding(0, dp(4), 0, 0);
        deviceCard.addView(drawerDetail);
        drawer.addView(deviceCard);
        TextView switchDevice = button("Conectar otro equipo", CYAN, false);
        switchDevice.setOnClickListener(v -> disconnect("Cambiar de equipo"));
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        switchParams.topMargin = dp(12);
        drawer.addView(switchDevice, switchParams);
        TextView groupsEntry = button("Grupos de canales", TEXT, false);
        groupsEntry.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(0x00000000, CARD_STROKE, 12), null));
        groupsEntry.setOnClickListener(v -> {
            closeDrawer();
            openGroupsEditor();
        });
        LinearLayout.LayoutParams groupsEntryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        groupsEntryParams.topMargin = dp(8);
        drawer.addView(groupsEntry, groupsEntryParams);
        drawerLogHolder = new LinearLayout(this);
        drawerLogHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        holderParams.topMargin = dp(16);
        drawer.addView(drawerLogHolder, holderParams);
        drawer.addView(text("XLink DSP · " + BuildConfig.GIT_SHA, 11, MUTED));

        // ===== EQ de entrada agrandado (encima de todo, oculto hasta que se agranda)
        LinearLayout expanded = new LinearLayout(this);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setBackgroundColor(BG);
        expanded.setClickable(true);
        expanded.setVisibility(View.GONE);
        eqExpanded = expanded;
        LinearLayout expandedTop = new LinearLayout(this);
        expandedTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView expandedTitle = label("EQ DE ENTRADA");
        expandedTop.addView(expandedTitle);
        eqBigInfo = text("", 14, TEXT);
        eqBigInfo.setTypeface(Typeface.DEFAULT_BOLD);
        eqBigInfo.setPadding(dp(16), 0, dp(8), 0);
        eqBigInfo.setSingleLine(true);
        eqBigInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        expandedTop.addView(eqBigInfo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // − / + de a 1 dB para la banda elegida, para afinar sin arrastrar.
        TextView bigMinus = stepButton("−");
        bigMinus.setOnClickListener(v -> stepGain(-1));
        expandedTop.addView(bigMinus, new LinearLayout.LayoutParams(dp(48), dp(32)));
        TextView bigPlus = stepButton("+");
        bigPlus.setOnClickListener(v -> stepGain(1));
        LinearLayout.LayoutParams bigPlusParams = new LinearLayout.LayoutParams(dp(48), dp(32));
        bigPlusParams.leftMargin = dp(8);
        bigPlusParams.rightMargin = dp(16);
        expandedTop.addView(bigPlus, bigPlusParams);
        ExpandView collapse = new ExpandView(this, true);
        collapse.setOnClickListener(v -> collapseEq());
        expandedTop.addView(collapse, new LinearLayout.LayoutParams(dp(44), dp(32)));
        expanded.addView(expandedTop);
        eqCurveBig = new EqCurveView(this);
        eqCurveBig.setListener(eqCurveListener);
        LinearLayout.LayoutParams bigParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bigParams.topMargin = dp(8);
        expanded.addView(eqCurveBig, bigParams);
        // Los presets abajo, para arrancar la curva desde uno.
        HorizontalScrollView bigPresetsScroll = new HorizontalScrollView(this);
        bigPresetsScroll.setHorizontalScrollBarEnabled(false);
        eqBigPresetChips = new LinearLayout(this);
        bigPresetsScroll.addView(eqBigPresetChips);
        eqBigPresetChips.addView(chip("Custom", -1, v -> applyPreset(-1)));
        for (int i = 0; i < EqPresets.NAMES.length; i++) {
            int preset = i;
            eqBigPresetChips.addView(chip(EqPresets.NAMES[i], i, v -> applyPreset(preset)));
        }
        LinearLayout.LayoutParams bigPresetsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bigPresetsParams.topMargin = dp(8);
        expanded.addView(bigPresetsScroll, bigPresetsParams);
        root.addView(expanded, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Android 15+ (targetSdk 36) fuerza edge-to-edge: sin esto el contenido
        // se dibuja debajo de la barra de estado/notch y se ve "cortado" arriba.
        // La barra de pestañas va de borde a borde, solo el contenido lleva margen.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            connectionPage.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            topBar.setPadding(dp(4) + bars.left, bars.top + dp(4), pad + bars.right, dp(4));
            content.setPadding(pad + bars.left, dp(4), pad + bars.right, pad);
            navBar.setPadding(bars.left, 0, bars.right, bars.bottom);
            drawer.setPadding(dp(20) + bars.left, bars.top + dp(20), dp(20), bars.bottom + dp(16));
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            expanded.setPadding(pad + safe.left, dp(12) + safe.top, pad + safe.right, dp(8) + safe.bottom);
            return insets;
        });

        setContentView(root);

        // Íconos claros en las barras del sistema (el fondo es oscuro). Va después
        // de setContentView: antes no existe el DecorView y getInsetsController() revienta.
        WindowInsetsController insetsController = getWindow().getInsetsController();
        if (insetsController != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            insetsController.setSystemBarsAppearance(0, lightBars);
        }
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 12, MUTED);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLetterSpacing(0.12f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, CARD_STROKE, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    /** Botón: relleno (primario, degradé naranja->rosa) o contorneado del color dado. */
    private TextView button(String value, int color, boolean filled) {
        TextView view = text(value, 15, filled ? 0xFF14100C : color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setAllCaps(false);
        GradientDrawable shape;
        if (filled) {
            shape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color, PINK});
            shape.setCornerRadius(dp(12));
        } else {
            shape = rounded(0x00000000, color, 12);
        }
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        view.setClickable(true);
        return view;
    }

    /** Botón de paso (−/+) neutro: se ve como botón sin gastar los colores de acento. */
    private TextView stepButton(String value) {
        TextView view = text(value, 17, TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        GradientDrawable shape = rounded(0xFF1E2330, 0xFF323949, 12);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        view.setClickable(true);
        return view;
    }

    private TextView pageTitle(String value) {
        TextView view = text(value, 22, TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void showPage(int page) {
        currentPage = page;
        renderState();
        autoSearch();
    }

    // ---------------------------------------------------------------- Ecualizador

    private void onConfigLoaded(Config loaded) {
        config = loaded;
        log("Config: bloques " + loaded.blocks.keySet() + ", EQ " + loaded.eqChannels.size()
                + " canales, EQ de entrada " + loaded.inputEq.size() + " bandas");
        runOnUiThread(() -> {
            if (masterFade != null) {
                masterFade.cancel();
            }
            masterMuted = false;
            eqCustom.clear();
            eqActivePreset.clear();
            buildGainRows();
            buildChannelRows();
            buildCrossover();
            boolean hasInput = !loaded.inputEq.isEmpty();
            if (!hasInput && loaded.eqChannels.isEmpty()) {
                eqChannelChips.removeAllViews();
                eqTitle.setText("ECUALIZADOR  ·  no vino en la respuesta");
                return;
            }
            // Al releer (Descartar) se queda en el EQ que estaba; si no, el de entrada.
            boolean keep = (eqSelection == -1 && hasInput)
                    || (eqSelection >= 0 && eqSelection < loaded.eqChannels.size());
            buildEqChips(keep ? eqSelection : hasInput ? -1 : 0);
            renderState();
            refreshGroupsEditor();
        });
    }

    /** Pill seleccionable (canales y presets); el tag es su valor para styleChips. */
    private TextView chip(String name, int tag, View.OnClickListener onClick) {
        TextView chip = text(name, 13, TEXT);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTag(tag);
        chip.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        return chip;
    }

    private void styleChips(LinearLayout chips, int selectedTag) {
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean selected = (int) chip.getTag() == selectedTag;
            chip.setTextColor(selected ? BG : TEXT);
            chip.setBackground(selected ? rounded(ORANGE, ORANGE, 17) : rounded(0x00000000, CARD_STROKE, 17));
            if (selected) {
                scrollIntoView(chip);
            }
        }
    }

    /** Si la pill elegida quedó fuera de la fila deslizable, la trae a la vista. */
    private void scrollIntoView(View chip) {
        // La fila se pudo rearmar (grupos, recarga) mientras esto esperaba al layout.
        if (chip.getParent() == null || !(chip.getParent().getParent() instanceof HorizontalScrollView)) {
            return;
        }
        HorizontalScrollView scroll = (HorizontalScrollView) chip.getParent().getParent();
        scroll.post(() -> {
            // Recién cargada la config la tarjeta todavía no se midió: esperar al layout.
            if (scroll.getWidth() == 0 || chip.getWidth() == 0) {
                scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        if (scroll.getWidth() > 0) {
                            scroll.removeOnLayoutChangeListener(this);
                            scrollIntoView(chip);
                        }
                    }
                });
                return;
            }
            int left = chip.getLeft();
            int right = chip.getRight() + dp(8);
            int visibleLeft = scroll.getScrollX();
            int visibleRight = visibleLeft + scroll.getWidth();
            if (left < visibleLeft) {
                scroll.smoothScrollTo(left, 0);
            } else if (right > visibleRight) {
                scroll.smoothScrollTo(right - scroll.getWidth(), 0);
            }
        });
    }

    private View stepperRow(String name, TextView value,
                            View.OnClickListener onMinus, View.OnClickListener onPlus) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(name, 13, MUTED), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = stepButton("−");
        minus.setOnClickListener(onMinus);
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(34)));
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView plus = stepButton("+");
        plus.setOnClickListener(onPlus);
        row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(34)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        row.setLayoutParams(params);
        return row;
    }

    private boolean isInputEq() {
        return eqSelection < 0;
    }

    private List<Config.Band> currentBands() {
        return isInputEq() ? config.inputEq : config.eqChannels.get(eqSelection);
    }

    // Rangos de xair: EQIN ±12 dB enteros; EQCH ±18 dB, 20..20000 Hz, Q 0.6..9.9.
    private float maxGain() {
        return isInputEq() ? 12 : 18;
    }

    /** selection = -1 para el EQ de entrada, 0..n-1 para el EQ de cada canal de salida. */
    private void selectEq(int selection) {
        if (selection >= 0) {
            selection = leader(selection);
        }
        eqSelection = selection;
        styleChips(eqChannelChips, selection);
        List<Config.Band> bands = currentBands();
        if (!eqCustom.containsKey(selection)) {
            eqCustom.put(selection, snapshot(bands));
            if (selection < 0) {
                restoreInputCustom(bands);
            }
        }
        stylePresetChips(activePreset());
        eqTitle.setText((isInputEq() ? "EQ DE ENTRADA" : "EQ CANAL " + (selection + 1))
                + "  ·  " + bands.size() + " bandas" + (demoMode ? "  ·  demo" : ""));
        eqCurve.setBands(bands, isInputEq(), maxGain());
        refreshEqUnderlay();
        eqSyncText.setText(isInputEq() ? "Arrastrá un punto o usá − / +" : "Arrastrá un punto; sus bordes cambian la Q");
        eqFreqRow.setVisibility(isInputEq() ? View.GONE : View.VISIBLE);
        eqQRow.setVisibility(isInputEq() ? View.GONE : View.VISIBLE);
        eqBandsList.removeAllViews();
        eqBandRows.clear();
        for (int i = 0; i < bands.size(); i++) {
            eqBandsList.addView(bandRow(i));
        }
        selectBand(0);
    }

    /** "+ IN": solo en el EQ de un canal y si el DSP tiene EQ de entrada. */
    private void refreshEqUnderlay() {
        boolean available = !isInputEq() && !config.inputEq.isEmpty();
        eqInputToggle.setVisibility(available ? View.VISIBLE : View.GONE);
        eqExpandButton.setVisibility(isInputEq() ? View.VISIBLE : View.GONE);
        eqInputToggle.setTextColor(eqShowInput ? BG : MUTED);
        eqInputToggle.setBackground(eqShowInput ? rounded(0xFFFFC857, 0xFFFFC857, 12) : rounded(0xCC0E1116, CARD_STROKE, 12));
        eqCurve.setUnderlay(available && eqShowInput ? config.inputEq : null);
    }

    /**
     * Si el widget dejó un preset en el EQ de entrada, lo que vino del DSP es ese
     * preset y no el Custom: el Custom real es el guardado en prefs.
     */
    private void restoreInputCustom(List<Config.Band> bands) {
        int widgetPreset = prefs.getInt(EqWidget.PREF_WIDGET_PRESET, -1);
        float[] saved = EqWidget.loadCustomIn(prefs);
        if (widgetPreset >= 0 && saved != null && saved.length == bands.size()) {
            float[][] custom = eqCustom.get(-1);
            for (int i = 0; i < saved.length; i++) {
                custom[i][2] = saved[i];
            }
            eqActivePreset.put(-1, widgetPreset);
        } else {
            EqWidget.saveCustomIn(this, bands);
            prefs.edit().putInt(EqWidget.PREF_WIDGET_PRESET, -1).apply();
            EqWidget.refresh(this);
        }
    }

    private int activePreset() {
        Integer preset = eqActivePreset.get(eqSelection);
        return preset != null ? preset : -1;
    }

    private void selectBand(int band) {
        if (band < 0 || band >= currentBands().size()) {
            return;
        }
        eqSelectedBand = band;
        eqCurve.setHighlightedBand(band);
        eqCurveBig.setHighlightedBand(band);
        for (int i = 0; i < eqBandsList.getChildCount(); i++) {
            eqBandsList.getChildAt(i).setBackground(i == band ? rounded(0x14FFFFFF, 0x00000000, 10) : null);
        }
        // Elegida desde el gráfico puede estar fuera de la lista visible: la trae a la vista.
        if (band < eqBandsList.getChildCount()) {
            View row = eqBandsList.getChildAt(band);
            eqBandsScroll.post(() -> {
                int top = row.getTop();
                int bottom = row.getBottom();
                int visibleTop = eqBandsScroll.getScrollY();
                int visibleBottom = visibleTop + eqBandsScroll.getHeight();
                if (top < visibleTop) {
                    eqBandsScroll.smoothScrollTo(0, top);
                } else if (bottom > visibleBottom) {
                    eqBandsScroll.smoothScrollTo(0, bottom - eqBandsScroll.getHeight());
                }
            });
        }
        refreshEditor();
    }

    private void refreshEditor() {
        Config.Band band = currentBands().get(eqSelectedBand);
        eqBandLabel.setText("F" + (eqSelectedBand + 1) + "  ·  " + formatHz(band.frequency));
        eqGainValue.setText(String.format(Locale.ROOT, "%+.0f dB", band.gainDb));
        eqGainValue.setTextColor(gainColor(band.gainDb));
        eqFreqValue.setText(formatHz(band.frequency));
        eqQValue.setText(String.format(Locale.ROOT, "%.1f", band.q));
        eqBigInfo.setText("F" + (eqSelectedBand + 1) + "  ·  " + formatHz(band.frequency) + "  ·  "
                + String.format(Locale.ROOT, "%+.0f dB", band.gainDb));
        eqBigInfo.setTextColor(gainColor(band.gainDb) == MUTED ? TEXT : gainColor(band.gainDb));
    }

    /** El preset activo marcado en las pills de abajo del EQ y en las del agrandado. */
    private void stylePresetChips(int preset) {
        styleChips(eqPresetChips, preset);
        styleChips(eqBigPresetChips, preset);
    }

    private void invalidateCurves() {
        eqCurve.invalidate();
        eqCurveBig.invalidate();
    }

    /**
     * Agranda el EQ de entrada: gira a horizontal y lo muestra a pantalla completa,
     * editable igual que el chico. El botón de achicar (o atrás) lo vuelve a su lugar.
     */
    private void expandEq() {
        if (!isInputEq() || eqExpanded.getVisibility() == View.VISIBLE) {
            return;
        }
        eqCurveBig.setBands(currentBands(), true, maxGain());
        eqCurveBig.setHighlightedBand(eqSelectedBand);
        refreshEditor();
        eqExpanded.setVisibility(View.VISIBLE);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, collapseEqOnBack);
    }

    private void collapseEq() {
        if (eqExpanded.getVisibility() != View.VISIBLE) {
            return;
        }
        eqExpanded.setVisibility(View.GONE);
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(collapseEqOnBack);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.show(WindowInsets.Type.systemBars());
        }
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        invalidateCurves();
    }

    private View bandRow(int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView number = text("F" + (index + 1), 12, MUTED);
        number.setTypeface(Typeface.MONOSPACE);
        row.addView(number, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView frequency = text("", 14, TEXT);
        frequency.setTypeface(Typeface.MONOSPACE);
        row.addView(frequency, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView q = text("", 13, MUTED);
        q.setTypeface(Typeface.MONOSPACE);
        if (!isInputEq()) {
            row.addView(q, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        TextView gain = text("", 14, TEXT);
        gain.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        gain.setGravity(Gravity.END);
        row.addView(gain, new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> selectBand(index));
        eqBandRows.add(new TextView[]{frequency, q, gain});
        updateBandRow(index);
        return row;
    }

    private void updateBandRow(int index) {
        Config.Band band = currentBands().get(index);
        TextView[] views = eqBandRows.get(index);
        views[0].setText(formatHz(band.frequency));
        views[1].setText(String.format(Locale.ROOT, "Q %.1f", band.q));
        views[2].setText(String.format(Locale.ROOT, "%+.1f dB", band.gainDb));
        views[2].setTextColor(gainColor(band.gainDb));
    }

    private int gainColor(float gainDb) {
        return gainDb > 0 ? ORANGE : gainDb < 0 ? CYAN : MUTED;
    }

    private void stepGain(int direction) {
        Config.Band band = currentBands().get(eqSelectedBand);
        float gain = Math.max(-maxGain(), Math.min(maxGain(), band.gainDb + direction));
        if (gain != band.gainDb) {
            band.gainDb = gain;
            onBandEdited(eqSelectedBand, true, false);
        }
    }

    /** Pasos de 1/12 de octava, redondeados como el arrastre. */
    private void stepFrequency(int direction) {
        Config.Band band = currentBands().get(eqSelectedBand);
        float frequency = EqCurveView.roundHz(band.frequency * (float) Math.pow(2, direction / 12.0));
        if (frequency == band.frequency) {
            frequency = Math.max(20, Math.min(20000, band.frequency + direction));
        }
        if (frequency != band.frequency) {
            band.frequency = frequency;
            onBandEdited(eqSelectedBand, true, true);
        }
    }

    private void stepQ(int direction) {
        Config.Band band = currentBands().get(eqSelectedBand);
        float q = Math.round(Math.max(EqCurveView.MIN_Q, Math.min(EqCurveView.MAX_Q, band.q + direction * 0.1f)) * 10) / 10f;
        if (q != band.q) {
            band.q = q;
            refreshAfterEdit(eqSelectedBand);
            markCustom();
            queueEqWrite("Q", eqSelectedBand);
        }
    }

    /** Edición a mano (arrastre o − / +). done = soltó el dedo: recién ahí sale al DSP. */
    private void onBandEdited(int band, boolean done, boolean frequencyChanged) {
        if (band != eqSelectedBand) {
            selectBand(band);
        }
        refreshAfterEdit(band);
        if (!done) {
            return;
        }
        markCustom();
        queueEqWrite("G", band);
        if (frequencyChanged) {
            queueEqWrite("F", band);
        }
    }

    private void refreshAfterEdit(int band) {
        updateBandRow(band);
        refreshEditor();
        invalidateCurves();
    }

    /** Lo editado a mano pasa a ser el Custom de este EQ. */
    private void markCustom() {
        for (int ch : eqTargets()) {
            eqActivePreset.put(ch, -1);
            eqCustom.put(ch, snapshot(currentBands()));
        }
        stylePresetChips(-1);
        if (isInputEq()) {
            EqWidget.saveCustomIn(this, currentBands());
            prefs.edit().putInt(EqWidget.PREF_WIDGET_PRESET, -1).apply();
            EqWidget.refresh(this);
        }
    }

    /** preset = -1 vuelve al Custom; si no, aplica la curva del preset a las ganancias. */
    private void applyPreset(int preset) {
        if (config == null) {
            return;
        }
        List<Config.Band> bands = currentBands();
        float[][] custom = eqCustom.get(eqSelection);
        for (int i = 0; i < bands.size(); i++) {
            Config.Band band = bands.get(i);
            float oldFrequency = band.frequency;
            float oldQ = band.q;
            if (preset < 0) {
                band.frequency = custom[i][0];
                band.q = custom[i][1];
                band.gainDb = custom[i][2];
            } else {
                band.gainDb = Math.max(-maxGain(), Math.min(maxGain(), EqPresets.gainAt(preset, band.frequency)));
            }
            queueEqWrite("G", i);
            if (!isInputEq() && band.frequency != oldFrequency) {
                queueEqWrite("F", i);
            }
            if (!isInputEq() && band.q != oldQ) {
                queueEqWrite("Q", i);
            }
            updateBandRow(i);
        }
        for (int ch : eqTargets()) {
            eqActivePreset.put(ch, preset);
        }
        stylePresetChips(preset);
        if (isInputEq()) {
            prefs.edit().putInt(EqWidget.PREF_WIDGET_PRESET, preset).apply();
            EqWidget.refresh(this);
        }
        refreshEditor();
        invalidateCurves();
        log("EQ " + (isInputEq() ? "IN" : "CH" + (eqSelection + 1)) + ": preset "
                + (preset < 0 ? "Custom" : EqPresets.NAMES[preset]));
    }

    private static float[][] snapshot(List<Config.Band> bands) {
        float[][] copy = new float[bands.size()][];
        for (int i = 0; i < bands.size(); i++) {
            Config.Band band = bands.get(i);
            copy[i] = new float[]{band.frequency, band.q, band.gainDb};
        }
        return copy;
    }

    // ---------------------------------------------------------------- Canales

    private int channelCount() {
        return Math.max(config.routing.length, Math.max(config.phase.length, config.delays.length));
    }

    private void buildChannelRows() {
        channelRows.removeAllViews();
        for (int ch = 0; ch < channelCount(); ch++) {
            LinearLayout card = card();
            card.addView(channelRow(ch));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = ch > 0 ? dp(10) : 0;
            channelRows.addView(card, params);
        }
    }

    /**
     * Una salida: entrada (routing), fase y delay. Comandos de xair:
     * [3][ch][0|1|2] routing, [6][ch][0|1] fase, [7][ch][mm] delay (canal 0-based).
     */
    private View channelRow(int channel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("CH" + (channel + 1), 15, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView delayValue = text("", 13, MUTED);
        delayValue.setTypeface(Typeface.MONOSPACE);
        top.addView(delayValue);
        row.addView(top);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlsParams.topMargin = dp(8);
        if (channel < config.routing.length) {
            RoutingView routing = new RoutingView(this);
            routing.setRoute(config.routing[channel]);
            routing.setListener(route -> {
                config.routing[channel] = route;
                queueWrite(Commands.routing(channel, route));
            });
            controls.addView(routing, new LinearLayout.LayoutParams(dp(80), dp(34)));
        }
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (channel < config.phase.length) {
            PhaseView phase = new PhaseView(this);
            phase.setInverted(config.phase[channel] == 1);
            phase.setListener(inverted -> {
                config.phase[channel] = inverted ? 1 : 0;
                queueWrite(Commands.phase(channel, inverted));
            });
            controls.addView(phase, new LinearLayout.LayoutParams(dp(80), dp(34)));
        }
        row.addView(controls, controlsParams);

        if (channel < config.delays.length) {
            LinearLayout delayRow = new LinearLayout(this);
            delayRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView minus = stepButton("−");
            delayRow.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(32)));
            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(Commands.DELAY_MAX_MM);
            seekBar.setProgress(Math.min(Commands.DELAY_MAX_MM, Math.max(0, config.delays[channel])));
            seekBar.setProgressTintList(ColorStateList.valueOf(VIOLET));
            seekBar.setThumbTintList(ColorStateList.valueOf(VIOLET));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_STROKE));
            delayRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(36), 1f));
            TextView plus = stepButton("+");
            delayRow.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(32)));
            delayValue.setText(formatDelay(seekBar.getProgress()));
            Runnable send = () -> {
                config.delays[channel] = seekBar.getProgress();
                queueWrite(Commands.delay(channel, seekBar.getProgress()));
            };
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    delayValue.setText(formatDelay(progress));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                // Como xair: el valor sale al soltar el slider.
                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    send.run();
                }
            });
            minus.setOnClickListener(v -> {
                seekBar.setProgress(Math.max(0, seekBar.getProgress() - DELAY_STEP_MM));
                send.run();
            });
            plus.setOnClickListener(v -> {
                seekBar.setProgress(Math.min(Commands.DELAY_MAX_MM, seekBar.getProgress() + DELAY_STEP_MM));
                send.run();
            });
            LinearLayout.LayoutParams delayParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            delayParams.topMargin = dp(8);
            row.addView(delayRow, delayParams);
        }
        return row;
    }

    private static String formatDelay(int mm) {
        return String.format(Locale.ROOT, "Delay %d mm · %.2f ms", mm, mm / MM_PER_MS);
    }

    // ---------------------------------------------------------------- Crossover

    private View xoSection(String name, String hint, TextView value, LinearLayout slopes, TextView slopeHint,
                           boolean hpf) {
        LinearLayout section = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(label(name));
        TextView hintView = text("  " + hint, 12, MUTED);
        titleRow.addView(hintView);
        section.addView(titleRow);
        View frequencyRow = stepperRow("Frecuencia", value, v -> stepXoFrequency(hpf, -1), v -> stepXoFrequency(hpf, 1));
        ((LinearLayout.LayoutParams) frequencyRow.getLayoutParams()).topMargin = dp(12);
        section.addView(frequencyRow);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        for (Slope slope : Slope.values()) {
            slopes.addView(chip(slope.label, slope.ordinal(), v -> setXoSlope(hpf, slope)));
        }
        scroll.addView(slopes);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(12);
        section.addView(scroll, scrollParams);
        slopeHint.setLineSpacing(dp(2), 1f);
        slopeHint.setPadding(dp(2), dp(8), 0, 0);
        section.addView(slopeHint);
        return section;
    }

    private int xoCount() {
        return Math.min(Math.min(config.hpfFreq.length, config.hpfSlope.length),
                Math.min(config.lpfFreq.length, config.lpfSlope.length));
    }

    private void buildCrossover() {
        xoChips.removeAllViews();
        xoView.setConfig(config);
        if (xoCount() == 0) {
            return;
        }
        addGroupChips(xoChips, xoCount(), this::selectXo, false);
        selectXo(Math.min(xoSelected, xoCount() - 1));
    }

    private void selectXo(int channel) {
        xoSelected = leader(channel);
        styleChips(xoChips, xoSelected);
        xoView.setSelected(xoSelected, linked(xoSelected));
        refreshXo();
    }

    private void refreshXo() {
        int ch = xoSelected;
        boolean hpfOn = !"OFF".equals(config.hpfSlope[ch]);
        boolean lpfOn = !"OFF".equals(config.lpfSlope[ch]);
        xoHpfValue.setText(hpfOn ? formatHz(config.hpfFreq[ch]) : "—");
        xoLpfValue.setText(lpfOn ? formatHz(config.lpfFreq[ch]) : "—");
        styleChips(xoHpfSlopes, slopeIndex(config.hpfSlope[ch]));
        styleChips(xoLpfSlopes, slopeIndex(config.lpfSlope[ch]));
        xoHpfHint.setText(slopeHint(Slope.fromCode(config.hpfSlope[ch]), true));
        xoLpfHint.setText(slopeHint(Slope.fromCode(config.lpfSlope[ch]), false));
        xoView.invalidate();
    }

    /** Qué hace la pendiente elegida, en criollo. */
    private static String slopeHint(Slope slope, boolean hpf) {
        if (slope == null) {
            return "";
        }
        if (slope == Slope.OFF) {
            return hpf ? "Sin filtro: pasan todos los graves." : "Sin filtro: pasan todos los agudos.";
        }
        String db = slope.code.substring(0, slope.code.indexOf("DB"));
        String steep = " · " + db + " dB por octava.";
        if (slope.code.endsWith("BTW")) {
            return "Butterworth" + steep + "\nLo más plano posible antes del corte; en el corte cae 3 dB.";
        }
        if (slope.code.endsWith("LKR")) {
            return "Linkwitz-Riley" + steep + "\nEl estándar para crossovers: en el corte cae 6 dB, así el HPF de un "
                    + "parlante y el LPF del otro suman parejo.";
        }
        return "Bessel" + steep + "\nEl corte más suave y el que mejor respeta los transitorios; menos preciso en la "
                + "frecuencia de corte.";
    }

    private static int slopeIndex(String code) {
        Slope slope = Slope.fromCode(code);
        return slope != null ? slope.ordinal() : -1;
    }

    private void setXoFrequency(boolean hpf, int hz, boolean send) {
        for (int ch : linked(xoSelected)) {
            if (ch >= xoCount()) {
                continue;
            }
            (hpf ? config.hpfFreq : config.lpfFreq)[ch] = hz;
            if (send) {
                queueWrite(hpf ? Commands.hpfFrequency(ch, hz) : Commands.lpfFrequency(ch, hz));
            }
        }
        refreshXo();
    }

    private void stepXoFrequency(boolean hpf, int direction) {
        int ch = xoSelected;
        if ("OFF".equals((hpf ? config.hpfSlope : config.lpfSlope)[ch])) {
            return;
        }
        int current = (hpf ? config.hpfFreq : config.lpfFreq)[ch];
        int hz = Math.round(EqCurveView.roundHz(current * (float) Math.pow(2, direction / 12.0)));
        if (hz == current) {
            hz = current + direction;
        }
        hz = Math.max(20, Math.min(20000, hz));
        // El HPF no puede quedar por encima del LPF.
        if (hpf && !"OFF".equals(config.lpfSlope[ch])) {
            hz = Math.min(hz, config.lpfFreq[ch]);
        } else if (!hpf && !"OFF".equals(config.hpfSlope[ch])) {
            hz = Math.max(hz, config.hpfFreq[ch]);
        }
        if (hz != current) {
            setXoFrequency(hpf, hz, true);
        }
    }

    private void setXoSlope(boolean hpf, Slope slope) {
        if (slope.code.equals((hpf ? config.hpfSlope : config.lpfSlope)[xoSelected])) {
            return;
        }
        Runnable apply = () -> {
            for (int ch : linked(xoSelected)) {
                if (ch >= xoCount()) {
                    continue;
                }
                (hpf ? config.hpfSlope : config.lpfSlope)[ch] = slope.code;
                queueWrite(hpf ? Commands.hpfSlope(ch, slope) : Commands.lpfSlope(ch, slope));
            }
            refreshXo();
        };
        if (hpf && slope == Slope.OFF) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("¿Sacar el corte de graves?")
                    .setMessage("Sin HPF, un tweeter o un medio recibe graves y se puede romper. "
                            + "Hacelo solo en canales de sub o full range.")
                    .setPositiveButton("Sacar igual", (dialog, which) -> apply.run())
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            apply.run();
        }
    }

    // ---------------------------------------------------------------- Grupos

    /** Canales que recibe una edición hecha en channel: su grupo si los grupos están prendidos. */
    private int[] linked(int channel) {
        return groupsOn ? groups.members(channel) : new int[]{channel};
    }

    private int leader(int channel) {
        return groupsOn ? groups.leader(channel) : channel;
    }

    /** Aplica un cambio de Niveles a todo el grupo; si hubo más de uno, redibuja las filas. */
    private void forGainGroup(int channel, int length, java.util.function.IntConsumer action) {
        int[] targets = linked(channel);
        for (int ch : targets) {
            if (ch < length) {
                action.accept(ch);
            }
        }
        if (targets.length > 1) {
            handler.post(this::buildGainRows);
        }
    }

    private int groupChannelCount() {
        if (config == null) {
            return 0;
        }
        return Math.max(Math.max(config.eqChannels.size(), xoCount()),
                Math.max(config.channelGains.length, config.limThreshold.length));
    }

    private static String channelNames(int[] channels) {
        StringBuilder names = new StringBuilder();
        for (int ch : channels) {
            names.append(names.length() > 0 ? " · " : "").append("CH").append(ch + 1);
        }
        return names.toString();
    }

    /** "· con CH2" al lado del nombre de un canal vinculado (vacío si va solo). */
    private TextView groupTag(int channel) {
        int[] members = linked(channel);
        StringBuilder others = new StringBuilder();
        for (int ch : members) {
            if (ch != channel) {
                others.append(others.length() > 0 ? ", " : "").append("CH").append(ch + 1);
            }
        }
        TextView tag = text(others.length() > 0 ? "  · con " + others : "", 12, CYAN);
        tag.setSingleLine(true);
        return tag;
    }

    /**
     * Una pill por canal suelto y una sola pill fusionada por grupo ("CH1 · CH2"); su
     * tag es el primer canal del grupo. stored = mostrar los grupos aunque estén apagados.
     */
    private void addGroupChips(LinearLayout chips, int count, java.util.function.IntConsumer onSelect,
                               boolean stored) {
        for (int ch = 0; ch < count; ch++) {
            int[] members = stored ? groups.members(ch) : linked(ch);
            if (members[0] != ch) {
                continue;
            }
            List<Integer> shown = new ArrayList<>();
            for (int member : members) {
                if (member < count) {
                    shown.add(member);
                }
            }
            int[] visible = new int[shown.size()];
            for (int i = 0; i < visible.length; i++) {
                visible[i] = shown.get(i);
            }
            int first = ch;
            TextView chip = chip(channelNames(visible), first, v -> onSelect.accept(first));
            makeLinkable(chip, first, stored);
            chips.addView(chip);
        }
    }

    private void buildEqChips(int selection) {
        eqChannelChips.removeAllViews();
        boolean hasInput = !config.inputEq.isEmpty();
        if (hasInput) {
            eqChannelChips.addView(chip("IN", -1, v -> selectEq(-1)));
        }
        addGroupChips(eqChannelChips, config.eqChannels.size(), this::selectEq, false);
        boolean valid = (selection == -1 && hasInput) || (selection >= 0 && selection < config.eqChannels.size());
        selectEq(valid ? selection : hasInput ? -1 : 0);
    }

    /**
     * Mantener apretada una pill la levanta y sigue al dedo: soltada sobre otra pill de la
     * misma fila, las vincula (manda la que agarraste); soltada en el lugar, abre el menú.
     * Va a mano (no con drag and drop del sistema) para que la fila deslizable no la corte.
     */
    private void makeLinkable(View chip, int channel, boolean always) {
        float[] down = new float[2];
        boolean[] dragging = {false};
        View[] hovered = {null};
        chip.setOnLongClickListener(v -> {
            if (!groupsOn && !always) {
                android.widget.Toast.makeText(this, "Prendé los grupos (la cadena de arriba) para vincular canales",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            dragging[0] = true;
            v.getParent().requestDisallowInterceptTouchEvent(true);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            v.setElevation(dp(8));
            v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
            return true;
        });
        chip.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    dragging[0] = false;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!dragging[0]) {
                        return false;
                    }
                    v.setTranslationX(event.getRawX() - down[0]);
                    v.setTranslationY(event.getRawY() - down[1]);
                    View target = chipAt((ViewGroup) v.getParent(), v, event.getRawX(), event.getRawY());
                    if (target != hovered[0]) {
                        if (hovered[0] != null) {
                            hovered[0].animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        }
                        if (target != null) {
                            target.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start();
                        }
                        hovered[0] = target;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!dragging[0]) {
                        return false;
                    }
                    dragging[0] = false;
                    if (hovered[0] != null) {
                        hovered[0].animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    }
                    View drop = hovered[0];
                    hovered[0] = null;
                    v.setElevation(0);
                    v.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f).setDuration(150).start();
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        if (drop != null) {
                            List<Integer> targets = new ArrayList<>();
                            for (int ch : groups.members((int) drop.getTag())) {
                                targets.add(ch);
                            }
                            confirmLink(channel, targets);
                        } else if (Math.hypot(event.getRawX() - down[0], event.getRawY() - down[1]) < dp(24)) {
                            showGroupMenu(channel);
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** La pill de la fila (que no sea la arrastrada ni de su grupo) bajo el dedo, o null. */
    private View chipAt(ViewGroup row, View dragged, float rawX, float rawY) {
        int source = (int) dragged.getTag();
        int[] location = new int[2];
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child == dragged || !(child.getTag() instanceof Integer) || (int) child.getTag() < 0
                    || groups.leader((int) child.getTag()) == groups.leader(source)) {
                continue;
            }
            child.getLocationOnScreen(location);
            if (rawX >= location[0] && rawX <= location[0] + child.getWidth()
                    && rawY >= location[1] && rawY <= location[1] + child.getHeight()) {
                return child;
            }
        }
        return null;
    }

    /**
     * Tocar un canal o grupo muestra directo con qué se puede vincular: una fila por
     * grupo ("CH1 + CH2") o canal suelto. Si ya está en un grupo, "Separar…" lo desarma.
     */
    private void showGroupMenu(int source) {
        int[] own = groups.members(source);
        List<int[]> options = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int ch = 0; ch < groupChannelCount(); ch++) {
            int[] theirs = groups.members(ch);
            if (theirs[0] != ch || groups.leader(ch) == groups.leader(source)) {
                continue;
            }
            StringBuilder label = new StringBuilder();
            for (int member : theirs) {
                label.append(label.length() > 0 ? " + " : "").append("CH").append(member + 1);
            }
            options.add(theirs);
            labels.add(label.toString());
        }
        boolean[] checked = new boolean[options.size()];
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Vincular " + channelNames(own) + " con…")
                .setNegativeButton("Cancelar", null);
        if (options.isEmpty()) {
            builder.setMessage("No quedan canales para vincular.");
        } else {
            builder.setMultiChoiceItems(labels.toArray(new String[0]), checked,
                            (dialog, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Vincular", (dialog, which) -> {
                        List<Integer> targets = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                for (int ch : options.get(i)) {
                                    targets.add(ch);
                                }
                            }
                        }
                        if (!targets.isEmpty()) {
                            confirmLink(source, targets);
                        }
                    });
        }
        if (own.length > 1) {
            builder.setNeutralButton("Separar…", (dialog, which) -> showSplitMenu(source));
        }
        builder.show();
    }

    /** Sacar un canal del grupo o desarmarlo entero. */
    private void showSplitMenu(int channel) {
        int[] members = groups.members(channel);
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int ch : members) {
            items.add("Sacar CH" + (ch + 1) + " del grupo");
            actions.add(() -> {
                groups.remove(ch);
                onGroupsChanged("CH" + (ch + 1) + " ya no está vinculado");
            });
        }
        items.add("Desarmar el grupo");
        actions.add(() -> {
            groups.dissolve(channel);
            onGroupsChanged("Grupo " + channelNames(members) + " desarmado");
        });
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Separar " + channelNames(members))
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Avisa que los targets copian los ajustes de source y, si se acepta, los vincula. */
    private void confirmLink(int source, List<Integer> targets) {
        int[] targetArray = new int[targets.size()];
        for (int i = 0; i < targetArray.length; i++) {
            targetArray[i] = targets.get(i);
        }
        String names = channelNames(targetArray);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("¿Vincular CH" + (source + 1) + " con " + names + "?")
                .setMessage(names + (targets.size() == 1 ? " toma" : " toman") + " los ajustes de CH" + (source + 1)
                        + " y desde ahora se editan juntos: crossover, EQ, ganancia, mute y limiter.\n\n"
                        + "La entrada, la fase y el delay siguen siendo de cada canal.")
                .setPositiveButton("Vincular", (dialog, which) -> {
                    groups.link(source, targets);
                    if (config != null) {
                        for (int target : targets) {
                            copyChannel(source, target);
                        }
                    }
                    if (!groupsOn) {
                        setGroupsOn(true);
                    }
                    onGroupsChanged("CH" + (source + 1) + " vinculado con " + names);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Copia a "to" todo lo que se edita en grupo (no la entrada, la fase ni el delay); encola solo lo distinto. */
    private void copyChannel(int from, int to) {
        if (from < xoCount() && to < xoCount()) {
            if (config.hpfFreq[to] != config.hpfFreq[from]) {
                config.hpfFreq[to] = config.hpfFreq[from];
                queueWrite(Commands.hpfFrequency(to, config.hpfFreq[to]));
            }
            if (config.lpfFreq[to] != config.lpfFreq[from]) {
                config.lpfFreq[to] = config.lpfFreq[from];
                queueWrite(Commands.lpfFrequency(to, config.lpfFreq[to]));
            }
            Slope hpf = Slope.fromCode(config.hpfSlope[from]);
            if (hpf != null && !config.hpfSlope[to].equals(config.hpfSlope[from])) {
                config.hpfSlope[to] = config.hpfSlope[from];
                queueWrite(Commands.hpfSlope(to, hpf));
            }
            Slope lpf = Slope.fromCode(config.lpfSlope[from]);
            if (lpf != null && !config.lpfSlope[to].equals(config.lpfSlope[from])) {
                config.lpfSlope[to] = config.lpfSlope[from];
                queueWrite(Commands.lpfSlope(to, lpf));
            }
        }
        if (from < config.eqChannels.size() && to < config.eqChannels.size()) {
            List<Config.Band> source = config.eqChannels.get(from);
            List<Config.Band> target = config.eqChannels.get(to);
            for (int i = 0; i < Math.min(source.size(), target.size()); i++) {
                Config.Band band = target.get(i);
                Config.Band lead = source.get(i);
                if (band.frequency != lead.frequency) {
                    band.frequency = lead.frequency;
                    queueWrite(Commands.eqChannelFrequency(to, i, band.frequency));
                }
                if (band.q != lead.q) {
                    band.q = lead.q;
                    queueWrite(Commands.eqChannelQ(to, i, band.q));
                }
                if (band.gainDb != lead.gainDb) {
                    band.gainDb = lead.gainDb;
                    queueWrite(Commands.eqChannelGain(to, i, band.gainDb));
                }
            }
            eqCustom.remove(to);
            Integer preset = eqActivePreset.get(from);
            eqActivePreset.put(to, preset != null ? preset : -1);
        }
        if (from < config.channelGains.length && to < config.channelGains.length) {
            if (config.channelGains[to] != config.channelGains[from]) {
                config.channelGains[to] = config.channelGains[from];
                queueWrite(Commands.channelGain(to, Math.round(config.channelGains[to])));
            }
            if (config.channelMutes[to] != config.channelMutes[from]) {
                config.channelMutes[to] = config.channelMutes[from];
                queueWrite(Commands.mute(to, config.channelMutes[to]));
            }
        }
        if (from < config.limThreshold.length && to < config.limThreshold.length
                && config.limThreshold[to] != config.limThreshold[from]) {
            config.limThreshold[to] = config.limThreshold[from];
            queueWrite(Commands.limiterThreshold(to, config.limThreshold[to], limiterAuto));
        }
        if (from < config.limAttack.length && to < config.limAttack.length
                && config.limAttack[to] != config.limAttack[from]) {
            config.limAttack[to] = config.limAttack[from];
            queueWrite(Commands.limiterAttack(to, config.limAttack[to]));
        }
        if (from < config.limDecay.length && to < config.limDecay.length
                && config.limDecay[to] != config.limDecay[from]) {
            config.limDecay[to] = config.limDecay[from];
            queueWrite(Commands.limiterDecay(to, config.limDecay[to]));
        }
    }

    private void onGroupsChanged(String message) {
        if (groupsKey != null) {
            prefs.edit().putString(PREF_GROUPS_PREFIX + groupsKey, groups.encode()).apply();
        }
        log("Grupos: " + message);
        refreshAfterGroups();
    }

    /** Redibuja todo lo que muestra los grupos (pills, filas y el editor). */
    private void refreshAfterGroups() {
        if (config != null) {
            buildGainRows();
            buildCrossover();
            if (!config.inputEq.isEmpty() || !config.eqChannels.isEmpty()) {
                buildEqChips(eqSelection);
            }
        }
        refreshGroupsEditor();
    }

    private void setGroupsOn(boolean on) {
        groupsOn = on;
        prefs.edit().putBoolean(PREF_GROUPS_ON, on).apply();
        for (ChainView button : groupButtons) {
            button.setOn(on);
        }
        log("Grupos " + (on ? "prendidos" : "apagados"));
    }

    /** Tocar la cadena prende o apaga los grupos (quedan guardados igual). */
    private void toggleGroups() {
        setGroupsOn(!groupsOn);
        refreshAfterGroups();
        if (groupsOn && !prefs.getBoolean(PREF_GROUPS_HELP_HIDDEN, false)) {
            showGroupsHelp();
        } else {
            // Recordatorio de que mantenerla apretada abre la edición de grupos.
            android.widget.Toast.makeText(this, (groupsOn ? "Grupos prendidos" : "Grupos apagados")
                    + " · mantené apretada la cadena para editarlos", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showGroupsHelp() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), 0);
        TextView message = text("Los canales vinculados se editan juntos: crossover, EQ, ganancia, mute y limiter.\n\n"
                + "Arrastrá una pill sobre otra para vincularlas. La que agarrás manda.\n\n"
                + "Tocá la cadena para prender o apagar los grupos; mantenela apretada para editarlos.", 14, TEXT);
        message.setLineSpacing(dp(2), 1f);
        body.addView(message);
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        demoParams.topMargin = dp(8);
        body.addView(new GroupsDemoView(this), demoParams);
        android.widget.CheckBox hide = new android.widget.CheckBox(this);
        hide.setText("No volver a mostrar");
        hide.setTextColor(MUTED);
        hide.setButtonTintList(ColorStateList.valueOf(CYAN));
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hideParams.topMargin = dp(12);
        body.addView(hide, hideParams);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Grupos de canales")
                .setView(body)
                .setPositiveButton("OK", (dialog, which) -> {
                    if (hide.isChecked()) {
                        prefs.edit().putBoolean(PREF_GROUPS_HELP_HIDDEN, true).apply();
                    }
                })
                .show();
    }

    /** Pantalla de grupos: prender/apagar, las pills para vincular y la lista para separar. */
    private void openGroupsEditor() {
        groupsEditorBody = new LinearLayout(this);
        groupsEditorBody.setOrientation(LinearLayout.VERTICAL);
        groupsEditorBody.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(groupsEditorBody);
        groupsEditor = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Grupos de canales")
                .setView(scroll)
                .setPositiveButton("Listo", null)
                .setOnDismissListener(dialog -> {
                    groupsEditor = null;
                    groupsEditorBody = null;
                })
                .show();
        refreshGroupsEditor();
    }

    private void refreshGroupsEditor() {
        if (groupsEditorBody == null) {
            return;
        }
        LinearLayout body = groupsEditorBody;
        body.removeAllViews();

        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView state = text(groupsOn ? "Grupos prendidos" : "Grupos apagados: cada canal va por su lado", 14,
                groupsOn ? TEXT : MUTED);
        toggleRow.addView(state, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ChainView toggle = new ChainView(this);
        toggle.setOn(groupsOn);
        toggle.setOnClickListener(v -> {
            setGroupsOn(!groupsOn);
            refreshAfterGroups();
        });
        toggleRow.addView(toggle, new LinearLayout.LayoutParams(dp(44), dp(34)));
        body.addView(toggleRow);

        int count = groupChannelCount();
        if (count == 0) {
            TextView empty = text("Conectate al DSP para armar los grupos.", 13, MUTED);
            empty.setPadding(0, dp(12), 0, 0);
            body.addView(empty);
            return;
        }
        TextView hint = text("Mantené apretado un canal y arrastralo sobre otro, o tocalo para elegir con cuál "
                + "vincularlo. El que agarrás manda.", 13, MUTED);
        hint.setPadding(0, dp(14), 0, 0);
        body.addView(hint);
        body.addView(new GroupsDemoView(this));
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        addGroupChips(chips, count, this::showGroupMenu, true);
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean grouped = groups.members((int) chip.getTag()).length > 1;
            chip.setTextColor(grouped ? CYAN : TEXT);
            chip.setBackground(rounded(grouped ? 0x2200E5FF : 0x00000000, grouped ? CYAN : CARD_STROKE, 17));
        }
        chipsScroll.addView(chips);
        body.addView(chipsScroll);

        TextView listLabel = label("GRUPOS");
        listLabel.setPadding(0, dp(18), 0, dp(4));
        body.addView(listLabel);
        boolean any = false;
        for (int ch = 0; ch < count; ch++) {
            int[] members = groups.members(ch);
            if (members.length < 2 || members[0] != ch) {
                continue;
            }
            any = true;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView name = text(channelNames(members), 15, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView split = stepButton("Separar");
            split.setTextSize(13);
            split.setPadding(dp(14), 0, dp(14), 0);
            int first = ch;
            split.setOnClickListener(v -> {
                groups.dissolve(first);
                onGroupsChanged("Grupo " + channelNames(members) + " desarmado");
            });
            row.addView(split, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));
            body.addView(row);
        }
        if (!any) {
            body.addView(text("Todavía no hay canales vinculados.", 13, MUTED));
        }
    }

    // ---------------------------------------------------------------- Descartar

    /** Tira lo que no se grabó y vuelve a leer el DSP, que tiene lo último grabado. */
    private void confirmDiscard() {
        if (session == null) {
            return;
        }
        int count = session.pendingCount() + session.heldCount();
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("¿Descartar " + count + (count == 1 ? " cambio?" : " cambios?"))
                .setMessage("Lo que no grabaste se pierde y se vuelve a leer la configuración del DSP.")
                .setPositiveButton("Descartar", (dialog, which) -> {
                    if (session == null) {
                        return;
                    }
                    session.discard();
                    log("Cambios descartados: releyendo el DSP");
                    config = null;
                    renderState();
                    setSync("", MUTED);
                    session.readConfig();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- Ganancias

    private void buildGainRows() {
        gainRows.removeAllViews();
        if (!Float.isNaN(config.mainGain)) {
            LinearLayout master = card();
            master.addView(label("MASTER"));
            master.addView(gainRow("Master", -1, config.mainGain, Commands.MAIN_GAIN_MIN, Commands.MAIN_GAIN_MAX));
            gainRows.addView(master);
            LinearLayout channelsHeader = new LinearLayout(this);
            channelsHeader.setGravity(Gravity.CENTER_VERTICAL);
            channelsHeader.setPadding(dp(4), dp(20), 0, dp(10));
            channelsHeader.addView(label("CANALES"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (config.limThreshold.length > 0) {
                TextView auto = chip(limiterAuto ? "Limiter auto" : "Limiter manual", 0, v -> {
                    limiterAuto = !limiterAuto;
                    prefs.edit().putBoolean(PREF_LIMITER_AUTO, limiterAuto).apply();
                    buildGainRows();
                });
                auto.setTextColor(limiterAuto ? BG : MUTED);
                auto.setBackground(limiterAuto ? rounded(PINK, PINK, 17) : rounded(0x00000000, CARD_STROKE, 17));
                LinearLayout.LayoutParams autoParams = (LinearLayout.LayoutParams) auto.getLayoutParams();
                autoParams.rightMargin = 0;
                channelsHeader.addView(auto, autoParams);
            }
            gainRows.addView(channelsHeader);
        }
        for (int ch = 0; ch < config.channelGains.length; ch++) {
            LinearLayout card = card();
            card.addView(gainRow("CH" + (ch + 1), ch, config.channelGains[ch], Commands.CHANNEL_GAIN_MIN, Commands.CHANNEL_GAIN_MAX));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = ch > 0 ? dp(10) : 0;
            gainRows.addView(card, params);
        }
    }

    /** channel = -1 para el master. */
    private View gainRow(String name, int channel, float gain, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, channel < 0 ? dp(6) : 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(name, 15, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        top.addView(channel >= 0 ? groupTag(channel) : new View(this),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", 15, TEXT);
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        TextView masterMute = null;
        if (channel < 0) {
            masterMute = chip("Mute", 0, null);
            LinearLayout.LayoutParams muteParams = (LinearLayout.LayoutParams) masterMute.getLayoutParams();
            muteParams.rightMargin = dp(12);
            top.addView(masterMute, muteParams);
        } else {
            TextView mute = chip("Mute", 0, null);
            mute.setOnClickListener(v -> {
                boolean muted = !config.channelMutes[channel];
                forGainGroup(channel, config.channelMutes.length, ch -> {
                    config.channelMutes[ch] = muted;
                    queueWrite(Commands.mute(ch, muted));
                });
                styleMute(mute, value, channel);
            });
            LinearLayout.LayoutParams muteParams = (LinearLayout.LayoutParams) mute.getLayoutParams();
            muteParams.rightMargin = dp(12);
            top.addView(mute, muteParams);
            styleMute(mute, value, channel);
        }
        top.addView(value);
        row.addView(top);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMin(min);
        seekBar.setMax(max);
        seekBar.setProgress(Math.round(Math.max(min, Math.min(max, gain))));
        seekBar.setProgressTintList(ColorStateList.valueOf(channel < 0 ? ORANGE : CYAN));
        seekBar.setThumbTintList(ColorStateList.valueOf(channel < 0 ? ORANGE : CYAN));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_STROKE));
        value.setText(String.format(Locale.ROOT, "%+d dB", seekBar.getProgress()));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(String.format(Locale.ROOT, "%+d dB", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            // Como xair: el valor sale al soltar el slider.
            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                sendGain(channel, bar.getProgress());
            }
        });
        // − / + de a 1 dB a los costados del slider, como el delay en Canales.
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = stepButton("−");
        minus.setOnClickListener(v -> {
            seekBar.setProgress(Math.max(min, seekBar.getProgress() - 1));
            sendGain(channel, seekBar.getProgress());
        });
        sliderRow.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(32)));
        sliderRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(36), 1f));
        TextView plus = stepButton("+");
        plus.setOnClickListener(v -> {
            seekBar.setProgress(Math.min(max, seekBar.getProgress() + 1));
            sendGain(channel, seekBar.getProgress());
        });
        sliderRow.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(32)));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        seekParams.topMargin = dp(6);
        row.addView(sliderRow, seekParams);
        if (masterMute != null) {
            TextView muteChip = masterMute;
            View[] controls = {seekBar, minus, plus};
            // El slider queda visible (atenuado) pero no se mueve mientras está en mute.
            seekBar.setOnTouchListener((v, event) -> masterMuted);
            Runnable style = () -> {
                muteChip.setTextColor(masterMuted ? BG : MUTED);
                muteChip.setBackground(masterMuted ? rounded(RED, RED, 17) : rounded(0x00000000, CARD_STROKE, 17));
                value.setAlpha(masterMuted ? 0.4f : 1f);
                for (View control : controls) {
                    if (control != seekBar) {
                        control.setEnabled(!masterMuted);
                    }
                    control.setAlpha(masterMuted ? 0.4f : 1f);
                }
            };
            muteChip.setOnClickListener(v -> toggleMasterMute(seekBar, style));
            style.run();
        }
        if (channel >= 0) {
            addLimiterRows(row, channel);
        }
        return row;
    }

    /**
     * Mute del master como un fade a mano: baja de a 1 dB hasta el mínimo y, al
     * sacarlo, sube igual hasta donde estaba. Sale ya, aunque Live esté apagado.
     */
    private void toggleMasterMute(SeekBar seekBar, Runnable style) {
        if (masterFade != null) {
            masterFade.cancel();
        }
        int from = seekBar.getProgress();
        int to;
        if (masterMuted) {
            to = masterBeforeMute;
        } else {
            masterBeforeMute = from;
            to = Commands.MAIN_GAIN_MIN;
        }
        masterMuted = !masterMuted;
        style.run();
        log("Master: " + (masterMuted ? "mute (fade a " + to + " dB)" : "vuelve a " + to + " dB"));
        masterFade = android.animation.ValueAnimator.ofInt(from, to);
        masterFade.setDuration(Math.max(200, Math.abs(to - from) * 25L));
        masterFade.setInterpolator(new android.view.animation.LinearInterpolator());
        masterFade.addUpdateListener(animation -> {
            int db = (int) animation.getAnimatedValue();
            if (db != Math.round(config.mainGain)) {
                seekBar.setProgress(db);
                config.mainGain = db;
                if (session != null) {
                    session.sendNow(Commands.masterGain(db));
                }
            }
        });
        masterFade.start();
    }

    /** channel = -1 para el master; un canal arrastra a su grupo. */
    private void sendGain(int channel, int db) {
        if (channel < 0) {
            config.mainGain = db;
            queueWrite(Commands.masterGain(db));
        } else {
            forGainGroup(channel, config.channelGains.length, ch -> {
                config.channelGains[ch] = db;
                queueWrite(Commands.channelGain(ch, db));
            });
        }
    }

    /** Limiter del canal: umbral, y attack y decay solo en manual (en auto los calcula el DSP). */
    private void addLimiterRows(LinearLayout row, int channel) {
        if (channel < config.limThreshold.length) {
            row.addView(miniSlider("Limiter", Commands.LIMIT_THRESHOLD_MIN, Commands.LIMIT_THRESHOLD_MAX,
                    config.limThreshold[channel], "%+d dB", PINK, value ->
                            forGainGroup(channel, config.limThreshold.length, ch -> {
                                config.limThreshold[ch] = value;
                                queueWrite(Commands.limiterThreshold(ch, value, limiterAuto));
                            })));
        }
        if (limiterAuto) {
            return;
        }
        if (channel < config.limAttack.length) {
            row.addView(miniSlider("Attack", Commands.LIMIT_ATTACK_MIN, Commands.LIMIT_ATTACK_MAX,
                    config.limAttack[channel], "%d ms", VIOLET, value ->
                            forGainGroup(channel, config.limAttack.length, ch -> {
                                config.limAttack[ch] = value;
                                queueWrite(Commands.limiterAttack(ch, value));
                            })));
        }
        if (channel < config.limDecay.length) {
            row.addView(miniSlider("Decay", Commands.LIMIT_DECAY_MIN, Commands.LIMIT_DECAY_MAX,
                    config.limDecay[channel], "%d ms", VIOLET, value ->
                            forGainGroup(channel, config.limDecay.length, ch -> {
                                config.limDecay[ch] = value;
                                queueWrite(Commands.limiterDecay(ch, value));
                            })));
        }
    }

    /** Fila compacta: nombre + valor a la izquierda, slider a la derecha; sale al soltar. */
    private View miniSlider(String name, int min, int max, int initial, String format, int color,
                            java.util.function.IntConsumer onSet) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameView = text(name, 12, MUTED);
        line.addView(nameView, new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView value = text("", 12, TEXT);
        value.setTypeface(Typeface.MONOSPACE);
        line.addView(value, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMin(min);
        seekBar.setMax(max);
        seekBar.setProgress(Math.max(min, Math.min(max, initial)));
        seekBar.setProgressTintList(ColorStateList.valueOf(color));
        seekBar.setThumbTintList(ColorStateList.valueOf(color));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_STROKE));
        value.setText(String.format(Locale.ROOT, format, seekBar.getProgress()));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(String.format(Locale.ROOT, format, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                onSet.accept(bar.getProgress());
            }
        });
        line.addView(seekBar, new LinearLayout.LayoutParams(0, dp(32), 1f));
        return line;
    }

    private void styleMute(TextView mute, TextView value, int channel) {
        boolean muted = config.channelMutes[channel];
        mute.setTextColor(muted ? BG : MUTED);
        mute.setBackground(muted ? rounded(RED, RED, 17) : rounded(0x00000000, CARD_STROKE, 17));
        value.setAlpha(muted ? 0.4f : 1f);
    }

    // ---------------------------------------------------------------- Escritura del EQ

    /** type: "G" ganancia, "F" frecuencia, "Q" factor Q (el EQ de entrada solo tiene ganancia). */
    private void queueEqWrite(String type, int bandIndex) {
        Config.Band band = currentBands().get(bandIndex);
        if (isInputEq()) {
            queueWrite(Commands.inputEqGain(bandIndex, band.gainDb));
            return;
        }
        // La banda editada se copia igual a los demás canales del grupo.
        for (int ch : eqTargets()) {
            if (bandIndex >= config.eqChannels.get(ch).size()) {
                continue;
            }
            Config.Band target = config.eqChannels.get(ch).get(bandIndex);
            target.frequency = band.frequency;
            target.q = band.q;
            target.gainDb = band.gainDb;
            if (type.equals("F")) {
                queueWrite(Commands.eqChannelFrequency(ch, bandIndex, band.frequency));
            } else if (type.equals("Q")) {
                queueWrite(Commands.eqChannelQ(ch, bandIndex, band.q));
            } else {
                queueWrite(Commands.eqChannelGain(ch, bandIndex, band.gainDb));
            }
        }
    }

    /** EQ que recibe la edición: el elegido y su grupo (el de entrada va solo). */
    private int[] eqTargets() {
        if (isInputEq()) {
            return new int[]{eqSelection};
        }
        List<Integer> targets = new ArrayList<>();
        for (int ch : linked(eqSelection)) {
            if (ch < config.eqChannels.size()) {
                targets.add(ch);
            }
        }
        int[] result = new int[targets.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = targets.get(i);
        }
        return result;
    }

    /** Encola un comando (la clave pisa uno pendiente igual); con Live sale ya. */
    private void queueWrite(Command command) {
        if (session == null) {
            return;
        }
        session.queue(command);
        if (eqLive) {
            flushEq();
        } else {
            updateEqHeader();
            setSync("Cambios sin grabar", ORANGE);
        }
    }

    /** Botón Grabar: manda lo pendiente (y lo que haya quedado retenido). */
    private void saveAll() {
        if (session != null) {
            session.releaseHeld();
        }
        flushEq();
    }

    private void setSync(String text, int color) {
        for (TextView sync : syncTexts) {
            sync.setText(text);
            sync.setTextColor(color);
        }
    }

    /**
     * Título de página + Grupos + Live + Grabar, y abajo el estado del envío con
     * Descartar (todo compartido entre las pestañas).
     */
    private View editableHeader(String title) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        block.addView(header);
        TextView titleView = pageTitle(title);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ChainView groupsButton = new ChainView(this);
        groupsButton.setOn(groupsOn);
        groupsButton.setOnClickListener(v -> toggleGroups());
        groupsButton.setOnLongClickListener(v -> {
            openGroupsEditor();
            return true;
        });
        LinearLayout.LayoutParams groupsParams = new LinearLayout.LayoutParams(dp(44), dp(34));
        groupsParams.rightMargin = dp(8);
        header.addView(groupsButton, groupsParams);
        groupButtons.add(groupsButton);
        TextView live = text("", 13, TEXT);
        live.setTypeface(Typeface.DEFAULT_BOLD);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(12), 0, dp(12), 0);
        live.setOnClickListener(v -> toggleLive());
        header.addView(live, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
        liveButtons.add(live);
        TextView save = button("Grabar", ORANGE, true);
        save.setTextSize(13);
        save.setPadding(dp(14), 0, dp(14), 0);
        save.setOnClickListener(v -> saveAll());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        saveParams.leftMargin = dp(8);
        header.addView(save, saveParams);
        saveButtons.add(save);

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, dp(4), 0, 0);
        TextView sync = text("", 12, MUTED);
        syncTexts.add(sync);
        status.addView(sync, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView discard = text("Descartar", 12, TEXT);
        discard.setTypeface(Typeface.DEFAULT_BOLD);
        discard.setGravity(Gravity.CENTER);
        discard.setPadding(dp(12), 0, dp(12), 0);
        discard.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(0xFF1E2330, 0xFF323949, 14), null));
        discard.setOnClickListener(v -> confirmDiscard());
        status.addView(discard, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)));
        discardButtons.add(discard);
        block.addView(status);
        return block;
    }

    private void toggleLive() {
        eqLive = !eqLive;
        prefs.edit().putBoolean(PREF_EQ_LIVE, eqLive).apply();
        log("EQ: Live " + (eqLive ? "activado" : "desactivado"));
        updateEqHeader();
        // Al prender Live, lo pendiente sale de una.
        if (eqLive) {
            flushEq();
        }
    }

    private void updateEqHeader() {
        int held = session != null ? session.heldCount() : 0;
        int count = session != null ? session.pendingCount() + held : 0;
        boolean saving = session != null && session.isSending();
        boolean show = config != null && state == State.CONNECTED;
        for (TextView live : liveButtons) {
            live.setVisibility(show ? View.VISIBLE : View.GONE);
            live.setText(eqLive ? "● Live" : "○ Live");
            live.setTextColor(eqLive ? BG : MUTED);
            live.setBackground(eqLive ? rounded(GREEN, GREEN, 17) : rounded(0x00000000, CARD_STROKE, 17));
        }
        // Con Live el botón Grabar sobra: todo sale al instante.
        for (TextView save : saveButtons) {
            save.setVisibility(show && (!eqLive || held > 0) ? View.VISIBLE : View.GONE);
            save.setText(saving ? "Grabando…" : count > 0 ? "Grabar (" + count + ")" : "Grabar");
            save.setEnabled(count > 0 && !saving);
            save.setAlpha(count > 0 || saving ? 1f : 0.4f);
        }
        for (TextView discard : discardButtons) {
            discard.setVisibility(show && count > 0 && !saving ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Manda al DSP todos los cambios pendientes (Grabar, o cada cambio con Live). */
    private void flushEq() {
        if (session == null || session.pendingCount() == 0) {
            return;
        }
        session.flush();
        updateEqHeader();
    }

    private static String formatHz(float hz) {
        if (hz >= 1000) {
            float khz = hz / 1000f;
            return (khz == Math.round(khz) ? String.valueOf(Math.round(khz)) : String.format(Locale.ROOT, "%.2f", khz)
                    .replaceAll("0+$", "")) + " kHz";
        }
        return Math.round(hz) + " Hz";
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /** El log técnico vive debajo de Conectar o, con un equipo conectado, en el panel lateral. */
    private void placeLog(boolean connected) {
        ViewGroup target = connected ? drawerLogHolder : (ViewGroup) connectScreen;
        if (logBlock.getParent() == target) {
            return;
        }
        if (logBlock.getParent() != null) {
            ((ViewGroup) logBlock.getParent()).removeView(logBlock);
        }
        if (connected) {
            target.addView(logBlock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            logScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            target.addView(logBlock);
            logScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)));
        }
    }

    private void setConnectedInfo(String name, String subtitle, String detail, int color) {
        runOnUiThread(() -> {
            topTitle.setText(name);
            topSubtitle.setText(subtitle + (demoMode ? " · demo" : ""));
            drawerName.setText(name);
            drawerDetail.setText(detail);
            for (View dot : new View[]{topDot, drawerDot}) {
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(color);
                dot.setBackground(shape);
            }
        });
    }

    private void openDrawer() {
        if (drawerOpen) {
            return;
        }
        drawerOpen = true;
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(1f).setDuration(200).start();
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(-dp(300));
        drawerPanel.animate().translationX(0).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeDrawerOnBack);
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        drawerOpen = false;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeDrawerOnBack);
        drawerScrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> drawerScrim.setVisibility(View.GONE)).start();
        drawerPanel.animate().translationX(-dp(300)).setDuration(180)
                .withEndAction(() -> drawerPanel.setVisibility(View.GONE)).start();
    }

    /** Corta la conexión (real o demo) y vuelve a la pantalla de conexión, que busca sola. */
    private void disconnect(String reason) {
        log(reason);
        demoMode = false;
        closeSession();
        config = null;
        setStatus(MUTED, "Desconectado", "Elegí tu DSP para conectar");
        setState(State.IDLE);
        handler.post(this::autoSearch);
    }

    private void setLogVisible(boolean visible) {
        logScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        logToggle.setText(visible ? "▾  Ocultar log técnico" : "▸  Ver log técnico");
    }

    private void setStatus(int color, String title, String detail) {
        runOnUiThread(() -> {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            statusDot.setBackground(dot);
            statusText.setText(title);
            statusDetail.setText(detail);
        });
    }

    private void setState(State newState) {
        state = newState;
        connectedActivity = new WeakReference<>(newState == State.CONNECTED ? this : null);
        renderState();
    }

    /**
     * Widget: aplica un preset al EQ de entrada por la conexión abierta y lo graba
     * (sin importar Live). false si no hay conexión con la config cargada.
     */
    static boolean applyInputPresetFromWidget(int preset) {
        XLinkActivity activity = connectedActivity.get();
        if (activity == null || activity.config == null || activity.config.inputEq.isEmpty()) {
            return false;
        }
        activity.runOnUiThread(() -> {
            if (activity.eqSelection != -1) {
                activity.selectEq(-1);
            }
            activity.applyPreset(preset);
            activity.flushEq();
        });
        return true;
    }

    private void renderState() {
        runOnUiThread(() -> {
            boolean busy = state == State.SCANNING || state == State.CONNECTING;
            scanButton.setEnabled(!busy);
            scanButton.setAlpha(busy ? 0.4f : 1f);
            scanButton.setText(state == State.SCANNING ? "Buscando…" : "Buscar");
            boolean canDemo = state == State.IDLE || state == State.SCANNING;
            demoButton.setEnabled(canDemo);
            demoButton.setAlpha(canDemo ? 1f : 0.4f);

            boolean canConnect = state == State.CONNECTED
                    || ((state == State.IDLE || state == State.SCANNING) && selectedDevice != null);
            connectButton.setEnabled(canConnect);
            connectButton.setAlpha(canConnect ? 1f : 0.4f);
            connectButton.setText(state == State.CONNECTED ? "Desconectar"
                    : state == State.CONNECTING ? "Conectando…" : "Conectar");
            passwordField.setEnabled(state == State.IDLE);

            boolean connected = state == State.CONNECTED;
            connectScreen.setVisibility(connected ? View.GONE : View.VISIBLE);
            mainScreen.setVisibility(connected ? View.VISIBLE : View.GONE);
            if (!connected && drawerOpen) {
                closeDrawer();
            }
            placeLog(connected);
            boolean hasXo = config != null && config.hpfFreq.length > 0 && config.lpfFreq.length > 0;
            xoCard.setVisibility(hasXo ? View.VISIBLE : View.GONE);
            xoEmpty.setVisibility(config != null && !hasXo ? View.VISIBLE : View.GONE);
            boolean hasChannels = config != null && channelCount() > 0;
            channelsCard.setVisibility(hasChannels ? View.VISIBLE : View.GONE);
            channelsEmpty.setVisibility(hasChannels ? View.GONE : View.VISIBLE);
            channelsEmpty.setText(config != null ? "El DSP no mandó routing / fase / delay"
                    : connected ? "Cargando la configuración del DSP…"
                    : "Conectate al DSP: los canales se cargan solos al entrar");
            eqCard.setVisibility(config != null ? View.VISIBLE : View.GONE);
            boolean hasGains = config != null && (config.channelGains.length > 0 || !Float.isNaN(config.mainGain));
            gainCard.setVisibility(hasGains ? View.VISIBLE : View.GONE);
            gainEmpty.setVisibility(hasGains ? View.GONE : View.VISIBLE);
            gainEmpty.setText(config != null ? "El DSP no mandó ganancias" : "Cargando la configuración del DSP…");
            updateEqHeader();
            eqEmpty.setVisibility(config != null ? View.GONE : View.VISIBLE);
            eqEmpty.setText(connected ? "Cargando la configuración del DSP…"
                    : "Conectate al DSP: el ecualizador se carga solo al entrar");

            for (int i = 0; i < pages.length; i++) {
                boolean selected = i == currentPage;
                pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
                navItems[i].setTextColor(selected ? ORANGE : MUTED);
            }
        });
    }

    private void addCandidateRow(BluetoothDevice device, String name, int rssi) {
        runOnUiThread(() -> {
            deviceList.removeView(devicesEmpty);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = text(name, 16, TEXT);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(nameView);
            texts.addView(text(device.getAddress(), 12, MUTED));
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(signalBars(rssi) + "  " + rssi + " dBm", 12, MUTED));
            row.setOnClickListener(v -> {
                if (state == State.IDLE || state == State.SCANNING) {
                    selectDevice(device);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(6);
            deviceList.addView(row, params);
            candidateRows.put(device.getAddress(), row);
            highlightSelection();
        });
    }

    private static String signalBars(int rssi) {
        if (rssi >= -60) return "▂▄▆█";
        if (rssi >= -70) return "▂▄▆";
        if (rssi >= -80) return "▂▄";
        return "▂";
    }

    private void selectDevice(BluetoothDevice device) {
        selectedDevice = device;
        highlightSelection();
        renderState();
    }

    private void highlightSelection() {
        for (Map.Entry<String, View> entry : candidateRows.entrySet()) {
            boolean selected = selectedDevice != null && entry.getKey().equals(selectedDevice.getAddress());
            entry.getValue().setBackground(selected
                    ? rounded(0x1A00E5FF, CYAN, 12)
                    : rounded(0x00000000, CARD_STROKE, 12));
        }
    }

    private void clearCandidates() {
        seenDevices.clear();
        candidates.clear();
        candidateRows.clear();
        selectedDevice = null;
        runOnUiThread(() -> {
            deviceList.removeAllViews();
            devicesEmpty.setText("Buscando equipos…");
            deviceList.addView(devicesEmpty);
        });
    }

    // ---------------------------------------------------------------- Acciones

    @Override
    protected void onResume() {
        super.onResume();
        autoSearch();
    }

    /** Busca sola cuando está la pantalla de conexión (sin equipo) y no hay nada en curso. */
    private void autoSearch() {
        if (state != State.IDLE || bluetoothAdapter == null) {
            return;
        }
        if (!bluetoothAdapter.isEnabled() || !isLocationEnabled()) {
            setStatus(ORANGE, "Desconectado", "Activá Bluetooth y Ubicación para buscar tu DSP");
            return;
        }
        if (!hasRequiredPermissions() && permissionsAsked) {
            return;
        }
        log("Búsqueda automática");
        search();
    }

    private void onSearchClicked() {
        log("Tocaste Buscar");
        search();
    }

    private void search() {
        closeSession();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            fail("Activá el Bluetooth del celular");
            return;
        }
        if (!isLocationEnabled()) {
            fail("Activá la Ubicación del celular: Android la pide para escanear BLE");
            return;
        }
        if (hasRequiredPermissions()) {
            startScan();
        } else {
            log("Pidiendo permisos de Bluetooth…");
            permissionsAsked = true;
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    private void onConnectClicked() {
        if (state == State.CONNECTED) {
            disconnect("Tocaste Desconectar");
            return;
        }
        String password = passwordField.getText().toString().trim();
        if (password.isEmpty()) {
            setStatus(ORANGE, "Falta la password", "Ingresá la password del equipo");
            return;
        }
        if (selectedDevice == null) {
            setStatus(ORANGE, "Elegí un equipo", "Tocá Buscar y seleccioná tu DSP");
            return;
        }
        log("Tocaste Conectar");
        prefs.edit().putString(PREF_PASSWORD, password).apply();
        stopScan();
        demoMode = false;
        connectToDevice(selectedDevice, password);
    }

    private void startDemo() {
        log("Modo demo: DSP simulado con datos capturados de un PX 201");
        stopScan();
        demoMode = true;
        connectedDevice = null;
        setStatus(VIOLET, "Conectando…", MockTransport.DEVICE_NAME);
        setState(State.CONNECTING);
        startSession(new MockTransport(scheduler), "demo");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                log("Permisos concedidos");
                startScan();
            } else {
                fail("Sin permisos de Bluetooth/Ubicación no se puede buscar");
            }
        }
    }

    private String[] requiredPermissions() {
        return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        return locationManager != null && locationManager.isLocationEnabled();
    }

    // ---------------------------------------------------------------- Escaneo

    private void startScan() {
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            fail("Scanner BLE no disponible");
            return;
        }
        clearCandidates();
        log("Buscando dispositivo…");
        setStatus(CYAN, "Buscando…", "Escaneando equipos cercanos");
        setState(State.SCANNING);
        bluetoothLeScanner.startScan(scanCallback);
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable);
        if (bluetoothLeScanner != null && state == State.SCANNING) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            String name = device.getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null || !seenDevices.add(address)) {
                return;
            }
            if (isCandidate(name)) {
                log("Posible DSP: " + name + " [" + address + "] rssi=" + result.getRssi());
                candidates.put(address, device);
                boolean wasLastUsed = address.equals(prefs.getString(PREF_LAST_ADDRESS, null));
                if (selectedDevice == null || wasLastUsed) {
                    selectedDevice = device;
                }
                addCandidateRow(device, name, result.getRssi());
                renderState();
            } else {
                log("  (otro) " + name + " [" + address + "] rssi=" + result.getRssi());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            fail("Falla en el escaneo BLE (código " + errorCode + ")");
        }
    };

    private static boolean isCandidate(String name) {
        if (name.equals("P8")) {
            return true;
        }
        for (String part : DEVICE_NAME_CONTAINS) {
            if (name.contains(part)) {
                return true;
            }
        }
        for (String prefix : DEVICE_NAME_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private final Runnable scanTimeoutRunnable = () -> {
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        setState(State.IDLE);
        if (candidates.isEmpty()) {
            runOnUiThread(() -> devicesEmpty.setText("Ningún DSP a la vista"));
            setStatus(ORANGE, "No se encontró ningún DSP",
                    "Prendé el equipo y cerrá la app Expert si está abierta (el DSP no se anuncia si ya está conectado)");
        } else {
            log("Escaneo terminado: " + candidates.size() + " DSP");
            setStatus(MUTED, candidates.size() + (candidates.size() == 1 ? " DSP encontrado" : " DSP encontrados"),
                    "Ingresá la password y tocá Conectar");
        }
    };

    // ---------------------------------------------------------------- Sesión con el DSP

    private void connectToDevice(BluetoothDevice device, String password) {
        connectedDevice = device;
        setStatus(CYAN, "Conectando…", device.getAddress());
        setState(State.CONNECTING);
        startSession(new BleTransport(this, device.getAddress()), password);
    }

    private void startSession(Transport transport, String password) {
        closeSession();
        session = new Session(transport, password, scheduler, sessionListener);
        session.connect();
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // Todos los callbacks llegan en el hilo principal.
    private final Session.Listener sessionListener = new Session.Listener() {
        @Override
        public void onReady(String model, String preset) {
            onAuthenticated(model, preset);
        }

        @Override
        public void onConfig(Config loaded) {
            onConfigLoaded(loaded);
        }

        @Override
        public void onClosed(String reason, boolean wasReady) {
            session = null;
            if (wasReady) {
                log("Desconectado: " + reason);
                setStatus(ORANGE, "Se perdió la conexión", "Buscando de nuevo…");
                setState(State.IDLE);
                handler.post(XLinkActivity.this::autoSearch);
            } else {
                fail(reason);
            }
        }

        @Override
        public void onSending(int left) {
            updateEqHeader();
            setSync("Enviando…" + (left > 0 ? " (" + left + ")" : ""), MUTED);
        }

        @Override
        public void onFlushed(int missedAcks) {
            updateEqHeader();
            if (missedAcks > 0) {
                setSync("⚠ " + missedAcks + " sin confirmar (ver log)", ORANGE);
            } else if (demoMode) {
                setSync(eqLive ? "✓ Aplicado (demo)" : "✓ Grabado (demo)", VIOLET);
            } else {
                setSync("✓ Grabado en el DSP", GREEN);
            }
        }

        @Override
        public void onLog(String message) {
            log(message);
        }
    };

    private void onAuthenticated(String model, String preset) {
        log("OK: autenticado — modelo " + model + ", preset " + preset);
        if (demoMode) {
            setStatus(VIOLET, "Conectado a " + MockTransport.DEVICE_NAME,
                    "Modelo " + model + "  ·  Preset " + preset + "  ·  datos simulados");
            setConnectedInfo(MockTransport.DEVICE_NAME, "Modelo " + model + " · Preset " + preset,
                    "Datos simulados de un PX 201", VIOLET);
        } else {
            String address = connectedDevice.getAddress();
            prefs.edit().putString(PREF_LAST_ADDRESS, address).apply();
            String name = connectedDevice.getName() != null ? connectedDevice.getName() : address;
            setStatus(GREEN, "Conectado a " + name, "Modelo " + model + "  ·  Preset " + preset + "  ·  " + address);
            setConnectedInfo(name, "Modelo " + model + " · Preset " + preset,
                    "Modelo " + model + "  ·  Preset " + preset + "\n" + address, GREEN);
        }
        groupsKey = demoMode ? "demo" : connectedDevice.getAddress();
        groups = ChannelGroups.decode(prefs.getString(PREF_GROUPS_PREFIX + groupsKey, null));
        config = null;
        eqSelection = Integer.MIN_VALUE;
        setState(State.CONNECTED);
        session.readConfig();
    }

    // ---------------------------------------------------------------- Log / errores

    private void log(String message) {
        Log.i(TAG, message);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        runOnUiThread(() -> {
            logView.append(stamp + "  " + message + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void fail(String message) {
        closeSession();
        handler.removeCallbacks(scanTimeoutRunnable);
        if (state == State.SCANNING && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        log("ERROR: " + message);
        setStatus(RED, "Error", message);
        setState(State.IDLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectedActivity = new WeakReference<>(null);
        stopScan();
        closeSession();
    }
}
