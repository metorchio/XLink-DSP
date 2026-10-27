package ar.mtorchio.xlinkdsp;

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
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Conexión BLE "de un tiro" para el widget: conecta al último DSP usado, se
 * autentica con la password guardada, manda una lista de comandos (uno por
 * escritura GATT) y se desconecta. Mismo protocolo que MaxoXActivity:
 * CCCD -> "AT+LESEND=0" -> "[0][pass]" -> espera "[77][1]".
 */
final class DspLink {

    interface Callback {
        /** error = null si salió todo bien. Se llama en el hilo principal. */
        void onDone(String error);
    }

    private static final long TIMEOUT_MS = 9000;
    // Como en la app: cada comando espera el "OK" del DSP (o ACK_TIMEOUT_MS) antes del siguiente.
    private final Runnable ackTimeout = () -> {
        Log.i(MaxoXActivity.TAG, "Widget: sin OK del DSP");
        sendNextCommand();
    };
    private boolean started = false;

    private final Context context;
    private final String password;
    private final ArrayDeque<String> commands;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder rxBuffer = new StringBuilder();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean passwordSent = false;
    private boolean authenticated = false;
    private boolean finished = false;
    // Hay una escritura GATT en curso (Android permite una sola operación a la vez).
    private boolean writing = false;

    private DspLink(Context context, String password, List<String> commands, Callback callback) {
        this.context = context.getApplicationContext();
        this.password = password;
        this.commands = new ArrayDeque<>(commands);
        this.callback = callback;
    }

    static void send(Context context, String address, String password, List<String> commands, Callback callback) {
        new DspLink(context, password, commands, callback).start(address);
    }

    private void start(String address) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            finish("Bluetooth apagado");
            return;
        }
        // Conexión + un OK (o su timeout) por comando.
        handler.postDelayed(() -> finish(authenticated ? "El DSP dejó de responder" : "No se encontró el DSP"),
                TIMEOUT_MS + commands.size() * MaxoXActivity.ACK_TIMEOUT_MS);
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException e) {
            finish("Sin permiso de Bluetooth: abrí la app");
        }
    }

    private void finish(String error) {
        handler.post(() -> {
            if (finished) {
                return;
            }
            finished = true;
            handler.removeCallbacksAndMessages(null);
            try {
                if (gatt != null) {
                    gatt.disconnect();
                    gatt.close();
                }
            } catch (SecurityException ignored) {
            }
            Log.i(MaxoXActivity.TAG, "Widget: " + (error == null ? "OK" : error));
            callback.onDone(error);
        });
    }

    private synchronized void write(String command) {
        writing = true;
        try {
            int result = gatt.writeCharacteristic(writeCharacteristic, (command + "\r\n").getBytes(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (result != BluetoothStatusCodes.SUCCESS) {
                finish("Error al escribir (" + result + ")");
            }
        } catch (SecurityException e) {
            finish("Sin permiso de Bluetooth: abrí la app");
        }
    }

    private synchronized void sendNextCommand() {
        handler.removeCallbacks(ackTimeout);
        started = true;
        String command = commands.poll();
        if (command == null) {
            finish(null);
            return;
        }
        Log.i(MaxoXActivity.TAG, "Widget TX -> " + command);
        handler.postDelayed(ackTimeout, MaxoXActivity.ACK_TIMEOUT_MS);
        write(command);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    finish("Sin permiso de Bluetooth: abrí la app");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(authenticated ? "Se cortó la conexión" : "No se pudo conectar al DSP");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattCharacteristic notify = null;
            for (BluetoothGattService service : gatt.getServices()) {
                if (service.getCharacteristic(MaxoXActivity.CHAR_WRITE) != null
                        && service.getCharacteristic(MaxoXActivity.CHAR_NOTIFY) != null) {
                    writeCharacteristic = service.getCharacteristic(MaxoXActivity.CHAR_WRITE);
                    notify = service.getCharacteristic(MaxoXActivity.CHAR_NOTIFY);
                }
            }
            if (notify == null) {
                finish("No es un DSP compatible");
                return;
            }
            try {
                gatt.setCharacteristicNotification(notify, true);
                BluetoothGattDescriptor descriptor = notify.getDescriptor(MaxoXActivity.CCCD);
                if (descriptor == null || gatt.writeDescriptor(descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                    write("AT+LESEND=0");
                }
            } catch (SecurityException e) {
                finish("Sin permiso de Bluetooth: abrí la app");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            write("AT+LESEND=0");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (DspLink.this) {
                writing = false;
            }
            if (!passwordSent) {
                passwordSent = true;
                write("[0][" + password + "]");
            } else if (authenticated && !started) {
                // El OK de la password llegó mientras su escritura seguía pendiente.
                sendNextCommand();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (authenticated) {
                if (started && new String(value).contains("OK")) {
                    sendNextCommand();
                }
                return;
            }
            rxBuffer.append(new String(value));
            String received = rxBuffer.toString();
            if (received.contains("[77][1]")) {
                // Si la escritura de la password todavía no confirmó, el próximo
                // comando sale desde onCharacteristicWrite.
                synchronized (DspLink.this) {
                    authenticated = true;
                    if (writing) {
                        return;
                    }
                }
                sendNextCommand();
            } else if (received.contains("[24]") || received.contains("ER")) {
                finish("Password inválida");
            }
        }
    };
}
