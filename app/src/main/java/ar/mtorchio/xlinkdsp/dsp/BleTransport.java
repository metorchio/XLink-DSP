/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp.dsp;

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

import java.util.UUID;

/**
 * Transport sobre el módulo BLE del DSP (xair BluetoothLeService): servicio fff0,
 * se escribe por fff2 y las respuestas llegan como notificaciones por fff1.
 *
 * Android admite una sola operación GATT pendiente, así que open() encadena
 * connectGatt -> discoverServices -> CCCD y recién ahí avisa onOpen(). Todos los
 * callbacks se pasan al hilo principal.
 */
public final class BleTransport implements Transport {

    private static final String TAG = "XLinkDsp";
    public static final UUID CHAR_WRITE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_NOTIFY = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final String address;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean opened;
    private boolean closed;

    public BleTransport(Context context, String address) {
        this.context = context.getApplicationContext();
        this.address = address;
    }

    @Override
    public void open(Listener listener) {
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            closeWith("Bluetooth apagado");
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            listener.onLog("Conectando (GATT) a " + address + "…");
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            closeWith("Sin permiso de Bluetooth");
        } catch (IllegalArgumentException e) {
            closeWith("Dirección Bluetooth inválida");
        }
    }

    @Override
    public boolean write(String line) {
        if (closed || gatt == null || writeCharacteristic == null) {
            return false;
        }
        try {
            int result = gatt.writeCharacteristic(writeCharacteristic, line.getBytes(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (result != BluetoothStatusCodes.SUCCESS) {
                listener.onLog("ERROR: writeCharacteristic rechazado (" + result + ")");
                return false;
            }
            return true;
        } catch (SecurityException e) {
            closeWith("Sin permiso de Bluetooth");
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
        writeCharacteristic = null;
    }

    /** Cierra por un error y avisa una sola vez. */
    private void closeWith(String reason) {
        if (closed) {
            return;
        }
        close();
        main.post(() -> listener.onClosed(reason));
    }

    /** Corre en el hilo principal, salvo que ya se haya cerrado. */
    private void onMain(Runnable task) {
        main.post(() -> {
            if (!closed) {
                task.run();
            }
        });
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            onMain(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    listener.onLog("GATT conectado, descubriendo servicios…");
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        closeWith("Sin permiso de Bluetooth");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeWith("Desconectado (status " + status + ")");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            onMain(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeWith("Falla al descubrir servicios (" + status + ")");
                    return;
                }
                BluetoothGattCharacteristic notify = null;
                for (BluetoothGattService service : g.getServices()) {
                    if (service.getCharacteristic(CHAR_WRITE) != null && service.getCharacteristic(CHAR_NOTIFY) != null) {
                        writeCharacteristic = service.getCharacteristic(CHAR_WRITE);
                        notify = service.getCharacteristic(CHAR_NOTIFY);
                    }
                }
                if (notify == null) {
                    closeWith("El equipo no tiene las características BLE esperadas (fff1/fff2)");
                    return;
                }
                listener.onLog("Características encontradas, activando notificaciones…");
                try {
                    g.setCharacteristicNotification(notify, true);
                    BluetoothGattDescriptor descriptor = notify.getDescriptor(CCCD);
                    // Sin CCCD (o si no se pudo escribir) se sigue igual, como xair.
                    if (descriptor == null || g.writeDescriptor(descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                        markOpen();
                    }
                } catch (SecurityException e) {
                    closeWith("Sin permiso de Bluetooth");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            onMain(() -> {
                if (CCCD.equals(descriptor.getUuid())) {
                    listener.onLog("Notificaciones activadas (status " + status + ")");
                    markOpen();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (CHAR_WRITE.equals(characteristic.getUuid())) {
                onMain(listener::onWritten);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            String chunk = new String(value);
            Log.d(TAG, "RX: " + chunk);
            onMain(() -> listener.onData(chunk));
        }
    };

    private void markOpen() {
        if (!opened) {
            opened = true;
            listener.onOpen();
        }
    }
}
