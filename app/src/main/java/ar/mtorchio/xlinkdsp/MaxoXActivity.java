package ar.mtorchio.xlinkdsp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
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

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * XLink DSP (MVP): conecta por BLE al hardware DSP, manda la password, muestra
 * modelo/preset y permite pedir datos de solo lectura ("a;" = volcado de
 * configuración, "V;" = voltaje), mostrando la respuesta cruda.
 *
 * Replica el protocolo real de
 * br.com.expertelectronics.xair.BLEMainActivity + BluetoothLeService
 * (repo ExpertDSP): escaneo por nombre -> connectGatt -> discoverServices ->
 * notificaciones -> "AT+LESEND=0" -> envío de password "[0][pass]".
 */
public class MaxoXActivity extends Activity {

    static final String TAG = "MaxoXBLE";

    static final UUID CHAR_WRITE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_NOTIFY = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Mismo filtro que xair BLEMainActivity (contains / prefijo de 2 letras / igual).
    private static final String[] DEVICE_NAME_CONTAINS = {
            "AMP", "X4", "X6", "X8", "A4", "A6", "A8", "B4", "B6", "B8", "D4", "D6", "D8", "S6", "S8",
            "PX 201", "PX 202", "PX 208", "PX 101", "PX 102", "PX 108", "PX 103", "PX 203",
            "PRO6P", "PRO8", "MXAiR"
    };
    private static final String[] DEVICE_NAME_PREFIXES = {"M8", "mn", "W8"};

    private static final int REQUEST_PERMISSIONS = 1;
    private static final long RESPONSE_TIMEOUT_MS = 8000;
    // El DSP contesta "OK" a cada comando de escritura; si no llega, seguir igual tras esto.
    static final long ACK_TIMEOUT_MS = 500;
    private static final long SCAN_TIMEOUT_MS = 8000;

    static final String PREFS = "xlink";
    static final String PREF_PASSWORD = "password";
    static final String PREF_LAST_ADDRESS = "last_address";
    private static final String PREF_EQ_LIVE = "eq_live";

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
    // Rangos de xair Limiter (modelos con valores enteros).
    private static final int LIMIT_THRESHOLD_MIN = -54;
    private static final int LIMIT_THRESHOLD_MAX = 6;
    private static final int LIMIT_ATTACK_MIN = 1;
    private static final int LIMIT_ATTACK_MAX = 200;
    private static final int LIMIT_DECAY_MIN = 1;
    private static final int LIMIT_DECAY_MAX = 990;
    private static final String PREF_XO_LINK = "xo_link";
    private static final String PREF_LIMITER_AUTO = "limiter_auto";
    // Rangos de xair Gain (modelos con ganancia en dB enteros).
    private static final int MAIN_GAIN_MIN = -53;
    private static final int MAIN_GAIN_MAX = 0;
    private static final int CHANNEL_GAIN_MIN = -33;
    private static final int CHANNEL_GAIN_MAX = 9;
    // xair Delay: distancia en mm (6500 en la serie PX "común"); 343 mm = 1 ms de sonido.
    private static final int DELAY_MAX_MM = 6500;
    private static final int DELAY_STEP_MM = 10;
    private static final float MM_PER_MS = 343f;
    // xair Routing (modelos de 2 entradas) y Phase: {etiqueta, valor}.
    private static final String[] ROUTING_LABELS = {"L", "R", "L+R"};
    private static final int[] ROUTING_VALUES = {2, 1, 0};

    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private SharedPreferences prefs;

    private State state = State.IDLE;
    private final Set<String> seenDevices = new HashSet<>();
    private final Map<String, BluetoothDevice> candidates = new LinkedHashMap<>();
    private final Map<String, View> candidateRows = new LinkedHashMap<>();
    private BluetoothDevice selectedDevice;
    private boolean waitingForPasswordResponse = false;
    private boolean passwordSent = false;
    private final StringBuilder rxBuffer = new StringBuilder();
    private String lastCommand;

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
    private LinearLayout eqBandsList;
    private LinearLayout eqPresetChips;
    private TextView eqBandLabel;
    private TextView eqSyncText;
    private TextView eqGainValue;
    private TextView eqFreqValue;
    private TextView eqQValue;
    private View eqFreqRow;
    private View eqQRow;
    private final List<TextView[]> eqBandRows = new ArrayList<>();
    private DspConfig config;
    private int eqSelection;
    private int eqSelectedBand;
    // "Custom" por EQ (clave = eqSelection): lo grabado en el DSP más las ediciones a mano.
    private final Map<Integer, float[][]> eqCustom = new HashMap<>();
    // Preset activo por EQ: -1 = Custom.
    private final Map<Integer, Integer> eqActivePreset = new HashMap<>();
    // Cola de escrituras del EQ: una operación GATT a la vez; la clave (tipo+canal+banda)
    // pisa valores viejos que todavía no salieron.
    private final Map<String, String> pendingWrites = new LinkedHashMap<>();
    private boolean writeInFlight = false;
    // Comando de la cola esperando el "OK" del DSP: el siguiente no sale hasta que llegue
    // (o pase ACK_TIMEOUT_MS). Mandarlos de corrido hacía que el DSP descartara la mayoría.
    private String awaitingAck;
    private int missedAcks = 0;
    private final Runnable ackTimeout = () -> {
        String command;
        synchronized (pendingWrites) {
            command = awaitingAck;
            awaitingAck = null;
            if (command != null) {
                missedAcks++;
            }
        }
        if (command != null) {
            log("Sin OK del DSP para " + command);
            pumpEqWrites();
        }
    };
    // Live = cada cambio sale al DSP al instante; si no, se acumulan hasta Grabar.
    private boolean eqLive;
    private boolean eqSaving = false;
    // Live / Grabar está en el encabezado de cada página editable (EQ y Ganancias).
    private final List<TextView> liveButtons = new ArrayList<>();
    private final List<TextView> saveButtons = new ArrayList<>();
    private final List<TextView> syncTexts = new ArrayList<>();
    private LinearLayout gainCard;
    private TextView gainEmpty;
    private LinearLayout gainRows;
    // Actividad con una conexión (real o demo) abierta, para que el widget la use.
    private static WeakReference<MaxoXActivity> connectedActivity = new WeakReference<>(null);
    private boolean permissionsAsked = false;
    // Modo demo: "conectado" a un DSP simulado con respuestas de MockDsp (para el emulador).
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
    private TextView xoLinkChip;
    private CrossoverView xoView;
    private TextView xoHpfValue;
    private TextView xoLpfValue;
    private LinearLayout xoHpfSlopes;
    private LinearLayout xoLpfSlopes;
    private int xoSelected;
    private boolean xoLink;
    // Cambios que esperan Grabar aunque Live esté prendido (crossover: un error rompe parlantes).
    private final Map<String, String> heldWrites = new LinkedHashMap<>();
    private boolean limiterAuto;

    private final Runnable timeoutRunnable = () -> {
        if (waitingForPasswordResponse) {
            fail("El DSP no contestó a la password");
        }
    };

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
        xoLink = prefs.getBoolean(PREF_XO_LINK, true);
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
        TextView channelsSync = text("", 12, MUTED);
        channelsSync.setPadding(0, dp(4), 0, 0);
        syncTexts.add(channelsSync);
        channelsPage.addView(channelsSync);
        channelsEmpty = text("Conectate al DSP: los canales se cargan solos al entrar", 14, MUTED);
        channelsEmpty.setPadding(0, dp(12), 0, 0);
        channelsPage.addView(channelsEmpty);
        channelsCard = card();
        ScrollView channelsScroll = new ScrollView(this);
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
        TextView xoSync = text("", 12, MUTED);
        xoSync.setPadding(0, dp(4), 0, 0);
        syncTexts.add(xoSync);
        crossoverPage.addView(xoSync);
        xoEmpty = text("El DSP no mandó el crossover", 14, MUTED);
        xoEmpty.setPadding(0, dp(12), 0, 0);
        crossoverPage.addView(xoEmpty);
        xoCard = card();
        ScrollView xoScroll = new ScrollView(this);
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
        xoLinkChip = chip("L/R juntos", -1, v -> toggleXoLink());
        LinearLayout.LayoutParams linkParams = (LinearLayout.LayoutParams) xoLinkChip.getLayoutParams();
        linkParams.rightMargin = 0;
        linkParams.leftMargin = dp(8);
        xoChipsRow.addView(xoLinkChip, linkParams);
        xoBody.addView(xoChipsRow);
        xoView = new CrossoverView(this);
        xoView.setListener((channel, hpf, frequency, done) -> setXoFrequency(hpf, Math.round(frequency), done));
        LinearLayout.LayoutParams xoViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        xoViewParams.topMargin = dp(10);
        xoBody.addView(xoView, xoViewParams);
        xoHpfValue = text("", 15, TEXT);
        xoHpfSlopes = new LinearLayout(this);
        xoBody.addView(xoSection("PASA ALTOS · HPF", "corta los graves", xoHpfValue, xoHpfSlopes, true));
        xoLpfValue = text("", 15, TEXT);
        xoLpfSlopes = new LinearLayout(this);
        xoBody.addView(xoSection("PASA BAJOS · LPF", "corta los agudos", xoLpfValue, xoLpfSlopes, false));
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
        eqCard = card();
        eqTitle = label("ECUALIZADOR");
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
        eqCurve.setListener(new EqCurveView.Listener() {
            @Override
            public void onBandSelected(int band) {
                selectBand(band);
            }

            @Override
            public void onBandChanged(int band, boolean done) {
                onBandEdited(band, done, !isInputEq());
            }
        });
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        curveParams.topMargin = dp(10);
        eqCard.addView(eqCurve, curveParams);

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
        presetsParams.topMargin = dp(10);
        eqCard.addView(presetsScroll, presetsParams);

        // Editor de la banda seleccionada
        LinearLayout editorHeader = new LinearLayout(this);
        editorHeader.setGravity(Gravity.CENTER_VERTICAL);
        eqBandLabel = text("", 14, TEXT);
        eqBandLabel.setTypeface(Typeface.DEFAULT_BOLD);
        editorHeader.addView(eqBandLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        eqSyncText = text("Arrastrá un punto o usá − / +", 12, MUTED);
        syncTexts.add(eqSyncText);
        editorHeader.addView(eqSyncText);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(12);
        eqCard.addView(editorHeader, headerParams);
        eqGainValue = text("", 15, TEXT);
        eqCard.addView(stepperRow("Ganancia", eqGainValue, v -> stepGain(-1), v -> stepGain(1)));
        eqFreqValue = text("", 15, TEXT);
        eqFreqRow = stepperRow("Frecuencia", eqFreqValue, v -> stepFrequency(-1), v -> stepFrequency(1));
        eqCard.addView(eqFreqRow);
        eqQValue = text("", 15, TEXT);
        eqQRow = stepperRow("Q", eqQValue, v -> stepQ(-1), v -> stepQ(1));
        eqCard.addView(eqQRow);
        ScrollView bandsScroll = new ScrollView(this);
        eqBandsList = new LinearLayout(this);
        eqBandsList.setOrientation(LinearLayout.VERTICAL);
        bandsScroll.addView(eqBandsList);
        LinearLayout.LayoutParams bandsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bandsParams.topMargin = dp(8);
        eqCard.addView(bandsScroll, bandsParams);
        LinearLayout.LayoutParams eqParams = cardParams();
        eqParams.height = 0;
        eqParams.weight = 1f;
        eqPage.addView(eqCard, eqParams);

        // ===== Página 4: Niveles (master + cada canal: ganancia, mute y limiter)
        gainPage.addView(editableHeader("Niveles"));
        TextView gainSync = text("", 12, MUTED);
        gainSync.setPadding(0, dp(4), 0, 0);
        syncTexts.add(gainSync);
        gainPage.addView(gainSync);
        gainEmpty = text("El DSP no mandó ganancias", 14, MUTED);
        gainEmpty.setPadding(0, dp(12), 0, 0);
        gainPage.addView(gainEmpty);
        gainCard = card();
        ScrollView gainScroll = new ScrollView(this);
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
            TextView item = text(navIcons[i] + "\n" + navLabels[i], 12, MUTED);
            item.setGravity(Gravity.CENTER);
            item.setTypeface(Typeface.DEFAULT_BOLD);
            item.setLineSpacing(dp(2), 1f);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
            item.setClickable(true);
            item.setOnClickListener(v -> showPage(page));
            navItems[i] = item;
            navBar.addView(item, new LinearLayout.LayoutParams(0, dp(64), 1f));
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
        drawerLogHolder = new LinearLayout(this);
        drawerLogHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        holderParams.topMargin = dp(16);
        drawer.addView(drawerLogHolder, holderParams);
        drawer.addView(text("XLink DSP · MVP", 11, MUTED));

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

    private void onConfigLoaded(DspConfig loaded) {
        config = loaded;
        log("Config: bloques " + loaded.blocks.keySet() + ", EQ " + loaded.eqChannels.size()
                + " canales, EQ de entrada " + loaded.inputEq.size() + " bandas");
        runOnUiThread(() -> {
            eqCustom.clear();
            eqActivePreset.clear();
            buildGainRows();
            buildChannelRows();
            buildCrossover();
            eqChannelChips.removeAllViews();
            boolean hasInput = !loaded.inputEq.isEmpty();
            if (hasInput) {
                eqChannelChips.addView(chip("IN", -1, v -> selectEq(-1)));
            }
            for (int ch = 0; ch < loaded.eqChannels.size(); ch++) {
                int selection = ch;
                eqChannelChips.addView(chip("CH" + (ch + 1), ch, v -> selectEq(selection)));
            }
            if (!hasInput && loaded.eqChannels.isEmpty()) {
                eqTitle.setText("ECUALIZADOR  ·  no vino en la respuesta");
                return;
            }
            selectEq(hasInput ? -1 : 0);
            renderState();
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
        if (!(chip.getParent().getParent() instanceof HorizontalScrollView)) {
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
        TextView minus = button("−", CYAN, false);
        minus.setOnClickListener(onMinus);
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(34)));
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView plus = button("+", ORANGE, false);
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

    private List<DspConfig.Band> currentBands() {
        return isInputEq() ? config.inputEq : config.eqChannels.get(eqSelection);
    }

    // Rangos de xair: EQIN ±12 dB enteros; EQCH ±18 dB, 20..20000 Hz, Q 0.6..9.9.
    private float maxGain() {
        return isInputEq() ? 12 : 18;
    }

    /** selection = -1 para el EQ de entrada, 0..n-1 para el EQ de cada canal de salida. */
    private void selectEq(int selection) {
        eqSelection = selection;
        styleChips(eqChannelChips, selection);
        List<DspConfig.Band> bands = currentBands();
        if (!eqCustom.containsKey(selection)) {
            eqCustom.put(selection, snapshot(bands));
            if (selection < 0) {
                restoreInputCustom(bands);
            }
        }
        styleChips(eqPresetChips, activePreset());
        eqTitle.setText((isInputEq() ? "EQ DE ENTRADA" : "EQ CANAL " + (selection + 1))
                + "  ·  " + bands.size() + " bandas" + (demoMode ? "  ·  demo" : ""));
        eqCurve.setBands(bands, isInputEq(), maxGain());
        eqFreqRow.setVisibility(isInputEq() ? View.GONE : View.VISIBLE);
        eqQRow.setVisibility(isInputEq() ? View.GONE : View.VISIBLE);
        eqBandsList.removeAllViews();
        eqBandRows.clear();
        for (int i = 0; i < bands.size(); i++) {
            eqBandsList.addView(bandRow(i));
        }
        selectBand(0);
    }

    /**
     * Si el widget dejó un preset en el EQ de entrada, lo que vino del DSP es ese
     * preset y no el Custom: el Custom real es el guardado en prefs.
     */
    private void restoreInputCustom(List<DspConfig.Band> bands) {
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
        for (int i = 0; i < eqBandsList.getChildCount(); i++) {
            eqBandsList.getChildAt(i).setBackground(i == band ? rounded(0x14FFFFFF, 0x00000000, 10) : null);
        }
        refreshEditor();
    }

    private void refreshEditor() {
        DspConfig.Band band = currentBands().get(eqSelectedBand);
        eqBandLabel.setText("F" + (eqSelectedBand + 1) + "  ·  " + formatHz(band.frequency));
        eqGainValue.setText(String.format(Locale.ROOT, "%+.0f dB", band.gainDb));
        eqGainValue.setTextColor(gainColor(band.gainDb));
        eqFreqValue.setText(formatHz(band.frequency));
        eqQValue.setText(String.format(Locale.ROOT, "%.1f", band.q));
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
        DspConfig.Band band = currentBands().get(index);
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
        DspConfig.Band band = currentBands().get(eqSelectedBand);
        float gain = Math.max(-maxGain(), Math.min(maxGain(), band.gainDb + direction));
        if (gain != band.gainDb) {
            band.gainDb = gain;
            onBandEdited(eqSelectedBand, true, false);
        }
    }

    /** Pasos de 1/12 de octava, redondeados como el arrastre. */
    private void stepFrequency(int direction) {
        DspConfig.Band band = currentBands().get(eqSelectedBand);
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
        DspConfig.Band band = currentBands().get(eqSelectedBand);
        float q = Math.round(Math.max(0.6f, Math.min(9.9f, band.q + direction * 0.1f)) * 10) / 10f;
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
        eqCurve.invalidate();
    }

    /** Lo editado a mano pasa a ser el Custom de este EQ. */
    private void markCustom() {
        eqActivePreset.put(eqSelection, -1);
        eqCustom.put(eqSelection, snapshot(currentBands()));
        styleChips(eqPresetChips, -1);
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
        List<DspConfig.Band> bands = currentBands();
        float[][] custom = eqCustom.get(eqSelection);
        for (int i = 0; i < bands.size(); i++) {
            DspConfig.Band band = bands.get(i);
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
        eqActivePreset.put(eqSelection, preset);
        styleChips(eqPresetChips, preset);
        if (isInputEq()) {
            prefs.edit().putInt(EqWidget.PREF_WIDGET_PRESET, preset).apply();
            EqWidget.refresh(this);
        }
        refreshEditor();
        eqCurve.invalidate();
        log("EQ " + (isInputEq() ? "IN" : "CH" + (eqSelection + 1)) + ": preset "
                + (preset < 0 ? "Custom" : EqPresets.NAMES[preset]));
    }

    private static float[][] snapshot(List<DspConfig.Band> bands) {
        float[][] copy = new float[bands.size()][];
        for (int i = 0; i < bands.size(); i++) {
            DspConfig.Band band = bands.get(i);
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
            if (ch > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(CARD_STROKE);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.topMargin = dp(10);
                channelRows.addView(divider, dividerParams);
            }
            channelRows.addView(channelRow(ch));
        }
    }

    /**
     * Una salida: entrada (routing), fase y delay. Comandos de xair:
     * [3][ch][0|1|2] routing, [6][ch][0|1] fase, [7][ch][mm] delay (canal 0-based).
     */
    private View channelRow(int channel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("CH" + (channel + 1), 15, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
            LinearLayout routing = new LinearLayout(this);
            for (int i = 0; i < ROUTING_LABELS.length; i++) {
                int value = ROUTING_VALUES[i];
                routing.addView(chip(ROUTING_LABELS[i], value, v -> {
                    config.routing[channel] = value;
                    styleChips(routing, value);
                    queueWrite("ROUTE" + channel, "[3][" + channel + "][" + value + "]");
                }));
            }
            styleChips(routing, config.routing[channel]);
            controls.addView(routing);
        }
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (channel < config.phase.length) {
            LinearLayout phase = new LinearLayout(this);
            String[] labels = {"0°", "180°"};
            for (int value = 0; value < labels.length; value++) {
                int phaseValue = value;
                phase.addView(chip(labels[value], value, v -> {
                    config.phase[channel] = phaseValue;
                    styleChips(phase, phaseValue);
                    queueWrite("PHASE" + channel, "[6][" + channel + "][" + phaseValue + "]");
                }));
            }
            styleChips(phase, config.phase[channel]);
            controls.addView(phase);
        }
        row.addView(controls, controlsParams);

        if (channel < config.delays.length) {
            LinearLayout delayRow = new LinearLayout(this);
            delayRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView minus = button("−", CYAN, false);
            delayRow.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(32)));
            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(DELAY_MAX_MM);
            seekBar.setProgress(Math.min(DELAY_MAX_MM, Math.max(0, config.delays[channel])));
            seekBar.setProgressTintList(ColorStateList.valueOf(VIOLET));
            seekBar.setThumbTintList(ColorStateList.valueOf(VIOLET));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_STROKE));
            delayRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(36), 1f));
            TextView plus = button("+", ORANGE, false);
            delayRow.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(32)));
            delayValue.setText(formatDelay(seekBar.getProgress()));
            Runnable send = () -> {
                config.delays[channel] = seekBar.getProgress();
                queueWrite("DELAY" + channel, "[7][" + channel + "][" + seekBar.getProgress() + "]");
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
                seekBar.setProgress(Math.min(DELAY_MAX_MM, seekBar.getProgress() + DELAY_STEP_MM));
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

    private View xoSection(String name, String hint, TextView value, LinearLayout slopes, boolean hpf) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(16), 0, 0);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(label(name));
        TextView hintView = text("  " + hint, 12, MUTED);
        titleRow.addView(hintView);
        section.addView(titleRow);
        section.addView(stepperRow("Frecuencia", value, v -> stepXoFrequency(hpf, -1), v -> stepXoFrequency(hpf, 1)));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        for (int i = 0; i < CrossoverView.SLOPES.length; i++) {
            int index = i;
            slopes.addView(chip(CrossoverView.SLOPE_LABELS[i], i, v -> setXoSlope(hpf, index)));
        }
        scroll.addView(slopes);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(8);
        section.addView(scroll, scrollParams);
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
        for (int ch = 0; ch < xoCount(); ch++) {
            int channel = ch;
            xoChips.addView(chip("CH" + (ch + 1), ch, v -> selectXo(channel)));
        }
        styleXoLink();
        selectXo(Math.min(xoSelected, xoCount() - 1));
    }

    /** Par L/R del canal (CH1+CH2, CH3+CH4...) si "L/R juntos" está prendido; si no, -1. */
    private int xoPartner(int channel) {
        int partner = channel ^ 1;
        return xoLink && partner < xoCount() ? partner : -1;
    }

    private void selectXo(int channel) {
        xoSelected = channel;
        styleChips(xoChips, channel);
        xoView.setSelected(channel, xoPartner(channel));
        refreshXo();
    }

    private void toggleXoLink() {
        xoLink = !xoLink;
        prefs.edit().putBoolean(PREF_XO_LINK, xoLink).apply();
        styleXoLink();
        xoView.setSelected(xoSelected, xoPartner(xoSelected));
    }

    private void styleXoLink() {
        xoLinkChip.setTextColor(xoLink ? BG : MUTED);
        xoLinkChip.setBackground(xoLink ? rounded(CYAN, CYAN, 17) : rounded(0x00000000, CARD_STROKE, 17));
    }

    private void refreshXo() {
        int ch = xoSelected;
        boolean hpfOn = !"OFF".equals(config.hpfSlope[ch]);
        boolean lpfOn = !"OFF".equals(config.lpfSlope[ch]);
        xoHpfValue.setText(hpfOn ? formatHz(config.hpfFreq[ch]) : "—");
        xoLpfValue.setText(lpfOn ? formatHz(config.lpfFreq[ch]) : "—");
        styleChips(xoHpfSlopes, slopeIndex(config.hpfSlope[ch]));
        styleChips(xoLpfSlopes, slopeIndex(config.lpfSlope[ch]));
        xoView.invalidate();
    }

    private static int slopeIndex(String slope) {
        for (int i = 0; i < CrossoverView.SLOPES.length; i++) {
            if (CrossoverView.SLOPES[i].equals(slope)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Comandos de xair Crossover (canal 0-based): [2][ch][0][Hz] HPF, [2][ch][1][pendiente],
     * [2][ch][2][Hz] LPF, [2][ch][3][pendiente]. Siempre esperan a Grabar.
     */
    private void queueXo(int channel, int param, String value) {
        queueWrite("XO" + channel + ":" + param, "[2][" + channel + "][" + param + "][" + value + "]", false);
    }

    private void setXoFrequency(boolean hpf, int hz, boolean send) {
        for (int ch : new int[]{xoSelected, xoPartner(xoSelected)}) {
            if (ch < 0) {
                continue;
            }
            (hpf ? config.hpfFreq : config.lpfFreq)[ch] = hz;
            if (send) {
                queueXo(ch, hpf ? 0 : 2, String.valueOf(hz));
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

    private void setXoSlope(boolean hpf, int index) {
        String slope = CrossoverView.SLOPES[index];
        if (slope.equals((hpf ? config.hpfSlope : config.lpfSlope)[xoSelected])) {
            return;
        }
        Runnable apply = () -> {
            for (int ch : new int[]{xoSelected, xoPartner(xoSelected)}) {
                if (ch < 0) {
                    continue;
                }
                (hpf ? config.hpfSlope : config.lpfSlope)[ch] = slope;
                queueXo(ch, hpf ? 1 : 3, slope);
            }
            refreshXo();
        };
        if (hpf && "OFF".equals(slope)) {
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

    // ---------------------------------------------------------------- Ganancias

    private void buildGainRows() {
        gainRows.removeAllViews();
        if (!Float.isNaN(config.mainGain)) {
            gainRows.addView(label("MASTER"));
            gainRows.addView(gainRow("Master", -1, config.mainGain, MAIN_GAIN_MIN, MAIN_GAIN_MAX));
            LinearLayout channelsHeader = new LinearLayout(this);
            channelsHeader.setGravity(Gravity.CENTER_VERTICAL);
            channelsHeader.setPadding(0, dp(18), 0, 0);
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
            gainRows.addView(gainRow("CH" + (ch + 1), ch, config.channelGains[ch], CHANNEL_GAIN_MIN, CHANNEL_GAIN_MAX));
        }
    }

    /** channel = -1 para el master. Comandos de xair Gain: [100][ch][dB] y [999][ch][1|0] (mute). */
    private View gainRow(String name, int channel, float gain, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(name, 15, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", 15, TEXT);
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        if (channel >= 0) {
            TextView mute = chip("Mute", 0, null);
            mute.setOnClickListener(v -> {
                config.channelMutes[channel] = !config.channelMutes[channel];
                styleMute(mute, value, channel);
                queueWrite("MUTE" + channel, "[999][" + channel + "][" + (config.channelMutes[channel] ? 1 : 0) + "]");
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
                int progress = bar.getProgress();
                if (channel < 0) {
                    config.mainGain = progress;
                } else {
                    config.channelGains[channel] = progress;
                }
                queueWrite("GAIN" + channel, "[100][" + channel + "][" + progress + "]");
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        seekParams.topMargin = dp(4);
        row.addView(seekBar, seekParams);
        if (channel >= 0) {
            addLimiterRows(row, channel);
        }
        return row;
    }

    /**
     * Limiter del canal (xair Limiter): umbral [103] (o [113] en modo auto, donde el DSP
     * calcula attack y decay a partir del HPF), attack [102] y decay [104].
     */
    private void addLimiterRows(LinearLayout row, int channel) {
        if (channel < config.limThreshold.length) {
            row.addView(miniSlider("Limiter", LIMIT_THRESHOLD_MIN, LIMIT_THRESHOLD_MAX,
                    config.limThreshold[channel], "%+d dB", PINK, value -> {
                        config.limThreshold[channel] = value;
                        queueWrite("LIMT" + channel, "[" + (limiterAuto ? 113 : 103) + "][" + channel + "][" + value + "]");
                    }));
        }
        if (limiterAuto) {
            return;
        }
        if (channel < config.limAttack.length) {
            row.addView(miniSlider("Attack", LIMIT_ATTACK_MIN, LIMIT_ATTACK_MAX,
                    config.limAttack[channel], "%d ms", VIOLET, value -> {
                        config.limAttack[channel] = value;
                        queueWrite("LIMA" + channel, "[102][" + channel + "][" + value + "]");
                    }));
        }
        if (channel < config.limDecay.length) {
            row.addView(miniSlider("Decay", LIMIT_DECAY_MIN, LIMIT_DECAY_MAX,
                    config.limDecay[channel], "%d ms", VIOLET, value -> {
                        config.limDecay[channel] = value;
                        queueWrite("LIMD" + channel, "[104][" + channel + "][" + value + "]");
                    }));
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

    /**
     * Comandos de xair (EQCH / EQIN), canal 0-based:
     * [202][ch][Fn][dB] ganancia, [203][ch][Fn][Hz] frecuencia, [201][ch][Fn][Q] factor Q
     * (bandas desde F1). El EQ de entrada usa canal -1 y bandas desde F0.
     */
    private void queueEqWrite(String type, int bandIndex) {
        DspConfig.Band band = currentBands().get(bandIndex);
        String command;
        if (isInputEq()) {
            command = "[202][-1][F" + bandIndex + "][" + Math.round(band.gainDb) + "]";
        } else {
            String target = "[" + eqSelection + "][F" + (bandIndex + 1) + "]";
            switch (type) {
                case "F":
                    command = "[203]" + target + "[" + Math.round(band.frequency) + "]";
                    break;
                case "Q":
                    command = "[201]" + target + "[" + String.format(Locale.ROOT, "%.1f", band.q) + "]";
                    break;
                default:
                    command = "[202]" + target + "[" + Math.round(band.gainDb) + "]";
            }
        }
        queueWrite(type + eqSelection + ":" + bandIndex, command);
    }

    /** Encola un comando (la clave pisa uno pendiente igual); con Live sale ya. */
    private void queueWrite(String key, String command) {
        queueWrite(key, command, true);
    }

    /** allowLive = false: aunque Live esté prendido, el cambio espera a Grabar (crossover). */
    private void queueWrite(String key, String command, boolean allowLive) {
        boolean held = eqLive && !allowLive;
        synchronized (pendingWrites) {
            (held ? heldWrites : pendingWrites).put(key, command);
        }
        if (eqLive && allowLive) {
            flushEq();
        } else {
            updateEqHeader();
            setSync(held ? "Crossover: tocá Grabar para aplicarlo" : "Cambios sin grabar", ORANGE);
        }
    }

    /** Botón Grabar: manda lo pendiente, incluido lo retenido del crossover. */
    private void saveAll() {
        synchronized (pendingWrites) {
            pendingWrites.putAll(heldWrites);
            heldWrites.clear();
        }
        flushEq();
    }

    private void setSync(String text, int color) {
        for (TextView sync : syncTexts) {
            sync.setText(text);
            sync.setTextColor(color);
        }
    }

    /** Título de página + Live + Grabar (compartidos entre EQ y Ganancias). */
    private View editableHeader(String title) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(pageTitle(title), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
        return header;
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
        int count;
        int held;
        synchronized (pendingWrites) {
            count = pendingWrites.size() + heldWrites.size();
            held = heldWrites.size();
        }
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
            save.setText(eqSaving ? "Grabando…" : count > 0 ? "Grabar (" + count + ")" : "Grabar");
            save.setEnabled(count > 0 && !eqSaving);
            save.setAlpha(count > 0 || eqSaving ? 1f : 0.4f);
        }
    }

    /** Manda al DSP todos los cambios pendientes (Grabar, o cada cambio con Live). */
    private void flushEq() {
        if (demoMode) {
            List<String> commands;
            synchronized (pendingWrites) {
                commands = new ArrayList<>(pendingWrites.values());
                pendingWrites.clear();
            }
            for (String command : commands) {
                log("TX (demo) -> " + command);
            }
            updateEqHeader();
            setSync(eqLive ? "✓ Aplicado (demo)" : "✓ Grabado (demo)", VIOLET);
            return;
        }
        synchronized (pendingWrites) {
            if (pendingWrites.isEmpty()) {
                return;
            }
            eqSaving = true;
        }
        updateEqHeader();
        pumpEqWrites();
    }

    private void pumpEqWrites() {
        String command;
        int left;
        synchronized (pendingWrites) {
            if (!eqSaving || writeInFlight || awaitingAck != null || bluetoothGatt == null
                    || state != State.CONNECTED) {
                return;
            }
            if (pendingWrites.isEmpty()) {
                eqSaving = false;
                int missed = missedAcks;
                missedAcks = 0;
                runOnUiThread(() -> {
                    updateEqHeader();
                    if (missed == 0) {
                        setSync("✓ Grabado en el DSP", GREEN);
                    } else {
                        setSync("⚠ " + missed + " sin confirmar (ver log)", ORANGE);
                    }
                });
                return;
            }
            String key = pendingWrites.keySet().iterator().next();
            command = pendingWrites.remove(key);
            left = pendingWrites.size();
            awaitingAck = command;
        }
        handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
        sendCommand(bluetoothGatt, command + "\r\n");
        runOnUiThread(() -> {
            updateEqHeader();
            setSync("Enviando…" + (left > 0 ? " (" + left + ")" : ""), MUTED);
        });
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
        closeGatt();
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
        MaxoXActivity activity = connectedActivity.get();
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
        closeGatt();
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
        closeGatt();
        connectToDevice(selectedDevice);
    }

    private void sendUserCommand(String command, String title) {
        if (state != State.CONNECTED || (bluetoothGatt == null && !demoMode)) {
            return;
        }
        lastCommand = title;
        synchronized (rxBuffer) {
            rxBuffer.setLength(0);
        }
        if (demoMode) {
            log("TX (demo) -> " + command);
            String response = MockDsp.respond(command);
            if (response != null) {
                // Pequeña demora para que se vea el "esperando…" como con el equipo real.
                handler.postDelayed(() -> onDataReceived(response), 400);
            }
            return;
        }
        sendCommand(bluetoothGatt, command + "\r\n");
    }

    private void startDemo() {
        log("Modo demo: DSP simulado con datos capturados de un PX 201");
        stopScan();
        closeGatt();
        demoMode = true;
        setStatus(VIOLET, "Conectado a " + MockDsp.DEVICE_NAME,
                "Modelo " + MockDsp.MODEL + "  ·  Preset " + MockDsp.PRESET + "  ·  datos simulados");
        setConnectedInfo(MockDsp.DEVICE_NAME, "Modelo " + MockDsp.MODEL + " · Preset " + MockDsp.PRESET,
                "Datos simulados de un PX 201", VIOLET);
        config = null;
        setState(State.CONNECTED);
        sendUserCommand("a;", "Configuración");
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

    // ---------------------------------------------------------------- GATT

    private void connectToDevice(BluetoothDevice device) {
        log("Conectando (GATT) a " + device.getAddress() + "…");
        setStatus(CYAN, "Conectando…", device.getAddress());
        setState(State.CONNECTING);
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() {
        handler.removeCallbacks(timeoutRunnable);
        waitingForPasswordResponse = false;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        writeCharacteristic = null;
        notifyCharacteristic = null;
        synchronized (pendingWrites) {
            pendingWrites.clear();
            heldWrites.clear();
            writeInFlight = false;
            eqSaving = false;
            awaitingAck = null;
            missedAcks = 0;
        }
        handler.removeCallbacks(ackTimeout);
        synchronized (pendingWrites) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT conectado, descubriendo servicios…");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Desconectado (status " + status + ")");
                if (gatt != bluetoothGatt) {
                    return;
                }
                closeGatt();
                if (state == State.CONNECTING) {
                    fail("El DSP cortó la conexión antes de autenticar");
                } else if (state == State.CONNECTED) {
                    setStatus(ORANGE, "Se perdió la conexión", "Buscando de nuevo…");
                    setState(State.IDLE);
                    handler.post(MaxoXActivity.this::autoSearch);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Falla al descubrir servicios (" + status + ")");
                return;
            }
            log("Servicios descubiertos (" + gatt.getServices().size() + "), buscando características fff1/fff2…");
            if (!findCharacteristics(gatt.getServices())) {
                fail("El equipo no tiene las características BLE esperadas (fff1/fff2)");
                return;
            }
            log("Características encontradas, activando notificaciones…");
            // Android admite una sola operación GATT pendiente: cada paso sigue
            // en el callback del anterior (CCCD -> AT+LESEND -> password).
            if (!enableNotifications(gatt, notifyCharacteristic)) {
                sendCleanLE(gatt);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD.equals(descriptor.getUuid())) {
                log("Notificaciones activadas (status " + status + ")");
                sendCleanLE(gatt);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (!CHAR_WRITE.equals(characteristic.getUuid())) {
                return;
            }
            synchronized (pendingWrites) {
                writeInFlight = false;
            }
            if (passwordSent) {
                pumpEqWrites();
                return;
            }
            log("Enviando password…");
            String password = passwordField.getText().toString().trim();
            passwordSent = true;
            waitingForPasswordResponse = true;
            handler.postDelayed(timeoutRunnable, RESPONSE_TIMEOUT_MS);
            sendCommand(gatt, "[0][" + password + "]\r\n");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            String chunk = new String(value);
            Log.d(TAG, "RX: " + chunk);
            String received;
            synchronized (rxBuffer) {
                rxBuffer.append(chunk);
                received = rxBuffer.toString();
            }
            if (waitingForPasswordResponse) {
                if (received.contains("[77][1]")) {
                    String model = extractField(received, 3);
                    String preset = extractField(received, 4);
                    onAuthenticated(gatt, model, preset);
                } else if (received.contains("[24]") || received.contains("ER")) {
                    fail("Password inválida");
                    closeGatt();
                }
                return;
            }
            if (state == State.CONNECTED) {
                if (chunk.contains("OK")) {
                    boolean acked;
                    synchronized (pendingWrites) {
                        acked = awaitingAck != null;
                        awaitingAck = null;
                    }
                    if (acked) {
                        handler.removeCallbacks(ackTimeout);
                        pumpEqWrites();
                    }
                }
                showReceived(received);
            }
        }
    };

    /** Respuesta del modo demo: entra igual que una notificación del DSP. */
    private void onDataReceived(String response) {
        if (!demoMode || state != State.CONNECTED) {
            return;
        }
        String received;
        synchronized (rxBuffer) {
            rxBuffer.append(response);
            received = rxBuffer.toString();
        }
        showReceived(received);
    }

    private void showReceived(String received) {
        if ("Configuración".equals(lastCommand) && DspConfig.isComplete(received)) {
            onConfigLoaded(DspConfig.parse(received));
        }
    }

    private void onAuthenticated(BluetoothGatt gatt, String model, String preset) {
        handler.removeCallbacks(timeoutRunnable);
        waitingForPasswordResponse = false;
        synchronized (rxBuffer) {
            rxBuffer.setLength(0);
        }
        BluetoothDevice device = gatt.getDevice();
        prefs.edit().putString(PREF_LAST_ADDRESS, device.getAddress()).apply();
        log("OK: autenticado — modelo " + model + ", preset " + preset);
        String name = device.getName() != null ? device.getName() : device.getAddress();
        setStatus(GREEN, "Conectado a " + name, "Modelo " + model + "  ·  Preset " + preset + "  ·  " + device.getAddress());
        setConnectedInfo(name, "Modelo " + model + " · Preset " + preset,
                "Modelo " + model + "  ·  Preset " + preset + "\n" + device.getAddress(), GREEN);
        setState(State.CONNECTED);
        config = null;
        renderState();
        // Traer la configuración de una (solo lectura). Pequeña espera para que
        // termine la escritura de la password, que puede seguir pendiente en el stack GATT.
        handler.postDelayed(() -> sendUserCommand("a;", "Configuración"), 300);
    }

    private String extractField(String response, int bracketIndex) {
        try {
            String[] byOpenBracket = response.split("\\[");
            String afterBracket = byOpenBracket[bracketIndex];
            return afterBracket.split("\\]")[0];
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean findCharacteristics(List<BluetoothGattService> services) {
        for (BluetoothGattService service : services) {
            BluetoothGattCharacteristic write = service.getCharacteristic(CHAR_WRITE);
            BluetoothGattCharacteristic notify = service.getCharacteristic(CHAR_NOTIFY);
            if (write != null && notify != null) {
                writeCharacteristic = write;
                notifyCharacteristic = notify;
                return true;
            }
        }
        return false;
    }

    /** Devuelve true si quedó una escritura del CCCD pendiente (sigue en onDescriptorWrite). */
    private boolean enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            return false;
        }
        return gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                == BluetoothStatusCodes.SUCCESS;
    }

    // Equivalente a BluetoothLeService.cleanLE() en xair; la password sale en onCharacteristicWrite.
    private void sendCleanLE(BluetoothGatt gatt) {
        passwordSent = false;
        synchronized (rxBuffer) {
            rxBuffer.setLength(0);
        }
        sendCommand(gatt, "AT+LESEND=0\r\n");
    }

    private void sendCommand(BluetoothGatt gatt, String command) {
        if (writeCharacteristic == null) {
            return;
        }
        // No dejar la password a la vista en el log técnico.
        log("TX -> " + command.trim().replaceAll("^\\[0\\]\\[.*\\]$", "[0][****]"));
        int result = gatt.writeCharacteristic(writeCharacteristic, command.getBytes(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (result == BluetoothStatusCodes.SUCCESS) {
            synchronized (pendingWrites) {
                writeInFlight = true;
            }
        } else {
            log("ERROR: writeCharacteristic rechazado (" + result + ") para " + command.trim());
        }
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
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(scanTimeoutRunnable);
        if (state == State.SCANNING && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        waitingForPasswordResponse = false;
        log("ERROR: " + message);
        setStatus(RED, "Error", message);
        setState(State.IDLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectedActivity = new WeakReference<>(null);
        stopScan();
        closeGatt();
    }
}
