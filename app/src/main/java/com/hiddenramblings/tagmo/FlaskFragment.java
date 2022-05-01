package com.hiddenramblings.tagmo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.nfc.TagLostException;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.hiddenramblings.tagmo.eightbit.charset.CharsetCompat;
import com.hiddenramblings.tagmo.eightbit.io.Debug;
import com.hiddenramblings.tagmo.eightbit.material.IconifiedSnackbar;
import com.hiddenramblings.tagmo.widget.Toasty;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
@SuppressLint("MissingPermission")
public class FlaskFragment extends Fragment {

    private boolean hasCheckedPermissions = false;
    private ViewGroup fragmentView;
    private LinearLayout deviceList;
    private ProgressBar progressBar;
    private Snackbar statusBar;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeService flaskService;
    private String flaskAddress;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    ActivityResultLauncher<String[]> onRequestLocationQ = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isLocationAvailable = true;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (entry.getKey().equals(Manifest.permission.ACCESS_FINE_LOCATION)
                    && !entry.getValue()) isLocationAvailable = false;
        }
        if (isLocationAvailable) {
            activateBluetooth();
        } else {
            new Toasty(requireActivity()).Long(R.string.flask_permissions);
            ((BrowserActivity) requireActivity()).showBrowserPage();
        }
    });

    ActivityResultLauncher<String[]> onRequestBluetoothS = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isBluetoothAvailable = true;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (!entry.getValue()) isBluetoothAvailable = false;
        }
        if (isBluetoothAvailable) {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter) {
                selectBluetoothDevice();
            } else {
                new Toasty(requireActivity()).Long(R.string.flask_bluetooth);
                ((BrowserActivity) requireActivity()).showBrowserPage();
            }
        } else {
            new Toasty(requireActivity()).Long(R.string.flask_bluetooth);
            ((BrowserActivity) requireActivity()).showBrowserPage();
        }
    });
    ActivityResultLauncher<Intent> onRequestBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        mBluetoothAdapter = getBluetoothAdapter();
        if (null != mBluetoothAdapter) {
            selectBluetoothDevice();
        } else {
            new Toasty(requireActivity()).Long(R.string.flask_bluetooth);
            ((BrowserActivity) requireActivity()).showBrowserPage();
        }
    });
    ActivityResultLauncher<String[]> onRequestLocation = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isLocationAvailable = true;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (!entry.getValue()) isLocationAvailable = false;
        }
        if (isLocationAvailable) {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter)
                selectBluetoothDevice();
            else
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } else {
            new Toasty(requireActivity()).Long(R.string.flask_permissions);
            ((BrowserActivity) requireActivity()).showBrowserPage();
        }
    });
    protected ServiceConnection mServerConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d("FlaskService", "onServiceConnected");
            BluetoothLeService.LocalBinder localBinder = (BluetoothLeService.LocalBinder) binder;
            flaskService = localBinder.getService();
            if (flaskService.initialize()) {
                if (flaskService.connect(flaskAddress)) {
                    flaskService.setListener(new BluetoothLeService.BluetoothGattListener() {
                        boolean isServiceDiscovered = false;
                        @Override
                        public void onServicesDiscovered() {
                            isServiceDiscovered = true;
                            try {
                                flaskService.readCustomCharacteristic();
                                dismissConnectionNotice();
                                new Toasty(requireActivity()).Short(R.string.flask_connected);
                            } catch (TagLostException tle) {
                                stopFlaskService();
                                new Toasty(requireActivity()).Short(R.string.flask_invalid);
                            }
                        }

                        @Override
                        public void onServicesDisconnect() {
                            if (!isServiceDiscovered) {
                                stopFlaskService();
                                new Toasty(requireActivity()).Short(R.string.flask_missing);
                            }
                        }
                    });
                } else {
                    stopFlaskService();
                    new Toasty(requireActivity()).Short(R.string.flask_invalid);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d("FlaskService", "onServiceDisconnected");
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_flask, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        fragmentView = (ViewGroup) view;
        deviceList = view.findViewById(R.id.bluetooth_devices);
        progressBar = view.findViewById(R.id.pairing_progress);
    }

    private void verifyPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
                activateBluetooth();
            } else {
                final String[] PERMISSIONS_LOCATION = {
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                };
                onRequestLocationQ.launch(PERMISSIONS_LOCATION);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final String[] PERMISSIONS_LOCATION = {
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            onRequestLocation.launch(PERMISSIONS_LOCATION);
        } else {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter)
                selectBluetoothDevice();
            else
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    private void activateBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final String[] PERMISSIONS_BLUETOOTH = {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            };
            onRequestBluetoothS.launch(PERMISSIONS_BLUETOOTH);
        } else {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter)
                selectBluetoothDevice();
            else
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothAdapter mBluetoothAdapter;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mBluetoothAdapter = ((BluetoothManager) requireContext()
                    .getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            if (null != mBluetoothAdapter) {
                if (!mBluetoothAdapter.isEnabled()) mBluetoothAdapter.enable();
                return mBluetoothAdapter;
            }
        } else {
            //noinspection deprecation
            return BluetoothAdapter.getDefaultAdapter();
        }
        return null;
    }

    ActivityResultLauncher<Intent> onRequestPairing = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> selectBluetoothDevice());

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private final BroadcastReceiver pairingRequest = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.bluetooth.device.action.PAIRING_REQUEST")) {
                try {
                    BluetoothDevice device = intent
                            .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getName().toLowerCase(Locale.ROOT).startsWith("flask")) {
                        device.setPin((String.valueOf(intent.getIntExtra(
                                "android.bluetooth.device.extra.PAIRING_KEY", 0
                        ))).getBytes(CharsetCompat.UTF_8));
                        device.setPairingConfirmation(true);
                        flaskAddress = device.getAddress();
                        dismissFlaskDiscovery();
                        showConnectionNotice(true);
                        startFlaskService();
                    } else {
                        View bonded = getLayoutInflater().inflate(R.layout.bluetooth_device,
                                fragmentView, false);
                        bonded.setOnClickListener(view1 -> {
                            device.setPin((String.valueOf(intent.getIntExtra(
                                    "android.bluetooth.device.extra.PAIRING_KEY", 0
                            ))).getBytes(CharsetCompat.UTF_8));
                            device.setPairingConfirmation(true);
                            flaskAddress = device.getAddress();
                            dismissFlaskDiscovery();
                            showConnectionNotice(false);
                            startFlaskService();
                        });
                        setButtonText(bonded, device);
                    }
                } catch (Exception ex) {
                    Debug.Log(ex);
                    new Toasty(requireActivity()).Short(R.string.flask_failed);
                }
            }
        }
    };

    private void selectBluetoothDevice() {
        deviceList.removeAllViews();
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();

        for (BluetoothDevice device : pairedDevices) {
//            if (device.getName().toLowerCase(Locale.ROOT).startsWith("flask")) {
//                flaskAddress = device.getAddress();
//                dismissFlaskDiscovery();
//                showConnectionNotice(true);
//                startFlaskService();
//                break;
//            }
            View bonded = getLayoutInflater().inflate(R.layout.bluetooth_device,
                    fragmentView, false);
            bonded.setOnClickListener(view1 -> {
                flaskAddress = device.getAddress();
                dismissFlaskDiscovery();
                showConnectionNotice(false);
                startFlaskService();
            });
            setButtonText(bonded, device);
        }
        View paired = getLayoutInflater().inflate(R.layout.bluetooth_device,
                fragmentView, false);
        paired.setOnClickListener(view1 -> {
            try {
                onRequestPairing.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (ActivityNotFoundException anf) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    IntentFilter filter = new IntentFilter(
                            "android.bluetooth.device.action.PAIRING_REQUEST"
                    );
                    requireActivity().registerReceiver(pairingRequest, filter);
                    if (mBluetoothAdapter.isDiscovering())
                        mBluetoothAdapter.cancelDiscovery();
                    mBluetoothAdapter.startDiscovery();
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });
        ((TextView) paired.findViewById(R.id.bluetooth_text)).setText(R.string.bluetooth_pair);
        deviceList.addView(paired, 0);
    }

    private void setButtonText(View button, BluetoothDevice device) {
        ((TextView) button.findViewById(R.id.bluetooth_text)).setText(device.getName());
        deviceList.addView(button);
    }

    private void showConnectionNotice(boolean wasFound) {
        int message = wasFound ? R.string.flask_located : R.string.flask_selected;
        statusBar = new IconifiedSnackbar(requireActivity(), fragmentView).buildSnackbar(
                message, R.drawable.ic_bluup_flask_24dp, Snackbar.LENGTH_INDEFINITE
        );
        statusBar.show();
    }

    private void dismissConnectionNotice() {
        if (null != statusBar && statusBar.isShown()) statusBar.dismiss();
    }

    public void startFlaskService() {
        Intent service = new Intent(requireActivity(), BluetoothLeService.class);
        requireActivity().startService(service);
        requireActivity().bindService(service, mServerConn, Context.BIND_AUTO_CREATE);
    }

    public void stopFlaskService() {
        dismissConnectionNotice();
        flaskService.disconnect();
        requireActivity().unbindService(mServerConn);
        requireActivity().stopService(new Intent(requireActivity(), BluetoothLeService.class));
    }

    private void dismissFlaskDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                requireActivity().unregisterReceiver(pairingRequest);
            } catch (Exception ignored) { }
        }
        if (null != mBluetoothAdapter) {
            if (mBluetoothAdapter.isDiscovering())
                mBluetoothAdapter.cancelDiscovery();
            progressBar.setVisibility(View.INVISIBLE);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager)
                requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service
                : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BluetoothLeService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissFlaskDiscovery();
        if (isServiceRunning())
            stopFlaskService();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!hasCheckedPermissions) {
            hasCheckedPermissions = true;
            verifyPermissions();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        dismissFlaskDiscovery();
    }
}
