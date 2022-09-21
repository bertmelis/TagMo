package com.hiddenramblings.tagmo.eightbit.security;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.hiddenramblings.tagmo.eightbit.io.Debug;

public class ProviderAdapter {

    public ProviderAdapter(Activity activity, ProviderInstallListener listener) {
        if (Debug.isOlder(Build.VERSION_CODES.M)) {
            ProviderInstaller.installIfNeededAsync(activity, new ProviderInstaller.ProviderInstallListener() {
                @Override
                public void onProviderInstalled() {
                    if (null != listener) listener.onProviderInstalled();
                }

                @Override
                public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                    GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
                    if (availability.isUserResolvableError(errorCode)) {
                        try {
                            availability.showErrorDialogFragment(
                                    activity, errorCode, 7000, dialog -> {
                                if (null != listener) listener.onProviderInstallFailed();
                            });
                        } catch (IllegalArgumentException ex) {
                            if (null != listener) listener.onProviderInstallException();
                        }
                    } else {
                        if (null != listener) listener.onProviderInstallFailed();
                    }
                }
            });
        } else {
            if (null != listener) listener.onProviderInstalled();
        }
    }

    public interface ProviderInstallListener {
        void onProviderInstalled();
        void onProviderInstallException();
        void onProviderInstallFailed();
    }
}