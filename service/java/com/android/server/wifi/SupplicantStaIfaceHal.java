/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.wifi;

import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hardware.wifi.supplicant.V1_0.WpsConfigMethods;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.wifi.util.NativeUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 */
public class SupplicantStaIfaceHal {
    private static final boolean DBG = false;
    private static final String TAG = "SupplicantStaIfaceHal";
    private static final String SERVICE_MANAGER_NAME = "manager";
    /**
     * Regex pattern for extracting the wps device type bytes.
     * Matches a strings like the following: "<categ>-<OUI>-<subcateg>";
     */
    private static final Pattern WPS_DEVICE_TYPE_PATTERN =
            Pattern.compile("^(\\d{1,2})-([0-9a-fA-F]{8})-(\\d{1,2})$");

    private IServiceManager mIServiceManager = null;
    // Supplicant HAL interface objects
    private ISupplicant mISupplicant;
    private ISupplicantStaIface mISupplicantStaIface;
    // Currently configured network in wpa_supplicant
    private SupplicantStaNetworkHal mCurrentNetwork;
    // Currently configured network's framework network Id.
    private int mFrameworkNetworkId;
    private final Object mLock = new Object();
    private final Context mContext;
    private final WifiMonitor mWifiMonitor;

    public SupplicantStaIfaceHal(Context context, WifiMonitor monitor) {
        mContext = context;
        mWifiMonitor = monitor;
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        if (DBG) Log.i(TAG, "Registering ISupplicant service ready callback.");
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!mIServiceManager.linkToDeath(cookie -> {
                    Log.wtf(TAG, "IServiceManager died: cookie=" + cookie);
                    synchronized (mLock) {
                        supplicantServiceDiedHandler();
                        mIServiceManager = null; // Will need to register a new ServiceNotification
                    }
                }, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
                IServiceNotification serviceNotificationCb = new IServiceNotification.Stub() {
                    public void onRegistration(String fqName, String name, boolean preexisting) {
                        synchronized (mLock) {
                            if (DBG) {
                                Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                                        + ", " + name + " preexisting=" + preexisting);
                            }
                            if (!initSupplicantService() || !initSupplicantStaIface()) {
                                Log.e(TAG, "initalizing ISupplicantIfaces failed.");
                                supplicantServiceDiedHandler();
                            } else {
                                Log.i(TAG, "Completed initialization of ISupplicant interfaces.");
                            }
                        }
                    }
                };
                /* TODO(b/33639391) : Use the new ISupplicant.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(ISupplicant.kInterfaceName,
                        "", serviceNotificationCb)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for ISupplicant service: "
                        + e);
                supplicantServiceDiedHandler();
            }
            return true;
        }
    }

    private boolean initSupplicantService() {
        synchronized (mLock) {
            try {
                mISupplicant = getSupplicantMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            }
            if (mISupplicant == null) {
                Log.e(TAG, "Got null ISupplicant service. Stopping supplicant HIDL startup");
                return false;
            }
        }
        return true;
    }

    private boolean initSupplicantStaIface() {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
            try {
                mISupplicant.listInterfaces((SupplicantStatus status,
                        ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (status.code != SupplicantStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                return false;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant ifaces. Stopping supplicant HIDL startup.");
                return false;
            }
            Mutable<ISupplicantIface> supplicantIface = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                if (status.code != SupplicantStatusCode.SUCCESS) {
                                    Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                    return;
                                }
                                supplicantIface.value = iface;
                            });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        return false;
                    }
                    break;
                }
            }
            if (supplicantIface.value == null) {
                Log.e(TAG, "initSupplicantStaIface got null iface");
                return false;
            }
            mISupplicantStaIface = getStaIfaceMockable(supplicantIface.value);
            return true;
        }
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
        }
    }

    /**
     * Signals whether Initialization completed successfully. Only necessary for testing, is not
     * needed to guard calls etc.
     */
    public boolean isInitializationComplete() {
        return mISupplicantStaIface != null;
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        return IServiceManager.getService(SERVICE_MANAGER_NAME);
    }

    protected ISupplicant getSupplicantMockable() throws RemoteException {
        return ISupplicant.getService();
    }

    protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
        return ISupplicantStaIface.asInterface(iface.asBinder());
    }

    /**
     * Add a network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return SupplicantStaNetwork of the added network in wpa_supplicant.
     */
    private SupplicantStaNetworkHal addNetwork(WifiConfiguration config) {
        logi("addSupplicantStaNetwork via HIDL");
        if (config == null) {
            loge("Cannot add NULL network!");
            return null;
        }
        SupplicantStaNetworkHal network = addNetwork();
        if (network == null) {
            loge("Failed to add a network!");
            return null;
        }
        if (network.saveWifiConfiguration(config)) {
            return network;
        } else {
            loge("Failed to save variables for: " + config.configKey());
            return null;
        }
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Triggers disconnect command to wpa_supplicant (if |shouldDisconnect| is true).
     * 2. Remove any existing network in wpa_supplicant.
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @param shouldDisconnect whether to trigger a disconnection or not.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(WifiConfiguration config, boolean shouldDisconnect) {
        mFrameworkNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mCurrentNetwork = null;
        logd("connectToNetwork " + config.configKey()
                + " (shouldDisconnect " + shouldDisconnect + ")");
        if (shouldDisconnect && !disconnect()) {
            loge("Failed to trigger disconnect");
            return false;
        }
        if (!removeAllNetworks()) {
            loge("Failed to remove existing networks");
            return false;
        }
        mCurrentNetwork = addNetwork(config);
        if (mCurrentNetwork == null) {
            loge("Failed to add/save network configuration: " + config.configKey());
            return false;
        }
        if (!mCurrentNetwork.select()) {
            loge("Failed to select network configuration: " + config.configKey());
            return false;
        }
        mFrameworkNetworkId = config.networkId;
        return true;
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 2. Set the new bssid for the network in wpa_supplicant.
     * 3. Trigger reassociate command to wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration config) {
        if (mFrameworkNetworkId != config.networkId || mCurrentNetwork == null) {
            Log.w(TAG, "Cannot roam to a different network, initiate new connection. "
                    + "Current network ID: " + mFrameworkNetworkId);
            return connectToNetwork(config, false);
        }
        String bssid = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
        logd("roamToNetwork" + config.configKey() + " (bssid " + bssid + ")");
        if (!mCurrentNetwork.setBssid(bssid)) {
            loge("Failed to set new bssid on network: " + config.configKey());
            return false;
        }
        if (!reassociate()) {
            loge("Failed to trigger reassociate");
            return false;
        }
        return true;
    }

    /**
     * Load all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return true if succeeds, false otherwise.
     */
    public boolean loadNetworks(Map<String, WifiConfiguration> configs,
                                SparseArray<Map<String, String>> networkExtras) {
        List<Integer> networkIds = listNetworks();
        if (networkIds == null) {
            Log.e(TAG, "Failed to list networks");
            return false;
        }
        for (Integer networkId : networkIds) {
            SupplicantStaNetworkHal network = getNetwork(networkId);
            if (network == null) {
                Log.e(TAG, "Failed to get network with ID: " + networkId);
                return false;
            }
            WifiConfiguration config = new WifiConfiguration();
            Map<String, String> networkExtra = new HashMap<>();
            if (!network.loadWifiConfiguration(config, networkExtra)) {
                Log.e(TAG, "Failed to load wifi configuration for network with ID: " + networkId);
                return false;
            }
            // Set the default IP assignments.
            config.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
            config.setProxySettings(IpConfiguration.ProxySettings.NONE);

            networkExtras.put(networkId, networkExtra);
            String configKey = networkExtra.get(SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY);
            final WifiConfiguration duplicateConfig = configs.put(configKey, config);
            if (duplicateConfig != null) {
                // The network is already known. Overwrite the duplicate entry.
                Log.i(TAG, "Replacing duplicate network: " + duplicateConfig.networkId);
                removeNetwork(duplicateConfig.networkId);
                networkExtras.remove(duplicateConfig.networkId);
            }
        }
        return true;
    }

    /**
     * Remove all networks from supplicant
     */
    public boolean removeAllNetworks() {
        synchronized (mLock) {
            ArrayList<Integer> networks = listNetworks();
            if (networks == null) {
                Log.e(TAG, "removeAllNetworks failed, got null networks");
                return false;
            }
            for (int id : networks) {
                if (!removeNetwork(id)) {
                    Log.e(TAG, "removeAllNetworks failed to remove network: " + id);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Set the currently configured network's bssid.
     *
     * @param bssidStr Bssid to set in the form of "XX:XX:XX:XX:XX:XX"
     * @return true if succeeds, false otherwise.
     */
    public boolean setCurrentNetworkBssid(String bssidStr) {
        if (mCurrentNetwork == null) return false;
        return mCurrentNetwork.setBssid(bssidStr);
    }

    /**
     * Get the currently configured network's WPS NFC token.
     *
     * @return Hex string corresponding to the WPS NFC token.
     */
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        if (mCurrentNetwork == null) return null;
        return mCurrentNetwork.getWpsNfcConfigurationToken();
    }

    /**
     * Gets the interface name.
     *
     * @return returns the name of Iface or null if the call fails
     */
    private String getName() {
        synchronized (mLock) {
            final String methodStr = "getName";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            final Mutable<String> gotName = new Mutable<>();
            try {
                mISupplicantStaIface.getName((SupplicantStatus status, String name) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        gotName.value = name;

                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            return gotName.value;
        }
    }

    /**
     * Adds a new network.
     *
     * @return The ISupplicantNetwork object for the new network, or null if the call fails
     */
    private SupplicantStaNetworkHal addNetwork() {
        synchronized (mLock) {
            final String methodStr = "addNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<ISupplicantNetwork> newNetwork = new Mutable<>();
            try {
                mISupplicantStaIface.addNetwork((SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        newNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (newNetwork.value != null) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(newNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * Remove network from supplicant with network Id
     *
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean removeNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "removeNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeNetwork(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Use this to mock the creation of SupplicantStaNetworkHal instance.
     *
     * @param iSupplicantStaNetwork ISupplicantStaNetwork instance retrieved from HIDL.
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    protected SupplicantStaNetworkHal getStaNetworkMockable(
            ISupplicantStaNetwork iSupplicantStaNetwork) {
        return new SupplicantStaNetworkHal(iSupplicantStaNetwork, mContext, mWifiMonitor);
    }

    /**
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    private SupplicantStaNetworkHal getNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "getNetwork";
            if (DBG) Log.i(TAG, methodStr);
            Mutable<ISupplicantNetwork> gotNetwork = new Mutable<>();
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.getNetwork(id, (SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        gotNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (gotNetwork.value != null) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(gotNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * @return a list of SupplicantNetworkID ints for all networks controlled by supplicant, returns
     * null if the call fails
     */
    private java.util.ArrayList<Integer> listNetworks() {
        synchronized (mLock) {
            final String methodStr = "listNetworks";
            if (DBG) Log.i(TAG, methodStr);
            Mutable<ArrayList<Integer>> networkIdList = new Mutable<>();
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.listNetworks((SupplicantStatus status,
                        java.util.ArrayList<Integer> networkIds) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        networkIdList.value = networkIds;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            return networkIdList.value;
        }
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsDeviceName(String name) {
        synchronized (mLock) {
            final String methodStr = "setWpsDeviceName";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsDeviceName(name);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS device type.
     *
     * @param typeStr Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsDeviceType(String typeStr) {
        Matcher match = WPS_DEVICE_TYPE_PATTERN.matcher(typeStr);
        if (!match.find() || match.groupCount() != 3) {
            Log.e(TAG, "Malformed WPS device type " + typeStr);
            return false;
        }
        short categ = Short.parseShort(match.group(1));
        byte[] oui = NativeUtil.hexStringToByteArray(match.group(2));
        short subCateg = Short.parseShort(match.group(3));

        byte[] bytes = new byte[8];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        byteBuffer.putShort(categ);
        byteBuffer.put(oui);
        byteBuffer.putShort(subCateg);
        return setWpsDeviceType(bytes);
    }

    private boolean setWpsDeviceType(byte[/* 8 */] type) {
        synchronized (mLock) {
            final String methodStr = "setWpsDeviceType";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsDeviceType(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS manufacturer.
     *
     * @param manufacturer String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsManufacturer(String manufacturer) {
        synchronized (mLock) {
            final String methodStr = "setWpsManufacturer";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsManufacturer(manufacturer);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS model name.
     *
     * @param modelName String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsModelName(String modelName) {
        synchronized (mLock) {
            final String methodStr = "setWpsModelName";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsModelName(modelName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS model number.
     *
     * @param modelNumber String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsModelNumber(String modelNumber) {
        synchronized (mLock) {
            final String methodStr = "setWpsModelNumber";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsModelNumber(modelNumber);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS serial number.
     *
     * @param serialNumber String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsSerialNumber(String serialNumber) {
        synchronized (mLock) {
            final String methodStr = "setWpsSerialNumber";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsSerialNumber(serialNumber);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set WPS config methods
     *
     * @param configMethodsStr List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsConfigMethods(String configMethodsStr) {
        short configMethodsMask = 0;
        String[] configMethodsStrArr = configMethodsStr.split("\\s+");
        for (int i = 0; i < configMethodsStrArr.length; i++) {
            configMethodsMask |= stringToWpsConfigMethod(configMethodsStrArr[i]);
        }
        return setWpsConfigMethods(configMethodsMask);
    }

    private boolean setWpsConfigMethods(short configMethods) {
        synchronized (mLock) {
            final String methodStr = "setWpsConfigMethods";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsConfigMethods(configMethods);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate() {
        synchronized (mLock) {
            final String methodStr = "reassociate";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reassociate();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect() {
        synchronized (mLock) {
            final String methodStr = "reconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect() {
        synchronized (mLock) {
            final String methodStr = "disconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.disconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Enable or disable power save mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setPowerSave(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setPowerSave";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setPowerSave(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS discover with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsDiscover(String macAddress) {
        return initiateTdlsDiscover(NativeUtil.macAddressToByteArray(macAddress));
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsDiscover(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsDiscover";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsDiscover(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS setup with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsSetup(String macAddress) {
        return initiateTdlsSetup(NativeUtil.macAddressToByteArray(macAddress));
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsSetup(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsSetup";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsSetup(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS teardown with the specified AP.
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsTeardown(String macAddress) {
        return initiateTdlsTeardown(NativeUtil.macAddressToByteArray(macAddress));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsTeardown(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsTeardown";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsTeardown(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP elements |elements| from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param infoElements ANQP elements to be queried. Refer to ISupplicantStaIface.AnqpInfoId.
     * @param hs20SubTypes HS subtypes to be queried. Refer to ISupplicantStaIface.Hs20AnqpSubTypes.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateAnqpQuery(String bssid, ArrayList<Short> infoElements,
                                     ArrayList<Integer> hs20SubTypes) {
        return initiateAnqpQuery(
                NativeUtil.macAddressToByteArray(bssid), infoElements, hs20SubTypes);
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateAnqpQuery(byte[/* 6 */] macAddress,
            java.util.ArrayList<Short> infoElements, java.util.ArrayList<Integer> subTypes) {
        synchronized (mLock) {
            final String methodStr = "initiateAnqpQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateAnqpQuery(macAddress,
                        infoElements, subTypes);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP ICON from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param fileName Name of the file to request.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateHs20IconQuery(String bssid, String fileName) {
        return initiateHs20IconQuery(NativeUtil.macAddressToByteArray(bssid), fileName);
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateHs20IconQuery(byte[/* 6 */] macAddress, String fileName) {
        synchronized (mLock) {
            final String methodStr = "initiateHs20IconQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateHs20IconQuery(macAddress,
                        fileName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress() {
        synchronized (mLock) {
            final String methodStr = "getMacAddress";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<String> gotMac = new Mutable<>();
            try {
                mISupplicantStaIface.getMacAddress((SupplicantStatus status,
                        byte[/* 6 */] macAddr) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        gotMac.value = NativeUtil.macAddressFromByteArray(macAddr);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            return gotMac.value;
        }
    }

    /**
     * Start using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startRxFilter() {
        synchronized (mLock) {
            final String methodStr = "startRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Stop using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean stopRxFilter() {
        synchronized (mLock) {
            final String methodStr = "stopRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.stopRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    public static final byte RX_FILTER_TYPE_V4_MULTICAST =
            ISupplicantStaIface.RxFilterType.V6_MULTICAST;
    public static final byte RX_FILTER_TYPE_V6_MULTICAST =
            ISupplicantStaIface.RxFilterType.V6_MULTICAST;
    /**
     * Add an RX filter.
     *
     * @param type one of {@link #RX_FILTER_TYPE_V4_MULTICAST} or
     *        {@link #RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean addRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "addRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.addRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Remove an RX filter.
     *
     * @param type one of {@link #RX_FILTER_TYPE_V4_MULTICAST} or
     *        {@link #RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean removeRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "removeRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    public static final byte BT_COEX_MODE_ENABLED = ISupplicantStaIface.BtCoexistenceMode.ENABLED;
    public static final byte BT_COEX_MODE_DISABLED = ISupplicantStaIface.BtCoexistenceMode.DISABLED;
    public static final byte BT_COEX_MODE_SENSE = ISupplicantStaIface.BtCoexistenceMode.SENSE;
    /**
     * Set Bt co existense mode.
     *
     * @param mode one of the above {@link #BT_COEX_MODE_ENABLED}, {@link #BT_COEX_MODE_DISABLED}
     *             or {@link #BT_COEX_MODE_SENSE} values.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceMode(byte mode) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceMode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setBtCoexistenceMode(mode);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /** Enable or disable BT coexistence mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceScanModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceScanModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaIface.setBtCoexistenceScanModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param enable true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSuspendModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setSuspendModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setSuspendModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set country code.
     *
     * @param codeStr 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(String codeStr) {
        return setCountryCode(NativeUtil.stringToByteArray(codeStr));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean setCountryCode(byte[/* 2 */] code) {
        synchronized (mLock) {
            final String methodStr = "setCountryCode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setCountryCode(code);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Start WPS pin registrar operation with the specified peer and pin.
     *
     * @param bssidStr BSSID of the peer.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsRegistrar(String bssidStr, String pin) {
        return startWpsRegistrar(NativeUtil.macAddressToByteArray(bssidStr), pin);
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean startWpsRegistrar(byte[/* 6 */] bssid, String pin) {
        synchronized (mLock) {
            final String methodStr = "startWpsRegistrar";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsRegistrar(bssid, pin);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssidStr BSSID of the peer.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPbc(String bssidStr) {
        return startWpsPbc(NativeUtil.macAddressToByteArray(bssidStr));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean startWpsPbc(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "startWpsPbc";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsPbc(bssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Start WPS pin keypad operation with the specified pin.
     *
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPinKeypad(String pin) {
        synchronized (mLock) {
            final String methodStr = "startWpsPinKeypad";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsPinKeypad(pin);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssidStr BSSID of the peer.
     * @return new pin generated on success, null otherwise.
     */
    public String startWpsPinDisplay(String bssidStr) {
        return startWpsPinDisplay(NativeUtil.macAddressToByteArray(bssidStr));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private String startWpsPinDisplay(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "startWpsPinDisplay";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            final Mutable<String> gotPin = new Mutable<>();
            try {
                mISupplicantStaIface.startWpsPinDisplay(bssid,
                        (SupplicantStatus status, String pin) -> {
                            if (checkStatusAndLogFailure(status, methodStr)) {
                                gotPin.value = pin;
                            }
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
            }
            return gotPin.value;
        }
    }

    /**
     * Cancels any ongoing WPS requests.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean cancelWps() {
        synchronized (mLock) {
            final String methodStr = "cancelWps";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.cancelWps();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Sets whether to use external sim for SIM/USIM processing.
     *
     * @param useExternalSim true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setExternalSim(boolean useExternalSim) {
        synchronized (mLock) {
            final String methodStr = "setExternalSim";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setExternalSim(useExternalSim);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Returns false if SupplicantStaIface is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantStaIfaceAndLogFailure(final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (mISupplicantStaIface == null) {
            Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaIface is null");
            return false;
        }
        return true;
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private static boolean checkStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (status.code != SupplicantStatusCode.SUCCESS) {
            Log.e(TAG, methodStr + " failed: " + supplicantStatusCodeToString(status.code) + ", "
                    + status.debugMessage);
            return false;
        }
        return true;
    }

    /**
     * Converts SupplicantStatus code values to strings for debug logging
     * TODO(b/34811152) Remove this, or make it more break resistance
     */
    public static String supplicantStatusCodeToString(int code) {
        switch (code) {
            case 0:
                return "SUCCESS";
            case 1:
                return "FAILURE_UNKNOWN";
            case 2:
                return "FAILURE_ARGS_INVALID";
            case 3:
                return "FAILURE_IFACE_INVALID";
            case 4:
                return "FAILURE_IFACE_UNKNOWN";
            case 5:
                return "FAILURE_IFACE_EXISTS";
            case 6:
                return "FAILURE_IFACE_DISABLED";
            case 7:
                return "FAILURE_IFACE_NOT_DISCONNECTED";
            case 8:
                return "FAILURE_NETWORK_INVALID";
            case 9:
                return "FAILURE_NETWORK_UNKNOWN";
            default:
                return "??? UNKNOWN_CODE";
        }
    }


    /**
     * Converts the Wps config method string to the equivalent enum value.
     */
    private static short stringToWpsConfigMethod(String configMethod) {
        switch (configMethod) {
            case "usba":
                return WpsConfigMethods.USBA;
            case "ethernet":
                return WpsConfigMethods.ETHERNET;
            case "label":
                return WpsConfigMethods.LABEL;
            case "display":
                return WpsConfigMethods.DISPLAY;
            case "int_nfc_token":
                return WpsConfigMethods.INT_NFC_TOKEN;
            case "ext_nfc_token":
                return WpsConfigMethods.EXT_NFC_TOKEN;
            case "nfc_interface":
                return WpsConfigMethods.NFC_INTERFACE;
            case "push_button":
                return WpsConfigMethods.PUSHBUTTON;
            case "keypad":
                return WpsConfigMethods.KEYPAD;
            case "virtual_push_button":
                return WpsConfigMethods.VIRT_PUSHBUTTON;
            case "physical_push_button":
                return WpsConfigMethods.PHY_PUSHBUTTON;
            case "p2ps":
                return WpsConfigMethods.P2PS;
            case "virtual_display":
                return WpsConfigMethods.VIRT_DISPLAY;
            case "physical_display":
                return WpsConfigMethods.PHY_DISPLAY;
            default:
                throw new IllegalArgumentException(
                        "Invalid WPS config method: " + configMethod);
        }
    }

    private static class Mutable<E> {
        public E value;

        Mutable() {
            value = null;
        }

        Mutable(E value) {
            this.value = value;
        }
    }

    private void logd(String s) {
        Log.d(TAG, s);
    }

    private void logi(String s) {
        Log.i(TAG, s);
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}
