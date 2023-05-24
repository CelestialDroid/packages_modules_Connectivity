/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.nsd.NsdManager.MDNS_DISCOVERY_MANAGER_EVENT;
import static android.net.nsd.NsdManager.MDNS_SERVICE_EVENT;
import static android.net.nsd.NsdManager.RESOLVE_SERVICE_SUCCEEDED;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;
import static com.android.modules.utils.build.SdkLevel.isAtLeastU;
import static com.android.networkstack.apishim.ConstantsShim.REGISTER_NSD_OFFLOAD_ENGINE;
import static com.android.server.connectivity.mdns.MdnsAdvertiser.AdvertiserMetrics;
import static com.android.server.connectivity.mdns.MdnsConstants.NO_PACKET;
import static com.android.server.connectivity.mdns.MdnsRecord.MAX_LABEL_LENGTH;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.mdns.aidl.DiscoveryInfo;
import android.net.mdns.aidl.GetAddressInfo;
import android.net.mdns.aidl.IMDnsEventListener;
import android.net.mdns.aidl.RegistrationInfo;
import android.net.mdns.aidl.ResolutionInfo;
import android.net.nsd.INsdManager;
import android.net.nsd.INsdManagerCallback;
import android.net.nsd.INsdServiceConnector;
import android.net.nsd.IOffloadEngine;
import android.net.nsd.MDnsManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.OffloadEngine;
import android.net.nsd.OffloadServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.metrics.NetworkNsdReportedMetrics;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.InetAddressUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.ExecutorProvider;
import com.android.server.connectivity.mdns.MdnsAdvertiser;
import com.android.server.connectivity.mdns.MdnsDiscoveryManager;
import com.android.server.connectivity.mdns.MdnsFeatureFlags;
import com.android.server.connectivity.mdns.MdnsInterfaceSocket;
import com.android.server.connectivity.mdns.MdnsMultinetworkSocketClient;
import com.android.server.connectivity.mdns.MdnsSearchOptions;
import com.android.server.connectivity.mdns.MdnsServiceBrowserListener;
import com.android.server.connectivity.mdns.MdnsServiceInfo;
import com.android.server.connectivity.mdns.MdnsSocketProvider;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";
    /**
     * Enable discovery using the Java DiscoveryManager, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_DISCOVERY_MANAGER_VERSION = "mdns_discovery_manager_version";
    private static final String LOCAL_DOMAIN_NAME = "local";

    /**
     * Enable advertising using the Java MdnsAdvertiser, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_ADVERTISER_VERSION = "mdns_advertiser_version";

    /**
     * Comma-separated list of type:flag mappings indicating the flags to use to allowlist
     * discovery/advertising using MdnsDiscoveryManager / MdnsAdvertiser for a given type.
     *
     * For example _mytype._tcp.local and _othertype._tcp.local would be configured with:
     * _mytype._tcp:mytype,_othertype._tcp.local:othertype
     *
     * In which case the flags:
     * "mdns_discovery_manager_allowlist_mytype_version",
     * "mdns_advertiser_allowlist_mytype_version",
     * "mdns_discovery_manager_allowlist_othertype_version",
     * "mdns_advertiser_allowlist_othertype_version"
     * would be used to toggle MdnsDiscoveryManager / MdnsAdvertiser for each type. The flags will
     * be read with
     * {@link DeviceConfigUtils#isTetheringFeatureEnabled}
     *
     * @see #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ALLOWLIST_FLAG_SUFFIX
     */
    private static final String MDNS_TYPE_ALLOWLIST_FLAGS = "mdns_type_allowlist_flags";

    private static final String MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX =
            "mdns_discovery_manager_allowlist_";
    private static final String MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX =
            "mdns_advertiser_allowlist_";
    private static final String MDNS_ALLOWLIST_FLAG_SUFFIX = "_version";

    @VisibleForTesting
    static final String MDNS_CONFIG_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF =
            "mdns_config_running_app_active_importance_cutoff";
    @VisibleForTesting
    static final int DEFAULT_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    private final int mRunningAppActiveImportanceCutoff;

    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long CLEANUP_DELAY_MS = 10000;
    private static final int IFACE_IDX_ANY = 0;
    private static final int MAX_SERVICES_COUNT_METRIC_PER_CLIENT = 100;
    @VisibleForTesting
    static final int NO_TRANSACTION = -1;
    private static final int NO_SENT_QUERY_COUNT = 0;
    private static final int DISCOVERY_QUERY_SENT_CALLBACK = 1000;
    private static final SharedLog LOGGER = new SharedLog("serviceDiscovery");

    private final Context mContext;
    private final NsdStateMachine mNsdStateMachine;
    private final MDnsManager mMDnsManager;
    private final MDnsEventCallback mMDnsEventCallback;
    @NonNull
    private final Dependencies mDeps;
    @NonNull
    private final MdnsMultinetworkSocketClient mMdnsSocketClient;
    @NonNull
    private final MdnsDiscoveryManager mMdnsDiscoveryManager;
    @NonNull
    private final MdnsSocketProvider mMdnsSocketProvider;
    @NonNull
    private final MdnsAdvertiser mAdvertiser;
    @NonNull
    private final Clock mClock;
    private final SharedLog mServiceLogs = LOGGER.forSubComponent(TAG);
    // WARNING : Accessing these values in any thread is not safe, it must only be changed in the
    // state machine thread. If change this outside state machine, it will need to introduce
    // synchronization.
    private boolean mIsDaemonStarted = false;
    private boolean mIsMonitoringSocketsStarted = false;

    /**
     * Clients receiving asynchronous messages
     */
    private final HashMap<NsdServiceConnector, ClientInfo> mClients = new HashMap<>();

    /* A map from transaction(unique) id to client info */
    private final SparseArray<ClientInfo> mTransactionIdToClientInfoMap = new SparseArray<>();

    // Note this is not final to avoid depending on the Wi-Fi service starting before NsdService
    @Nullable
    private WifiManager.MulticastLock mHeldMulticastLock;
    // Fulfilled network requests that require the Wi-Fi lock: key is the obtained Network
    // (non-null), value is the requested Network (nullable)
    @NonNull
    private final ArraySet<Network> mWifiLockRequiredNetworks = new ArraySet<>();
    @NonNull
    private final ArraySet<Integer> mRunningAppActiveUids = new ArraySet<>();

    private final long mCleanupDelayMs;

    private static final int INVALID_ID = 0;
    private int mUniqueId = 1;
    // The count of the connected legacy clients.
    private int mLegacyClientCount = 0;
    // The number of client that ever connected.
    private int mClientNumberId = 1;

    private final RemoteCallbackList<IOffloadEngine> mOffloadEngines =
            new RemoteCallbackList<>();

    private static class OffloadEngineInfo {
        @NonNull final String mInterfaceName;
        final long mOffloadCapabilities;
        final long mOffloadType;
        @NonNull final IOffloadEngine mOffloadEngine;

        OffloadEngineInfo(@NonNull IOffloadEngine offloadEngine,
                @NonNull String interfaceName, long capabilities, long offloadType) {
            this.mOffloadEngine = offloadEngine;
            this.mInterfaceName = interfaceName;
            this.mOffloadCapabilities = capabilities;
            this.mOffloadType = offloadType;
        }
    }

    @VisibleForTesting
    static class MdnsListener implements MdnsServiceBrowserListener {
        protected final int mClientRequestId;
        protected final int mTransactionId;
        @NonNull
        protected final NsdServiceInfo mReqServiceInfo;
        @NonNull
        protected final String mListenedServiceType;

        MdnsListener(int clientRequestId, int transactionId, @NonNull NsdServiceInfo reqServiceInfo,
                @NonNull String listenedServiceType) {
            mClientRequestId = clientRequestId;
            mTransactionId = transactionId;
            mReqServiceInfo = reqServiceInfo;
            mListenedServiceType = listenedServiceType;
        }

        @NonNull
        public String getListenedServiceType() {
            return mListenedServiceType;
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) { }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) { }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onSearchStoppedWithError(int error) { }

        @Override
        public void onSearchFailedToStart() { }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) { }

        @Override
        public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) { }
    }

    private class DiscoveryListener extends MdnsListener {

        DiscoveryListener(int clientRequestId, int transactionId,
                @NonNull NsdServiceInfo reqServiceInfo, @NonNull String listenServiceType) {
            super(clientRequestId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_FOUND,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_LOST,
                    new MdnsEvent(mClientRequestId, serviceInfo));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }
    }

    private class ResolutionListener extends MdnsListener {

        ResolutionListener(int clientRequestId, int transactionId,
                @NonNull NsdServiceInfo reqServiceInfo, @NonNull String listenServiceType) {
            super(clientRequestId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceFound(MdnsServiceInfo serviceInfo, boolean isServiceFromCache) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.RESOLVE_SERVICE_SUCCEEDED,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }
    }

    private class ServiceInfoListener extends MdnsListener {

        ServiceInfoListener(int clientRequestId, int transactionId,
                @NonNull NsdServiceInfo reqServiceInfo, @NonNull String listenServiceType) {
            super(clientRequestId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientRequestId, serviceInfo));
        }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED_LOST,
                    new MdnsEvent(mClientRequestId, serviceInfo));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }
    }

    private class SocketRequestMonitor implements MdnsSocketProvider.SocketRequestMonitor {
        @Override
        public void onSocketRequestFulfilled(@Nullable Network socketNetwork,
                @NonNull MdnsInterfaceSocket socket, @NonNull int[] transports) {
            // The network may be null for Wi-Fi SoftAp interfaces (tethering), but there is no APF
            // filtering on such interfaces, so taking the multicast lock is not necessary to
            // disable APF filtering of multicast.
            if (socketNetwork == null
                    || !CollectionUtils.contains(transports, TRANSPORT_WIFI)
                    || CollectionUtils.contains(transports, TRANSPORT_VPN)) {
                return;
            }

            if (mWifiLockRequiredNetworks.add(socketNetwork)) {
                updateMulticastLock();
            }
        }

        @Override
        public void onSocketDestroyed(@Nullable Network socketNetwork,
                @NonNull MdnsInterfaceSocket socket) {
            if (mWifiLockRequiredNetworks.remove(socketNetwork)) {
                updateMulticastLock();
            }
        }
    }

    private class UidImportanceListener implements ActivityManager.OnUidImportanceListener {
        private final Handler mHandler;

        private UidImportanceListener(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            mHandler.post(() -> handleUidImportanceChanged(uid, importance));
        }
    }

    private void handleUidImportanceChanged(int uid, int importance) {
        // Lower importance values are more "important"
        final boolean modified = importance <= mRunningAppActiveImportanceCutoff
                ? mRunningAppActiveUids.add(uid)
                : mRunningAppActiveUids.remove(uid);
        if (modified) {
            updateMulticastLock();
        }
    }

    /**
     * Take or release the lock based on updated internal state.
     *
     * This determines whether the lock needs to be held based on
     * {@link #mWifiLockRequiredNetworks}, {@link #mRunningAppActiveUids} and
     * {@link ClientInfo#mClientRequests}, so it must be called after any of the these have been
     * updated.
     */
    private void updateMulticastLock() {
        final int needsLockUid = getMulticastLockNeededUid();
        if (needsLockUid >= 0 && mHeldMulticastLock == null) {
            final WifiManager wm = mContext.getSystemService(WifiManager.class);
            if (wm == null) {
                Log.wtf(TAG, "Got a TRANSPORT_WIFI network without WifiManager");
                return;
            }
            mHeldMulticastLock = wm.createMulticastLock(TAG);
            mHeldMulticastLock.acquire();
            mServiceLogs.log("Taking multicast lock for uid " + needsLockUid);
        } else if (needsLockUid < 0 && mHeldMulticastLock != null) {
            mHeldMulticastLock.release();
            mHeldMulticastLock = null;
            mServiceLogs.log("Released multicast lock");
        }
    }

    /**
     * @return The UID of an app requiring the multicast lock, or -1 if none.
     */
    private int getMulticastLockNeededUid() {
        if (mWifiLockRequiredNetworks.size() == 0) {
            // Return early if NSD is not active, or not on any relevant network
            return -1;
        }
        for (int i = 0; i < mTransactionIdToClientInfoMap.size(); i++) {
            final ClientInfo clientInfo = mTransactionIdToClientInfoMap.valueAt(i);
            if (!mRunningAppActiveUids.contains(clientInfo.mUid)) {
                // Ignore non-active UIDs
                continue;
            }

            if (clientInfo.hasAnyJavaBackendRequestForNetworks(mWifiLockRequiredNetworks)) {
                return clientInfo.mUid;
            }
        }
        return -1;
    }

    /**
     * Data class of mdns service callback information.
     */
    private static class MdnsEvent {
        final int mClientRequestId;
        @Nullable
        final MdnsServiceInfo mMdnsServiceInfo;
        final boolean mIsServiceFromCache;

        MdnsEvent(int clientRequestId) {
            this(clientRequestId, null /* mdnsServiceInfo */, false /* isServiceFromCache */);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo) {
            this(clientRequestId, mdnsServiceInfo, false /* isServiceFromCache */);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo,
                boolean isServiceFromCache) {
            mClientRequestId = clientRequestId;
            mMdnsServiceInfo = mdnsServiceInfo;
            mIsServiceFromCache = isServiceFromCache;
        }
    }

    private class NsdStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final EnabledState mEnabledState = new EnabledState();

        @Override
        protected String getWhatToString(int what) {
            return NsdManager.nameOf(what);
        }

        private void maybeStartDaemon() {
            if (mIsDaemonStarted) {
                if (DBG) Log.d(TAG, "Daemon is already started.");
                return;
            }
            mMDnsManager.registerEventListener(mMDnsEventCallback);
            mMDnsManager.startDaemon();
            mIsDaemonStarted = true;
            maybeScheduleStop();
            mServiceLogs.log("Start mdns_responder daemon");
        }

        private void maybeStopDaemon() {
            if (!mIsDaemonStarted) {
                if (DBG) Log.d(TAG, "Daemon has not been started.");
                return;
            }
            mMDnsManager.unregisterEventListener(mMDnsEventCallback);
            mMDnsManager.stopDaemon();
            mIsDaemonStarted = false;
            mServiceLogs.log("Stop mdns_responder daemon");
        }

        private boolean isAnyRequestActive() {
            return mTransactionIdToClientInfoMap.size() != 0;
        }

        private void scheduleStop() {
            sendMessageDelayed(NsdManager.DAEMON_CLEANUP, mCleanupDelayMs);
        }
        private void maybeScheduleStop() {
            // The native daemon should stay alive and can't be cleanup
            // if any legacy client connected.
            if (!isAnyRequestActive() && mLegacyClientCount == 0) {
                scheduleStop();
            }
        }

        private void cancelStop() {
            this.removeMessages(NsdManager.DAEMON_CLEANUP);
        }

        private void maybeStartMonitoringSockets() {
            if (mIsMonitoringSocketsStarted) {
                if (DBG) Log.d(TAG, "Socket monitoring is already started.");
                return;
            }

            mMdnsSocketProvider.startMonitoringSockets();
            mIsMonitoringSocketsStarted = true;
        }

        private void maybeStopMonitoringSocketsIfNoActiveRequest() {
            if (!mIsMonitoringSocketsStarted) return;
            if (isAnyRequestActive()) return;

            mMdnsSocketProvider.requestStopWhenInactive();
            mIsMonitoringSocketsStarted = false;
        }

        NsdStateMachine(String name, Handler handler) {
            super(name, handler);
            addState(mDefaultState);
                addState(mEnabledState, mDefaultState);
            State initialState = mEnabledState;
            setInitialState(initialState);
            setLogRecSize(25);
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo cInfo;
                final int clientRequestId = msg.arg2;
                switch (msg.what) {
                    case NsdManager.REGISTER_CLIENT:
                        final ConnectorArgs arg = (ConnectorArgs) msg.obj;
                        final INsdManagerCallback cb = arg.callback;
                        try {
                            cb.asBinder().linkToDeath(arg.connector, 0);
                            final String tag = "Client" + arg.uid + "-" + mClientNumberId++;
                            final NetworkNsdReportedMetrics metrics =
                                    mDeps.makeNetworkNsdReportedMetrics(
                                            !arg.useJavaBackend, (int) mClock.elapsedRealtime());
                            cInfo = new ClientInfo(cb, arg.uid, arg.useJavaBackend,
                                    mServiceLogs.forSubComponent(tag), metrics);
                            mClients.put(arg.connector, cInfo);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Client request id " + clientRequestId
                                    + " has already died");
                        }
                        break;
                    case NsdManager.UNREGISTER_CLIENT:
                        final NsdServiceConnector connector = (NsdServiceConnector) msg.obj;
                        cInfo = mClients.remove(connector);
                        if (cInfo != null) {
                            cInfo.expungeAllRequests();
                            if (cInfo.isPreSClient()) {
                                mLegacyClientCount -= 1;
                            }
                        }
                        maybeStopMonitoringSocketsIfNoActiveRequest();
                        maybeScheduleStop();
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onDiscoverServicesFailedImmediately(
                                    clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                       break;
                    case NsdManager.STOP_DISCOVERY:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onStopDiscoveryFailed(
                                    clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onRegisterServiceFailedImmediately(
                                    clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onUnregisterServiceFailed(
                                    clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onResolveServiceFailedImmediately(
                                    clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.STOP_RESOLUTION:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onStopResolutionFailed(
                                    clientRequestId, NsdManager.FAILURE_OPERATION_NOT_RUNNING);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE_CALLBACK:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onServiceInfoCallbackRegistrationFailed(
                                    clientRequestId, NsdManager.FAILURE_BAD_PARAMETERS);
                        }
                        break;
                    case NsdManager.DAEMON_CLEANUP:
                        maybeStopDaemon();
                        break;
                    // This event should be only sent by the legacy (target SDK < S) clients.
                    // Mark the sending client as legacy.
                    case NsdManager.DAEMON_STARTUP:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cancelStop();
                            cInfo.setPreSClient();
                            mLegacyClientCount += 1;
                            maybeStartDaemon();
                        }
                        break;
                    default:
                        Log.e(TAG, "Unhandled " + msg);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private ClientInfo getClientInfoForReply(Message msg) {
                final ListenerArgs args = (ListenerArgs) msg.obj;
                return mClients.get(args.connector);
            }
        }

        class EnabledState extends State {
            @Override
            public void enter() {
                sendNsdStateChangeBroadcast(true);
            }

            @Override
            public void exit() {
                // TODO: it is incorrect to stop the daemon without expunging all requests
                // and sending error callbacks to clients.
                scheduleStop();
            }

            private boolean requestLimitReached(ClientInfo clientInfo) {
                if (clientInfo.mClientRequests.size() >= ClientInfo.MAX_LIMIT) {
                    if (DBG) Log.d(TAG, "Exceeded max outstanding requests " + clientInfo);
                    return true;
                }
                return false;
            }

            private void storeLegacyRequestMap(int clientRequestId, int transactionId,
                    ClientInfo clientInfo, int what, long startTimeMs) {
                clientInfo.mClientRequests.put(clientRequestId,
                        new LegacyClientRequest(transactionId, what, startTimeMs));
                mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
                // Remove the cleanup event because here comes a new request.
                cancelStop();
            }

            private void storeAdvertiserRequestMap(int clientRequestId, int transactionId,
                    ClientInfo clientInfo, @Nullable Network requestedNetwork) {
                clientInfo.mClientRequests.put(clientRequestId, new AdvertiserClientRequest(
                        transactionId, requestedNetwork, mClock.elapsedRealtime()));
                mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
                updateMulticastLock();
            }

            private void removeRequestMap(
                    int clientRequestId, int transactionId, ClientInfo clientInfo) {
                final ClientRequest existing = clientInfo.mClientRequests.get(clientRequestId);
                if (existing == null) return;
                clientInfo.mClientRequests.remove(clientRequestId);
                mTransactionIdToClientInfoMap.remove(transactionId);

                if (existing instanceof LegacyClientRequest) {
                    maybeScheduleStop();
                } else {
                    maybeStopMonitoringSocketsIfNoActiveRequest();
                    updateMulticastLock();
                }
            }

            private void storeDiscoveryManagerRequestMap(int clientRequestId, int transactionId,
                    MdnsListener listener, ClientInfo clientInfo,
                    @Nullable Network requestedNetwork) {
                clientInfo.mClientRequests.put(clientRequestId, new DiscoveryManagerRequest(
                        transactionId, listener, requestedNetwork, mClock.elapsedRealtime()));
                mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
                updateMulticastLock();
            }

            /**
             * Truncate a service name to up to 63 UTF-8 bytes.
             *
             * See RFC6763 4.1.1: service instance names are UTF-8 and up to 63 bytes. Truncating
             * names used in registerService follows historical behavior (see mdnsresponder
             * handle_regservice_request).
             */
            @NonNull
            private String truncateServiceName(@NonNull String originalName) {
                return MdnsUtils.truncateServiceName(originalName, MAX_LABEL_LENGTH);
            }

            private void stopDiscoveryManagerRequest(ClientRequest request, int clientRequestId,
                    int transactionId, ClientInfo clientInfo) {
                clientInfo.unregisterMdnsListenerFromRequest(request);
                removeRequestMap(clientRequestId, transactionId, clientInfo);
            }

            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo clientInfo;
                final int transactionId;
                final int clientRequestId = msg.arg2;
                final ListenerArgs args;
                final OffloadEngineInfo offloadEngineInfo;
                switch (msg.what) {
                    case NsdManager.DISCOVER_SERVICES: {
                        if (DBG) Log.d(TAG, "Discover services");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in discovery");
                            break;
                        }

                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onDiscoverServicesFailedImmediately(
                                    clientRequestId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        transactionId = getUniqueId();
                        final Pair<String, String> typeAndSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeAndSubtype == null
                                ? null : typeAndSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                || mDeps.isMdnsDiscoveryManagerEnabled(mContext)
                                || useDiscoveryManagerForType(serviceType)) {
                            if (serviceType == null) {
                                clientInfo.onDiscoverServicesFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }

                            final String listenServiceType = serviceType + ".local";
                            maybeStartMonitoringSockets();
                            final MdnsListener listener = new DiscoveryListener(clientRequestId,
                                    transactionId, info, listenServiceType);
                            final MdnsSearchOptions.Builder optionsBuilder =
                                    MdnsSearchOptions.newBuilder()
                                            .setNetwork(info.getNetwork())
                                            .setRemoveExpiredService(true)
                                            .setIsPassiveMode(true);
                            if (typeAndSubtype.second != null) {
                                // The parsing ensures subtype starts with an underscore.
                                // MdnsSearchOptions expects the underscore to not be present.
                                optionsBuilder.addSubtype(typeAndSubtype.second.substring(1));
                            }
                            mMdnsDiscoveryManager.registerListener(
                                    listenServiceType, listener, optionsBuilder.build());
                            storeDiscoveryManagerRequestMap(clientRequestId, transactionId,
                                    listener, clientInfo, info.getNetwork());
                            clientInfo.onDiscoverServicesStarted(
                                    clientRequestId, info, transactionId);
                            clientInfo.log("Register a DiscoveryListener " + transactionId
                                    + " for service type:" + listenServiceType);
                        } else {
                            maybeStartDaemon();
                            if (discoverServices(transactionId, info)) {
                                if (DBG) {
                                    Log.d(TAG, "Discover " + msg.arg2 + " " + transactionId
                                            + info.getServiceType());
                                }
                                storeLegacyRequestMap(clientRequestId, transactionId, clientInfo,
                                        msg.what, mClock.elapsedRealtime());
                                clientInfo.onDiscoverServicesStarted(
                                        clientRequestId, info, transactionId);
                            } else {
                                stopServiceDiscovery(transactionId);
                                clientInfo.onDiscoverServicesFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.STOP_DISCOVERY: {
                        if (DBG) Log.d(TAG, "Stop service discovery");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in stop discovery");
                            break;
                        }

                        final ClientRequest request =
                                clientInfo.mClientRequests.get(clientRequestId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in STOP_DISCOVERY");
                            break;
                        }
                        transactionId = request.mTransactionId;
                        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
                        // point, so this needs to check the type of the original request to
                        // unregister instead of looking at the flag value.
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(
                                    request, clientRequestId, transactionId, clientInfo);
                            clientInfo.onStopDiscoverySucceeded(clientRequestId, request);
                            clientInfo.log("Unregister the DiscoveryListener " + transactionId);
                        } else {
                            removeRequestMap(clientRequestId, transactionId, clientInfo);
                            if (stopServiceDiscovery(transactionId)) {
                                clientInfo.onStopDiscoverySucceeded(clientRequestId, request);
                            } else {
                                clientInfo.onStopDiscoveryFailed(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.REGISTER_SERVICE: {
                        if (DBG) Log.d(TAG, "Register service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in registration");
                            break;
                        }

                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onRegisterServiceFailedImmediately(
                                    clientRequestId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        transactionId = getUniqueId();
                        final NsdServiceInfo serviceInfo = args.serviceInfo;
                        final String serviceType = serviceInfo.getServiceType();
                        final Pair<String, String> typeSubtype = parseTypeAndSubtype(serviceType);
                        final String registerServiceType = typeSubtype == null
                                ? null : typeSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                || mDeps.isMdnsAdvertiserEnabled(mContext)
                                || useAdvertiserForType(registerServiceType)) {
                            if (registerServiceType == null) {
                                Log.e(TAG, "Invalid service type: " + serviceType);
                                clientInfo.onRegisterServiceFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }
                            serviceInfo.setServiceType(registerServiceType);
                            serviceInfo.setServiceName(truncateServiceName(
                                    serviceInfo.getServiceName()));

                            maybeStartMonitoringSockets();
                            // TODO: pass in the subtype as well. Including the subtype in the
                            // service type would generate service instance names like
                            // Name._subtype._sub._type._tcp, which is incorrect
                            // (it should be Name._type._tcp).
                            mAdvertiser.addService(transactionId, serviceInfo, typeSubtype.second);
                            storeAdvertiserRequestMap(clientRequestId, transactionId, clientInfo,
                                    serviceInfo.getNetwork());
                        } else {
                            maybeStartDaemon();
                            if (registerService(transactionId, serviceInfo)) {
                                if (DBG) {
                                    Log.d(TAG, "Register " + clientRequestId
                                            + " " + transactionId);
                                }
                                storeLegacyRequestMap(clientRequestId, transactionId, clientInfo,
                                        msg.what, mClock.elapsedRealtime());
                                // Return success after mDns reports success
                            } else {
                                unregisterService(transactionId);
                                clientInfo.onRegisterServiceFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }

                        }
                        break;
                    }
                    case NsdManager.UNREGISTER_SERVICE: {
                        if (DBG) Log.d(TAG, "unregister service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in unregistration");
                            break;
                        }
                        final ClientRequest request =
                                clientInfo.mClientRequests.get(clientRequestId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE");
                            break;
                        }
                        transactionId = request.mTransactionId;
                        removeRequestMap(clientRequestId, transactionId, clientInfo);

                        // Note isMdnsAdvertiserEnabled may have changed to false at this point,
                        // so this needs to check the type of the original request to unregister
                        // instead of looking at the flag value.
                        final long stopTimeMs = mClock.elapsedRealtime();
                        if (request instanceof AdvertiserClientRequest) {
                            final AdvertiserMetrics metrics =
                                    mAdvertiser.getAdvertiserMetrics(transactionId);
                            mAdvertiser.removeService(transactionId);
                            clientInfo.onUnregisterServiceSucceeded(clientRequestId, transactionId,
                                    request.calculateRequestDurationMs(stopTimeMs), metrics);
                        } else {
                            if (unregisterService(transactionId)) {
                                clientInfo.onUnregisterServiceSucceeded(clientRequestId,
                                        transactionId,
                                        request.calculateRequestDurationMs(stopTimeMs),
                                        new AdvertiserMetrics(NO_PACKET /* repliedRequestsCount */,
                                                NO_PACKET /* sentPacketCount */,
                                                0 /* conflictDuringProbingCount */,
                                                0 /* conflictAfterProbingCount */));
                            } else {
                                clientInfo.onUnregisterServiceFailed(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.RESOLVE_SERVICE: {
                        if (DBG) Log.d(TAG, "Resolve service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in resolution");
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        transactionId = getUniqueId();
                        final Pair<String, String> typeSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeSubtype == null
                                ? null : typeSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                ||  mDeps.isMdnsDiscoveryManagerEnabled(mContext)
                                || useDiscoveryManagerForType(serviceType)) {
                            if (serviceType == null) {
                                clientInfo.onResolveServiceFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }
                            final String resolveServiceType = serviceType + ".local";

                            maybeStartMonitoringSockets();
                            final MdnsListener listener = new ResolutionListener(clientRequestId,
                                    transactionId, info, resolveServiceType);
                            final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                                    .setNetwork(info.getNetwork())
                                    .setIsPassiveMode(true)
                                    .setResolveInstanceName(info.getServiceName())
                                    .setRemoveExpiredService(true)
                                    .build();
                            mMdnsDiscoveryManager.registerListener(
                                    resolveServiceType, listener, options);
                            storeDiscoveryManagerRequestMap(clientRequestId, transactionId,
                                    listener, clientInfo, info.getNetwork());
                            clientInfo.log("Register a ResolutionListener " + transactionId
                                    + " for service type:" + resolveServiceType);
                        } else {
                            if (clientInfo.mResolvedService != null) {
                                clientInfo.onResolveServiceFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_ALREADY_ACTIVE);
                                break;
                            }

                            maybeStartDaemon();
                            if (resolveService(transactionId, info)) {
                                clientInfo.mResolvedService = new NsdServiceInfo();
                                storeLegacyRequestMap(clientRequestId, transactionId, clientInfo,
                                        msg.what, mClock.elapsedRealtime());
                            } else {
                                clientInfo.onResolveServiceFailedImmediately(
                                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.STOP_RESOLUTION: {
                        if (DBG) Log.d(TAG, "Stop service resolution");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in stop resolution");
                            break;
                        }

                        final ClientRequest request =
                                clientInfo.mClientRequests.get(clientRequestId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in STOP_RESOLUTION");
                            break;
                        }
                        transactionId = request.mTransactionId;
                        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
                        // point, so this needs to check the type of the original request to
                        // unregister instead of looking at the flag value.
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(
                                    request, clientRequestId, transactionId, clientInfo);
                            clientInfo.onStopResolutionSucceeded(clientRequestId, request);
                            clientInfo.log("Unregister the ResolutionListener " + transactionId);
                        } else {
                            removeRequestMap(clientRequestId, transactionId, clientInfo);
                            if (stopResolveService(transactionId)) {
                                clientInfo.onStopResolutionSucceeded(clientRequestId, request);
                            } else {
                                clientInfo.onStopResolutionFailed(
                                        clientRequestId, NsdManager.FAILURE_OPERATION_NOT_RUNNING);
                            }
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    }
                    case NsdManager.REGISTER_SERVICE_CALLBACK: {
                        if (DBG) Log.d(TAG, "Register a service callback");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in callback registration");
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        transactionId = getUniqueId();
                        final Pair<String, String> typeAndSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeAndSubtype == null
                                ? null : typeAndSubtype.first;
                        if (serviceType == null) {
                            clientInfo.onServiceInfoCallbackRegistrationFailed(clientRequestId,
                                    NsdManager.FAILURE_BAD_PARAMETERS);
                            break;
                        }
                        final String resolveServiceType = serviceType + ".local";

                        maybeStartMonitoringSockets();
                        final MdnsListener listener = new ServiceInfoListener(clientRequestId,
                                transactionId, info, resolveServiceType);
                        final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                                .setNetwork(info.getNetwork())
                                .setIsPassiveMode(true)
                                .setResolveInstanceName(info.getServiceName())
                                .setRemoveExpiredService(true)
                                .build();
                        mMdnsDiscoveryManager.registerListener(
                                resolveServiceType, listener, options);
                        storeDiscoveryManagerRequestMap(clientRequestId, transactionId, listener,
                                clientInfo, info.getNetwork());
                        clientInfo.onServiceInfoCallbackRegistered(transactionId);
                        clientInfo.log("Register a ServiceInfoListener " + transactionId
                                + " for service type:" + resolveServiceType);
                        break;
                    }
                    case NsdManager.UNREGISTER_SERVICE_CALLBACK: {
                        if (DBG) Log.d(TAG, "Unregister a service callback");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in callback unregistration");
                            break;
                        }

                        final ClientRequest request =
                                clientInfo.mClientRequests.get(clientRequestId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE_CALLBACK");
                            break;
                        }
                        transactionId = request.mTransactionId;
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(
                                    request, clientRequestId, transactionId, clientInfo);
                            clientInfo.onServiceInfoCallbackUnregistered(clientRequestId, request);
                            clientInfo.log("Unregister the ServiceInfoListener " + transactionId);
                        } else {
                            loge("Unregister failed with non-DiscoveryManagerRequest.");
                        }
                        break;
                    }
                    case MDNS_SERVICE_EVENT:
                        if (!handleMDnsServiceEvent(msg.arg1, msg.arg2, msg.obj)) {
                            return NOT_HANDLED;
                        }
                        break;
                    case MDNS_DISCOVERY_MANAGER_EVENT:
                        if (!handleMdnsDiscoveryManagerEvent(msg.arg1, msg.arg2, msg.obj)) {
                            return NOT_HANDLED;
                        }
                        break;
                    case NsdManager.REGISTER_OFFLOAD_ENGINE:
                        offloadEngineInfo = (OffloadEngineInfo) msg.obj;
                        // TODO: Limits the number of registrations created by a given class.
                        mOffloadEngines.register(offloadEngineInfo.mOffloadEngine,
                                offloadEngineInfo);
                        sendAllOffloadServiceInfos(offloadEngineInfo);
                        break;
                    case NsdManager.UNREGISTER_OFFLOAD_ENGINE:
                        mOffloadEngines.unregister((IOffloadEngine) msg.obj);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private boolean handleMDnsServiceEvent(int code, int transactionId, Object obj) {
                NsdServiceInfo servInfo;
                ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
                if (clientInfo == null) {
                    Log.e(TAG, String.format(
                            "transactionId %d for %d has no client mapping", transactionId, code));
                    return false;
                }

                /* This goes in response as msg.arg2 */
                int clientRequestId = clientInfo.getClientRequestId(transactionId);
                if (clientRequestId < 0) {
                    // This can happen because of race conditions. For example,
                    // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
                    // and we may get in this situation.
                    Log.d(TAG, String.format("%d for transactionId %d that is no longer active",
                            code, transactionId));
                    return false;
                }
                final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
                if (request == null) {
                    Log.e(TAG, "Unknown client request. clientRequestId=" + clientRequestId);
                    return false;
                }
                if (DBG) {
                    Log.d(TAG, String.format(
                            "MDns service event code:%d transactionId=%d", code, transactionId));
                }
                switch (code) {
                    case IMDnsEventListener.SERVICE_FOUND: {
                        final DiscoveryInfo info = (DiscoveryInfo) obj;
                        final String name = info.serviceName;
                        final String type = info.registrationType;
                        servInfo = new NsdServiceInfo(name, type);
                        final int foundNetId = info.netId;
                        if (foundNetId == 0L) {
                            // Ignore services that do not have a Network: they are not usable
                            // by apps, as they would need privileged permissions to use
                            // interfaces that do not have an associated Network.
                            break;
                        }
                        if (foundNetId == INetd.DUMMY_NET_ID) {
                            // Ignore services on the dummy0 interface: they are only seen when
                            // discovering locally advertised services, and are not reachable
                            // through that interface.
                            break;
                        }
                        setServiceNetworkForCallback(servInfo, info.netId, info.interfaceIdx);

                        clientInfo.onServiceFound(clientRequestId, servInfo, request);
                        break;
                    }
                    case IMDnsEventListener.SERVICE_LOST: {
                        final DiscoveryInfo info = (DiscoveryInfo) obj;
                        final String name = info.serviceName;
                        final String type = info.registrationType;
                        final int lostNetId = info.netId;
                        servInfo = new NsdServiceInfo(name, type);
                        // The network could be set to null (netId 0) if it was torn down when the
                        // service is lost
                        // TODO: avoid returning null in that case, possibly by remembering
                        // found services on the same interface index and their network at the time
                        setServiceNetworkForCallback(servInfo, lostNetId, info.interfaceIdx);
                        clientInfo.onServiceLost(clientRequestId, servInfo, request);
                        break;
                    }
                    case IMDnsEventListener.SERVICE_DISCOVERY_FAILED:
                        clientInfo.onDiscoverServicesFailed(clientRequestId,
                                NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        break;
                    case IMDnsEventListener.SERVICE_REGISTERED: {
                        final RegistrationInfo info = (RegistrationInfo) obj;
                        final String name = info.serviceName;
                        servInfo = new NsdServiceInfo(name, null /* serviceType */);
                        clientInfo.onRegisterServiceSucceeded(clientRequestId, servInfo,
                                transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        break;
                    }
                    case IMDnsEventListener.SERVICE_REGISTRATION_FAILED:
                        clientInfo.onRegisterServiceFailed(clientRequestId,
                                NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        break;
                    case IMDnsEventListener.SERVICE_RESOLVED: {
                        final ResolutionInfo info = (ResolutionInfo) obj;
                        int index = 0;
                        final String fullName = info.serviceFullName;
                        while (index < fullName.length() && fullName.charAt(index) != '.') {
                            if (fullName.charAt(index) == '\\') {
                                ++index;
                            }
                            ++index;
                        }
                        if (index >= fullName.length()) {
                            Log.e(TAG, "Invalid service found " + fullName);
                            break;
                        }

                        String name = unescape(fullName.substring(0, index));
                        String rest = fullName.substring(index);
                        String type = rest.replace(".local.", "");

                        final NsdServiceInfo serviceInfo = clientInfo.mResolvedService;
                        serviceInfo.setServiceName(name);
                        serviceInfo.setServiceType(type);
                        serviceInfo.setPort(info.port);
                        serviceInfo.setTxtRecords(info.txtRecord);
                        // Network will be added after SERVICE_GET_ADDR_SUCCESS

                        stopResolveService(transactionId);
                        removeRequestMap(clientRequestId, transactionId, clientInfo);

                        final int transactionId2 = getUniqueId();
                        if (getAddrInfo(transactionId2, info.hostname, info.interfaceIdx)) {
                            storeLegacyRequestMap(clientRequestId, transactionId2, clientInfo,
                                    NsdManager.RESOLVE_SERVICE, request.mStartTimeMs);
                        } else {
                            clientInfo.onResolveServiceFailed(clientRequestId,
                                    NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    }
                    case IMDnsEventListener.SERVICE_RESOLUTION_FAILED:
                        /* NNN resolveId errorCode */
                        stopResolveService(transactionId);
                        removeRequestMap(clientRequestId, transactionId, clientInfo);
                        clientInfo.onResolveServiceFailed(clientRequestId,
                                NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        clientInfo.mResolvedService = null;
                        break;
                    case IMDnsEventListener.SERVICE_GET_ADDR_FAILED:
                        /* NNN resolveId errorCode */
                        stopGetAddrInfo(transactionId);
                        removeRequestMap(clientRequestId, transactionId, clientInfo);
                        clientInfo.onResolveServiceFailed(clientRequestId,
                                NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        clientInfo.mResolvedService = null;
                        break;
                    case IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS: {
                        /* NNN resolveId hostname ttl addr interfaceIdx netId */
                        final GetAddressInfo info = (GetAddressInfo) obj;
                        final String address = info.address;
                        final int netId = info.netId;
                        InetAddress serviceHost = null;
                        try {
                            serviceHost = InetAddress.getByName(address);
                        } catch (UnknownHostException e) {
                            Log.wtf(TAG, "Invalid host in GET_ADDR_SUCCESS", e);
                        }

                        // If the resolved service is on an interface without a network, consider it
                        // as a failure: it would not be usable by apps as they would need
                        // privileged permissions.
                        if (netId != NETID_UNSET && serviceHost != null) {
                            clientInfo.mResolvedService.setHost(serviceHost);
                            setServiceNetworkForCallback(clientInfo.mResolvedService,
                                    netId, info.interfaceIdx);
                            clientInfo.onResolveServiceSucceeded(
                                    clientRequestId, clientInfo.mResolvedService, request);
                        } else {
                            clientInfo.onResolveServiceFailed(clientRequestId,
                                    NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        }
                        stopGetAddrInfo(transactionId);
                        removeRequestMap(clientRequestId, transactionId, clientInfo);
                        clientInfo.mResolvedService = null;
                        break;
                    }
                    default:
                        return false;
                }
                return true;
            }

            @Nullable
            private NsdServiceInfo buildNsdServiceInfoFromMdnsEvent(
                    final MdnsEvent event, int code) {
                final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                final String[] typeArray = serviceInfo.getServiceType();
                final String joinedType;
                if (typeArray.length == 0
                        || !typeArray[typeArray.length - 1].equals(LOCAL_DOMAIN_NAME)) {
                    Log.wtf(TAG, "MdnsServiceInfo type does not end in .local: "
                            + Arrays.toString(typeArray));
                    return null;
                } else {
                    joinedType = TextUtils.join(".",
                            Arrays.copyOfRange(typeArray, 0, typeArray.length - 1));
                }
                final String serviceType;
                switch (code) {
                    case NsdManager.SERVICE_FOUND:
                    case NsdManager.SERVICE_LOST:
                        // For consistency with historical behavior, discovered service types have
                        // a dot at the end.
                        serviceType = joinedType + ".";
                        break;
                    case RESOLVE_SERVICE_SUCCEEDED:
                        // For consistency with historical behavior, resolved service types have
                        // a dot at the beginning.
                        serviceType = "." + joinedType;
                        break;
                    default:
                        serviceType = joinedType;
                        break;
                }
                final String serviceName = serviceInfo.getServiceInstanceName();
                final NsdServiceInfo servInfo = new NsdServiceInfo(serviceName, serviceType);
                final Network network = serviceInfo.getNetwork();
                // In MdnsDiscoveryManagerEvent, the Network can be null which means it is a
                // network for Tethering interface. In other words, the network == null means the
                // network has netId = INetd.LOCAL_NET_ID.
                setServiceNetworkForCallback(
                        servInfo,
                        network == null ? INetd.LOCAL_NET_ID : network.netId,
                        serviceInfo.getInterfaceIndex());
                return servInfo;
            }

            private boolean handleMdnsDiscoveryManagerEvent(
                    int transactionId, int code, Object obj) {
                final ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
                if (clientInfo == null) {
                    Log.e(TAG, String.format(
                            "id %d for %d has no client mapping", transactionId, code));
                    return false;
                }

                final MdnsEvent event = (MdnsEvent) obj;
                final int clientRequestId = event.mClientRequestId;
                final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
                if (request == null) {
                    Log.e(TAG, "Unknown client request. clientRequestId=" + clientRequestId);
                    return false;
                }

                // Deal with the discovery sent callback
                if (code == DISCOVERY_QUERY_SENT_CALLBACK) {
                    request.onQuerySent();
                    return true;
                }

                // Deal with other callbacks.
                final NsdServiceInfo info = buildNsdServiceInfoFromMdnsEvent(event, code);
                // Errors are already logged if null
                if (info == null) return false;
                mServiceLogs.log(String.format(
                        "MdnsDiscoveryManager event code=%s transactionId=%d",
                        NsdManager.nameOf(code), transactionId));
                switch (code) {
                    case NsdManager.SERVICE_FOUND:
                        clientInfo.onServiceFound(clientRequestId, info, request);
                        break;
                    case NsdManager.SERVICE_LOST:
                        clientInfo.onServiceLost(clientRequestId, info, request);
                        break;
                    case NsdManager.RESOLVE_SERVICE_SUCCEEDED: {
                        final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                        info.setPort(serviceInfo.getPort());

                        Map<String, String> attrs = serviceInfo.getAttributes();
                        for (Map.Entry<String, String> kv : attrs.entrySet()) {
                            final String key = kv.getKey();
                            try {
                                info.setAttribute(key, serviceInfo.getAttributeAsBytes(key));
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "Invalid attribute", e);
                            }
                        }
                        final List<InetAddress> addresses = getInetAddresses(serviceInfo);
                        if (addresses.size() != 0) {
                            info.setHostAddresses(addresses);
                            request.setServiceFromCache(event.mIsServiceFromCache);
                            clientInfo.onResolveServiceSucceeded(clientRequestId, info, request);
                        } else {
                            // No address. Notify resolution failure.
                            clientInfo.onResolveServiceFailed(clientRequestId,
                                    NsdManager.FAILURE_INTERNAL_ERROR, transactionId,
                                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        }

                        // Unregister the listener immediately like IMDnsEventListener design
                        if (!(request instanceof DiscoveryManagerRequest)) {
                            Log.wtf(TAG, "non-DiscoveryManager request in DiscoveryManager event");
                            break;
                        }
                        stopDiscoveryManagerRequest(
                                request, clientRequestId, transactionId, clientInfo);
                        break;
                    }
                    case NsdManager.SERVICE_UPDATED: {
                        final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                        info.setPort(serviceInfo.getPort());

                        Map<String, String> attrs = serviceInfo.getAttributes();
                        for (Map.Entry<String, String> kv : attrs.entrySet()) {
                            final String key = kv.getKey();
                            try {
                                info.setAttribute(key, serviceInfo.getAttributeAsBytes(key));
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "Invalid attribute", e);
                            }
                        }

                        final List<InetAddress> addresses = getInetAddresses(serviceInfo);
                        info.setHostAddresses(addresses);
                        clientInfo.onServiceUpdated(clientRequestId, info, request);
                        // Set the ServiceFromCache flag only if the service is actually being
                        // retrieved from the cache. This flag should not be overridden by later
                        // service updates, which may not be cached.
                        if (event.mIsServiceFromCache) {
                            request.setServiceFromCache(true);
                        }
                        break;
                    }
                    case NsdManager.SERVICE_UPDATED_LOST:
                        clientInfo.onServiceUpdatedLost(clientRequestId, request);
                        break;
                    default:
                        return false;
                }
                return true;
            }
       }
    }

    @NonNull
    private static List<InetAddress> getInetAddresses(@NonNull MdnsServiceInfo serviceInfo) {
        final List<String> v4Addrs = serviceInfo.getIpv4Addresses();
        final List<String> v6Addrs = serviceInfo.getIpv6Addresses();
        final List<InetAddress> addresses = new ArrayList<>(v4Addrs.size() + v6Addrs.size());
        for (String ipv4Address : v4Addrs) {
            try {
                addresses.add(InetAddresses.parseNumericAddress(ipv4Address));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv4 address", e);
            }
        }
        for (String ipv6Address : v6Addrs) {
            try {
                final Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(
                        ipv6Address);
                addresses.add(InetAddressUtils.withScopeId(addr, serviceInfo.getInterfaceIndex()));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv6 address", e);
            }
        }
        return addresses;
    }

    private static void setServiceNetworkForCallback(NsdServiceInfo info, int netId, int ifaceIdx) {
        switch (netId) {
            case NETID_UNSET:
                info.setNetwork(null);
                break;
            case INetd.LOCAL_NET_ID:
                // Special case for LOCAL_NET_ID: Networks on netId 99 are not generally
                // visible / usable for apps, so do not return it. Store the interface
                // index instead, so at least if the client tries to resolve the service
                // with that NsdServiceInfo, it will be done on the same interface.
                // If they recreate the NsdServiceInfo themselves, resolution would be
                // done on all interfaces as before T, which should also work.
                info.setNetwork(null);
                info.setInterfaceIndex(ifaceIdx);
                break;
            default:
                info.setNetwork(new Network(netId));
        }
    }

    // The full service name is escaped from standard DNS rules on mdnsresponder, making it suitable
    // for passing to standard system DNS APIs such as res_query() . Thus, make the service name
    // unescape for getting right service address. See "Notes on DNS Name Escaping" on
    // external/mdnsresponder/mDNSShared/dns_sd.h for more details.
    private String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                    break;
                }
                c = s.charAt(i);
                if (c != '.' && c != '\\') {
                    if (i + 2 >= s.length()) {
                        Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                        break;
                    }
                    c = (char) ((c - '0') * 100 + (s.charAt(i + 1) - '0') * 10
                            + (s.charAt(i + 2) - '0'));
                    i += 2;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Check the given service type is valid and construct it to a service type
     * which can use for discovery / resolution service.
     *
     * <p>The valid service type should be 2 labels, or 3 labels if the query is for a
     * subtype (see RFC6763 7.1). Each label is up to 63 characters and must start with an
     * underscore; they are alphanumerical characters or dashes or underscore, except the
     * last one that is just alphanumerical. The last label must be _tcp or _udp.
     *
     * <p>The subtype may also be specified with a comma after the service type, for example
     * _type._tcp,_subtype.
     *
     * @param serviceType the request service type for discovery / resolution service
     * @return constructed service type or null if the given service type is invalid.
     */
    @Nullable
    public static Pair<String, String> parseTypeAndSubtype(String serviceType) {
        if (TextUtils.isEmpty(serviceType)) return null;

        final String typeOrSubtypePattern = "_[a-zA-Z0-9-_]{1,61}[a-zA-Z0-9]";
        final Pattern serviceTypePattern = Pattern.compile(
                // Optional leading subtype (_subtype._type._tcp)
                // (?: xxx) is a non-capturing parenthesis, don't capture the dot
                "^(?:(" + typeOrSubtypePattern + ")\\.)?"
                        // Actual type (_type._tcp.local)
                        + "(" + typeOrSubtypePattern + "\\._(?:tcp|udp))"
                        // Drop '.' at the end of service type that is compatible with old backend.
                        // e.g. allow "_type._tcp.local."
                        + "\\.?"
                        // Optional subtype after comma, for "_type._tcp,_subtype" format
                        + "(?:,(" + typeOrSubtypePattern + "))?"
                        + "$");
        final Matcher matcher = serviceTypePattern.matcher(serviceType);
        if (!matcher.matches()) return null;
        // Use the subtype either at the beginning or after the comma
        final String subtype = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        return new Pair<>(matcher.group(2), subtype);
    }

    @VisibleForTesting
    NsdService(Context ctx, Handler handler, long cleanupDelayMs) {
        this(ctx, handler, cleanupDelayMs, new Dependencies());
    }

    @VisibleForTesting
    NsdService(Context ctx, Handler handler, long cleanupDelayMs, Dependencies deps) {
        mCleanupDelayMs = cleanupDelayMs;
        mContext = ctx;
        mNsdStateMachine = new NsdStateMachine(TAG, handler);
        mNsdStateMachine.start();
        mMDnsManager = ctx.getSystemService(MDnsManager.class);
        mMDnsEventCallback = new MDnsEventCallback(mNsdStateMachine);
        mDeps = deps;

        mMdnsSocketProvider = deps.makeMdnsSocketProvider(ctx, handler.getLooper(),
                LOGGER.forSubComponent("MdnsSocketProvider"), new SocketRequestMonitor());
        // Netlink monitor starts on boot, and intentionally never stopped, to ensure that all
        // address events are received. When the netlink monitor starts, any IP addresses already
        // on the interfaces will not be seen. In practice, the network will not connect at boot
        // time As a result, all the netlink message should be observed if the netlink monitor
        // starts here.
        handler.post(mMdnsSocketProvider::startNetLinkMonitor);

        // NsdService is started after ActivityManager (startOtherServices in SystemServer, vs.
        // startBootstrapServices).
        mRunningAppActiveImportanceCutoff = mDeps.getDeviceConfigInt(
                MDNS_CONFIG_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF,
                DEFAULT_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF);
        final ActivityManager am = ctx.getSystemService(ActivityManager.class);
        am.addOnUidImportanceListener(new UidImportanceListener(handler),
                mRunningAppActiveImportanceCutoff);

        mMdnsSocketClient =
                new MdnsMultinetworkSocketClient(handler.getLooper(), mMdnsSocketProvider,
                        LOGGER.forSubComponent("MdnsMultinetworkSocketClient"));
        mMdnsDiscoveryManager = deps.makeMdnsDiscoveryManager(new ExecutorProvider(),
                mMdnsSocketClient, LOGGER.forSubComponent("MdnsDiscoveryManager"));
        handler.post(() -> mMdnsSocketClient.setCallback(mMdnsDiscoveryManager));
        MdnsFeatureFlags flags = new MdnsFeatureFlags.Builder().setIsMdnsOffloadFeatureEnabled(
                mDeps.isTetheringFeatureNotChickenedOut(
                        MdnsFeatureFlags.NSD_FORCE_DISABLE_MDNS_OFFLOAD)).build();
        mAdvertiser = deps.makeMdnsAdvertiser(handler.getLooper(), mMdnsSocketProvider,
                new AdvertiserCallback(), LOGGER.forSubComponent("MdnsAdvertiser"), flags);
        mClock = deps.makeClock();
    }

    /**
     * Dependencies of NsdService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Check whether the MdnsDiscoveryManager feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsDiscoveryManager feature is enabled.
         */
        public boolean isMdnsDiscoveryManagerEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isTetheringFeatureEnabled(context,
                    MDNS_DISCOVERY_MANAGER_VERSION);
        }

        /**
         * Check whether the MdnsAdvertiser feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsAdvertiser feature is enabled.
         */
        public boolean isMdnsAdvertiserEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isTetheringFeatureEnabled(context,
                    MDNS_ADVERTISER_VERSION);
        }

        /**
         * Get the type allowlist flag value.
         * @see #MDNS_TYPE_ALLOWLIST_FLAGS
         */
        @Nullable
        public String getTypeAllowlistFlags() {
            return DeviceConfigUtils.getDeviceConfigProperty(NAMESPACE_TETHERING,
                    MDNS_TYPE_ALLOWLIST_FLAGS, null);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureEnabled
         */
        public boolean isFeatureEnabled(Context context, String feature) {
            return DeviceConfigUtils.isTetheringFeatureEnabled(context, feature);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureNotChickenedOut
         */
        public boolean isTetheringFeatureNotChickenedOut(String feature) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(feature);
        }

        /**
         * @see MdnsDiscoveryManager
         */
        public MdnsDiscoveryManager makeMdnsDiscoveryManager(
                @NonNull ExecutorProvider executorProvider,
                @NonNull MdnsMultinetworkSocketClient socketClient, @NonNull SharedLog sharedLog) {
            return new MdnsDiscoveryManager(executorProvider, socketClient, sharedLog);
        }

        /**
         * @see MdnsAdvertiser
         */
        public MdnsAdvertiser makeMdnsAdvertiser(
                @NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
                @NonNull MdnsAdvertiser.AdvertiserCallback cb, @NonNull SharedLog sharedLog,
                MdnsFeatureFlags featureFlags) {
            return new MdnsAdvertiser(looper, socketProvider, cb, sharedLog, featureFlags);
        }

        /**
         * @see MdnsSocketProvider
         */
        public MdnsSocketProvider makeMdnsSocketProvider(@NonNull Context context,
                @NonNull Looper looper, @NonNull SharedLog sharedLog,
                @NonNull MdnsSocketProvider.SocketRequestMonitor socketCreationCallback) {
            return new MdnsSocketProvider(context, looper, sharedLog, socketCreationCallback);
        }

        /**
         * @see DeviceConfig#getInt(String, String, int)
         */
        public int getDeviceConfigInt(@NonNull String config, int defaultValue) {
            return DeviceConfig.getInt(NAMESPACE_TETHERING, config, defaultValue);
        }

        /**
         * @see Binder#getCallingUid()
         */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /**
         * @see NetworkNsdReportedMetrics
         */
        public NetworkNsdReportedMetrics makeNetworkNsdReportedMetrics(
                boolean isLegacy, int clientId) {
            return new NetworkNsdReportedMetrics(isLegacy, clientId);
        }

        /**
         * @see MdnsUtils.Clock
         */
        public Clock makeClock() {
            return new Clock();
        }
    }

    /**
     * Return whether a type is allowlisted to use the Java backend.
     * @param type The service type
     * @param flagPrefix One of {@link #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX} or
     *                   {@link #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX}.
     */
    private boolean isTypeAllowlistedForJavaBackend(@Nullable String type,
            @NonNull String flagPrefix) {
        if (type == null) return false;
        final String typesConfig = mDeps.getTypeAllowlistFlags();
        if (TextUtils.isEmpty(typesConfig)) return false;

        final String mappingPrefix = type + ":";
        String mappedFlag = null;
        for (String mapping : TextUtils.split(typesConfig, ",")) {
            if (mapping.startsWith(mappingPrefix)) {
                mappedFlag = mapping.substring(mappingPrefix.length());
                break;
            }
        }

        if (mappedFlag == null) return false;

        return mDeps.isFeatureEnabled(mContext,
                flagPrefix + mappedFlag + MDNS_ALLOWLIST_FLAG_SUFFIX);
    }

    private boolean useDiscoveryManagerForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX);
    }

    private boolean useAdvertiserForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX);
    }

    public static NsdService create(Context context) {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        NsdService service = new NsdService(context, handler, CLEANUP_DELAY_MS);
        return service;
    }

    private static class MDnsEventCallback extends IMDnsEventListener.Stub {
        private final StateMachine mStateMachine;

        MDnsEventCallback(StateMachine sm) {
            mStateMachine = sm;
        }

        @Override
        public void onServiceRegistrationStatus(final RegistrationInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceDiscoveryStatus(final DiscoveryInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceResolutionStatus(final ResolutionInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onGettingServiceAddressStatus(final GetAddressInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return this.HASH;
        }
    }

    private void sendAllOffloadServiceInfos(@NonNull OffloadEngineInfo offloadEngineInfo) {
        final String targetInterface = offloadEngineInfo.mInterfaceName;
        final IOffloadEngine offloadEngine = offloadEngineInfo.mOffloadEngine;
        final List<MdnsAdvertiser.OffloadServiceInfoWrapper> offloadWrappers =
                mAdvertiser.getAllInterfaceOffloadServiceInfos(targetInterface);
        for (MdnsAdvertiser.OffloadServiceInfoWrapper wrapper : offloadWrappers) {
            try {
                offloadEngine.onOffloadServiceUpdated(wrapper.mOffloadServiceInfo);
            } catch (RemoteException e) {
                // Can happen in regular cases, do not log a stacktrace
                Log.i(TAG, "Failed to send offload callback, remote died: " + e.getMessage());
            }
        }
    }

    private void sendOffloadServiceInfosUpdate(@NonNull String targetInterfaceName,
            @NonNull OffloadServiceInfo offloadServiceInfo, boolean isRemove) {
        final int count = mOffloadEngines.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                final OffloadEngineInfo offloadEngineInfo =
                        (OffloadEngineInfo) mOffloadEngines.getBroadcastCookie(i);
                final String interfaceName = offloadEngineInfo.mInterfaceName;
                if (!targetInterfaceName.equals(interfaceName)
                        || ((offloadEngineInfo.mOffloadType
                        & offloadServiceInfo.getOffloadType()) == 0)) {
                    continue;
                }
                try {
                    if (isRemove) {
                        mOffloadEngines.getBroadcastItem(i).onOffloadServiceRemoved(
                                offloadServiceInfo);
                    } else {
                        mOffloadEngines.getBroadcastItem(i).onOffloadServiceUpdated(
                                offloadServiceInfo);
                    }
                } catch (RemoteException e) {
                    // Can happen in regular cases, do not log a stacktrace
                    Log.i(TAG, "Failed to send offload callback, remote died: " + e.getMessage());
                }
            }
        } finally {
            mOffloadEngines.finishBroadcast();
        }
    }

    private class AdvertiserCallback implements MdnsAdvertiser.AdvertiserCallback {
        // TODO: add a callback to notify when a service is being added on each interface (as soon
        // as probing starts), and call mOffloadCallbacks. This callback is for
        // OFFLOAD_CAPABILITY_FILTER_REPLIES offload type.

        @Override
        public void onRegisterServiceSucceeded(int transactionId, NsdServiceInfo registeredInfo) {
            mServiceLogs.log("onRegisterServiceSucceeded: transactionId " + transactionId);
            final ClientInfo clientInfo = getClientInfoOrLog(transactionId);
            if (clientInfo == null) return;

            final int clientRequestId = getClientRequestIdOrLog(clientInfo, transactionId);
            if (clientRequestId < 0) return;

            // onRegisterServiceSucceeded only has the service name in its info. This aligns with
            // historical behavior.
            final NsdServiceInfo cbInfo = new NsdServiceInfo(registeredInfo.getServiceName(), null);
            final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
            clientInfo.onRegisterServiceSucceeded(clientRequestId, cbInfo, transactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
        }

        @Override
        public void onRegisterServiceFailed(int transactionId, int errorCode) {
            final ClientInfo clientInfo = getClientInfoOrLog(transactionId);
            if (clientInfo == null) return;

            final int clientRequestId = getClientRequestIdOrLog(clientInfo, transactionId);
            if (clientRequestId < 0) return;
            final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
            clientInfo.onRegisterServiceFailed(clientRequestId, errorCode, transactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
        }

        @Override
        public void onOffloadStartOrUpdate(@NonNull String interfaceName,
                @NonNull OffloadServiceInfo offloadServiceInfo) {
            sendOffloadServiceInfosUpdate(interfaceName, offloadServiceInfo, false /* isRemove */);
        }

        @Override
        public void onOffloadStop(@NonNull String interfaceName,
                @NonNull OffloadServiceInfo offloadServiceInfo) {
            sendOffloadServiceInfosUpdate(interfaceName, offloadServiceInfo, true /* isRemove */);
        }

        private ClientInfo getClientInfoOrLog(int transactionId) {
            final ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
            if (clientInfo == null) {
                Log.e(TAG, String.format("Callback for service %d has no client", transactionId));
            }
            return clientInfo;
        }

        private int getClientRequestIdOrLog(@NonNull ClientInfo info, int transactionId) {
            final int clientRequestId = info.getClientRequestId(transactionId);
            if (clientRequestId < 0) {
                Log.e(TAG, String.format(
                        "Client request ID not found for service %d", transactionId));
            }
            return clientRequestId;
        }
    }

    private static class ConnectorArgs {
        @NonNull public final NsdServiceConnector connector;
        @NonNull public final INsdManagerCallback callback;
        public final boolean useJavaBackend;
        public final int uid;

        ConnectorArgs(@NonNull NsdServiceConnector connector, @NonNull INsdManagerCallback callback,
                boolean useJavaBackend, int uid) {
            this.connector = connector;
            this.callback = callback;
            this.useJavaBackend = useJavaBackend;
            this.uid = uid;
        }
    }

    @Override
    public INsdServiceConnector connect(INsdManagerCallback cb, boolean useJavaBackend) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET, "NsdService");
        final int uid = mDeps.getCallingUid();
        if (cb == null) {
            throw new IllegalArgumentException("Unknown client callback from uid=" + uid);
        }
        if (DBG) Log.d(TAG, "New client connect. useJavaBackend=" + useJavaBackend);
        final INsdServiceConnector connector = new NsdServiceConnector();
        mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(NsdManager.REGISTER_CLIENT,
                new ConnectorArgs((NsdServiceConnector) connector, cb, useJavaBackend, uid)));
        return connector;
    }

    private static class ListenerArgs {
        public final NsdServiceConnector connector;
        public final NsdServiceInfo serviceInfo;
        ListenerArgs(NsdServiceConnector connector, NsdServiceInfo serviceInfo) {
            this.connector = connector;
            this.serviceInfo = serviceInfo;
        }
    }

    private class NsdServiceConnector extends INsdServiceConnector.Stub
            implements IBinder.DeathRecipient  {
        @Override
        public void registerService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.REGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void unregisterService(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.UNREGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, null)));
        }

        @Override
        public void discoverServices(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DISCOVER_SERVICES, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void stopDiscovery(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.STOP_DISCOVERY, 0, listenerKey, new ListenerArgs(this, null)));
        }

        @Override
        public void resolveService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.RESOLVE_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void stopResolution(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.STOP_RESOLUTION, 0, listenerKey, new ListenerArgs(this, null)));
        }

        @Override
        public void registerServiceInfoCallback(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.REGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void unregisterServiceInfoCallback(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.UNREGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, null)));
        }

        @Override
        public void startDaemon() {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DAEMON_STARTUP, new ListenerArgs(this, null)));
        }

        @Override
        public void binderDied() {
            mNsdStateMachine.sendMessage(
                    mNsdStateMachine.obtainMessage(NsdManager.UNREGISTER_CLIENT, this));

        }

        @Override
        public void registerOffloadEngine(String ifaceName, IOffloadEngine cb,
                @OffloadEngine.OffloadCapability long offloadCapabilities,
                @OffloadEngine.OffloadType long offloadTypes) {
            checkOffloadEnginePermission(mContext);
            Objects.requireNonNull(ifaceName);
            Objects.requireNonNull(cb);
            mNsdStateMachine.sendMessage(
                    mNsdStateMachine.obtainMessage(NsdManager.REGISTER_OFFLOAD_ENGINE,
                            new OffloadEngineInfo(cb, ifaceName, offloadCapabilities,
                                    offloadTypes)));
        }

        @Override
        public void unregisterOffloadEngine(IOffloadEngine cb) {
            checkOffloadEnginePermission(mContext);
            Objects.requireNonNull(cb);
            mNsdStateMachine.sendMessage(
                    mNsdStateMachine.obtainMessage(NsdManager.UNREGISTER_OFFLOAD_ENGINE, cb));
        }

        private static void checkOffloadEnginePermission(Context context) {
            if (!SdkLevel.isAtLeastT()) {
                throw new SecurityException("API is not available in before API level 33");
            }
            // REGISTER_NSD_OFFLOAD_ENGINE was only added to the SDK in V, but may
            // be back ported to older builds: accept it as long as it's signature-protected
            if (PermissionUtils.checkAnyPermissionOf(context, REGISTER_NSD_OFFLOAD_ENGINE)
                    && (SdkLevel.isAtLeastV() || PermissionUtils.isSystemSignaturePermission(
                    context, REGISTER_NSD_OFFLOAD_ENGINE))) {
                return;
            }
            if (PermissionUtils.checkAnyPermissionOf(context, NETWORK_STACK,
                    PERMISSION_MAINLINE_NETWORK_STACK, NETWORK_SETTINGS)) {
                return;
            }
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", List.of(REGISTER_NSD_OFFLOAD_ENGINE, NETWORK_STACK,
                    PERMISSION_MAINLINE_NETWORK_STACK, NETWORK_SETTINGS)) + ".");
        }
    }

    private void sendNsdStateChangeBroadcast(boolean isEnabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        int nsdState = isEnabled ? NsdManager.NSD_STATE_ENABLED : NsdManager.NSD_STATE_DISABLED;
        intent.putExtra(NsdManager.EXTRA_NSD_STATE, nsdState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    private boolean registerService(int transactionId, NsdServiceInfo service) {
        if (DBG) {
            Log.d(TAG, "registerService: " + transactionId + " " + service);
        }
        String name = service.getServiceName();
        String type = service.getServiceType();
        int port = service.getPort();
        byte[] textRecord = service.getTxtRecord();
        final int registerInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && registerInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to register service on not found");
            return false;
        }
        return mMDnsManager.registerService(
                transactionId, name, type, port, textRecord, registerInterface);
    }

    private boolean unregisterService(int transactionId) {
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean discoverServices(int transactionId, NsdServiceInfo serviceInfo) {
        final String type = serviceInfo.getServiceType();
        final int discoverInterface = getNetworkInterfaceIndex(serviceInfo);
        if (serviceInfo.getNetwork() != null && discoverInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to discover service on not found");
            return false;
        }
        return mMDnsManager.discover(transactionId, type, discoverInterface);
    }

    private boolean stopServiceDiscovery(int transactionId) {
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean resolveService(int transactionId, NsdServiceInfo service) {
        final String name = service.getServiceName();
        final String type = service.getServiceType();
        final int resolveInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && resolveInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to resolve service on not found");
            return false;
        }
        return mMDnsManager.resolve(transactionId, name, type, "local.", resolveInterface);
    }

    /**
     * Guess the interface to use to resolve or discover a service on a specific network.
     *
     * This is an imperfect guess, as for example the network may be gone or not yet fully
     * registered. This is fine as failing is correct if the network is gone, and a client
     * attempting to resolve/discover on a network not yet setup would have a bad time anyway; also
     * this is to support the legacy mdnsresponder implementation, which historically resolved
     * services on an unspecified network.
     */
    private int getNetworkInterfaceIndex(NsdServiceInfo serviceInfo) {
        final Network network = serviceInfo.getNetwork();
        if (network == null) {
            // Fallback to getInterfaceIndex if present (typically if the NsdServiceInfo was
            // provided by NsdService from discovery results, and the service was found on an
            // interface that has no app-usable Network).
            if (serviceInfo.getInterfaceIndex() != 0) {
                return serviceInfo.getInterfaceIndex();
            }
            return IFACE_IDX_ANY;
        }

        String interfaceName = getNetworkInterfaceName(network);
        if (interfaceName == null) {
            return IFACE_IDX_ANY;
        }
        return getNetworkInterfaceIndexByName(interfaceName);
    }

    private String getNetworkInterfaceName(@Nullable Network network) {
        if (network == null) {
            return null;
        }
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.wtf(TAG, "No ConnectivityManager");
            return null;
        }
        final LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) {
            return null;
        }
        // Only resolve on non-stacked interfaces
        return lp.getInterfaceName();
    }

    private int getNetworkInterfaceIndexByName(final String ifaceName) {
        final NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(ifaceName);
        } catch (SocketException e) {
            Log.e(TAG, "Error querying interface", e);
            return IFACE_IDX_ANY;
        }

        if (iface == null) {
            Log.e(TAG, "Interface not found: " + ifaceName);
            return IFACE_IDX_ANY;
        }

        return iface.getIndex();
    }

    private boolean stopResolveService(int transactionId) {
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean getAddrInfo(int transactionId, String hostname, int interfaceIdx) {
        return mMDnsManager.getServiceAddress(transactionId, hostname, interfaceIdx);
    }

    private boolean stopGetAddrInfo(int transactionId) {
        return mMDnsManager.stopOperation(transactionId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!PermissionUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        // Dump state machine logs
        mNsdStateMachine.dump(fd, pw, args);

        // Dump service and clients logs
        pw.println();
        pw.println("Logs:");
        pw.increaseIndent();
        mServiceLogs.reverseDump(pw);
        pw.decreaseIndent();
    }

    private abstract static class ClientRequest {
        private final int mTransactionId;
        private final long mStartTimeMs;
        private int mFoundServiceCount = 0;
        private int mLostServiceCount = 0;
        private final Set<String> mServices = new ArraySet<>();
        private boolean mIsServiceFromCache = false;
        private int mSentQueryCount = NO_SENT_QUERY_COUNT;

        private ClientRequest(int transactionId, long startTimeMs) {
            mTransactionId = transactionId;
            mStartTimeMs = startTimeMs;
        }

        public long calculateRequestDurationMs(long stopTimeMs) {
            return stopTimeMs - mStartTimeMs;
        }

        public void onServiceFound(String serviceName) {
            mFoundServiceCount++;
            if (mServices.size() <= MAX_SERVICES_COUNT_METRIC_PER_CLIENT) {
                mServices.add(serviceName);
            }
        }

        public void onServiceLost() {
            mLostServiceCount++;
        }

        public int getFoundServiceCount() {
            return mFoundServiceCount;
        }

        public int getLostServiceCount() {
            return mLostServiceCount;
        }

        public int getServicesCount() {
            return mServices.size();
        }

        public void setServiceFromCache(boolean isServiceFromCache) {
            mIsServiceFromCache = isServiceFromCache;
        }

        public boolean isServiceFromCache() {
            return mIsServiceFromCache;
        }

        public void onQuerySent() {
            mSentQueryCount++;
        }

        public int getSentQueryCount() {
            return mSentQueryCount;
        }
    }

    private static class LegacyClientRequest extends ClientRequest {
        private final int mRequestCode;

        private LegacyClientRequest(int transactionId, int requestCode, long startTimeMs) {
            super(transactionId, startTimeMs);
            mRequestCode = requestCode;
        }
    }

    private abstract static class JavaBackendClientRequest extends ClientRequest {
        @Nullable
        private final Network mRequestedNetwork;

        private JavaBackendClientRequest(int transactionId, @Nullable Network requestedNetwork,
                long startTimeMs) {
            super(transactionId, startTimeMs);
            mRequestedNetwork = requestedNetwork;
        }

        @Nullable
        public Network getRequestedNetwork() {
            return mRequestedNetwork;
        }
    }

    private static class AdvertiserClientRequest extends JavaBackendClientRequest {
        private AdvertiserClientRequest(int transactionId, @Nullable Network requestedNetwork,
                long startTimeMs) {
            super(transactionId, requestedNetwork, startTimeMs);
        }
    }

    private static class DiscoveryManagerRequest extends JavaBackendClientRequest {
        @NonNull
        private final MdnsListener mListener;

        private DiscoveryManagerRequest(int transactionId, @NonNull MdnsListener listener,
                @Nullable Network requestedNetwork, long startTimeMs) {
            super(transactionId, requestedNetwork, startTimeMs);
            mListener = listener;
        }
    }

    /* Information tracked per client */
    private class ClientInfo {

        private static final int MAX_LIMIT = 10;
        private final INsdManagerCallback mCb;
        /* Remembers a resolved service until getaddrinfo completes */
        private NsdServiceInfo mResolvedService;

        /* A map from client request ID (listenerKey) to the request */
        private final SparseArray<ClientRequest> mClientRequests = new SparseArray<>();

        // The target SDK of this client < Build.VERSION_CODES.S
        private boolean mIsPreSClient = false;
        private final int mUid;
        // The flag of using java backend if the client's target SDK >= U
        private final boolean mUseJavaBackend;
        // Store client logs
        private final SharedLog mClientLogs;
        // Report the nsd metrics data
        private final NetworkNsdReportedMetrics mMetrics;

        private ClientInfo(INsdManagerCallback cb, int uid, boolean useJavaBackend,
                SharedLog sharedLog, NetworkNsdReportedMetrics metrics) {
            mCb = cb;
            mUid = uid;
            mUseJavaBackend = useJavaBackend;
            mClientLogs = sharedLog;
            mClientLogs.log("New client. useJavaBackend=" + useJavaBackend);
            mMetrics = metrics;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("mResolvedService ").append(mResolvedService).append("\n");
            sb.append("mIsLegacy ").append(mIsPreSClient).append("\n");
            sb.append("mUseJavaBackend ").append(mUseJavaBackend).append("\n");
            sb.append("mUid ").append(mUid).append("\n");
            for (int i = 0; i < mClientRequests.size(); i++) {
                int clientRequestId = mClientRequests.keyAt(i);
                sb.append("clientRequestId ")
                        .append(clientRequestId)
                        .append(" transactionId ").append(mClientRequests.valueAt(i).mTransactionId)
                        .append(" type ").append(
                                mClientRequests.valueAt(i).getClass().getSimpleName())
                        .append("\n");
            }
            return sb.toString();
        }

        private boolean isPreSClient() {
            return mIsPreSClient;
        }

        private void setPreSClient() {
            mIsPreSClient = true;
        }

        private MdnsListener unregisterMdnsListenerFromRequest(ClientRequest request) {
            final MdnsListener listener =
                    ((DiscoveryManagerRequest) request).mListener;
            mMdnsDiscoveryManager.unregisterListener(
                    listener.getListenedServiceType(), listener);
            return listener;
        }

        // Remove any pending requests from the global map when we get rid of a client,
        // and send cancellations to the daemon.
        private void expungeAllRequests() {
            mClientLogs.log("Client unregistered. expungeAllRequests!");
            // TODO: to keep handler responsive, do not clean all requests for that client at once.
            for (int i = 0; i < mClientRequests.size(); i++) {
                final int clientRequestId = mClientRequests.keyAt(i);
                final ClientRequest request = mClientRequests.valueAt(i);
                final int transactionId = request.mTransactionId;
                mTransactionIdToClientInfoMap.remove(transactionId);
                if (DBG) {
                    Log.d(TAG, "Terminating clientRequestId " + clientRequestId
                            + " transactionId " + transactionId
                            + " type " + mClientRequests.get(clientRequestId));
                }

                if (request instanceof DiscoveryManagerRequest) {
                    final MdnsListener listener = unregisterMdnsListenerFromRequest(request);
                    if (listener instanceof DiscoveryListener) {
                        mMetrics.reportServiceDiscoveryStop(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.getServicesCount(),
                                request.getSentQueryCount());
                    } else if (listener instanceof ResolutionListener) {
                        mMetrics.reportServiceResolutionStop(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                    } else if (listener instanceof ServiceInfoListener) {
                        mMetrics.reportServiceInfoCallbackUnregistered(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.isServiceFromCache(),
                                request.getSentQueryCount());
                    }
                    continue;
                }

                if (request instanceof AdvertiserClientRequest) {
                    final AdvertiserMetrics metrics =
                            mAdvertiser.getAdvertiserMetrics(transactionId);
                    mAdvertiser.removeService(transactionId);
                    mMetrics.reportServiceUnregistration(transactionId,
                            request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                            metrics.mRepliedRequestsCount, metrics.mSentPacketCount,
                            metrics.mConflictDuringProbingCount,
                            metrics.mConflictAfterProbingCount);
                    continue;
                }

                if (!(request instanceof LegacyClientRequest)) {
                    throw new IllegalStateException("Unknown request type: " + request.getClass());
                }

                switch (((LegacyClientRequest) request).mRequestCode) {
                    case NsdManager.DISCOVER_SERVICES:
                        stopServiceDiscovery(transactionId);
                        mMetrics.reportServiceDiscoveryStop(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.getServicesCount(),
                                NO_SENT_QUERY_COUNT);
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        stopResolveService(transactionId);
                        mMetrics.reportServiceResolutionStop(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()));
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        unregisterService(transactionId);
                        mMetrics.reportServiceUnregistration(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                NO_PACKET /* repliedRequestsCount */,
                                NO_PACKET /* sentPacketCount */,
                                0 /* conflictDuringProbingCount */,
                                0 /* conflictAfterProbingCount */);
                        break;
                    default:
                        break;
                }
            }
            mClientRequests.clear();
            updateMulticastLock();
        }

        /**
         * Returns true if this client has any Java backend request that requests one of the given
         * networks.
         */
        boolean hasAnyJavaBackendRequestForNetworks(@NonNull ArraySet<Network> networks) {
            for (int i = 0; i < mClientRequests.size(); i++) {
                final ClientRequest req = mClientRequests.valueAt(i);
                if (!(req instanceof JavaBackendClientRequest)) {
                    continue;
                }
                final Network reqNetwork = ((JavaBackendClientRequest) mClientRequests.valueAt(i))
                        .getRequestedNetwork();
                if (MdnsUtils.isAnyNetworkMatched(reqNetwork, networks)) {
                    return true;
                }
            }
            return false;
        }

        // mClientRequests is a sparse array of client request id -> ClientRequest.  For a given
        // transaction id, return the corresponding client request id.
        private int getClientRequestId(final int transactionId) {
            for (int i = 0; i < mClientRequests.size(); i++) {
                if (mClientRequests.valueAt(i).mTransactionId == transactionId) {
                    return mClientRequests.keyAt(i);
                }
            }
            return -1;
        }

        private void log(String message) {
            mClientLogs.log(message);
        }

        void onDiscoverServicesStarted(int listenerKey, NsdServiceInfo info, int transactionId) {
            mMetrics.reportServiceDiscoveryStarted(transactionId);
            try {
                mCb.onDiscoverServicesStarted(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesStarted", e);
            }
        }
        void onDiscoverServicesFailedImmediately(int listenerKey, int error) {
            onDiscoverServicesFailed(listenerKey, error, NO_TRANSACTION, 0L /* durationMs */);
        }

        void onDiscoverServicesFailed(int listenerKey, int error, int transactionId,
                long durationMs) {
            mMetrics.reportServiceDiscoveryFailed(transactionId, durationMs);
            try {
                mCb.onDiscoverServicesFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesFailed", e);
            }
        }

        void onServiceFound(int listenerKey, NsdServiceInfo info, ClientRequest request) {
            request.onServiceFound(info.getServiceName());
            try {
                mCb.onServiceFound(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceFound(", e);
            }
        }

        void onServiceLost(int listenerKey, NsdServiceInfo info, ClientRequest request) {
            request.onServiceLost();
            try {
                mCb.onServiceLost(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceLost(", e);
            }
        }

        void onStopDiscoveryFailed(int listenerKey, int error) {
            try {
                mCb.onStopDiscoveryFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoveryFailed", e);
            }
        }

        void onStopDiscoverySucceeded(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceDiscoveryStop(
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.getFoundServiceCount(),
                    request.getLostServiceCount(),
                    request.getServicesCount(),
                    request.getSentQueryCount());
            try {
                mCb.onStopDiscoverySucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoverySucceeded", e);
            }
        }

        void onRegisterServiceFailedImmediately(int listenerKey, int error) {
            onRegisterServiceFailed(listenerKey, error, NO_TRANSACTION, 0L /* durationMs */);
        }

        void onRegisterServiceFailed(int listenerKey, int error, int transactionId,
                long durationMs) {
            mMetrics.reportServiceRegistrationFailed(transactionId, durationMs);
            try {
                mCb.onRegisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceFailed", e);
            }
        }

        void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info, int transactionId,
                long durationMs) {
            mMetrics.reportServiceRegistrationSucceeded(transactionId, durationMs);
            try {
                mCb.onRegisterServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceSucceeded", e);
            }
        }

        void onUnregisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onUnregisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceFailed", e);
            }
        }

        void onUnregisterServiceSucceeded(int listenerKey, int transactionId, long durationMs,
                AdvertiserMetrics metrics) {
            mMetrics.reportServiceUnregistration(transactionId, durationMs,
                    metrics.mRepliedRequestsCount, metrics.mSentPacketCount,
                    metrics.mConflictDuringProbingCount, metrics.mConflictAfterProbingCount);
            try {
                mCb.onUnregisterServiceSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceSucceeded", e);
            }
        }

        void onResolveServiceFailedImmediately(int listenerKey, int error) {
            onResolveServiceFailed(listenerKey, error, NO_TRANSACTION, 0L /* durationMs */);
        }

        void onResolveServiceFailed(int listenerKey, int error, int transactionId,
                long durationMs) {
            mMetrics.reportServiceResolutionFailed(transactionId, durationMs);
            try {
                mCb.onResolveServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceFailed", e);
            }
        }

        void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info,
                ClientRequest request) {
            mMetrics.reportServiceResolved(
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.isServiceFromCache(),
                    request.getSentQueryCount());
            try {
                mCb.onResolveServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceSucceeded", e);
            }
        }

        void onStopResolutionFailed(int listenerKey, int error) {
            try {
                mCb.onStopResolutionFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionFailed", e);
            }
        }

        void onStopResolutionSucceeded(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceResolutionStop(
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
            try {
                mCb.onStopResolutionSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionSucceeded", e);
            }
        }

        void onServiceInfoCallbackRegistrationFailed(int listenerKey, int error) {
            mMetrics.reportServiceInfoCallbackRegistrationFailed(NO_TRANSACTION);
            try {
                mCb.onServiceInfoCallbackRegistrationFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackRegistrationFailed", e);
            }
        }

        void onServiceInfoCallbackRegistered(int transactionId) {
            mMetrics.reportServiceInfoCallbackRegistered(transactionId);
        }

        void onServiceUpdated(int listenerKey, NsdServiceInfo info, ClientRequest request) {
            request.onServiceFound(info.getServiceName());
            try {
                mCb.onServiceUpdated(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdated", e);
            }
        }

        void onServiceUpdatedLost(int listenerKey, ClientRequest request) {
            request.onServiceLost();
            try {
                mCb.onServiceUpdatedLost(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdatedLost", e);
            }
        }

        void onServiceInfoCallbackUnregistered(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceInfoCallbackUnregistered(
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.getFoundServiceCount(),
                    request.getLostServiceCount(),
                    request.isServiceFromCache(),
                    request.getSentQueryCount());
            try {
                mCb.onServiceInfoCallbackUnregistered(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackUnregistered", e);
            }
        }
    }
}
