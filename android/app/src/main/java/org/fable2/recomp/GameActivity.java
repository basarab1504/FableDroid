package org.fable2.recomp;

import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.View;
import java.util.Map;
import android.view.ViewGroup;
import android.view.KeyEvent;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class GameActivity extends SDLActivity {
    private TouchControls touchControls;
    private Map<Integer, Integer> buttonMap;
    private int touchMode;
    private final Handler batteryHandler = new Handler(Looper.getMainLooper());
    private boolean batteryLogging;
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { updateTouchVisibility(); }
        @Override public void onInputDeviceRemoved(int id) { updateTouchVisibility(); }
        @Override public void onInputDeviceChanged(int id) { updateTouchVisibility(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Settings settings = new Settings(this);
        buttonMap = settings.buttonMap();
        touchMode = BuildConfig.DEBUG && getIntent().getBooleanExtra("benchmark", false)
            ? Settings.TOUCH_ALWAYS : settings.touchMode();
        if (touchMode != Settings.TOUCH_OFF) {
            touchControls = new TouchControls(this, false);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            getSystemService(InputManager.class).registerInputDeviceListener(deviceListener, null);
            updateTouchVisibility();
        }
        batteryLogging = settings.diagnosticLogs();
        if (batteryLogging) batteryHandler.post(batteryLogger);
    }
    @Override protected void onDestroy() {
        batteryHandler.removeCallbacks(batteryLogger);
        if (touchControls != null) getSystemService(InputManager.class).unregisterInputDeviceListener(deviceListener);
        super.onDestroy();
    }
    @Override protected void onPause() {
        if (touchControls != null) touchControls.releaseAll();
        super.onPause();
    }

    // Diagnostic logging: apps cannot read most CPU/GPU thermal zones, but the battery
    // temperature (a proxy for the device body), power saver and thermal status are public.
    // Every 15 s into battery.log next to the game logs; compare by timestamp.
    private final Runnable batteryLogger = new Runnable() {
        @Override public void run() {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            StringBuilder line = new StringBuilder("[battery]");
            if (battery != null) {
                int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                if (temperature != Integer.MIN_VALUE)
                    line.append(" temp_C=").append(temperature / 10).append('.').append(Math.abs(temperature % 10));
                if (level >= 0 && scale > 0) line.append(" level=").append(level * 100 / scale).append('%');
                line.append(" charging=").append(plugged != 0);
            }
            if (power != null) {
                line.append(" power_save=").append(power.isPowerSaveMode());
                line.append(" thermal_status=").append(power.getCurrentThermalStatus());
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, "battery.log"), true)) {
                    out.write(("[" + stamp + "] " + line + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException ignored) {}
            }
            if (batteryLogging) batteryHandler.postDelayed(this, 15000);
        }
    };

    // Auto: touch controls only without a physical gamepad. Handhelds with
    // built-in controls (AYN Thor, Retroid) report theirs as a gamepad.
    private void updateTouchVisibility() {
        if (touchControls == null) return;
        boolean show = touchMode == Settings.TOUCH_ALWAYS || controllerDevice() < 0;
        if (!show) touchControls.releaseAll();
        touchControls.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    @Override protected String[] getLibraries() {
        return new String[] { "SDL3", "rexruntime", "main" };
    }

    // Keyboard equivalents also let the device mirror exercise the real
    // handheld controller path without creating a second player/controller.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean gamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD)
            || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
        Integer remapped = gamepad && buttonMap != null ? buttonMap.get(event.getKeyCode()) : null;
        if (remapped != null) {
            // User remapping: send SDL the Xbox button chosen in the launcher.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                SDLControllerManager.onNativePadDown(event.getDeviceId(), remapped, 0);
            else if (event.getAction() == KeyEvent.ACTION_UP)
                SDLControllerManager.onNativePadUp(event.getDeviceId(), remapped, 0);
            return true;
        }
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                && !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            int button = mappedButton(event.getKeyCode());
            int device = controllerDevice();
            if (button != 0 && device >= 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0)
                        SDLControllerManager.onNativePadDown(device, button, 0);
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    SDLControllerManager.onNativePadUp(device, button, 0);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static int controllerDevice() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual() && SDLControllerManager.isDeviceSDLJoystick(id)) return id;
        }
        return -1;
    }

    private static int mappedButton(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_A: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_B: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_X: return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_Y: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_SPACE: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_DPAD_UP: return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return 0;
        }
    }
}
